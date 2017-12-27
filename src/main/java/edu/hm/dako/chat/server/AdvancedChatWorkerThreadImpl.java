package edu.hm.dako.chat.server;

import java.util.HashSet;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import edu.hm.dako.chat.client.SharedClientData;
import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ClientConversationStatus;
import edu.hm.dako.chat.common.ClientListEntry;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;
import edu.hm.dako.chat.connection.ConnectionTimeoutException;
import edu.hm.dako.chat.connection.EndOfFileException;

/**
 * Worker-Thread zur serverseitigen Bedienung einer Session mit einem Client.
 * Jedem Chat-Client wird serverseitig ein Worker-Thread zugeordnet.
 * 
 * @author Peter Mandl
 *
 */
public class AdvancedChatWorkerThreadImpl extends AbstractWorkerThread {

	private static Log log = LogFactory.getLog(AdvancedChatWorkerThreadImpl.class);

	public AdvancedChatWorkerThreadImpl(Connection con, SharedChatClientList clients, SharedServerCounter counter,
			ChatServerGuiInterface serverGuiInterface) {

		super(con, clients, counter, serverGuiInterface);
	}

	@Override
	public void run() {
		log.debug("ChatWorker-Thread erzeugt, Threadname: " + Thread.currentThread().getName());
		System.out.println("CHatWorker-Thread erzeugt");
		while (!finished && !Thread.currentThread().isInterrupted()) {
			try {
				// Warte auf naechste Nachricht des Clients und fuehre
				// entsprechende Aktion aus
				handleIncomingMessage();
			} catch (Exception e) {
				log.error("Exception waehrend der Nachrichtenverarbeitung");
				ExceptionHandler.logException(e);
			}
		}
		log.debug(Thread.currentThread().getName() + " beendet sich");
		closeConnection();
	}

	/**
	 * Senden eines Login-List-Update-Event an alle angemeldeten Clients
	 * 
	 * @param pdu
	 *            Zu sendende PDU
	 */
	protected void sendLoginListUpdateEvent(ChatPDU pdu) {

		// Liste der eingeloggten bzw. sich einloggenden User ermitteln
		Vector<String> clientList = clients.getRegisteredClientNameList();

		log.debug("Aktuelle Clientliste, die an die Clients uebertragen wird: " + clientList);

		pdu.setClients(clientList);

		Vector<String> clientList2 = clients.getClientNameList();

		// Login- oder Logout-Event-PDU an alle aktiven Clients senden
		for (String s : new Vector<String>(clientList2)) {
			log.debug("Fuer " + s + " wird Login- oder Logout-Event-PDU an alle aktiven Clients gesendet");

			ClientListEntry client = clients.getClient(s);
			try {
				if (client != null) {

					client.getConnection().send(pdu);
					log.debug("Login- oder Logout-Event-PDU an " + client.getUserName() + " gesendet");
					clients.incrNumberOfSentChatEvents(client.getUserName());
					eventCounter.getAndIncrement();
				}
			} catch (Exception e) {
				log.error("Senden einer Login- oder Logout-Event-PDU an " + s + " nicht moeglich");
				ExceptionHandler.logException(e);
			}
		}
	}

	@Override
	protected void loginRequestAction(ChatPDU receivedPdu) {
		ChatPDU pdu;
		log.debug("Login-Request-PDU für " + receivedPdu.getUserName() + " empfangen" + "\n" + receivedPdu);

		// Neuer Client moechte sich einloggen, Client in Client-Liste
		// eintragen
		if (!clients.existsClient(receivedPdu.getUserName())) {
			log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
			ClientListEntry client = new ClientListEntry(receivedPdu.getUserName(), connection);
			client.setLoginTime(System.nanoTime());
			clients.createClient(receivedPdu.getUserName(), client);
			clients.changeClientStatus(receivedPdu.getUserName(), ClientConversationStatus.REGISTERING);
			log.debug("User " + receivedPdu.getUserName() + " nun in Clientliste");

			userName = receivedPdu.getUserName();
			clientThreadName = receivedPdu.getClientThreadName();
			Thread.currentThread().setName(receivedPdu.getUserName());
			log.debug("Laenge der Clientliste: " + clients.size());
			serverGuiInterface.incrNumberOfLoggedInClients();

			// Warteliste der eingeloggten User ermitteln
			clients.createWaitList(userName);

			// Login-Event an alle Clients (auch an den gerade aktuell
			// Anfragenden) senden
			Vector<String> clientList = clients.getClientNameList();
			pdu = ChatPDU.createLoginEventPdu(userName, clientList, receivedPdu);
			sendLoginListUpdateEvent(pdu);
			log.debug("Login-Event-PDU für " + receivedPdu.getEventUserName() + "an alle angemeldeten und"
					+ "sich anmeldenden Clients senden. \n" + pdu);

		} else {
			// User bereits angemeldet, Fehlermeldung an Client senden,
			pdu = ChatPDU.createLoginErrorResponsePdu(receivedPdu, ChatPDU.LOGIN_ERROR);

			try {
				connection.send(pdu);
				log.debug("Login-Response-PDU an " + receivedPdu.getUserName() + " mit Fehlercode "
						+ ChatPDU.LOGIN_ERROR + " gesendet");
			} catch (Exception e) {
				log.debug("Senden einer Login-Response-PDU an " + receivedPdu.getUserName() + " nicth moeglich");
				ExceptionHandler.logExceptionAndTerminate(e);
			}
		}
	}

	@Override
	protected void logoutRequestAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu " + receivedPdu);
		ChatPDU pdu;
		// LogoutCounter für Benchmarking erhöhen
		logoutCounter.getAndIncrement();
		log.debug("Logout-Request von " + receivedPdu.getUserName() + "empfangen" + ", LogoutCount = "
				+ logoutCounter.get());

		if (!clients.existsClient(userName)) {
			log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
		} else {

			Vector<String> clientList = clients.getClientNameList();
			// LogoutEventPdu erstellen
			pdu = ChatPDU.createLogoutEventPdu(userName, clientList, receivedPdu);
			log.debug("Erstellte Pdu " + pdu); // AG
			// Status des Clients ändern in Unregistering
			clients.changeClientStatus(receivedPdu.getUserName(), ClientConversationStatus.UNREGISTERING);
			// Event an Clients versenden
			sendLoginListUpdateEvent(pdu);
			serverGuiInterface.decrNumberOfLoggedInClients();

		}
	}

	@Override
	protected void chatMessageRequestAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu " + receivedPdu);
		ClientListEntry client = null;
		clients.setRequestStartTime(receivedPdu.getUserName(), startTime);
		clients.incrNumberOfReceivedChatMessages(receivedPdu.getUserName());
		serverGuiInterface.incrNumberOfRequests();
		log.debug("Chat-Message-Request-PDU von " + receivedPdu.getUserName() + " mit Sequenznummer "
				+ receivedPdu.getSequenceNumber() + " empfangen");

		if (!clients.existsClient(receivedPdu.getUserName())) {
			log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
		} else {
			// Erstellen einer Waitlist
			clients.createWaitList(userName);

			// Liste der betroffenen Clients ermitteln
			Vector<String> sendList = clients.getClientNameList();

			// ChatMessageEventPdu erstellen
			ChatPDU pdu = ChatPDU.createChatMessageEventPdu(userName, receivedPdu);
			log.debug("ErstelltePdu " + pdu);

			// Event an Clients senden
			for (String s : new Vector<String>(sendList)) {
				client = clients.getClient(s);
				try {
					if ((client != null) && (client.getStatus() != ClientConversationStatus.UNREGISTERED)) {
						pdu.setUserName(client.getUserName());
						client.getConnection().send(pdu);
						log.debug("Chat-Event-PDU an " + client.getUserName() + " gesendet");
						// Event Counter für Benchmarking erhöhen
						clients.incrNumberOfSentChatEvents(client.getUserName());
						eventCounter.getAndIncrement();
						log.debug(userName + ": EventCounter erhoeht = " + eventCounter.get()
								+ ", Aktueller ConfirmCounter = " + confirmCounter.get()
								+ ", Anzahl gesendeter ChatMessages von dem Client = "
								+ receivedPdu.getSequenceNumber());
					}
				} catch (Exception e) {
					log.debug("Senden einer Chat-Event-PDU an " + client.getUserName() + " nicht moeglich");
					ExceptionHandler.logException(e);
				}
			}

			log.debug("Aktuelle Laenge der Clientliste: " + clients.size());
		}
	}

	/**
	 * Verbindung zu einem Client ordentlich abbauen
	 */
	private void closeConnection() {

		log.debug("Schliessen der Chat-Connection zum " + userName);

		// Bereinigen der Clientliste falls erforderlich

		if (clients.existsClient(userName)) {
			log.debug("Close Connection fuer " + userName
					+ ", Laenge der Clientliste vor dem bedingungslosen Loeschen: " + clients.size());

			clients.deleteClientWithoutCondition(userName);
			log.debug(
					"Laenge der Clientliste nach dem bedingungslosen Loeschen von " + userName + ": " + clients.size());
		}

		try {
			connection.close();
		} catch (Exception e) {
			log.debug("Exception bei close");
			// ExceptionHandler.logException(e);
		}
	}

	/**
	 * Antwort-PDU fuer den initiierenden Client aufbauen und senden
	 * 
	 * @param eventInitiatorClient
	 *            Name des Clients
	 */
	private void sendLogoutResponse(String eventInitiatorClient) {

		ClientListEntry client = clients.getClient(eventInitiatorClient);

		if (client != null) {
			ChatPDU responsePdu = ChatPDU.createLogoutResponsePdu(eventInitiatorClient, 0, 0, 0, 0,
					client.getNumberOfReceivedChatMessages(), clientThreadName);
			log.debug("Erstellte Pdu " + responsePdu); // AG
			log.debug(eventInitiatorClient + ": SentEvents aus Clientliste: " + client.getNumberOfSentEvents()
					+ ": ReceivedConfirms aus Clientliste: " + client.getNumberOfReceivedEventConfirms());
			try {
				clients.getClient(eventInitiatorClient).getConnection().send(responsePdu);
			} catch (Exception e) {
				log.debug("Senden einer Logout-Response-PDU an " + eventInitiatorClient + " fehlgeschlagen");
				log.debug("Exception Message: " + e.getMessage());
			}

			log.debug("Logout-Response-PDU an Client " + eventInitiatorClient + " gesendet");
		}
	}

	/**
	 * Prueft, ob Clients aus der Clientliste geloescht werden koennen
	 * 
	 * @return boolean, true: Client geloescht, false: Client nicht geloescht
	 */
	private boolean checkIfClientIsDeletable() {

		ClientListEntry client;

		// Worker-Thread beenden, wenn sein Client schon abgemeldet ist
		if (userName != null) {
			client = clients.getClient(userName);
			if (client != null) {
				if (client.isFinished()) {
					// Loesche den Client aus der Clientliste
					// Ein Loeschen ist aber nur zulaessig, wenn der Client
					// nicht mehr in einer anderen Warteliste ist
					log.debug("Laenge der Clientliste vor dem Entfernen von " + userName + ": " + clients.size());
					if (clients.deleteClient(userName) == true) {
						// Jetzt kann auch Worker-Thread beendet werden

						log.debug("Laenge der Clientliste nach dem Entfernen von " + userName + ": " + clients.size());
						log.debug("Worker-Thread fuer " + userName + " zum Beenden vorgemerkt");
						return true;
					}
				}
			}
		}

		// Garbage Collection in der Clientliste durchfuehren
		Vector<String> deletedClients = clients.gcClientList();
		if (deletedClients.contains(userName)) {
			log.debug("Ueber Garbage Collector ermittelt: Laufender Worker-Thread fuer " + userName
					+ " kann beendet werden");
			finished = true;
			return true;
		}
		return false;
	}

	@Override
	protected void handleIncomingMessage() throws Exception {
		if (checkIfClientIsDeletable() == true) {
			return;
		}

		// Warten auf naechste Nachricht
		ChatPDU receivedPdu = null;

		// Nach einer Minute wird geprueft, ob Client noch eingeloggt ist
		final int RECEIVE_TIMEOUT = 1200000;

		try {
			receivedPdu = (ChatPDU) connection.receive(RECEIVE_TIMEOUT);
			// Nachricht empfangen
			// Zeitmessung fuer Serverbearbeitungszeit starten
			startTime = System.nanoTime();
			log.debug(startTime + "nano zeit");

		} catch (ConnectionTimeoutException e) {

			// Wartezeit beim Empfang abgelaufen, pruefen, ob der Client
			// ueberhaupt noch etwas sendet
			log.debug("Timeout beim Empfangen, " + RECEIVE_TIMEOUT + " ms ohne Nachricht vom Client");

			if (clients.getClient(userName) != null) {

				if (clients.getClient(userName).getStatus() == ClientConversationStatus.UNREGISTERING) {
					// Worker-Thread wartet auf eine Nachricht vom Client, aber es
					// kommt nichts mehr an
					log.error("Client ist im Zustand UNREGISTERING und bekommt aber keine Nachricht mehr");
					// Zur Sicherheit eine Logout-Response-PDU an Client senden
					sendLogoutResponse(receivedPdu.getEventUserName());
					log.debug("Logout-Response-PDU wurde nochmals an " + receivedPdu.getEventUserName()
							+ "gesendet da Worker-Thread auf Nachricht vom Client wartet aber nichts mehr ankommt");
					// Worker-Thread wird beendet
					finished = true;
				}
				// Komplexer Logout: Implementierungsansatz für folgenden Fall: Client meldet
				// sich ab, obwohl er noch in einer Waitlist enthalten ist
			} else {

				// if (clients.deletable(receivedPdu.getUserName())== false){
				// HashSet<String> waitList = clients.getWaitLists(receivedPdu.getUserName());
				// clients.deleteClientWithoutCondition(receivedPdu.getUserName());
				// for (String s: waitList) {
				// if (clients.getWaitListSize(s) == 0) {
				// if (clients.getClientStatus(s)== ClientConversationStatus.REGISTERING) {
				// ChatPDU responsePdu = ChatPDU.createLoginResponsePdu(s, receivedPdu);
				// clients.getClient(s).getConnection().send(responsePdu);
				// } else if (clients.getClientStatus(s) == ClientConversationStatus.REGISTERED)
				// {
				// ClientListEntry c = clients.getClient(s);
				// ChatPDU responsePdu =
				// ChatPDU.createChatMessageResponsePdu(receivedPdu.getEventUserName(), 0, 0, 0,
				// 0, 
				// c.getNumberOfReceivedChatMessages(), receivedPdu.getClientThreadName(),
				// (System.nanoTime() - c.getStartTime()));
				// clients.getClient(s).getConnection().send(responsePdu);
				// } else if (clients.getClientStatus(s) ==
				// ClientConversationStatus.UNREGISTERING) {
				// sendLogoutResponse(s);
				// } 
				// }
				// }
				// }

	
			}

			return;

		} catch (EndOfFileException e) {
			log.debug("End of File beim Empfang, vermutlich Verbindungsabbau des Partners fuer " + userName);
			finished = true;
			return;

		} catch (java.net.SocketException e) {
			log.error("Verbindungsabbruch beim Empfang der naechsten Nachricht vom Client " + getName());
			finished = true;
			return;

		} catch (Exception e) {
			log.error("Empfang einer Nachricht fehlgeschlagen, Workerthread fuer User: " + userName);
			ExceptionHandler.logException(e);
			finished = true;
			return;
		}

		// Empfangene Nachricht bearbeiten
		try {
			
			switch (receivedPdu.getPduType()) {

			case LOGIN_REQUEST:
				log.debug("Empfangene Nachricht in Switch Case LOGIN_REQUEST");
				// Login-Request vom Client empfangen
				loginRequestAction(receivedPdu);
				break;

			case CHAT_MESSAGE_REQUEST:
				log.debug("Empfangene Nachricht in Switch Case CHAT_MESSAGE_REQUEST");
				// Chat-Nachricht angekommen, an alle verteilen
				chatMessageRequestAction(receivedPdu);
				break;

			case CHAT_MESSAGE_CONFIRM:
				log.debug("Empfangene Nachricht in Switch Case CHAT_MESSAGE_CONFIRM");
				// chat Nachricht beim Client angekommen
				chatMessageConfirmAction(receivedPdu);
				break;

			case LOGOUT_REQUEST:
				log.debug("Empfangene Nachricht in Switch Case LOGOUT_REQUEST");
				// Logout-Request vom Client empfangen
				logoutRequestAction(receivedPdu);
				break;

			case LOGIN_CONFIRM:
				log.debug("Empfangene Nachricht in Switch Case LOGIN_CONFIRM");
				// Login-Confirm von Client empfangen
				loginConfirmAction(receivedPdu);
				break;

			case LOGOUT_CONFIRM:
				log.debug("Empfangene Nachricht in Switch Case LOGOUT_CONFIRM");
				// Logout-Confirm von Client empfangen
				logoutConfirmAction(receivedPdu);
				break;

			default:
				log.debug("Falsche PDU empfangen von Client: " + receivedPdu.getUserName() + ", PduType: "
						+ receivedPdu.getPduType());
				break;
			}
		} catch (Exception e) {
			log.error("Exception bei der Nachrichtenverarbeitung");
			ExceptionHandler.logExceptionAndTerminate(e);
		}
	}

	
	/**
	 * Verschickt die Bestätigung das alle die Chat-Nachricht erhalten haben, wenn
	 * alle Clients das ChatMessage-Event bestätigt haben
	 * 
	 * @param receivedPdu
	 *            erhaltende ChatMessage-Confirm-PDU
	 */
	private void chatMessageConfirmAction(ChatPDU receivedPdu) {
		
		log.debug("Empfangene PDU " + receivedPdu);
		//Counter für Benchmarking erhöhen
		clients.incrNumberOfReceivedChatEventConfirms(receivedPdu.getEventUserName());
		confirmCounter.getAndIncrement();
		log.debug("Chat Message Confirm PDU von " + receivedPdu.getUserName() + " für User "
				+ receivedPdu.getEventUserName() + " empfangen.");
		log.debug("so viele Confirms" + confirmCounter + "werden gesendet");

		try {
			log.debug("Event User Name: " + receivedPdu.getEventUserName());
			log.debug("Größe vor Löschen" + clients.getWaitListSize(receivedPdu.getEventUserName()));
			//Client aus Warteliste löschen
			clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName);
			log.debug("Größe nach Löschen" + clients.getWaitListSize(receivedPdu.getEventUserName()));
			// Überprüfen, ob Wartelistegröße 0 ist
			if (clients.getWaitListSize(receivedPdu.getEventUserName()) == 0) {
				// Bekomme die Liste aller Clients
				ClientListEntry clientList = clients.getClient(receivedPdu.getEventUserName());
				// Testen ob überhaupt Clients vorhanden
				if (clientList != null) {
					// Erstellen der ResponsePDU
					ChatPDU responsePdu = ChatPDU.createChatMessageResponsePdu(receivedPdu.getEventUserName(), 0, 0, 0,
							0, clientList.getNumberOfReceivedChatMessages(), receivedPdu.getClientThreadName(),
							(System.nanoTime() - clientList.getStartTime())); 
					log.debug("Erstellte Pdu " + responsePdu);
				
					if (responsePdu.getServerTime() / 1000000 > 100) {
						log.debug(Thread.currentThread().getName()
								+ ", Benoetigte Serverzeit vor dem Senden der Response-Nachricht > 100 ms: "
								+ responsePdu.getServerTime() + " ns = " + responsePdu.getServerTime() / 1000000
								+ " ms");
					}

					try {
						// Senden der ResonsePDU
						clients.getClient(receivedPdu.getEventUserName()).getConnection().send(responsePdu);
						log.debug("Chat-Message-Response-PDU an " + receivedPdu.getEventUserName() + " gesendet"); 
						

					} catch (Exception e) {
						log.debug("Senden einer Chat-Message-Response-PDU an " + receivedPdu.getEventUserName()
								+ " nicht moeglich"); 
						ExceptionHandler.logExceptionAndTerminate(e);
					}
				}
			}
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
	}

	

	/**
	 * Verschickt die Login-Response-PDU als Zeichen das sich der Client anmelden
	 * darf, wenn alle Clients das Login-Event bestätigt haben
	 * 
	 * @param receivedPdu
	 *            erhaltene Login-Confirm-PDU
	 */
	private void loginConfirmAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu " + receivedPdu);
		//Counter für Benchmarking erhöhen
		clients.incrNumberOfReceivedChatEventConfirms(receivedPdu.getEventUserName()); 
		confirmCounter.getAndIncrement();
		log.debug("Login Confirm PDU von " + receivedPdu.getEventUserName() + " für User " + receivedPdu.getUserName()
				+ " empfangen.");
		log.debug("so viele Confirms" + confirmCounter + "werden gesendet");

		try {
			// löscht Client, der Nachricht bestätigt hat, aus der WArteliste raus
			clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName);

			// Überprüfen, ob Wartelistegröße 0 ist
			if (clients.getWaitListSize(receivedPdu.getEventUserName()) == 0) {
				// bekomme die Liste aller Clients
				ClientListEntry clientList = clients.getClient(receivedPdu.getEventUserName());

				if (clientList != null) {
					// Erstellen der ResponsePDU
					ChatPDU responsePdu = ChatPDU.createLoginResponsePdu(receivedPdu.getEventUserName(), receivedPdu); 
					log.debug("Erstellte Pdu " + responsePdu);
					try {
						// Senden der ResponsePDU
						clients.getClient(receivedPdu.getEventUserName()).getConnection().send(responsePdu);
						log.debug("LoginResponse Pdu wurde gesendet an " + responsePdu.getUserName());

					} catch (Exception e) {
						log.debug("Senden einer Login-Response-PDU an " + userName + " fehlgeschlagen");
						log.debug("Exception Message: " + e.getMessage());
						ExceptionHandler.logExceptionAndTerminate(e);
					}

					log.debug("Login-Response-PDU an Client " + userName + " gesendet");

					// Zustand des Clients ändern
					clients.changeClientStatus(userName, ClientConversationStatus.REGISTERED);

				}
			}
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}

	}

	/**
	 * Verschickt die Logout-Response-PDU als Zeichen das sich der Client ausloggen
	 * darf, wenn alle Clients das Logout-Event bestätigt haben
	 * 
	 * @param receivedPdu
	 *            erhaltene Logout-Confirm-PDU
	 */
	private void logoutConfirmAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu " + receivedPdu);
		//Counter für Benchmarking erhöhen
		clients.incrNumberOfReceivedChatEventConfirms(receivedPdu.getEventUserName());
		confirmCounter.getAndIncrement();
		log.debug("Logout Confirm PDU von " + receivedPdu.getUserName() + " für User " + receivedPdu.getEventUserName()
				+ " empfangen.");
		log.debug("so viele Confirms" + confirmCounter + "werden gesendet");

		try {
			// Löscht Client, der Nachricht bestätigt hat aus der Warteliste raus
			clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName);
			
			// Überprüfen, ob Wartelistegröße 0 ist
			if (clients.getWaitListSize(receivedPdu.getEventUserName()) == 0) {
				// bekomme die Liste aller Clients
				ClientListEntry clientList = clients.getClient(receivedPdu.getEventUserName());

				if (clientList != null) {
					
					try {
						// Der Thread muss hier noch warten, bevor ein Logout-Response gesendet
						// wird, da sich sonst ein Client abmeldet, bevor er seinen letzten Event
						// empfangen hat. das funktioniert nicht bei einer grossen Anzahl an
						// Clients (kalkulierte Events stimmen dann nicht mit tatsaechlich
						// empfangenen Events ueberein.
						// In der Advanced-Variante wird noch ein Confirm gesendet, das ist
						// sicherer.

						Thread.sleep(1000);
						log.debug("Zeit ist abgelaufen!");

					} catch (Exception e) {
						ExceptionHandler.logException(e);
					}
					//Status des Clients ändern
					clients.changeClientStatus(receivedPdu.getEventUserName(), ClientConversationStatus.UNREGISTERED);
					
					//LogoutResponse erstellen und senden
					sendLogoutResponse(receivedPdu.getEventUserName());

					clients.finish(receivedPdu.getEventUserName());
					log.debug("Laenge der Clientliste beim Vormerken zum Loeschen von " + receivedPdu.getUserName()
							+ ": " + clients.size());
		
				}
			}
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
	}
}
