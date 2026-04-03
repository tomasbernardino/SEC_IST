package ist.group29.depchain.common.network;

import java.net.InetAddress;
import java.security.GeneralSecurityException;
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

    /**
     * Helper — performs the manual 3-step handshake between two APLs
     * and asserts that both sides have established a session.
     */
    private void performManualHandshake(AuthenticatedPerfectLink aplInitiator,
            AuthenticatedPerfectLink aplResponder,
            FairLossLink fllInitiator, FairLossLink fllResponder) throws Exception {
        aplInitiator.startHandshake();
        aplResponder.deliver(Message.parseFrom(fllResponder.deliver().data()));
        aplInitiator.deliver(Message.parseFrom(fllInitiator.deliver().data()));
        Assertions.assertTrue(aplInitiator.isSessionEstablished());
        Assertions.assertTrue(aplResponder.isSessionEstablished());
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
     * until it is explicitly acknowledged/cancelled via cancelRetransmission()
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
        Thread.sleep(1200);

        Assertions.assertTrue(sendCount.get() >= 3, "Expected at least 3 transmissions (1 initial + 2 resends)");

        int countBeforeCancel = sendCount.get();
        slA.cancelRetransmission("Peer", 1);

        Thread.sleep(1000);

        Assertions.assertEquals(countBeforeCancel, sendCount.get(), "Count should not increase after cancellation");

        mockFll.close();
    }

    // Test 3: Normal Flow (LinkManager)

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
        Thread.sleep(1000);

        Assertions.assertEquals(1, deliveredCount.get(), "Message should be delivered exactly once");
    }

    // Test 4: Exactly-Once Delivery (AuthenticatedPerfectLink Deduplication)

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

        AuthenticatedPerfectLink aplA = new AuthenticatedPerfectLink(slA, "NodeA", "NodeB", kpA, kpB.getPublic());
        AuthenticatedPerfectLink aplB = new AuthenticatedPerfectLink(slB, "NodeB", "NodeA", kpB, kpA.getPublic());

        // Manually establish session/handshake, since we are not using the LinkManager
        performManualHandshake(aplA, aplB, fllA, fllB);

        // Session established send message
        aplA.send("payload".getBytes());
        byte[] rawPkt = fllB.deliver().data();
        Message msg = Message.parseFrom(rawPkt);

        // B receives message once
        byte[] del1 = aplB.deliver(msg);
        Assertions.assertNotNull(del1);
        Assertions.assertEquals("payload", new String(del1));

        // B receives the same message again (duplicate)
        byte[] del2 = aplB.deliver(msg);
        Assertions.assertNull(del2, "Duplicate message should be rejected");
    }

    // Test 5: Cryptographic Guarantee (Bad Handshake Signatures)

    /**
     * Verify that AuthenticatedPerfectLink correctly rejects initial handshakes
     * carrying invalid RSA signatures and does NOT establish a session
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

        AuthenticatedPerfectLink aplA = new AuthenticatedPerfectLink(slA, "NodeA", "NodeB", kpA, kpB.getPublic());
        AuthenticatedPerfectLink aplB = new AuthenticatedPerfectLink(slB, "NodeB", "NodeA", kpB, kpA.getPublic());

        KeyPair fakeDHKp = CryptoUtils.generateDHKeyPair();
        byte[] dhPubBytes = fakeDHKp.getPublic().getEncoded();

        // Bad Signature (using incorrect private key badKp instead of kpA)
        byte[] badSignature = CryptoUtils.sign(badKp.getPrivate(), CryptoUtils.toBytes("NodeA"),
                CryptoUtils.toBytes("NodeB"), dhPubBytes);

        Handshake hsBad = Handshake.newBuilder().setDhPublicKey(ByteString.copyFrom(dhPubBytes))
                .setSignature(ByteString.copyFrom(badSignature)).build();

        Message badMsg = Message.newBuilder().setSenderId("NodeA").setSequenceNumber(-1).setHandshake(hsBad).build();

        aplB.deliver(badMsg);

        Assertions.assertFalse(aplB.isSessionEstablished(),
                "Session key should NOT be established with a bad handshake signature");
    }

    // Test 6: Cryptographic Guarantee (Bad Data HMACs)

    /**
     * Verify that AuthenticatedPerfectLink correctly rejects tampered data messages
     * failing the HMAC check over an established session
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
        performManualHandshake(authA, authB, fllA, fllB);

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
     * Verify the system's behavior when a peer abruptly stops responding
     * Ensures the sender's LinkManager handles the underlying StubbornLink
     * retries gracefully without crashing the local Node process
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

        // Node A attempts to send again
        // Underlying FLL/SL encounter unreachable ports and retry continuously
        Assertions.assertDoesNotThrow(() -> lmA.send("NodeB", "msg2".getBytes()),
                "Node A should survive Node B crashing without throwing unhandled exceptions to the caller");
        Thread.sleep(1500);
        Assertions.assertEquals(1, delivered.get(), "Node B should not receive any messages after crashing");
    }

    // Test 8: Drop Message Recovery (StubbornLink retransmission over lossy
    // FairLossLink)

    /**
     * Simulate a lossy FairLossLink that drops the first N sends from Node A.
     * Verify that StubbornLink retransmission causes eventual session establishment
     * and end-to-end data delivery through the full APL stack.
     */
    @Test
    public void testDropMessageRecovery() throws Exception {
        ProcessInfo piA = new ProcessInfo("NodeA", InetAddress.getByName("localhost"), PORT_A);
        ProcessInfo piB = new ProcessInfo("NodeB", InetAddress.getByName("localhost"), PORT_B);

        Map<String, ProcessInfo> mapA = new HashMap<>();
        mapA.put("NodeB", piB);
        mapA.put("NodeA", piA);
        Map<String, ProcessInfo> mapB = new HashMap<>();
        mapB.put("NodeA", piA);
        mapB.put("NodeB", piB);

        // Lossy FLL: drops the first 3 sends from A, then works normally
        AtomicInteger dropCounter = new AtomicInteger(0);
        final int DROPS = 3;
        FairLossLink lossyFllA = new FairLossLink(PORT_A, mapA) {
            @Override
            public void send(byte[] payload, String recipientId) {
                if (dropCounter.getAndIncrement() < DROPS) {
                    return; // silently drop
                }
                super.send(payload, recipientId);
            }
        };
        fllB = new FairLossLink(PORT_B, mapB);

        StubbornLink slALocal = new StubbornLink(lossyFllA);
        slB = new StubbornLink(fllB);

        AuthenticatedPerfectLink aplA = new AuthenticatedPerfectLink(
                slALocal, "NodeA", "NodeB", kpA, kpB.getPublic());
        AuthenticatedPerfectLink aplB = new AuthenticatedPerfectLink(
                slB, "NodeB", "NodeA", kpB, kpA.getPublic());

        // Track data messages delivered to B
        AtomicInteger deliveredCount = new AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<String> deliveredPayload = new java.util.concurrent.atomic.AtomicReference<>();

        // Background receive loop for A (reads from lossyFllA, delivers to aplA)
        // Similar to LinkManager receive loop
        Thread recvA = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    FairLossLink.ReceivedPacket pkt = lossyFllA.deliver();
                    if (pkt != null) {
                        aplA.deliver(Message.parseFrom(pkt.data()));
                    }
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted())
                        break;
                }
            }
        }, "TestRecvA");
        recvA.setDaemon(true);

        // Background receive loop for B (reads from fllB, delivers to aplB)
        Thread recvB = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    FairLossLink.ReceivedPacket pkt = fllB.deliver();
                    if (pkt != null) {
                        byte[] data = aplB.deliver(Message.parseFrom(pkt.data()));
                        if (data != null) {
                            deliveredPayload.set(new String(data));
                            deliveredCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted())
                        break;
                }
            }
        }, "TestRecvB");
        recvB.setDaemon(true);

        recvA.start();
        recvB.start();

        // Start handshake — the first DROPS sends from A will be lost,
        // but StubbornLink will retransmit every 500ms until one gets through
        aplA.startHandshake();

        // Wait for SL retransmissions to overcome the initial drops + handshake
        Thread.sleep(DROPS * 500 + 1500);

        // Session should eventually establish despite initial drops
        Assertions.assertTrue(aplA.isSessionEstablished(),
                "Session should establish after StubbornLink retransmissions overcome FLL drops");
        Assertions.assertTrue(aplB.isSessionEstablished(),
                "Both sides should have an established session");

        // Send a data message — by this point dropCounter > DROPS, no more drops
        aplA.send("drop-test-payload".getBytes());

        // Wait for delivery through the receive loop
        Thread.sleep(1500);

        // Verify data was delivered exactly once
        Assertions.assertEquals(1, deliveredCount.get(),
                "Data message should be delivered exactly once despite retransmissions");
        Assertions.assertEquals("drop-test-payload", deliveredPayload.get());

        // Verify drops actually happened
        Assertions.assertTrue(dropCounter.get() > DROPS,
                "FLL should have dropped at least " + DROPS + " sends before recovering");

        // Cleanup
        recvA.interrupt();
        recvB.interrupt();
        slALocal.shutdown();
        lossyFllA.close();
    }

}