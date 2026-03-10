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

    // Kept for dynamic APL creation when new peers (e.g. clients) connect
    private final String selfId;
    private final KeyPair identityKeyPair;

    public LinkManager(ProcessInfo self, Map<String, ProcessInfo> peers,
            KeyPair identityKeyPair, Map<String, PublicKey> peerPublicKeys) throws SocketException {

        this.selfId = self.id();
        this.identityKeyPair = identityKeyPair;

        Map<String, ProcessInfo> fullAddressMap = new HashMap<>(peers);
        fullAddressMap.put(self.id(), self);

        this.fll = new FairLossLink(self.port(), fullAddressMap);
        this.sl = new StubbornLink(fll);
        this.apls = new HashMap<>();

        for (Map.Entry<String, ProcessInfo> entry : peers.entrySet()) {
            String peerId = entry.getKey();
            PublicKey peerPubKey = peerPublicKeys.get(peerId);
            AuthenticatedPerfectLink apl = new AuthenticatedPerfectLink(
                    sl, selfId, peerId, identityKeyPair, peerPubKey);
            apls.put(peerId, apl);
        }
        this.peerPublicKeys = new HashMap<>(peerPublicKeys);
    }

    private final Map<String, PublicKey> peerPublicKeys;

    public PublicKey getPeerPublicKey(String peerId) {
        return peerPublicKeys.get(peerId);
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
                FairLossLink.ReceivedPacket received = fll.deliver();
                if (received == null)
                    continue;

                Message msg = Message.parseFrom(received.data());

                String senderId = msg.getSenderId();
                AuthenticatedPerfectLink apl = apls.get(senderId);
                if (apl == null) {
                    // Dynamically create an APL for new peers (e.g. clients) if we have their key
                    PublicKey senderKey = peerPublicKeys.get(senderId);
                    if (senderKey == null) {
                        LOGGER.warning(
                                "[LinkManager] Message from unknown/untrusted sender: " + senderId + " - dropping");
                        continue;
                    }
                    // Register the sender's real network address so HANDSHAKE_ACK can be routed
                    // back
                    fll.registerPeer(senderId, received.address(), received.port());
                    LOGGER.info("[LinkManager] Creating dynamic APL for new peer: " + senderId
                            + " at " + received.address() + ":" + received.port());
                    apl = new AuthenticatedPerfectLink(sl, selfId, senderId, identityKeyPair, senderKey);
                    apls.put(senderId, apl);
                }

                byte[] delivered = apl.deliver(msg);
                if (delivered != null && listener != null) {
                    listener.onMessage(senderId, delivered);
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted())
                    break;
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
