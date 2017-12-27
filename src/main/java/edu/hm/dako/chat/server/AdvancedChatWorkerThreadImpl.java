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
		log.debug("Login-Request-PDU für " + receivedPdu.getUserName() + " empfangen" + "\n" + receivedPdu );

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

			// Warteliste der eingeloggten User ermitteln AJ
			clients.createWaitList(userName);

			// Login-Event an alle Clients (auch an den gerade aktuell
			// anfragenden) senden
			Vector<String> clientList = clients.getClientNameList();
			pdu = ChatPDU.createLoginEventPdu(userName, clientList, receivedPdu);
			sendLoginListUpdateEvent(pdu);
			log.debug("Login-Event-PDU für " + receivedPdu.getEventUserName() + "an alle angemeldeten und"
					+ "sich anmeldenden Clients senden. \n" + pdu); //AJ

			
			
			// Login Response senden
//			ChatPDU responsePdu = ChatPDU.createLoginResponsePdu(userName, receivedPdu); // AG brauchen wir hier nicht
//			
//
//			try {
//				clients.getClient(userName).getConnection().send(responsePdu);
//			} catch (Exception e) {
//				log.debug("Senden einer Login-Response-PDU an " + userName + " fehlgeschlagen");
//				log.debug("Exception Message: " + e.getMessage());
//			}
//
//			log.debug("Login-Response-PDU an Client " + userName + " gesendet");

			// Zustand des Clients aendern
			//clients.changeClientStatus(userName, ClientConversationStatus.REGISTERED); 

		} else {
			// User bereits angemeldet, Fehlermeldung an Client senden,
			// Fehlercode an Client senden
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
		log.debug("Empfangene Pdu " + receivedPdu); //AG
		log.debug(clients.getClientNameList());
		ChatPDU pdu;
		logoutCounter.getAndIncrement();
		log.debug("Logout-Request von " + receivedPdu.getUserName() + ", LogoutCount = " + logoutCounter.get());

		log.debug("Logout-Request-PDU von " + receivedPdu.getUserName() + " empfangen");

		if (!clients.existsClient(userName)) {
			log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
		} else {

			// Event an Client versenden
			Vector<String> clientList = clients.getClientNameList();
			pdu = ChatPDU.createLogoutEventPdu(userName, clientList, receivedPdu);
			log.debug("Erstellte Pdu " + pdu); //AG
			
			clients.changeClientStatus(receivedPdu.getUserName(), ClientConversationStatus.UNREGISTERING); 
			sendLoginListUpdateEvent(pdu);
			serverGuiInterface.decrNumberOfLoggedInClients();

			// Der Thread muss hier noch warten, bevor ein Logout-Response gesendet
			// wird, da sich sonst ein Client abmeldet, bevor er seinen letzten Event
			// empfangen hat. das funktioniert nicht bei einer grossen Anzahl an
			// Clients (kalkulierte Events stimmen dann nicht mit tatsaechlich
			// empfangenen Events ueberein.
			// In der Advanced-Variante wird noch ein Confirm gesendet, das ist
			// sicherer.

//			try {
//				Thread.sleep(1000);
////				log.debug("Zeit ist abgelaufen! Logout-Response an " +  + "wird abgeschickt!");
//			} catch (Exception e) {
//				ExceptionHandler.logException(e);
//			}

//			clients.changeClientStatus(receivedPdu.getUserName(), ClientConversationStatus.UNREGISTERED);
//
//			// Logout Response senden
//			sendLogoutResponse(receivedPdu.getUserName());
//
//			// Worker-Thread des Clients, der den Logout-Request gesendet
//			// hat, auch gleich zum Beenden markieren
//			clients.finish(receivedPdu.getUserName());
//			log.debug("Laenge der Clientliste beim Vormerken zum Loeschen von " + receivedPdu.getUserName() + ": "
//					+ clients.size());
		}
	}
	

	@Override
	protected void chatMessageRequestAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu " + receivedPdu); //AG
		ClientListEntry client = null; 
		clients.setRequestStartTime(receivedPdu.getUserName(), startTime);
		clients.incrNumberOfReceivedChatMessages(receivedPdu.getUserName());
		serverGuiInterface.incrNumberOfRequests();
		log.debug("Chat-Message-Request-PDU von " + receivedPdu.getUserName() + " mit Sequenznummer "
				+ receivedPdu.getSequenceNumber() + " empfangen");

		if (!clients.existsClient(receivedPdu.getUserName())) {
			log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
		} else {
			//AG: Erstellen einer Waitlist, an richtiger Stelle?
			clients.createWaitList(userName); 
			
			// Liste der betroffenen Clients ermitteln
			Vector<String> sendList = clients.getClientNameList();
						
			ChatPDU pdu = ChatPDU.createChatMessageEventPdu(userName, receivedPdu);
			log.debug("ErstelltePdu " + pdu); //AG
			// Event an Clients senden
			for (String s : new Vector<String>(sendList)) {
				client = clients.getClient(s);
				try {
					if ((client != null) && (client.getStatus() != ClientConversationStatus.UNREGISTERED)) {
						pdu.setUserName(client.getUserName());
						client.getConnection().send(pdu);
						log.debug("Chat-Event-PDU an " + client.getUserName() + " gesendet");
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
//			AG: auskommentiert da hier nicht mehr response pdu senden
//				client = clients.getClient(receivedPdu.getUserName());
//			if (client != null ) { 
//				ChatPDU responsePdu = ChatPDU.createChatMessageResponsePdu(receivedPdu.getUserName(), 0, 0, 0, 0,
//						client.getNumberOfReceivedChatMessages(), receivedPdu.getClientThreadName(),
//						(System.nanoTime() - client.getStartTime()));
//
//				if (responsePdu.getServerTime() / 1000000 > 100) {
//					log.debug(Thread.currentThread().getName()
//							+ ", Benoetigte Serverzeit vor dem Senden der Response-Nachricht > 100 ms: "
//							+ responsePdu.getServerTime() + " ns = " + responsePdu.getServerTime() / 1000000 + " ms");
//				}
//
//				try {
//					client.getConnection().send(responsePdu);
//					log.debug("Chat-Message-Response-PDU an " + receivedPdu.getUserName() + " gesendet");
//				} catch (Exception e) {
//					log.debug("Senden einer Chat-Message-Response-PDU an " + client.getUserName() + " nicht moeglich");
//					ExceptionHandler.logExceptionAndTerminate(e);
//				}
		//	}
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
			log.debug("Erstellte Pdu " + responsePdu); //AG
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
		final int RECEIVE_TIMEOUT = 1200000; //eigentlich 1200000
		
		//AG Timeout für Überprüfen, ob ein Client in einer Waitlist enthalten ist (10 Sekunden)
		//AG,LSfinal int WAIT_TIMEOUT = 100;

		//JA muss evtl noch eine if-klausel oder etwas anderes um den try catch block?
		// -> starten vom server: springt dauerhaft in catch block und im log wird dauerhaft der Satz
		// Timeout beim Empfangen, ... angezeigt
		try {
			receivedPdu = (ChatPDU) connection.receive(RECEIVE_TIMEOUT);
			//LS AG  receivedPdu = (ChatPDU) connection.receive(WAIT_TIMEOUT); // AG: 2 Timer gleichzeitig möglich???
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
					// JA
					sendLogoutResponse(receivedPdu.getEventUserName());
					log.debug("Logout-Response-PDU wurde nochmals an " + receivedPdu.getEventUserName() +
							"gesendet da Worker-Thread auf Nachricht vom Client wartet aber nichts mehr ankommt");
					// Worker-Thread wird beendet
					finished = true;
				} 
				//AG eventuell hier noch was für logout
			} else { 
				//LS, AG
//				if (clients.deletable(receivedPdu.getUserName())== false){
//					HashSet<String> waitList = clients.getWaitLists(receivedPdu.getUserName());
//					clients.deleteClientWithoutCondition(receivedPdu.getUserName());
//					for (String s: waitList) {
//						// JA: Wenn die WarteListe gleich 0 ist, wie sollte der Client der drinnen ist noch einen Status haben?
//						// Bzw da müsste doch rein theoretisch kein Client mehr drinnen sein? -> != 0
//						if (clients.getWaitListSize(s) == 0) {
//							if (clients.getClientStatus(s)== ClientConversationStatus.REGISTERING) {
//								ChatPDU responsePdu = ChatPDU.createLoginResponsePdu(s, receivedPdu);
//								clients.getClient(s).getConnection().send(responsePdu);
//							} else if (clients.getClientStatus(s) == ClientConversationStatus.REGISTERED) {
//								ClientListEntry c = clients.getClient(s);
//								ChatPDU responsePdu = ChatPDU.createChatMessageResponsePdu(receivedPdu.getEventUserName(), 0, 0, 0, 0, //Ag geändert von UserName
//										c.getNumberOfReceivedChatMessages(), receivedPdu.getClientThreadName(),
//										(System.nanoTime() - c.getStartTime()));
//								clients.getClient(s).getConnection().send(responsePdu);
//							} else if (clients.getClientStatus(s) == ClientConversationStatus.UNREGISTERING) {
//								sendLogoutResponse(s);
//							} else if (clients.getClientStatus(s) == ClientConversationStatus.UNREGISTERED) {
//							// JA
//							// Fall: Client-Status schon disconnectet zum Server dann gezwungermaßen löschen
//							// Status gleich UNREGISTERED
////								waitList.deletWaitListEntry()
//							

//							}
//						}
//					}
					
//				}
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
//			// JA Timersetzen wann letzte Request gekommen ist, bin mir nicht so sicher ob das unfug ist oder nicht???
//			lastRequestTime = System.currentTimeMillis();
//			 log.debug("Zeitpunkt wann zuletzt eine Request angekommen ist: " + lastRequestTime);
			
			switch (receivedPdu.getPduType()) {

			case LOGIN_REQUEST:
				// JA
				 log.debug("Empfangene Nachricht in Switch Case LOGIN_REQUEST");
				// Login-Request vom Client empfangen
				loginRequestAction(receivedPdu);
				break;

			case CHAT_MESSAGE_REQUEST:
				// JA
				 log.debug("Empfangene Nachricht in Switch Case CHAT_MESSAGE_REQUEST");
				// Chat-Nachricht angekommen, an alle verteilen
				chatMessageRequestAction(receivedPdu);
				break;
				
			case CHAT_MESSAGE_CONFIRM:
				// JA
				 log.debug("Empfangene Nachricht in Switch Case CHAT_MESSAGE_CONFIRM");
				// chat Nachricht beim Client angekommen
				chatMessageConfirmAction(receivedPdu);
				break;

			case LOGOUT_REQUEST:
				// JA
				 log.debug("Empfangene Nachricht in Switch Case LOGOUT_REQUEST");
				// Logout-Request vom Client empfangen
				logoutRequestAction(receivedPdu);
				break;

			 case LOGIN_CONFIRM:
				 // JA, Syso "umgeschrieben" als log.debug
				 log.debug("Empfangene Nachricht in Switch Case LOGIN_CONFIRM");
				 // Login-Confirm von Client empfangen
				 loginConfirmAction(receivedPdu);
				 break;
			 
			//AG 
			case LOGOUT_CONFIRM:
				// JA
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

	
	// Methode um Messages zu confirmen, sendet eine responsePDU an die CLients,
	// nachdem er sie aus der Liste gelöscht hat AL
	/**
	 * Verschickt die Bestätigung das alle die Chat-Nachricht erhalten haben,
	 * 				wenn alle Clients das ChatMessage-Event bestätigt haben
	 * 
	 * @param receivedPdu
	 * 			erhaltende ChatMessage-Confirm-PDU
	 */
	private void chatMessageConfirmAction(ChatPDU receivedPdu) {
	    //SharedChatClientList client = SharedChatClientList.getInstance(); //AG auskommentiert
		log.debug("Empfangene PDU " + receivedPdu); //AG
		clients.incrNumberOfReceivedChatEventConfirms(receivedPdu.getEventUserName());
		confirmCounter.getAndIncrement();
		log.debug("Chat Message Confirm PDU von " + receivedPdu.getEventUserName() + " für User "
				+ receivedPdu.getUserName() + " empfangen.");
		System.out.println("chat Message Confirm empfangen von" + userName);
		log.debug("so viele Confirms" + confirmCounter + "werden gesendet");

		try {
			// lösche clients aus Waitlist AL
			System.out.println("Event User Name: " + receivedPdu.getEventUserName());
			System.out.println("Größe vor Löschen" + clients.getWaitListSize(receivedPdu.getEventUserName()));
			clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName); //receivedPdu.getUserName()
			System.out.println("Größe nach Löschen" + clients.getWaitListSize(receivedPdu.getEventUserName()));
			// Wenn Waitlist aus 0, dann.... AL
			if (clients.getWaitListSize(receivedPdu.getEventUserName()) == 0) {
				// bekomme die Liste aller Clients
				ClientListEntry clientList = clients.getClient(receivedPdu.getEventUserName());
				// testen ob überhaupt Clients vorhanden AL
				if (clientList != null) {
					// erstelle response PDU AL
					ChatPDU responsePdu = ChatPDU.createChatMessageResponsePdu(receivedPdu.getEventUserName(), 0, 0, 0, 0,
							clientList.getNumberOfReceivedChatMessages(), receivedPdu.getClientThreadName(),
							(System.nanoTime() - clientList.getStartTime())); //JA: noch in die Klamma: receivedPdu
					log.debug("Erstellte Pdu " + responsePdu); //AG
					//AG: Übernahme aus loginRequestAction()
					if (responsePdu.getServerTime() / 1000000 > 100) {
						log.debug(Thread.currentThread().getName()
								+ ", Benoetigte Serverzeit vor dem Senden der Response-Nachricht > 100 ms: "
								+ responsePdu.getServerTime() + " ns = " + responsePdu.getServerTime() / 1000000 + " ms");
					}
					
					try {
						// sende response PDU AL
						clients.getClient(receivedPdu.getEventUserName()).getConnection().send(responsePdu);
						log.debug("Chat-Message-Response-PDU an " + receivedPdu.getUserName() + " gesendet"); //AG: eingefügt aus chatMessageRequestAction()
                        System.out.println("Chat-Message-Response-PDU an " + responsePdu.getUserName() + " gesendet"); // AG: wird an richtigen gesendet?
                        
					} catch (Exception e) {
						log.debug("Senden einer Chat-Message-Response-PDU an " + receivedPdu.getUserName() + " nicht moeglich"); //AG: eingefügt aus chatMessageRequestAction
						ExceptionHandler.logExceptionAndTerminate(e);
					}
				}
			}
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
	}

	// AG: Methode um Login zu bestätigen, sendet eine responsePDU an den Client, der sich einloggen will
	// nachdem er sie aus der Liste gelöscht hat AG
	
	/**
	 * Verschickt die Login-Response-PDU als Zeichen das sich der Client anmelden darf,
	 * 				wenn alle Clients das Login-Event bestätigt haben
	 *  
	 * @param receivedPdu
	 * 				erhaltene Login-Confirm-PDU
	 */
	private void loginConfirmAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu " + receivedPdu); //AG
		clients.incrNumberOfReceivedChatEventConfirms(receivedPdu.getEventUserName()); //Ag: falscher Counter
		confirmCounter.getAndIncrement();
		log.debug("Login Confirm PDU von " + receivedPdu.getEventUserName() + " für User "
				+ receivedPdu.getUserName() + " empfangen.");
		log.debug("so viele Confirms" + confirmCounter + "werden gesendet");

		try {
			// löscht Client, der Nachricht bestätigt hat aus der Liste raus
			clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName);

			// Wenn Waitlist leer ist AG
			if (clients.getWaitListSize(receivedPdu.getEventUserName()) == 0) {
				// bekomme die Liste aller Clients AG
				ClientListEntry clientList = clients.getClient(receivedPdu.getEventUserName());
				
				if (clientList != null) {
					// erstelle response PDU AL
					ChatPDU responsePdu = ChatPDU.createLoginResponsePdu(receivedPdu.getEventUserName(), receivedPdu); //AG geändert von UserName
					log.debug("Erstellte Pdu " + responsePdu); //AG
					try {
						// sende response PDU AL
						clients.getClient(receivedPdu.getEventUserName()).getConnection().send(responsePdu);
						System.out.println("LoginResponse Pdu wurde gesendet an "+ responsePdu.getUserName()); //AG
						
					} catch (Exception e) {
						log.debug("Senden einer Login-Response-PDU an " + userName + " fehlgeschlagen");
						log.debug("Exception Message: " + e.getMessage());
						ExceptionHandler.logExceptionAndTerminate(e); 
					}
					
					log.debug("Login-Response-PDU an Client " + userName + " gesendet");

					// AG: Zustand des Clients aendern 
					clients.changeClientStatus(userName, ClientConversationStatus.REGISTERED); 	
					
		
				}
			}
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
		
	}
	
	/**
	 * Verschickt die Logout-Response-PDU als Zeichen das sich der Client ausloggen darf,
	 * 				wenn alle Clients das Logout-Event bestätigt haben  
	 * 
	 * @param receivedPdu
	 * 				erhalltene Logout-Confirm-PDU
	 */
	private void logoutConfirmAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu " + receivedPdu); //AG
		System.out.println("In logoutConfirmAction");
		clients.incrNumberOfReceivedChatEventConfirms(receivedPdu.getEventUserName()); 
		confirmCounter.getAndIncrement(); 
		log.debug("Logout Confirm PDU von " + receivedPdu.getUserName() + " für User "
				+ receivedPdu.getEventUserName() + " empfangen.");
		log.debug("so viele Confirms" + confirmCounter + "werden gesendet");

		try {
			// löscht Client, der Nachricht bestätigt hat aus der Liste raus
			clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName);
			System.out.println("Löschen aus Waitlist");
			// Wenn Waitlist leer ist AG
			if (clients.getWaitListSize(receivedPdu.getEventUserName()) == 0) {
				// bekomme die Liste aller Clients AG
				ClientListEntry clientList = clients.getClient(receivedPdu.getEventUserName());
				
				if (clientList != null) {					
					// JA: Der Prof hat weiter oben in der Methode logoutRequestAction einen etwas
					// längeren Kommentar gelassen das er will das der Thread erst eine Zeitlang wartet
					// bis er die Logout-ResponsePDU abschickt
					// ich hab das jetzt mal in diese Methode gemacht da es meiner Meinung nach
					// mehr Sinn macht als da oben
					try {
						Thread.sleep(1000);
						log.debug("Zeit ist abgelaufen!");
						
					} catch (Exception e) {
						ExceptionHandler.logException(e);
					}
					
					clients.changeClientStatus(receivedPdu.getEventUserName(), ClientConversationStatus.UNREGISTERED); 
						
					sendLogoutResponse(receivedPdu.getEventUserName());
						
					clients.finish(receivedPdu.getEventUserName());
					log.debug("Laenge der Clientliste beim Vormerken zum Loeschen von " + receivedPdu.getUserName() + ": "
								+ clients.size());	
					// JA auskommentiert
//					// Logout Response senden
//						sendLogoutResponse(receivedPdu.getEventUserName());
//						System.out.println("Logoutresponse wurde gesendet an" + receivedPdu.getEventUserName());
//					
//					// Worker-Thread des Clients, der den Logout-Request gesendet
//					// hat, auch gleich zum Beenden markieren
//						clients.finish(receivedPdu.getUserName());
//						log.debug("Laenge der Clientliste beim Vormerken zum Loeschen von " + receivedPdu.getUserName() + ": "
//								+ clients.size());	
							
				}
			}
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
	}
}
