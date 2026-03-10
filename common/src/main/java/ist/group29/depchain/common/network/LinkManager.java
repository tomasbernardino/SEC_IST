package ist.group29.depchain.common.network;

import java.net.SocketException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import ist.group29.depchain.network.NetworkMessages.Message;

/**
 * Orchestrates the link stack for a single node.
 */
public class LinkManager {

    private static final Logger LOGGER = Logger.getLogger(LinkManager.class.getName());

    private final FairLossLink fll;
    private final StubbornLink sl;
    private final Map<String, AuthenticatedPerfectLink> apls;
    private volatile MessageListener listener;
    private Thread receiveThread;


    public LinkManager(ProcessInfo self, Map<String, ProcessInfo> peers,
                    KeyPair identityKeyPair, Map<String, PublicKey> peerPublicKeys) throws SocketException {

        Map<String, ProcessInfo> fullAddressMap = new HashMap<>(peers);
        fullAddressMap.put(self.id(), self);

        this.fll = new FairLossLink(self.port(), fullAddressMap);
        this.sl = new StubbornLink(fll);
        this.apls = new HashMap<>();

        for (Map.Entry<String, ProcessInfo> entry : peers.entrySet()) {
            String peerId = entry.getKey();
            PublicKey peerPubKey = peerPublicKeys.get(peerId);
            AuthenticatedPerfectLink apl = new AuthenticatedPerfectLink(
                    sl, self.id(), peerId, identityKeyPair, peerPubKey);
            apls.put(peerId, apl);
        }
    }

    

    public void setMessageListener(MessageListener listener) {
        this.listener = listener;
    }
    
    /** Start the receive loop and initiate handshakes with all peers. */
    public void start() {
        receiveThread = new Thread(this::receiveLoop, "LinkManager-Receive");
        receiveThread.setDaemon(true);
        receiveThread.start();

        for (AuthenticatedPerfectLink apl : apls.values()) {
            apl.startHandshake();
        }
        LOGGER.info("[LinkManager] Started — handshakes initiated with " + apls.size() + " peers");
    }

    public void send(String recipientId, byte[] payload) {
        AuthenticatedPerfectLink apl = apls.get(recipientId);
        if (apl != null) {
            apl.send(payload);
        } else {
            LOGGER.warning("[LinkManager] No APL for recipient: " + recipientId);
        }
    }

    public void broadcast(byte[] payload) {
        for (AuthenticatedPerfectLink apl : apls.values()) {
            apl.send(payload);
        }
    }

    private void receiveLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] raw = fll.deliver();
                if (raw == null) continue;

                Message msg = Message.parseFrom(raw);

                String senderId = msg.getSenderId();
                AuthenticatedPerfectLink apl = apls.get(senderId);
                if (apl == null) {
                    LOGGER.fine("Message from unknown sender: " + senderId);
                    continue;
                }

                byte[] delivered = apl.deliver(msg);
                if (delivered != null && listener != null) {
                    listener.onMessage(senderId, delivered);
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) break;
                LOGGER.log(Level.WARNING, "Error in receive loop", e);
            }
        }
    }

    public void shutdown() {
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        sl.shutdown();
        fll.close();
        LOGGER.info("[LinkManager] Shut down");
    }
}
