package ist.group29.depchain.common.network;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.SecretKey;

import com.google.protobuf.ByteString;

import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.network.NetworkMessages.AckMessage;
import ist.group29.depchain.network.NetworkMessages.DataMessage;
import ist.group29.depchain.network.NetworkMessages.Handshake;
import ist.group29.depchain.network.NetworkMessages.HandshakeAck;
import ist.group29.depchain.network.NetworkMessages.Message;

/**
 * Authenticated Perfect Link (APL) — one instance per peer.
 */
public class AuthenticatedPerfectLink {

    private static final Logger LOGGER = Logger.getLogger(AuthenticatedPerfectLink.class.getName());
    private static final long HANDSHAKE_SEQ = -1;

    private final StubbornLink sl;
    private final String selfId;
    private final String peerId;
    private final KeyPair identityKeyPair;   
    private final PublicKey peerPublicKey;    
    private final KeyPair dhKeyPair; 

    private final AtomicLong sendSeqCounter = new AtomicLong(0); 
    private final List<byte[]> sendBuffer = new ArrayList<>();

    // Duplicate filtering — simple Message-ID deduplication
    private final HashSet<Long> deliveredIds = new HashSet<>();
    
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
            throw new RuntimeException("Failed to generate DH key pair", e);
        }
    }

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

 
    public byte[] deliver(Message msg) {
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

            PublicKey peerDH = CryptoUtils.decodeDHPublicKey(peerDHPub);
            establishSession(CryptoUtils.computeSharedSecret(dhKeyPair.getPrivate(), peerDH));

            
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

            // Sender will retransmit Handshake if lost so there is no need to retransmit HandshakeAck
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

            sl.cancelRetransmission(peerId, HANDSHAKE_SEQ);
            LOGGER.info("[APL] Handshake completed with " + peerId + " (initiator)");
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "HandshakeAck processing failed", e);
        }
    }

    private synchronized void establishSession(SecretKey key) {
        if (sessionKey != null) return; 
        sessionKey = key;

        for (byte[] payload : sendBuffer) {
            doSend(payload);
        }
        sendBuffer.clear();
    }

    private byte[] onData(Message msg) {
        if (sessionKey == null) return null;

        try {
            DataMessage data = msg.getData();
            long seq = msg.getSequenceNumber();
            byte[] payload = data.getPayload().toByteArray();

            boolean validMac = CryptoUtils.verifyHmac(sessionKey, data.getMac().toByteArray(),
                    CryptoUtils.toBytes(peerId), CryptoUtils.toBytes(selfId),
                    CryptoUtils.toBytes(seq), payload);
            if (!validMac) {
                LOGGER.warning("[APL] Invalid MAC on data from " + peerId + " seq=" + seq);
                return null;
            }

            sendAck(seq);

            // Duplicate filtering
            synchronized (this) {
                if (!deliveredIds.add(seq)) {
                    return null; // Duplicate
                }
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

            boolean validMac = CryptoUtils.verifyHmac(sessionKey, ack.getMac().toByteArray(),
                    CryptoUtils.toBytes(peerId), CryptoUtils.toBytes(selfId),
                    CryptoUtils.toBytes(seq));
            if (!validMac) {
                LOGGER.warning("[APL] Invalid ACK MAC from " + peerId + " seq=" + seq);
                return;
            }

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

            sl.sendOnce(ackMsg, peerId);
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "ACK creation failed", e);
        }
    }
}
