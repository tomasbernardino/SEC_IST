package ist.group29.depchain;

import java.net.InetAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ist.group29.depchain.client.ClientLibrary;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.client.ClientMessages.TransactionStatus;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.common.network.ProcessInfo;
import ist.group29.depchain.server.consensus.Consensus;
import ist.group29.depchain.server.crypto.CryptoManager;
import ist.group29.depchain.server.service.Service;
import ist.group29.depchain.server.service.BlockchainState;
import ist.group29.depchain.server.service.TransactionManager;
import ist.group29.depchain.server.MessageRouter;
import ist.group29.depchain.server.service.account.BlockchainAccount;
import ist.group29.depchain.server.service.account.EOA;

public class E2ETest {
    private static final int BASE_PORT = 12000;
    private final List<String> nodeIds = List.of("node-0", "node-1", "node-2", "node-3");
    private final Map<String, ProcessInfo> processInfos = new HashMap<>();
    private final Map<String, KeyPair> keyPairs = new HashMap<>();
    private final Map<String, PublicKey> publicKeys = new HashMap<>();

    private final List<TestNode> cluster = new ArrayList<>();
    private ClientLibrary client;
    private ProcessInfo clientInfo;
    private KeyPair clientKeys;

    private boolean simulateByzantineLeader = false;

    private class TestNode {
        String id;
        Service service;
        LinkManager linkManager;
        Consensus consensus;
        ScheduledExecutorService pacemaker;

        void shutdown() {
            if (consensus != null)
                consensus.shutdown();
            if (linkManager != null)
                linkManager.shutdown();
            if (pacemaker != null)
                pacemaker.shutdownNow();
        }
    }

    @BeforeEach
    public void setup() throws Exception {
        clientInfo = new ProcessInfo("client-1", InetAddress.getByName("localhost"), BASE_PORT + 4);
        clientKeys = CryptoUtils.generateRSAKeyPair();
        publicKeys.put("client-1", clientKeys.getPublic());
        processInfos.put("client-1", clientInfo);

        for (int i = 0; i < 4; i++) {
            String id = nodeIds.get(i);
            ProcessInfo pi = new ProcessInfo(id, InetAddress.getByName("localhost"), BASE_PORT + i);
            KeyPair kp = CryptoUtils.generateRSAKeyPair();
            processInfos.put(id, pi);
            keyPairs.put(id, kp);
            publicKeys.put(id, kp.getPublic());
        }
    }

    private void bootCluster() throws Exception {
        for (int i = 0; i < 4; i++) {
            String id = nodeIds.get(i);

            Map<String, ProcessInfo> peers = new HashMap<>(processInfos);
            peers.remove(id);

            TestNode tn = new TestNode();
            tn.id = id;
            tn.linkManager = new LinkManager(processInfos.get(id), peers, keyPairs.get(id), publicKeys);

            BlockchainState state = new BlockchainState();
            TransactionManager tm = new TransactionManager(state, tn.linkManager);
            tn.service = new Service(state, tm);

            CryptoManager crypto;
            if (simulateByzantineLeader && i == 0) {
                crypto = new ByzantineCryptoManager(id, "../setup_config/keys");
            } else {
                crypto = new CryptoManager(id, "../setup_config/keys");
            }

            tn.pacemaker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            tn.consensus = new Consensus(id, nodeIds, tn.linkManager, tn.service, tm, crypto, tn.pacemaker);

            tn.linkManager.setMessageListener(new MessageRouter(tn.consensus, tm));
            cluster.add(tn);
        }

        for (TestNode tn : cluster) {
            tn.linkManager.start();
        }
        Thread.sleep(1500);

        for (TestNode tn : cluster) {
            tn.consensus.start();
        }
    }

    private void bootClient(boolean useBadKeys) throws Exception {
        KeyPair keysToUse = useBadKeys ? CryptoUtils.generateRSAKeyPair() : clientKeys;

        Map<String, ProcessInfo> servers = new HashMap<>(processInfos);
        servers.remove("client-1");

        org.web3j.crypto.ECKeyPair ecKeys = CryptoUtils.createECKeyPair();
        client = new ClientLibrary(clientInfo, servers, keysToUse, publicKeys, ecKeys);
        client.start();
        Thread.sleep(1000);
    }

    @AfterEach
    public void teardown() {
        if (client != null)
            client.stop();
        for (TestNode tn : cluster) {
            tn.shutdown();
        }
        cluster.clear();
        simulateByzantineLeader = false;
    }

    // Test 1: Normal Client Transaction Flow

    /**
     * Boot up 4 Consensus nodes and 1 Client.
     * Verify that when the Client sends a valid signed transaction request,
     * the system successfully reaches consensus and replies with a quorum.
     */
    @Test
    public void testNormalClientTransactionFlow() throws Exception {
        bootCluster();
        bootClient(false);

        addAccountToAllNodes(client.getMyAddress(), BigInteger.valueOf(1_000_000), 0);

        CompletableFuture<ist.group29.depchain.client.ClientMessages.TransactionResponse> future = client
                .submitTransaction("", 0, "E2E_Tx_1".getBytes());

        TransactionResponse receipt = future.get(5, TimeUnit.SECONDS);
        Assertions.assertEquals(TransactionStatus.SUCCESS, receipt.getStatus(),
                "Client transaction should successfully reach quorum with SUCCESS status");
    }

    // Test 2: Crash Fault Tolerance (f=1)

    /**
     * Boot up 4 Consensus nodes. Forcefully crash one of the replicas (Node-3).
     * Verify that the remaining 3 nodes (which satisfies N >= 3f+1) can still
     * successfully process a Client's transaction and reach consensus natively.
     */
    @Test
    public void testCrashFaultTolerance() throws Exception {
        bootCluster();

        cluster.get(3).shutdown();

        bootClient(false);

        addAccountToAllNodes(client.getMyAddress(), BigInteger.valueOf(1_000_000), 0);

        CompletableFuture<ist.group29.depchain.client.ClientMessages.TransactionResponse> future = client
                .submitTransaction("", 0, "Crash_Tolerance_Tx".getBytes());

        TransactionResponse receipt = future.get(5, TimeUnit.SECONDS);
        Assertions.assertEquals(TransactionStatus.SUCCESS, receipt.getStatus(),
                "Consensus should be reached and transaction successful despite 1 node crashing");
    }

    // Test 3: Byzantine Client (Invalid Signature)

    /**
     * Boot up 4 nodes and a malicious Client.
     * The malicious client signs a transaction using an unregistered, random RSA key.
     */
    @Test
    public void testByzantineClient_InvalidSignature() throws Exception {
        bootCluster();
        bootClient(true);

        CompletableFuture<ist.group29.depchain.client.ClientMessages.TransactionResponse> future = client.submitTransaction("", 0, "Hacker_Tx_Fake_Sig".getBytes());

        Assertions.assertThrows(TimeoutException.class, () -> {
            future.get(2, TimeUnit.SECONDS);
        }, "Malicious request should fail validation and timeout entirely");
    }

    // Test 4: Replay Attack Rejection

    /**
     * Submit a valid native transfer through the real client path, then replay the
     * exact same serialized client message. The first transfer should commit once,
     * while the replay should leave balances unchanged because the nonce has already
     * been consumed.
     */
    @Test
    public void testReplayAttackRejectedE2E() throws Exception {
        bootCluster();
        bootClient(false);

        String senderAddress = client.getMyAddress();
        String recipientAddress = "0x2222222222222222222222222222222222222222";
        BigInteger initialBalance = BigInteger.valueOf(1_000_000);
        BigInteger transferAmount = BigInteger.valueOf(25);

        addAccountToAllNodes(senderAddress, initialBalance, 0);
        addAccountToAllNodes(recipientAddress, BigInteger.ZERO, 0);

        CompletableFuture<TransactionResponse> future =
                client.submitTransaction(recipientAddress, transferAmount.longValue(), null, 1, 21_000);

        TransactionResponse receipt = future.get(8, TimeUnit.SECONDS);
        Assertions.assertEquals(
                TransactionStatus.SUCCESS,
                receipt.getStatus(),
            "Original transfer should commit successfully");

        Assertions.assertTrue(client.replayLastMessage(), "Client should have a last serialized message to replay");
        // Wait to check if the replay was processed or rejected. Since the replay should be rejected due to nonce reuse, we expect balances and nonce to remain unchanged after a short wait.
        waitForClusterBalance(recipientAddress, transferAmount, 5);
        waitForClusterNonce(senderAddress, 1, 5);
    }

    /**
     * Helper Class used to simulate malicious leader.
     */
    private static class ByzantineCryptoManager extends ist.group29.depchain.server.crypto.CryptoManager {
        public ByzantineCryptoManager(String nodeId, String keysDir) throws Exception {
            super(nodeId, keysDir);
        }

        @Override
        public byte[] aggregateSignatureShares(byte[] data, java.util.List<byte[]> sharesData,
                java.util.List<Integer> participantIds) throws Exception {
            byte[] realAgg = super.aggregateSignatureShares(data, sharesData, participantIds);

            // Corrupt the signature
            if (realAgg.length > 0) {
                realAgg[realAgg.length / 2] ^= 1;
            }
            return realAgg;
        }
    }

    private void addAccountToAllNodes(String address, BigInteger balance, long nonce) {
        String normalized = CryptoUtils.normalizeAddress(address);
        for (TestNode tn : cluster) {
            tn.service.getState().addAccount(new EOA(normalized, balance, nonce));
        }
    }

    private void waitForClusterBalance(String address, BigInteger expectedBalance, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        String normalized = CryptoUtils.normalizeAddress(address);

        while (System.nanoTime() < deadline) {
            boolean allMatch = true;
            for (TestNode tn : cluster) {
                BlockchainAccount account = tn.service.getState().getAccount(normalized);
                if (account == null || account.getBalance().compareTo(expectedBalance) != 0) {
                    allMatch = false;
                    break;
                }
            }

            if (allMatch) {
                return;
            }
            Thread.sleep(200);
        }

        Assertions.fail("Timed out waiting for balance " + expectedBalance + " on " + address);
    }

    private void waitForClusterNonce(String address, long expectedNonce, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        String normalized = CryptoUtils.normalizeAddress(address);

        while (System.nanoTime() < deadline) {
            boolean allMatch = true;
            for (TestNode tn : cluster) {
                BlockchainAccount account = tn.service.getState().getAccount(normalized);
                if (!(account instanceof EOA eoa) || eoa.getNonce() != expectedNonce) {
                    allMatch = false;
                    break;
                }
            }

            if (allMatch) {
                return;
            }
            Thread.sleep(200);
        }

        Assertions.fail("Timed out waiting for nonce " + expectedNonce + " on " + address);
    }
}
