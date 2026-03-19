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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ist.group29.depchain.client.ClientLibrary;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.common.network.ProcessInfo;
import ist.group29.depchain.server.consensus.Consensus;
import ist.group29.depchain.server.crypto.CryptoManager;
import ist.group29.depchain.server.service.Service;

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
            tn.service = new Service();
            tn.linkManager = new LinkManager(processInfos.get(id), peers, keyPairs.get(id), publicKeys);

            CryptoManager crypto;
            if (simulateByzantineLeader && i == 0) {
                crypto = new ByzantineCryptoManager(id, "../keys");
            } else {
                crypto = new CryptoManager(id, "../keys");
            }

            tn.pacemaker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            tn.consensus = new Consensus(id, nodeIds, tn.linkManager, tn.service, crypto, tn.pacemaker);

            tn.linkManager.setMessageListener(tn.consensus::onMessage);
            cluster.add(tn);
        }

        for (TestNode tn : cluster) {
            tn.linkManager.start();
        }
        Thread.sleep(1500); 

        for (TestNode tn : cluster) {
            tn.consensus.start("cmd-initial");
        }
    }

    private void bootClient(boolean useBadKeys) throws Exception {
        KeyPair keysToUse = useBadKeys ? CryptoUtils.generateRSAKeyPair() : clientKeys;

        Map<String, ProcessInfo> servers = new HashMap<>(processInfos);
        servers.remove("client-1"); 

        client = new ClientLibrary(clientInfo, servers, keysToUse, publicKeys);
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
     * Verify that when the Client sends a valid signed append request,
     * the system successfully reaches consensus and replies with a quorum.
     */
    @Test
    public void testNormalClientTransactionFlow() throws Exception {
        bootCluster();
        bootClient(false);

        CompletableFuture<Void> future = client.append("E2E_Tx_1");

        Assertions.assertDoesNotThrow(() -> {
            future.get(5, TimeUnit.SECONDS);
        }, "Client append should successfully reach quorum without timing out");
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

        CompletableFuture<Void> future = client.append("Crash_Tolerance_Tx");

        Assertions.assertDoesNotThrow(() -> {
            future.get(5, TimeUnit.SECONDS);
        }, "Consensus should be reached despite 1 node crashing");
    }

    // Test 3: Byzantine Fault Tolerance (Leader Drops/Mangles QC)

    /**
     * Simulate Node-0 (the initial leader) acting
     * maliciously by generating invalid Quorum Certificates.
     * Verify that replicas reject the invalid proposal and seamlessly advance the view to a new honest leader to commit.
     */
    @Test
    public void testByzantineFaultTolerance_LeaderInvalidQC() throws Exception {
        simulateByzantineLeader = true;
        bootCluster();
        bootClient(false);

        CompletableFuture<Void> future = client.append("Byzantine_Tolerance_Tx");

        Assertions.assertDoesNotThrow(() -> {
            future.get(8, TimeUnit.SECONDS);
        }, "Consensus should be reached by new leader after View Change without timing out");

        int decidedNodes = 0;
        Thread.sleep(100);
        decidedNodes = 0;
        for (TestNode tn : cluster) {
            if (tn.consensus.getLastDecidedView() >= 2) {
                decidedNodes++;
            }
        }

        Assertions.assertTrue(decidedNodes >= 3, 
            "At least a quorum of nodes should have finally committed the transaction under View 2 or higher, proving Node-0 failed it!");
    }

    // Test 4: Byzantine Client (Invalid Signature)

    /**
     * Boot up 4 nodes and a malicious Client.
     * The malicious client signs a transaction using an unregistered, random RSA key.
     */
    @Test
    public void testByzantineClient_InvalidSignature() throws Exception {
        bootCluster();
        bootClient(true);

        CompletableFuture<Void> future = client.append("Hacker_Tx_Fake_Sig");

        Assertions.assertThrows(TimeoutException.class, () -> {
            future.get(2, TimeUnit.SECONDS);
        }, "Malicious request should fail validation and timeout entirely");
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
}
