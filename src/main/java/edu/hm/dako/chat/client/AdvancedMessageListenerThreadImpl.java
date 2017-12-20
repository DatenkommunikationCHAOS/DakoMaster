package edu.hm.dako.chat.client;

import java.util.Vector;
import java.util.logging.Logger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.sun.xml.internal.ws.client.ClientSchemaValidationTube;

import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ClientConversationStatus;
import edu.hm.dako.chat.common.ClientListEntry;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;

/**
 * Thread wartet auf ankommende Nachrichten vom Server und bearbeitet diese.
 * 
 * @author Peter Mandl
 *
 */
public class AdvancedMessageListenerThreadImpl extends AbstractMessageListenerThread {

	private static Log log = LogFactory.getLog(AdvancedMessageListenerThreadImpl.class);

	public AdvancedMessageListenerThreadImpl(ClientUserInterface userInterface, Connection con,
			SharedClientData sharedData) {

		super(userInterface, con, sharedData);
	}

	@Override
	protected void loginResponseAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu "+ receivedPdu); //AG
		if (receivedPdu.getErrorCode() == ChatPDU.LOGIN_ERROR) {

			// Login hat nicht funktioniert
			log.error("Login-Response-PDU fuer Client " + receivedPdu.getUserName() + " mit Login-Error empfangen");
			userInterface.setErrorMessage("Chat-Server", "Anmelden beim Server nicht erfolgreich, Benutzer "
					+ receivedPdu.getUserName() + " vermutlich schon angemeldet", receivedPdu.getErrorCode());
			sharedClientData.status = ClientConversationStatus.UNREGISTERED;

			// Verbindung wird gleich geschlossen
			try {
				connection.close();
			} catch (Exception e) {
			}

		} else {
			// Login hat funktioniert
			sharedClientData.status = ClientConversationStatus.REGISTERED;

			userInterface.loginComplete();

			Thread.currentThread().setName("Listener" + "-" + sharedClientData.userName);
			log.debug("Login-Response-PDU fuer Client " + receivedPdu.getUserName() + " empfangen");
			System.out.println("Login REsponse Pdu für Client empfangen");
		}
	}

	@Override
	protected void loginEventAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu "+ receivedPdu);
		// Eventzaehler fuer Testzwecke erhoehen
		sharedClientData.eventCounter.getAndIncrement();
		int events = SharedClientData.loginEvents.incrementAndGet();

		log.debug(sharedClientData.userName + " erhaelt LoginEvent, LoginEventCounter: " + events);
		ChatPDU loginConfirmPdu = ChatPDU.createLoginEventConfirm(sharedClientData.userName, receivedPdu); //AG geändert von recPdu.getEventUserName()
		log.debug("Login Confirm Pdu wurde erstellt."); // AG
		log.debug("Erstellte Pdu "+ loginConfirmPdu); //AG
		try {
			connection.send(loginConfirmPdu); // AG Senden von ConfirmPdu
			log.debug("Login Confirm Pdu wurde gesendet von " + loginConfirmPdu.getUserName()); // AG
			handleUserListEvent(receivedPdu); // AG: Brauchen wir für Userlist in Gui anzeigen
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
		sharedClientData.confirmCounter.getAndIncrement();
	}

	@Override
	protected void logoutResponseAction(ChatPDU receivedPdu) {
// LS, AG eventuell noch eine Abdeckung für den speziellen Logoutfall?
		log.debug("Empfangene Pdu "+ receivedPdu); //AG
		log.debug(
				sharedClientData.userName + " empfaengt Logout-Response-PDU fuer Client " + receivedPdu.getUserName());
		sharedClientData.status = ClientConversationStatus.UNREGISTERED;

		userInterface.setSessionStatisticsCounter(sharedClientData.eventCounter.longValue(),
				sharedClientData.confirmCounter.longValue(), 0, 0, 0);

		log.debug("Vom Client gesendete Chat-Nachrichten:  " + sharedClientData.messageCounter.get());

		finished = true;
		userInterface.logoutComplete();
	}

	@Override
	protected void logoutEventAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu "+ receivedPdu); //AG
		// Eventzaehler fuer Testzwecke erhoehen
		sharedClientData.eventCounter.getAndIncrement();
		int events = SharedClientData.logoutEvents.incrementAndGet();

		log.debug("LogoutEventCounter: " + events);
		ChatPDU logoutConfirmPdu = ChatPDU.createLogoutEventConfirm(sharedClientData.userName, receivedPdu); //AG geändert von receivedPdu.getEventUserName()
		log.debug("Erstellte Pdu: " + logoutConfirmPdu); //AG
		try {
			connection.send(logoutConfirmPdu);
			System.out.println("Logout Confirm Pdu gesendet von" + "für Client " + receivedPdu.getEventUserName());
			handleUserListEvent(receivedPdu);
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
		sharedClientData.confirmCounter.getAndIncrement();
	}

	@Override
	protected void chatMessageResponseAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu " + receivedPdu); //AG
		log.debug("Sequenznummer der Chat-Response-PDU " + receivedPdu.getUserName() + ": "
				+ receivedPdu.getSequenceNumber() + ", Messagecounter: " + sharedClientData.messageCounter.get());

		log.debug(Thread.currentThread().getName()
				+ ", Benoetigte Serverzeit gleich nach Empfang der Response-Nachricht: " + receivedPdu.getServerTime()
				+ " ns = " + receivedPdu.getServerTime() / 1000000 + " ms");

		if (receivedPdu.getSequenceNumber() == sharedClientData.messageCounter.get()) {

			// Zuletzt gemessene Serverzeit fuer das Benchmarking
			// merken
			userInterface.setLastServerTime(receivedPdu.getServerTime());

			// Naechste Chat-Nachricht darf eingegeben werden
			userInterface.setLock(false);

			log.debug("Chat-Response-PDU fuer Client " + receivedPdu.getUserName() + " empfangen");

		} else {
			log.debug("Sequenznummer der Chat-Response-PDU " + receivedPdu.getUserName() + " passt nicht: "
					+ receivedPdu.getSequenceNumber() + "/" + sharedClientData.messageCounter.get());
		}

	}

	@Override
	protected void chatMessageEventAction(ChatPDU receivedPdu) {
		log.debug("Empfangene Pdu " + receivedPdu); //AG
		log.debug("Chat-Message-Event-PDU von " + receivedPdu.getEventUserName() + " empfangen");

		// Eventzaehler fuer Testzwecke erhoehen
		sharedClientData.eventCounter.getAndIncrement();
		int events = SharedClientData.messageEvents.incrementAndGet();

		log.debug("MessageEventCounter: " + events);

		// created ConfirmPDU und sendet diese weiter AL

		ChatPDU ConfirmPDU = ChatPDU.createMessageConfirmPdu(sharedClientData.userName, receivedPdu);
		log.debug("Erstellte Pdu " + ConfirmPDU); //AG
		try {
			connection.send(ConfirmPDU);
			// Liste durchgehen und an jeden einzelnen senden AL
			// sharedClientData.confirmCounter.getAndIncrement(); funktioniert nicht
			System.out.println("MessageConfirm gesendet" + ConfirmPDU);
			log.debug("Confirm gesendet" + ConfirmPDU);
		} catch (Exception e) {
			System.out.println("Confirm nicht möglich");
			// throw new IO Exception

		}

		// Empfangene Chat-Nachricht an User Interface zur
		// Darstellung uebergeben
		//AL zeile da drunter nur die da drunter eingefügt für counter
		sharedClientData.confirmCounter.getAndIncrement();
		userInterface.setMessageLine(receivedPdu.getEventUserName(), (String) receivedPdu.getMessage());
	}

	/**
	 * Bearbeitung aller vom Server ankommenden Nachrichten
	 */
	public void run() {

		ChatPDU receivedPdu = null;

		log.debug("AdvancedMessageListenerThread gestartet");

		while (!finished) {

			try {
				// Naechste ankommende Nachricht empfangen
				log.debug("Auf die naechste Nachricht vom Server warten");
				receivedPdu = receive();
				log.debug("Nach receive Aufruf, ankommende PDU mit PduType = " + receivedPdu.getPduType());
			} catch (Exception e) {
				finished = true;
			}

			if (receivedPdu != null) {

				switch (sharedClientData.status) {

				case REGISTERING:

					switch (receivedPdu.getPduType()) {

					case LOGIN_RESPONSE:
						// Login-Bestaetigung vom Server angekommen
						loginResponseAction(receivedPdu);

						break;

					case LOGIN_EVENT:
						// Meldung vom Server, dass sich die Liste der
						// angemeldeten User erweitert hat
						loginEventAction(receivedPdu);

						break;

					case LOGOUT_EVENT:
						// Meldung vom Server, dass sich die Liste der
						// angemeldeten User veraendert hat
						logoutEventAction(receivedPdu);

						break;

					case CHAT_MESSAGE_EVENT:
						// Chat-Nachricht vom Server gesendet
						chatMessageEventAction(receivedPdu);
						break;

					default:
						log.debug("Ankommende PDU im Zustand " + sharedClientData.status + " wird verworfen");
					}
					break;

				case REGISTERED:

					switch (receivedPdu.getPduType()) {

					case CHAT_MESSAGE_RESPONSE:

						// Die eigene zuletzt gesendete Chat-Nachricht wird vom
						// Server bestaetigt.
						chatMessageResponseAction(receivedPdu);
						break;

					case CHAT_MESSAGE_EVENT:
						// Chat-Nachricht vom Server gesendet --> nicht die eigene sondern eine andere
						// von einem anderen Client
						chatMessageEventAction(receivedPdu);
						break;

					// todo:
					// case --> anderer Client hat Nachricht bekommen AL

					// case CHAT_MESSAGE_RESPONSE_CONFIRM:
					// chatMessageEventAction(receivedPdu);
					// break;

					case LOGIN_EVENT:
						// Meldung vom Server, dass sich die Liste der
						// angemeldeten User erweitert hat
						loginEventAction(receivedPdu);

						break;

					case LOGOUT_EVENT:
						// Meldung vom Server, dass sich die Liste der
						// angemeldeten User veraendert hat
						logoutEventAction(receivedPdu);

						break;

					default:
						log.debug("Ankommende PDU im Zustand " + sharedClientData.status + " wird verworfen");
					}
					break;

				case UNREGISTERING:

					switch (receivedPdu.getPduType()) {

					case CHAT_MESSAGE_EVENT:
						// Chat-Nachricht vom Server gesendet
						chatMessageEventAction(receivedPdu);
						break;

					case LOGOUT_RESPONSE:
						// Bestaetigung des eigenen Logout
						logoutResponseAction(receivedPdu);
						break;

					case LOGIN_EVENT:
						// Meldung vom Server, dass sich die Liste der
						// angemeldeten User erweitert hat
						loginEventAction(receivedPdu);

						break;

					case LOGOUT_EVENT:
						// Meldung vom Server, dass sich die Liste der
						// angemeldeten User veraendert hat
						logoutEventAction(receivedPdu);

						break;

					default:
						log.debug("Ankommende PDU im Zustand " + sharedClientData.status + " wird verworfen");
						break;
					}
					break;

				case UNREGISTERED:
					log.debug("Ankommende PDU im Zustand " + sharedClientData.status + " wird verworfen");

					break;

				default:
					log.debug("Unzulaessiger Zustand " + sharedClientData.status);
				}
			}
		}

		// Verbindung noch schliessen
		try {
			connection.close();
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
		log.debug("Ordnungsgemaesses Ende des AdvancedMessageListener-Threads fuer User" + sharedClientData.userName
				+ ", Status: " + sharedClientData.status);
	} // run

//	protected void loginConfirmAction(ChatPDU receivedPdu) {
//		ChatPDU confirmPdu = ChatPDU.createLoginEventConfirm(sharedClientData.userName, receivedPdu);
//
//		try {
//			connection.send(confirmPdu);
//			System.out.println("Login Confirm gesendet");
//			log.debug("Login-Confirm-PDU fuer Client " + sharedClientData.userName + " an Server gesendet");
//		} catch (Exception e) {
//			ExceptionHandler.logException(e);
//		}
//	}

//	protected void sendlogoutConfirm(ChatPDU receivedPdu) {
//		ChatPDU ConfirmPdu = ChatPDU.createLogoutEventConfirm(sharedClientData.userName, receivedPdu);
//
//		try {
//			connection.send(ConfirmPdu);
//			log.debug("Logout-Confirm-PDU fuer Client " + sharedClientData.userName + " an Server gesendet");
//		} catch (Exception e) {
//			ExceptionHandler.logException(e);
//		}
//	}
}

////////////////////////////////////////////////
