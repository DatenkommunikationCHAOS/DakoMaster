package edu.hm.dako.chat.client;

import edu.hm.dako.chat.common.ExceptionHandler;

public class AdvancedClientImpl extends AbstractChatClient {
    
    /**
     * Kopie von ClientImpl für Advanced Klassen
     * 
     * 
     * 
     * Konstruktor
     * 
     * @param userInterface
     *          Schnittstelle zum User-Interface
     * @param serverPort
     *          Portnummer des Servers
     * @param remoteServerAddress
     *          IP-Adresse/Hostname des Servers
     */
    
    public AdvancedClientImpl(ClientUserInterface userInterface, int serverPort,
            String remoteServerAddress, String serverType) {

        super(userInterface, serverPort, remoteServerAddress);
        this.serverPort = serverPort;
        this.remoteServerAddress = remoteServerAddress;

        Thread.currentThread().setName("Client");
        threadName = Thread.currentThread().getName();

        try {
            // Simple TCP Server erzeugen
            messageListenerThread = new AdvancedMessageListenerThreadImpl(userInterface,
                        connection, sharedClientData);

            messageListenerThread.start();
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
    }
    
}
