package ist.group29.depchain.network;

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
 *
 * <p>Creates one {@link FairLossLink}, one {@link StubbornLink}, and one
 * {@link AuthenticatedPerfectLink} per peer. Runs the receive loop that
 * dispatches incoming messages to the correct APL based on sender ID.
 */
public class LinkManager {

    private static final Logger LOGGER = Logger.getLogger(LinkManager.class.getName());

    private final FairLossLink fll;
    private final StubbornLink sl;
    private final Map<String, AuthenticatedPerfectLink> apls;
    private final MessageListener listener;
    private Thread receiveThread;

    /**
     * @param self           this node's identity and port
     * @param peers          map of peer process IDs to their network info
     * @param identityKeyPair this node's RSA key pair (pre-distributed)
     * @param peerPublicKeys  map of peer IDs to their RSA public keys (pre-distributed)
     * @param listener       callback for delivered application payloads
     */
    public LinkManager(ProcessInfo self, Map<String, ProcessInfo> peers,
                       KeyPair identityKeyPair, Map<String, PublicKey> peerPublicKeys,
                       MessageListener listener) throws SocketException {

        // Include self in the address map so FLL can resolve all process IDs
        Map<String, ProcessInfo> fullAddressMap = new HashMap<>(peers);
        fullAddressMap.put(self.id(), self);

        this.fll = new FairLossLink(self.port(), fullAddressMap);
        this.sl = new StubbornLink(fll);
        this.listener = listener;
        this.apls = new HashMap<>();

        for (Map.Entry<String, ProcessInfo> entry : peers.entrySet()) {
            String peerId = entry.getKey();
            PublicKey peerPubKey = peerPublicKeys.get(peerId);
            AuthenticatedPerfectLink apl = new AuthenticatedPerfectLink(
                    sl, self.id(), peerId, identityKeyPair, peerPubKey);
            apls.put(peerId, apl);
        }
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

    /** Send application payload to a specific peer. */
    public void send(String recipientId, byte[] payload) {
        AuthenticatedPerfectLink apl = apls.get(recipientId);
        if (apl != null) {
            apl.send(payload);
        } else {
            LOGGER.warning("[LinkManager] No APL for recipient: " + recipientId);
        }
    }

    /** Broadcast application payload to all peers. */
    public void broadcast(byte[] payload) {
        for (AuthenticatedPerfectLink apl : apls.values()) {
            apl.send(payload);
        }
    }

    /** Main receive loop — dispatches messages to the correct APL. */
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
                if (delivered != null) {
                    listener.onMessage(senderId, delivered);
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) break;
                LOGGER.log(Level.WARNING, "Error in receive loop", e);
            }
        }
    }

    /** Graceful shutdown. */
    public void shutdown() {
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        sl.shutdown();
        fll.close();
        LOGGER.info("[LinkManager] Shut down");
    }
}
