package ist.group29.depchain.common.network;

import java.net.InetAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import com.google.protobuf.ByteString;

import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.network.NetworkMessages.DataMessage;
import ist.group29.depchain.network.NetworkMessages.Handshake;
import ist.group29.depchain.network.NetworkMessages.Message;

public class NetworkLayerTest {

    private static final int PORT_A = 11000;
    private static final int PORT_B = 11001;

    private FairLossLink fllA;
    private FairLossLink fllB;
    private StubbornLink slA;
    private StubbornLink slB;

    private KeyPair kpA;
    private KeyPair kpB;
    private KeyPair badKp;

    private LinkManager lmA;
    private LinkManager lmB;

    @BeforeEach
    public void setup() throws Exception {
        kpA = CryptoUtils.generateRSAKeyPair();
        kpB = CryptoUtils.generateRSAKeyPair();
        badKp = CryptoUtils.generateRSAKeyPair();
    }

    @AfterEach
    public void teardown() {
        if (fllA != null)
            fllA.close();
        if (fllB != null)
            fllB.close();
        if (slA != null)
            slA.shutdown();
        if (slB != null)
            slB.shutdown();
        if (lmA != null)
            lmA.shutdown();
        if (lmB != null)
            lmB.shutdown();
    }

    // Test 1: Normal Message Sending (FairLossLink)

    /**
     * Verify that a FairLossLink can successfully transmit and receive data over
     * UDP
     */
    @Test
    public void testFairLossLink_NormalMessageSending() throws Exception {
        Map<String, ProcessInfo> mapA = new HashMap<>();
        Map<String, ProcessInfo> mapB = new HashMap<>();

        ProcessInfo piA = new ProcessInfo("NodeA", InetAddress.getByName("localhost"), PORT_A);
        ProcessInfo piB = new ProcessInfo("NodeB", InetAddress.getByName("localhost"), PORT_B);

        mapA.put("NodeB", piB);
        mapB.put("NodeA", piA);

        fllA = new FairLossLink(PORT_A, mapA);
        fllB = new FairLossLink(PORT_B, mapB);

        String payloadStr = "Hello FLL";
        byte[] payload = payloadStr.getBytes();

        fllA.send(payload, "NodeB");

        Thread.sleep(100);

        FairLossLink.ReceivedPacket received = fllB.deliver();

        Assertions.assertNotNull(received);
        Assertions.assertEquals(payloadStr, new String(received.data()));
        Assertions.assertEquals(PORT_A, received.port());
    }

    // Test 2: Retransmission and Cancellation (StubbornLink)

    /**
     * Verify that StubbornLink periodically resends a message (every 500ms)
     * until it is explicitly acknowledged/cancelled via cancelRetransmission().
     */
    @Test
    public void testStubbornLink_RetransmissionAndCancel() throws Exception {
        Map<String, ProcessInfo> map = new HashMap<>();
        ProcessInfo pi = new ProcessInfo("Peer", InetAddress.getByName("localhost"), PORT_B);
        map.put("Peer", pi);

        AtomicInteger sendCount = new AtomicInteger(0);

        FairLossLink mockFll = new FairLossLink(PORT_A, map) {
            @Override
            public void send(byte[] payload, String recipientId) {
                sendCount.incrementAndGet();
            }
        };
        slA = new StubbornLink(mockFll);

        Message dummyMsg = Message.newBuilder()
                .setSenderId("NodeA")
                .setSequenceNumber(1)
                .build();

        slA.send(dummyMsg, "Peer");

        // Wait long enough for initial send (at 0) plus 2 resends (at 500, 1000)
        // Stubborn link RESEND_INTERVAL_MS = 500
        Thread.sleep(1200);

        Assertions.assertTrue(sendCount.get() >= 3, "Expected at least 3 transmissions (1 initial + 2 resends)");

        int countBeforeCancel = sendCount.get();
        slA.cancelRetransmission("Peer", 1);

        // Wait to see if it resends again
        Thread.sleep(1000);

        Assertions.assertEquals(countBeforeCancel, sendCount.get(), "Count should not increase after cancellation");

        mockFll.close();
    }

    // Test 3: Normal Flow (LinkManager)

    /**
     * Verify the normal, happy-path messaging flow between two isolated
     * LinkManagers.
     */
    @Test
    public void testLinkManager_NormalMessageFlow() throws Exception {
        ProcessInfo piA = new ProcessInfo("NodeA", InetAddress.getByName("localhost"), PORT_A);
        ProcessInfo piB = new ProcessInfo("NodeB", InetAddress.getByName("localhost"), PORT_B);

        Map<String, ProcessInfo> peersA = new HashMap<>();
        peersA.put("NodeB", piB);
        Map<String, PublicKey> keysA = new HashMap<>();
        keysA.put("NodeB", kpB.getPublic());

        Map<String, ProcessInfo> peersB = new HashMap<>();
        peersB.put("NodeA", piA);
        Map<String, PublicKey> keysB = new HashMap<>();
        keysB.put("NodeA", kpA.getPublic());

        lmA = new LinkManager(piA, peersA, kpA, keysA);
        lmB = new LinkManager(piB, peersB, kpB, keysB);

        // Track how many messages B successfully delivers
        AtomicInteger deliveredCount = new AtomicInteger(0);
        String testPayload = "Hello APL";

        lmB.setMessageListener((sender, payload) -> {
            Assertions.assertEquals("NodeA", sender);
            Assertions.assertEquals(testPayload, new String(payload));
            deliveredCount.incrementAndGet();
        });

        lmA.start();
        lmB.start();
        Thread.sleep(500);

        lmA.send("NodeB", testPayload.getBytes());
        Thread.sleep(1000); // Wait for delivery

        Assertions.assertEquals(1, deliveredCount.get(), "Message should be delivered exactly once");
    }

    // Test 4: Exactly-Once Delivery (AuthenticatedPerfectLink Deduplication)

    /**
     * Verify that AuthenticatedPerfectLink guarantees exactly-once delivery.
     */
    @Test
    public void testAuthenticatedPerfectLink_Deduplication() throws Exception {
        Map<String, ProcessInfo> mapA = new HashMap<>();
        ProcessInfo piB = new ProcessInfo("NodeB", InetAddress.getByName("localhost"), PORT_B);
        mapA.put("NodeB", piB);

        Map<String, ProcessInfo> mapB = new HashMap<>();
        ProcessInfo piA = new ProcessInfo("NodeA", InetAddress.getByName("localhost"), PORT_A);
        mapB.put("NodeA", piA);

        fllA = new FairLossLink(PORT_A, mapA);
        fllB = new FairLossLink(PORT_B, mapB);
        slA = new StubbornLink(fllA);
        slB = new StubbornLink(fllB);

        AuthenticatedPerfectLink authA = new AuthenticatedPerfectLink(slA, "NodeA", "NodeB", kpA, kpB.getPublic());
        AuthenticatedPerfectLink authB = new AuthenticatedPerfectLink(slB, "NodeB", "NodeA", kpB, kpA.getPublic());

        // Manually establish session/handshake, since we are not using the LinkManager
        authA.startHandshake();
        authB.deliver(Message.parseFrom(fllB.deliver().data()));
        authA.deliver(Message.parseFrom(fllA.deliver().data()));

        // Session established send message
        authA.send("payload".getBytes());
        byte[] rawPkt = fllB.deliver().data();
        Message msg = Message.parseFrom(rawPkt);

        // B receives message once
        byte[] del1 = authB.deliver(msg);
        Assertions.assertNotNull(del1);
        Assertions.assertEquals("payload", new String(del1));

        // B receives the same message again (duplicate)
        byte[] del2 = authB.deliver(msg);
        Assertions.assertNull(del2, "Duplicate message should be rejected");
    }

    // Test 5: Cryptographic Guarantee (Bad Handshake Signatures)

    /**
     * Verify that AuthenticatedPerfectLink correctly rejects initial handshakes
     * carrying invalid RSA signatures and does NOT establish a session.
     */
    @Test
    public void testCrypto_BadHandshakeSignature() throws Exception {
        ProcessInfo piA = new ProcessInfo("NodeA", InetAddress.getByName("localhost"), PORT_A);
        ProcessInfo piB = new ProcessInfo("NodeB", InetAddress.getByName("localhost"), PORT_B);

        Map<String, ProcessInfo> mapA = new HashMap<>();
        mapA.put("NodeB", piB);
        Map<String, ProcessInfo> mapB = new HashMap<>();
        mapB.put("NodeA", piA);

        fllA = new FairLossLink(PORT_A, mapA);
        fllB = new FairLossLink(PORT_B, mapB);
        slA = new StubbornLink(fllA);
        slB = new StubbornLink(fllB);

        AuthenticatedPerfectLink authA = new AuthenticatedPerfectLink(slA, "NodeA", "NodeB", kpA, kpB.getPublic());
        AuthenticatedPerfectLink authB = new AuthenticatedPerfectLink(slB, "NodeB", "NodeA", kpB, kpA.getPublic());

        // 1. TEST BAD HANDSHAKE SIGNATURE
        byte[] rsaPubBytes = kpA.getPublic().getEncoded();

        // BAD SIGNATURE (using an incorrect private key badKp instead of kpA)
        byte[] badSignature = CryptoUtils.sign(badKp.getPrivate(), CryptoUtils.toBytes("NodeA"),
                CryptoUtils.toBytes("NodeB"), rsaPubBytes);

        Handshake hsBad = Handshake.newBuilder().setDhPublicKey(ByteString.copyFrom(rsaPubBytes))
                .setSignature(ByteString.copyFrom(badSignature)).build();

        Message badMsg = Message.newBuilder().setSenderId("NodeA").setSequenceNumber(-1).setHandshake(hsBad).build();

        authB.deliver(badMsg);

        // At this point, Session should NOT be established because the signature was
        // invalid.
        Assertions.assertFalse(authB.isSessionEstablished(),
                "Session key should NOT be established with a bad handshake signature");
    }

    // Test 6: Cryptographic Guarantee (Bad Data HMACs)

    /**
     * Verify that AuthenticatedPerfectLink correctly rejects tampered data messages
     * failing the HMAC check over an established session.
     */
    @Test
    public void testCrypto_BadDataHMAC() throws Exception {
        ProcessInfo piA = new ProcessInfo("NodeA", InetAddress.getByName("localhost"), PORT_A);
        ProcessInfo piB = new ProcessInfo("NodeB", InetAddress.getByName("localhost"), PORT_B);

        Map<String, ProcessInfo> mapA = new HashMap<>();
        mapA.put("NodeB", piB);
        Map<String, ProcessInfo> mapB = new HashMap<>();
        mapB.put("NodeA", piA);

        fllA = new FairLossLink(PORT_A, mapA);
        fllB = new FairLossLink(PORT_B, mapB);
        slA = new StubbornLink(fllA);
        slB = new StubbornLink(fllB);

        AuthenticatedPerfectLink authA = new AuthenticatedPerfectLink(slA, "NodeA", "NodeB", kpA, kpB.getPublic());
        AuthenticatedPerfectLink authB = new AuthenticatedPerfectLink(slB, "NodeB", "NodeA", kpB, kpA.getPublic());

        // Establish session
        authA.startHandshake();
        authB.deliver(Message.parseFrom(fllB.deliver().data()));
        authA.deliver(Message.parseFrom(fllA.deliver().data()));

        Assertions.assertTrue(authB.isSessionEstablished(),
                "Session key should be established after a valid handshake");

        // A sends valid data, but we corrupt it in transit
        authA.send("test_payload".getBytes());
        byte[] rawPkt = fllB.deliver().data();
        Message msg = Message.parseFrom(rawPkt);

        // Corrupt HMAC
        DataMessage corruptedData = msg.getData().toBuilder().setMac(ByteString.copyFrom("badmac".getBytes())).build();
        Message corruptedMsg = msg.toBuilder().setData(corruptedData).build();

        byte[] resBad = authB.deliver(corruptedMsg);
        Assertions.assertNull(resBad, "Data should be dropped due to invalid HMAC catching the tampering");

        // If we send the valid message, it should be accepted
        byte[] resValid = authB.deliver(msg);
        Assertions.assertNotNull(resValid, "Valid data should be accepted after the bad one was dropped");
        Assertions.assertEquals("test_payload", new String(resValid));
    }

    // Test 7: Peer Crash & Disconnect Resilience

    /**
     * Verify the system's behavior when a peer abruptly stops responding.
     * Ensures the sender's LinkManager handles the underlying StubbornLink
     * retries gracefully without crashing the local Node process.
     */
    @Test
    public void testDependability_PeerCrashResilience() throws Exception {

        ProcessInfo piA = new ProcessInfo("NodeA", InetAddress.getByName("localhost"), PORT_A);
        ProcessInfo piB = new ProcessInfo("NodeB", InetAddress.getByName("localhost"), PORT_B);

        lmA = new LinkManager(piA, Map.of("NodeB", piB), kpA, Map.of("NodeB", kpB.getPublic()));
        lmB = new LinkManager(piB, Map.of("NodeA", piA), kpB, Map.of("NodeA", kpA.getPublic()));

        AtomicInteger delivered = new AtomicInteger(0);
        lmB.setMessageListener((sender, payload) -> delivered.incrementAndGet());

        lmA.start();
        lmB.start();

        Thread.sleep(500);
        lmA.send("NodeB", "msg1".getBytes());
        Thread.sleep(500);
        Assertions.assertEquals(1, delivered.get(), "First message should be delivered");

        // Crash Node B
        lmB.shutdown();

        // Node A attempts to send again.
        // Underlying FLL/SL encounter unreachable ports and retry continuously.
        Assertions.assertDoesNotThrow(() -> {
            lmA.send("NodeB", "msg2".getBytes());
            Thread.sleep(1500);
        }, "Node A should survive Node B crashing without throwing unhandled exceptions to the caller");
    }

}