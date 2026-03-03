package ist.group29.depchain.network;

import com.google.protobuf.ByteString;
import ist.group29.depchain.crypto.CryptoUtils;
import ist.group29.depchain.network.NetworkMessages.AckMessage;
import ist.group29.depchain.network.NetworkMessages.DataMessage;
import ist.group29.depchain.network.NetworkMessages.Handshake;
import ist.group29.depchain.network.NetworkMessages.HandshakeAck;
import ist.group29.depchain.network.NetworkMessages.Message;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.SecretKey;

/**
 * Authenticated Perfect Link (APL) — one instance per peer.
 *
 * <p>Provides reliable, authenticated, exactly-once delivery over a shared
 * {@link StubbornLink}. Uses an ECDH handshake (signed with pre-distributed
 * RSA keys) to establish a symmetric HMAC session key.
 *
 * <p>Semantics:
 * <ul>
 *   <li>PL1 (Reliable delivery): If p sends m to q and both are correct,
 *       q eventually delivers m.</li>
 *   <li>PL2 (No duplication): No message is delivered more than once.</li>
 *   <li>PL3 (No creation / Authenticity): If q delivers m from p,
 *       then p previously sent m.</li>
 * </ul>
 */
public class AuthenticatedPerfectLink {

    private static final Logger LOGGER = Logger.getLogger(AuthenticatedPerfectLink.class.getName());
    private static final long HANDSHAKE_SEQ = -1;

    private final StubbornLink sl;
    private final String selfId;
    private final String peerId;
    private final KeyPair identityKeyPair;   // RSA (pre-distributed)
    private final PublicKey peerPublicKey;    // Peer's RSA public key
    private final KeyPair dhKeyPair; 

    // Sending state
    private final AtomicLong sendSeqCounter = new AtomicLong(0); 
    private final List<byte[]> sendBuffer = new ArrayList<>();

    // Duplicate filtering
    private long highestDeliveredSeq = -1;
    private final HashSet<Long> nonDeliveredSeqs = new HashSet<>();
    
    // Session state
    private volatile SecretKey sessionKey = null;

    public AuthenticatedPerfectLink(StubbornLink sl, String selfId, String peerId,
                                     KeyPair identityKeyPair, PublicKey peerPublicKey) {
        this.sl = sl;
        this.selfId = selfId;
        this.peerId = peerId;
        this.identityKeyPair = identityKeyPair;
        this.peerPublicKey = peerPublicKey;
        try {
            this.dhKeyPair = CryptoUtils.generateDHKeyPair();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to generate ECDH key pair", e);
        }
    }

    // ======================== Handshake ========================

    /** Initiate a DH handshake with the peer (retransmitted by SL). */
    public void startHandshake() {
        try {
            byte[] dhPubBytes = dhKeyPair.getPublic().getEncoded();
            byte[] signature = CryptoUtils.sign(
                    identityKeyPair.getPrivate(),
                    CryptoUtils.toBytes(selfId),
                    CryptoUtils.toBytes(peerId),
                    dhPubBytes);

            Handshake hs = Handshake.newBuilder()
                    .setDhPublicKey(ByteString.copyFrom(dhPubBytes))
                    .setSignature(ByteString.copyFrom(signature))
                    .build();

            Message msg = Message.newBuilder()
                    .setSenderId(selfId)
                    .setSequenceNumber(HANDSHAKE_SEQ)
                    .setHandshake(hs)
                    .build();

            sl.send(msg, peerId);
            LOGGER.info("[APL] Handshake initiated with " + peerId);
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "Handshake creation failed", e);
        }
    }

    // ======================== Sending ========================

    /** Send application payload to the peer. Buffers if handshake is not yet complete. */
    public synchronized void send(byte[] payload) {
        if (sessionKey == null) {
            sendBuffer.add(payload);
            return;
        }
        doSend(payload);
    }

    private void doSend(byte[] payload) {
        try {
            long seq = sendSeqCounter.getAndIncrement();
            byte[] mac = CryptoUtils.hmac(sessionKey,
                    CryptoUtils.toBytes(selfId),
                    CryptoUtils.toBytes(peerId),
                    CryptoUtils.toBytes(seq),
                    payload);

            DataMessage data = DataMessage.newBuilder()
                    .setPayload(ByteString.copyFrom(payload))
                    .setMac(ByteString.copyFrom(mac))
                    .build();

            Message msg = Message.newBuilder()
                    .setSenderId(selfId)
                    .setSequenceNumber(seq)
                    .setData(data)
                    .build();

            sl.send(msg, peerId);
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "Failed to send data", e);
        }
    }

    // ======================== Receiving (called by LinkManager) ========================

    /**
     * Process an incoming message from this peer.
     *
     * @return the application payload if a new data message was delivered,
     *         or {@code null} for control messages and duplicates.
     */
    public byte[] deliver(Message msg) {
        // Validate that this message belongs to this APL instance
        if (!msg.getSenderId().equals(peerId)) {
            return null;
        }

        switch (msg.getPayloadCase()) {
            case HANDSHAKE:     onHandshake(msg);     return null;
            case HANDSHAKE_ACK: onHandshakeAck(msg);  return null;
            case ACK:           onAck(msg);           return null;
            case DATA:          return onData(msg);
            default:            return null;
        }
    }

    // ======================== Handshake Handlers ========================

    private void onHandshake(Message msg) {
        if (sessionKey != null) return; // Prevents replayed handshakes from being processed twice

        try {
            Handshake hs = msg.getHandshake();
            byte[] peerDHPub = hs.getDhPublicKey().toByteArray();

            boolean valid = CryptoUtils.verify(peerPublicKey, hs.getSignature().toByteArray(),
                    CryptoUtils.toBytes(peerId), CryptoUtils.toBytes(selfId), peerDHPub);
            if (!valid) {
                LOGGER.warning("[APL] Invalid handshake signature from " + peerId);
                return;
            }

            // Derive session key
            PublicKey peerDH = CryptoUtils.decodeDHPublicKey(peerDHPub);
            establishSession(CryptoUtils.computeSharedSecret(dhKeyPair.getPrivate(), peerDH));

            // Respond with HandshakeAck (fire-and-forget; sender will retransmit Handshake if lost)
            byte[] myDHPub = dhKeyPair.getPublic().getEncoded();
            byte[] ackSig = CryptoUtils.sign(identityKeyPair.getPrivate(),
                    CryptoUtils.toBytes(selfId), CryptoUtils.toBytes(peerId), myDHPub);

            HandshakeAck ack = HandshakeAck.newBuilder()
                    .setDhPublicKey(ByteString.copyFrom(myDHPub))
                    .setSignature(ByteString.copyFrom(ackSig))
                    .build();

            Message ackMsg = Message.newBuilder()
                    .setSenderId(selfId)
                    .setSequenceNumber(HANDSHAKE_SEQ)
                    .setHandshakeAck(ack)
                    .build();

            sl.sendOnce(ackMsg, peerId);
            LOGGER.info("[APL] Handshake completed with " + peerId + " (responder)");
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "Handshake processing failed", e);
        }
    }

    private void onHandshakeAck(Message msg) {
        if (sessionKey != null) return; // Prevents replayed handshakes from being processed twice

        try {
            HandshakeAck ack = msg.getHandshakeAck();
            byte[] peerDHPub = ack.getDhPublicKey().toByteArray();

            boolean valid = CryptoUtils.verify(peerPublicKey, ack.getSignature().toByteArray(),
                    CryptoUtils.toBytes(peerId), CryptoUtils.toBytes(selfId), peerDHPub);
            if (!valid) {
                LOGGER.warning("[APL] Invalid handshake ACK signature from " + peerId);
                return;
            }

            PublicKey peerDH = CryptoUtils.decodeDHPublicKey(peerDHPub);
            establishSession(CryptoUtils.computeSharedSecret(dhKeyPair.getPrivate(), peerDH));

            // Stop retransmitting the handshake
            sl.cancelRetransmission(peerId, HANDSHAKE_SEQ);
            LOGGER.info("[APL] Handshake completed with " + peerId + " (initiator)");
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "HandshakeAck processing failed", e);
        }
    }

    private synchronized void establishSession(SecretKey key) {
        if (sessionKey != null) return; // Idempotent
        sessionKey = key;
        // Flush buffered payloads
        for (byte[] payload : sendBuffer) {
            doSend(payload);
        }
        sendBuffer.clear();
    }

    // ======================== Data / ACK Handlers ========================

    private byte[] onData(Message msg) {
        if (sessionKey == null) return null;

        try {
            DataMessage data = msg.getData();
            long seq = msg.getSequenceNumber();
            byte[] payload = data.getPayload().toByteArray();

            // Verify HMAC (binds sender, recipient, seq, and payload)
            boolean validMac = CryptoUtils.verifyHmac(sessionKey, data.getMac().toByteArray(),
                    CryptoUtils.toBytes(peerId), CryptoUtils.toBytes(selfId),
                    CryptoUtils.toBytes(seq), payload);
            if (!validMac) {
                LOGGER.warning("[APL] Invalid MAC on data from " + peerId + " seq=" + seq);
                return null;
            }

            // Always send ACK (even for duplicates — silences the peer's SL)
            sendAck(seq);

            // Duplicate filtering
            synchronized (this) {
                if (seq <= highestDeliveredSeq) {
                    // Below or at highest delivered seq — deliver only if it fills a gap
                    if (nonDeliveredSeqs.remove(seq)) {
                        return payload; // Gap filled
                    }
                    return null; // Duplicate
                }
                // seq > highestDeliveredSeq — record any gaps and advance
                for (long i = highestDeliveredSeq + 1; i < seq; i++) {
                    nonDeliveredSeqs.add(i);
                }
                highestDeliveredSeq = seq;
                return payload;
            }
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "Data verification failed", e);
            return null;
        }
    }

    private void onAck(Message msg) {
        if (sessionKey == null) return;

        try {
            AckMessage ack = msg.getAck();
            long seq = msg.getSequenceNumber();

            // Verify ACK HMAC (binds sender, recipient, and acknowledged seq)
            boolean validMac = CryptoUtils.verifyHmac(sessionKey, ack.getMac().toByteArray(),
                    CryptoUtils.toBytes(peerId), CryptoUtils.toBytes(selfId),
                    CryptoUtils.toBytes(seq));
            if (!validMac) {
                LOGGER.warning("[APL] Invalid ACK MAC from " + peerId + " seq=" + seq);
                return;
            }

            // Tell SL to stop retransmitting the original data message
            sl.cancelRetransmission(peerId, seq);
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "ACK verification failed", e);
        }
    }

    private void sendAck(long seq) {
        try {
            byte[] mac = CryptoUtils.hmac(sessionKey,
                    CryptoUtils.toBytes(selfId),
                    CryptoUtils.toBytes(peerId),
                    CryptoUtils.toBytes(seq));

            AckMessage ack = AckMessage.newBuilder()
                    .setMac(ByteString.copyFrom(mac))
                    .build();

            Message ackMsg = Message.newBuilder()
                    .setSenderId(selfId)
                    .setSequenceNumber(seq)
                    .setAck(ack)
                    .build();

            sl.sendOnce(ackMsg, peerId); // Fire-and-forget
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "ACK creation failed", e);
        }
    }
}
