package ist.group29.depchain;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.security.KeyPair;

import org.mockito.Mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.invocation.InvocationOnMock;

import ist.group29.depchain.server.crypto.CryptoManager;
import com.google.protobuf.ByteString;

import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.network.ConsensusMessages.ConsensusMessage;
import ist.group29.depchain.network.ConsensusMessages.NewViewMessage;
import ist.group29.depchain.network.ConsensusMessages.PrepareMessage;
import ist.group29.depchain.network.ConsensusMessages.VoteMessage;
import ist.group29.depchain.server.consensus.Consensus;
import ist.group29.depchain.server.consensus.HotStuffNode;
import ist.group29.depchain.server.consensus.QuorumCertificate;
import ist.group29.depchain.server.service.Service;
import ist.group29.depchain.client.ClientMessages.ClientRequest;
import ist.group29.depchain.network.ConsensusMessages.PreCommitMessage;
import ist.group29.depchain.network.ConsensusMessages.CommitMessage;
import ist.group29.depchain.network.ConsensusMessages.DecideMessage;
import ist.group29.depchain.network.ConsensusMessages.SyncResponse;

/**
 * Unit tests for the Basic HotStuff consensus engine.
 * System parameters: n=4, f=1, quorum=3 (smallest BFT cluster).
 */
@ExtendWith(MockitoExtension.class)
class ConsensusTest {

        // 4-node BFT cluster: n=4, f=1, quorum=3
        static final int N = 4;
        static final int F = (N - 1) / 3;
        static final int QUORUM = N - F;

        static final List<String> NODE_IDS = List.of("node-0", "node-1", "node-2", "node-3");

        @Mock
        LinkManager mockLinkManager;

        @Mock
        CryptoManager mockCrypto;

        @Mock
        ScheduledExecutorService mockPacemaker;

        @Mock
        ScheduledFuture<?> mockFuture;

        Service service;
        Consensus consensus;
        Runnable scheduledTimeoutTask;

        @BeforeEach
        void setup() throws Exception {
                service = new Service();
                // Setup default mock behavior for CryptoManager (RSA)
                lenient().when(mockCrypto.computeSignatureShare(any())).thenReturn(new byte[128]);
                lenient().when(mockCrypto.aggregateSignatureShares(any(), anyList(), anyList()))
                                .thenReturn(new byte[128]);
                lenient().when(mockCrypto.verifyThresholdSignature(any(), any())).thenReturn(true);

                // Setup manual pacemaker
                lenient().doAnswer((InvocationOnMock invocation) -> {
                        scheduledTimeoutTask = invocation.getArgument(0);
                        return mockFuture;
                }).when(mockPacemaker).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

                consensus = new Consensus("node-0", NODE_IDS, mockLinkManager, service, mockCrypto, mockPacemaker);
        }

        void triggerTimeout() {
                if (scheduledTimeoutTask != null) {
                        Runnable task = scheduledTimeoutTask;
                        scheduledTimeoutTask = null;
                        task.run();
                }
        }

        // Test 1: Leader rotation

        /**
         * Verify that leader(v) maps view numbers to node IDs via round-robin
         * over the sorted node list.
         */
        @Test
        void testLeaderRotation() {
                assertEquals("node-0", consensus.leader(1), "View 1 leader should be node-0");
                assertEquals("node-1", consensus.leader(2), "View 2 leader should be node-1");
                assertEquals("node-2", consensus.leader(3), "View 3 leader should be node-2");
                assertEquals("node-3", consensus.leader(4), "View 4 leader should be node-3");
                assertEquals("node-0", consensus.leader(5), "View 5 should wrap to node-0 again");
        }

        // Test 2: safeNode - safety rule (branch extension check)

        /**
         * Verify that safeNode rejects a node that does NOT extend from
         * the lockedQC node AND whose QC is not fresher.
         */
        @Test
        void testSafeNodeSafetyRuleRejectsConflict() {

                consensus.start(null); // starts view 1

                HotStuffNode validProposal = HotStuffNode.genesis().createLeaf("valid-cmd", 1, "system",
                                System.currentTimeMillis());
                consensus.storeBlock(validProposal);

                // Receive PREPARE (to set currentProposal)
                QuorumCertificate genesisQC = QuorumCertificate.genesisQC();
                ConsensusMessage prepareMsgV1 = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(validProposal.getProto())
                                                .setJustify(genesisQC.getProto())
                                                .build())
                                .build();
                consensus.onMessage("node-0", prepareMsgV1.toByteArray());

                // Receive PRE-COMMIT (to set prepareQC)
                QuorumCertificate validPrepareQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 1, validProposal.getNodeHash(), new byte[64]);
                ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage.newBuilder()
                                                .setJustify(validPrepareQC.getProto())
                                                .build())
                                .build();
                consensus.onMessage("node-0", preCommitMsg.toByteArray());

                // Receive COMMIT (to set lockedQC)
                QuorumCertificate validPreCommitQC = QuorumCertificate.create(
                                QuorumCertificate.PRE_COMMIT, 1, validProposal.getNodeHash(), new byte[64]);
                ConsensusMessage commitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setCommit(ist.group29.depchain.network.ConsensusMessages.CommitMessage.newBuilder()
                                                .setJustify(validPreCommitQC.getProto())
                                                .build())
                                .build();
                consensus.onMessage("node-0", commitMsg.toByteArray());

                // Receive DECIDE
                QuorumCertificate validCommitQC = QuorumCertificate.create(
                                QuorumCertificate.COMMIT, 1, validProposal.getNodeHash(), new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
                                                .setJustify(validCommitQC.getProto())
                                                .build())
                                .build();
                consensus.onMessage("node-0", decideMsg.toByteArray());

                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Simulate a conflicting PREPARE in view 2
                // The conflicting node extends from genesis (view 0), NOT from the lockedQC
                // node
                HotStuffNode conflictingNode = HotStuffNode.genesis().createLeaf("conflicting-cmd", 2, "system", 0);

                QuorumCertificate staleJustify = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 1, conflictingNode.getProto().getParentHash().toByteArray(),
                                new byte[64]);

                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(2)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(conflictingNode.getProto())
                                                .setJustify(staleJustify.getProto())
                                                .build())
                                .build();

                consensus.onMessage("node-1", prepareMsg.toByteArray());

                // VOTE should NOT be sent because safeNode fails
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), org.mockito.ArgumentMatchers.any());
        }

        // Test 3: safeNode - liveness rule overrides old lock

        /**
         * Verify that safeNode accepts a conflicting proposal when the
         * QC is fresher than the lockedQC (liveness rule).
         */
        @Test
        void testSafeNodeLivenessRuleAcceptsFresherQC() {
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto,
                                mockPacemaker);
                replica.start(null);

                // Build a valid proposal from node-0
                HotStuffNode v1Proposal = HotStuffNode.genesis()
                                .createLeaf("hello-world", 1, "system", 0);

                QuorumCertificate freshJustify = QuorumCertificate.genesisQC();

                ConsensusMessage prepareMsg1 = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(v1Proposal.getProto())
                                                .setJustify(freshJustify.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg1.toByteArray());

                QuorumCertificate v1PrepareQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 1, v1Proposal.getNodeHash(), new byte[64]);

                ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage
                                                .newBuilder()
                                                .setJustify(v1PrepareQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", preCommitMsg.toByteArray());

                // Receive COMMIT
                QuorumCertificate v1PreCommitQC = QuorumCertificate.create(
                                QuorumCertificate.PRE_COMMIT, 1, v1Proposal.getNodeHash(), new byte[64]);
                ConsensusMessage commitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setCommit(CommitMessage.newBuilder()
                                                .setJustify(v1PreCommitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", commitMsg.toByteArray());

                // Replica is now locked on v1Proposal (view 1)

                triggerTimeout();
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                triggerTimeout();

                HotStuffNode conflictingProposal = HotStuffNode.genesis().createLeaf("conflicting-cmd", 3, "system", 0);
                replica.storeBlock(conflictingProposal);

                QuorumCertificate fresherQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 2,
                                conflictingProposal.getProto().getParentHash().toByteArray(), new byte[64]);

                ConsensusMessage prepareMsg2 = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(conflictingProposal.getProto())
                                                .setJustify(fresherQC.getProto())
                                                .build())
                                .build();

                org.mockito.Mockito.clearInvocations(mockLinkManager);
                replica.onMessage("node-2", prepareMsg2.toByteArray());

                // Verify the liveness rule passed by checking that a VOTE was sent back to
                // the leader
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.times(1))
                                .send(org.mockito.ArgumentMatchers.eq("node-2"), org.mockito.ArgumentMatchers.any());

                replica.shutdown();
        }

        // Test 4: Happy-path leader

        /**
         * Simulate a complete, happy-path view for node-0 (the leader for view 1).
         */
        @Test
        void testHappyPathLeaderCompletesOneView() throws Exception {
                // Use a latch to detect the decide upcall asynchronously
                CountDownLatch decideLatch = new CountDownLatch(1);
                Service trackingService = new Service() {
                        @Override
                        public synchronized void onDecide(String command, int viewNumber, String clientId,
                                        long timestamp) {
                                super.onDecide(command, viewNumber, clientId, timestamp);
                                decideLatch.countDown();
                        }
                };

                Consensus leader = new Consensus("node-0", NODE_IDS, mockLinkManager, trackingService, mockCrypto);

                // Feed a ClientRequest to leader node-0 so it will propose it
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair kp = kpg.generateKeyPair();
                org.mockito.Mockito.lenient().when(mockLinkManager.getPeerPublicKey("client-1"))
                                .thenReturn(kp.getPublic());

                long timestampReq = System.currentTimeMillis();
                byte[] sig = CryptoUtils.sign(kp.getPrivate(),
                                CryptoUtils.toBytes("APPEND"),
                                CryptoUtils.toBytes("cmd-view-1"),
                                CryptoUtils.toBytes(timestampReq));

                ClientRequest req = ClientRequest
                                .newBuilder()
                                .setOp(ClientRequest.Operation.APPEND)
                                .setValue("cmd-view-1")
                                .setTimestamp(timestampReq)
                                .setSignature(com.google.protobuf.ByteString.copyFrom(sig))
                                .build();
                leader.onMessage("client-1", req.toByteArray());

                // Feed n-f NEW-VIEW messages from three different peers
                leader.start(null);

                feedNewView(leader, "node-1", 1);
                feedNewView(leader, "node-2", 1);

                // Feed n-f PREPARE votes
                byte[] nodeHash = computeViewNodeHash("cmd-view-1", 1);
                feedVote(leader, "node-0", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(leader, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(leader, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);

                // Feed n-f PRE-COMMIT votes
                feedVote(leader, "node-0", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                feedVote(leader, "node-1", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                feedVote(leader, "node-2", QuorumCertificate.PRE_COMMIT, 1, nodeHash);

                // Feed n-f COMMIT votes
                feedVote(leader, "node-0", QuorumCertificate.COMMIT, 1, nodeHash);
                feedVote(leader, "node-1", QuorumCertificate.COMMIT, 1, nodeHash);
                feedVote(leader, "node-2", QuorumCertificate.COMMIT, 1, nodeHash);

                // Wait for the decide upcall
                boolean decided = decideLatch.await(5, TimeUnit.SECONDS);
                assertTrue(decided, "DECIDE should have been reached within timeout");
                assertEquals(1, trackingService.size(), "Blockchain should have exactly 1 entry");
                assertEquals("cmd-view-1", trackingService.getChain().get(0),
                                "Decided command should be 'cmd-view-1'");

                leader.shutdown();
        }

        // Test 5: HotStuffNode hash chaining

        /**
         * Verify that createLeaf("cmd", 0, "system", 0) produces deterministic hash
         * chains
         * and that extendsFrom() correctly checks ancestry.
         */
        @Test
        void testHotStuffNodeHashChaining() {
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode child1 = genesis.createLeaf("cmd", 0, "system", 0);
                HotStuffNode child2 = child1.createLeaf("cmd", 0, "system", 0);

                assertArrayEquals(genesis.getNodeHash(),
                                child1.getProto().getParentHash().toByteArray(),
                                "child1.parentHash must equal genesis.nodeHash");

                assertArrayEquals(child1.getNodeHash(),
                                child2.getProto().getParentHash().toByteArray(),
                                "child2.parentHash must equal child1.nodeHash");

                // extendsFrom checks
                consensus.storeBlock(child1);
                consensus.storeBlock(child2);
                assertTrue(consensus.extendsFrom(child1, genesis.getNodeHash()),
                                "child1 must extend from genesis");
                assertTrue(consensus.extendsFrom(child2, child1.getNodeHash()),
                                "child2 must extend from child1");
                assertTrue(consensus.extendsFrom(child2, genesis.getNodeHash()),
                                "child2 must extend from genesis (recursive)");

                // Determinism: same inputs must produce same hash
                HotStuffNode child1b = genesis.createLeaf("cmd", 0, "system", 0);
                assertArrayEquals(child1.getNodeHash(), child1b.getNodeHash(),
                                "Hash must be deterministic for the same inputs");
        }

        // Test 6: extendsFrom failure
        @Test
        void testRejectsInvalidParentHash() {
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("evil-cmd", 1, "system",
                                System.currentTimeMillis());
                byte[] randomHash = new byte[32];
                randomHash[0] = 1; // Not genesis hash
                HotStuffNode evilNode = new HotStuffNode(
                                proposed.getProto().toBuilder()
                                                .setParentHash(ByteString.copyFrom(randomHash))
                                                .build());

                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(evilNode.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();

                replica.onMessage("node-0", prepareMsg.toByteArray());
                // Replica should reject and NOT send a VOTE back to node-0
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), org.mockito.ArgumentMatchers.any());
                replica.shutdown();
        }

        // Test 7: Duplicate Vote Spammer
        @Test
        void testDuplicateVoteSpammer() {
                consensus.start(null);

                byte[] nodeHash = computeViewNodeHash("cmd-test", 1);

                feedNewView(consensus, "node-1", 1);
                feedNewView(consensus, "node-2", 1);

                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // One malicious node sends QUORUM number of identical votes
                for (int i = 0; i < QUORUM; i++) {
                        feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                }

                // The leader should NOT have reached quorum because all votes came from
                // "node-1"
                // It should NOT broadcast PRE-COMMIT
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
        }

        // Test 8: Leader Equivocation (Double Prepare)
        @Test
        void testLeaderEquivocation() {
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Leader sends first valid PREPARE
                HotStuffNode proposedA = HotStuffNode.genesis().createLeaf("cmd-A", 1, "system",
                                System.currentTimeMillis());
                ConsensusMessage prepareMsgA = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposedA.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsgA.toByteArray());

                // Replica should have voted for A
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.times(1))
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), org.mockito.ArgumentMatchers.any());

                // Leader equivocation! Sends a second PREPARE for the same view
                HotStuffNode proposedB = HotStuffNode.genesis().createLeaf("cmd-B", 1, "system",
                                System.currentTimeMillis());
                ConsensusMessage prepareMsgB = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposedB.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsgB.toByteArray());

                // Replica should REJECT B, so it should NOT vote a second time
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.times(1))
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), org.mockito.ArgumentMatchers.any());
                replica.shutdown();
        }

        // Test 9: Duplicate DECIDE Race Condition
        @Test
        void testDuplicateDecide() {
                int[] decideCalls = { 0 };
                Service trackingService = new Service() {
                        @Override
                        public synchronized void onDecide(String command, int viewNumber, String clientId,
                                        long timestamp) {
                                super.onDecide(command, viewNumber, clientId, timestamp);
                                decideCalls[0]++;
                        }
                };
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService, mockCrypto);
                replica.start(null); // Join view 1

                // First, feed a PREPARE so currentProposal is set!
                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd", 1, "system", 0);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());

                // PRE-COMMIT
                QuorumCertificate prepareQC = QuorumCertificate.create(QuorumCertificate.PREPARE, 1,
                                proposed.getNodeHash(), new byte[64]);
                ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage
                                                .newBuilder()
                                                .setJustify(prepareQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", preCommitMsg.toByteArray());

                // COMMIT
                QuorumCertificate preCommitQC = QuorumCertificate.create(QuorumCertificate.PRE_COMMIT, 1,
                                proposed.getNodeHash(), new byte[64]);
                ConsensusMessage commitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setCommit(CommitMessage.newBuilder()
                                                .setJustify(preCommitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", commitMsg.toByteArray());

                QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 1,
                                proposed.getNodeHash(),
                                new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();

                // Leader fires DECIDE multiple times
                replica.onMessage("node-0", decideMsg.toByteArray());
                replica.onMessage("node-0", decideMsg.toByteArray());
                replica.onMessage("node-0", decideMsg.toByteArray());

                // The service should have only executed the command ONCE
                assertEquals(1, decideCalls[0],
                                "Decide should be strictly idempotent, ignoring duplicate DECIDE messages.");
                replica.shutdown();
        }

        // Test 10: Skipped Phases
        @Test
        void testSkippedPhases() {
                int[] decideCalls = { 0 };
                Service trackingService = new Service() {
                        @Override
                        public synchronized void onDecide(String command, int viewNumber, String clientId,
                                        long timestamp) {
                                super.onDecide(command, viewNumber, clientId, timestamp);
                                decideCalls[0]++;
                        }
                };
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService, mockCrypto);
                replica.start(null);

                byte[] nodeHash = computeViewNodeHash("cmd", 1);
                QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 1, nodeHash,
                                new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();

                replica.onMessage("node-0", decideMsg.toByteArray());
                assertEquals(0, decideCalls[0], "Replica cannot decide without the block (currentProposal == null)");
                replica.shutdown();
        }

        // Test 11: Forged/Invalid QC Signature Test
        @Test
        void testForgedQC() {
                // Ensure the cryptographic validation FAILS for forged signatures
                lenient().when(mockCrypto.verifyThresholdSignature(any(), any())).thenReturn(false);

                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null);

                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd", 1, "system", 0);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                QuorumCertificate forgedQC = QuorumCertificate.create(QuorumCertificate.PREPARE, 1,
                                proposed.getNodeHash(),
                                new byte[64]);
                ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage
                                                .newBuilder()
                                                .setJustify(forgedQC.getProto())
                                                .build())
                                .build();

                replica.onMessage("node-0", preCommitMsg.toByteArray());

                // Should reject due to invalid signature, no vote sent
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), org.mockito.ArgumentMatchers.any());
                replica.shutdown();
        }

        // Test 12: Old View Proposal Test
        @Test
        void testOldViewProposal() {
                consensus.start(null);

                // Advance to view 2
                triggerTimeout();

                org.mockito.Mockito.clearInvocations(mockLinkManager);

                HotStuffNode oldNode = HotStuffNode.genesis().createLeaf("old", 2, "system", 0);
                ConsensusMessage oldPrepare = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(oldNode.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();

                consensus.onMessage("node-1", oldPrepare.toByteArray());

                // Replica should ignore old view 1 and not send any vote
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        // Test 13: Imposter Leader Test
        @Test
        void testImposterLeader() {
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);
                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd", 0, "system", 0);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();

                replica.onMessage("node-2", prepareMsg.toByteArray()); // node-2 is not leader for view 1
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
                replica.shutdown();
        }

        // Test 14: Incremental Branch Execution
        @Test
        void testIncrementalExecution() {
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode nodeV1 = genesis.createLeaf("cmd-view-1", 1, "system", 0);
                HotStuffNode nodeV2 = nodeV1.createLeaf("cmd-view-2", 2, "system", 0);
                HotStuffNode nodeV3 = nodeV2.createLeaf("cmd-view-3", 3, "system", 0);

                List<String> commands = new ArrayList<>();
                Service trackingService = new Service() {
                        @Override
                        public synchronized void onDecide(String command, int viewNumber, String clientId,
                                        long timestamp) {
                                super.onDecide(command, viewNumber, clientId, timestamp);
                                commands.add(command);
                        }
                };

                Consensus replica = new Consensus("node-2", NODE_IDS, mockLinkManager, trackingService, mockCrypto,
                                mockPacemaker);
                replica.start(null);

                replica.storeBlock(nodeV1);
                replica.storeBlock(nodeV2);
                replica.storeBlock(nodeV3);

                // Advance replica's view to 3
                triggerTimeout();
                triggerTimeout();
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                QuorumCertificate v2QC = QuorumCertificate.create(QuorumCertificate.PREPARE, 2, nodeV2.getNodeHash(),
                                new byte[64]);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(nodeV3.getProto())
                                                .setJustify(v2QC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-2", prepareMsg.toByteArray());

                // DECIDE for View 3
                QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 3, nodeV3.getNodeHash(),
                                new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setDecide(DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-2", decideMsg.toByteArray());

                // Verify incremental chronological execution
                assertEquals(3, commands.size(), "Should incrementally execute all 3 commands from the branch");
                assertEquals("cmd-view-1", commands.get(0), "V1 must execute first");
                assertEquals("cmd-view-2", commands.get(1), "V2 must execute second");
                assertEquals("cmd-view-3", commands.get(2), "V3 must execute last");

                replica.shutdown();
        }

        // Test 15: Sync Request Triggered on Missing Block
        @Test
        void testSyncRequestTriggeredOnMissingBlock() {
                // Create a replica at view 1 with no extra blocks in blockStore
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null);
                org.mockito.Mockito.lenient().when(mockCrypto.verifyThresholdSignature(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                                .thenReturn(true); // Force the fake QC to pass validation
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Build a PREPARE that references a block we DON'T have
                byte[] unknownHash = new byte[32];
                unknownHash[0] = (byte) 0xDE;
                unknownHash[1] = (byte) 0xAD;

                // Create a node that "extends from" the unknown block
                HotStuffNode proposed = new HotStuffNode(
                                ist.group29.depchain.network.ConsensusMessages.HotStuffNode.newBuilder()
                                                .setParentHash(ByteString.copyFrom(unknownHash))
                                                .setCommand("cmd-catch-up")
                                                .setViewNumber(1)
                                                .setNodeHash(ByteString.copyFrom(
                                                                CryptoUtils.computeHash(unknownHash, "cmd-catch-up",
                                                                                1)))
                                                .build());

                QuorumCertificate freshJustify = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 1, unknownHash, new byte[64]);

                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(freshJustify.getProto())
                                                .build())
                                .build();

                replica.onMessage("node-0", prepareMsg.toByteArray());

                // Verify a SyncRequest was sent to the leader (node-0)
                org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), captor.capture());

                boolean sentSyncRequest = false;
                for (byte[] raw : captor.getAllValues()) {
                        try {
                                ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
                                if (msg.hasSyncRequest()) {
                                        sentSyncRequest = true;
                                }
                        } catch (Exception ignored) {
                        }
                }
                assertTrue(sentSyncRequest, "Replica should send a SyncRequest when blocks are missing");
                replica.shutdown();
        }

        // Test 16: Sync Response Populates BlockStore
        @Test
        void testSyncResponsePopulatesBlockStore() {
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode nodeV1 = genesis.createLeaf("cmd", 0, "system", 0);
                HotStuffNode nodeV2 = nodeV1.createLeaf("cmd", 0, "system", 0);

                // Initially consensus only has genesis in blockStore
                // Send a SyncResponse containing nodeV1 and nodeV2
                ConsensusMessage syncResp = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setSyncResponse(
                                                SyncResponse.newBuilder()
                                                                .addNodes(nodeV1.getProto())
                                                                .addNodes(nodeV2.getProto())
                                                                .build())
                                .build();

                consensus.onMessage("node-1", syncResp.toByteArray());

                // Verify the nodes are now in the blockStore via extendsFrom
                assertTrue(consensus.extendsFrom(nodeV2, nodeV1.getNodeHash()),
                                "nodeV2 should extend from nodeV1 after sync");
                assertTrue(consensus.extendsFrom(nodeV2, genesis.getNodeHash()),
                                "nodeV2 should extend from genesis after sync");
        }

        // Test 17: Orphaned Branch Not Executed
        // Scenario: tree forks from nodeA — one branch is committed, the other is
        // orphaned.
        // Verifies executeCommittedBranch only walks the decided path.
        @Test
        void testOrphanedBranchNotExecuted() {
                List<String> commands = new ArrayList<>();
                Service trackingService = new Service() {
                        @Override
                        public synchronized void onDecide(String command, int viewNumber, String clientId,
                                        long timestamp) {
                                super.onDecide(command, viewNumber, clientId, timestamp);
                                commands.add(command);
                        }
                };

                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService, mockCrypto);
                replica.start(null);

                // Build forked tree:
                // genesis ← nodeA("cmd-a") ← nodeOrphan("cmd-orphan") [orphaned branch]
                // ↖ nodeCommitted("cmd-committed") [committed branch]
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode nodeA = genesis.createLeaf("cmd-a", 1, "system", 0);
                HotStuffNode nodeOrphan = nodeA.createLeaf("cmd-orphan", 2, "system", 0);
                HotStuffNode nodeCommitted = nodeA.createLeaf("cmd-committed", 3, "system", 0);

                replica.storeBlock(nodeA);
                replica.storeBlock(nodeOrphan);
                replica.storeBlock(nodeCommitted);

                QuorumCertificate justifyA = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 0, nodeA.getNodeHash(), new byte[64]);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(nodeCommitted.getProto())
                                                .setJustify(justifyA.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());

                QuorumCertificate commitQC = QuorumCertificate.create(
                                QuorumCertificate.COMMIT, 0, nodeCommitted.getNodeHash(), new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", decideMsg.toByteArray());

                assertEquals(2, commands.size(), "Should execute 2 commands on committed branch");
                assertEquals("cmd-a", commands.get(0));
                assertEquals("cmd-committed", commands.get(1));

                replica.shutdown();
        }

        // Test 18: Multi-Command Incremental Execution
        // Scenario: chain of 3 nodes decided at once — all 3 commands must execute in
        // order.
        @Test
        void testMultiCommandIncrementalExecution() {
                List<String> commands = new ArrayList<>();
                Service trackingService = new Service() {
                        @Override
                        public synchronized void onDecide(String command, int viewNumber, String clientId,
                                        long timestamp) {
                                super.onDecide(command, viewNumber, clientId, timestamp);
                                commands.add(command);
                        }
                };

                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService, mockCrypto);
                replica.start(null);

                // Build chain: genesis ← nodeA ← nodeB ← nodeC
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode nodeA = genesis.createLeaf("cmd-a", 1, "system", 0);
                HotStuffNode nodeB = nodeA.createLeaf("cmd-b", 2, "system", 0);
                HotStuffNode nodeC = nodeB.createLeaf("cmd-c", 3, "system", 0);

                replica.storeBlock(nodeA);
                replica.storeBlock(nodeB);
                replica.storeBlock(nodeC);

                // PREPARE for nodeC — justify references nodeB (parent of nodeC)
                QuorumCertificate justifyB = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 0, nodeB.getNodeHash(), new byte[64]);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(nodeC.getProto())
                                                .setJustify(justifyB.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());

                // DECIDE
                QuorumCertificate commitQC = QuorumCertificate.create(
                                QuorumCertificate.COMMIT, 0, nodeC.getNodeHash(), new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", decideMsg.toByteArray());

                // All 3 commands executed in forward order
                assertEquals(3, commands.size(), "Should execute all 3 commands in the branch");
                assertEquals("cmd-a", commands.get(0));
                assertEquals("cmd-b", commands.get(1));
                assertEquals("cmd-c", commands.get(2));

                replica.shutdown();
        }

        // Test 19: Insufficient Signature Shares
        @Test
        void testInsufficientSignatureShares() throws InterruptedException {
                // Return false for verification when fewer than consensus quorum (3)
                lenient().when(mockCrypto.verifyThresholdSignature(any(), any())).thenReturn(false);

                consensus.start(null);
                byte[] nodeHash = computeViewNodeHash("cmd", 1);

                // Only 2 nodes vote (below threshold 3)
                feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(consensus, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);

                // The leader should NOT have broadcast any PRE-COMMIT
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
        }

        // Test 20: Faulty Signature Share
        @Test
        void testFaultySignatureShare() throws Exception {
                // Simulate faulty aggregation rejection
                lenient().when(mockCrypto.aggregateSignatureShares(any(), anyList(), anyList()))
                                .thenThrow(new RuntimeException("Invalid share detected"));

                consensus.start(null);
                byte[] nodeHash = computeViewNodeHash("cmd", 1);

                org.mockito.Mockito.clearInvocations(mockLinkManager);
                // Nodes send votes, but aggregation fails
                feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(consensus, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(consensus, "node-3", QuorumCertificate.PREPARE, 1, nodeHash);

                // No broadcast should occur as aggregation failed
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
        }

        // Test 21: After PREPARE-only failure, prepareQC stays at genesis
        // Scenario: replica accepts PREPARE but never receives PRE-COMMIT. On timeout,
        // the NEW-VIEW carries genesis prepareQC — next leader won't preserve the
        // failed branch.
        @Test
        void testViewChangeAfterPrepareKeepsGenesisPrepareQC() {
                Consensus replica = new Consensus("node-3", NODE_IDS, mockLinkManager, service, mockCrypto,
                                mockPacemaker);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Feed PREPARE from node-0 (view 1 leader)
                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd-v1", 1, "system", 0);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());

                org.mockito.Mockito.clearInvocations(mockLinkManager);
                triggerTimeout();

                // After timeout, NEW-VIEW to node-1 (view 2 leader) should carry
                // genesis prepareQC (view 0) because prepareQC is only updated in onPreCommit
                org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor.capture());

                boolean foundGenesisNewView = false;
                for (byte[] raw : captor.getAllValues()) {
                        try {
                                ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
                                if (msg.hasNewView()) {
                                        QuorumCertificate qc = new QuorumCertificate(msg.getNewView().getJustify());
                                        if (qc.getViewNumber() == 0) {
                                                foundGenesisNewView = true;
                                        }
                                }
                        } catch (Exception ignored) {
                        }
                }
                assertTrue(foundGenesisNewView,
                                "After PREPARE-only failure, NEW-VIEW should carry genesis prepareQC (view 0)");
                replica.shutdown();
        }

        // Test 22: After PRE-COMMIT, prepareQC is updated — branch preserved
        // Scenario: replica receives PREPARE and PRE-COMMIT (prepareQC updated to view
        // 1).
        // On timeout, NEW-VIEW carries view-1 prepareQC — next leader must build on
        // this branch.
        @Test
        void testViewChangeAfterPreCommitUpdatesPrepareQC() {
                Consensus replica = new Consensus("node-3", NODE_IDS, mockLinkManager, service, mockCrypto,
                                mockPacemaker);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Feed PREPARE from node-0 (view 1 leader)
                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd-v1", 1, "system", 0);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());

                QuorumCertificate phaseQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 0, proposed.getNodeHash(), new byte[64]);
                ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage
                                                .newBuilder()
                                                .setJustify(phaseQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", preCommitMsg.toByteArray());

                triggerTimeout();

                // NEW-VIEW to node-1 should now carry the UPDATED prepareQC
                org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor.capture());

                boolean foundUpdatedNewView = false;
                for (byte[] raw : captor.getAllValues()) {
                        try {
                                ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
                                if (msg.hasNewView()) {
                                        QuorumCertificate qc = new QuorumCertificate(msg.getNewView().getJustify());
                                        if (java.util.Arrays.equals(qc.getNodeHash(), proposed.getNodeHash())) {
                                                foundUpdatedNewView = true;
                                        }
                                }
                        } catch (Exception ignored) {
                        }
                }
                assertTrue(foundUpdatedNewView,
                                "After PRE-COMMIT, NEW-VIEW should carry updated prepareQC referencing the proposal");
                replica.shutdown();
        }

        // Test 23: Byzantine replica replays an old view vote in a new view
        @Test
        void testByzantineReplayOldViewVote() {
                consensus.start(null);

                feedNewView(consensus, "node-1", 1);
                feedNewView(consensus, "node-2", 1);
                feedNewView(consensus, "node-3", 1);

                byte[] hash = computeViewNodeHash("cmd", 1);

                ConsensusMessage replayedVote = ConsensusMessage.newBuilder()
                                .setViewNumber(0)
                                .setVote(VoteMessage.newBuilder()
                                                .setPhase(QuorumCertificate.PREPARE)
                                                .setNodeHash(ByteString.copyFrom(hash))
                                                .setSignatureShare(ByteString.copyFrom(new byte[128]))
                                                .build())
                                .build();

                org.mockito.Mockito.clearInvocations(mockLinkManager);
                consensus.onMessage("node-1", replayedVote.toByteArray());

                // The vote for view 0 should be ignored
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
        }

        // Test 24: Pacemaker recovers timeoutTriggered
        @Test
        void testPacemakerRecovery() {
                consensus.start(null);

                feedNewView(consensus, "node-1", 1);
                feedNewView(consensus, "node-2", 1);

                org.mockito.Mockito.clearInvocations(mockLinkManager);

                byte[] hash = computeViewNodeHash("cmd", 1);
                feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, hash);

                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());

                triggerTimeout();

                org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor.capture());

                boolean foundNewView = false;
                for (byte[] raw : captor.getAllValues()) {
                        try {
                                ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
                                if (msg.hasNewView() && msg.getViewNumber() == 2) {
                                        foundNewView = true;
                                        break;
                                }
                        } catch (Exception ignored) {
                        }
                }
                assertTrue(foundNewView, "Should send NEW-VIEW for view 2 after partition timeout");
        }

        // Helpers

        private void feedNewView(Consensus c, String fromId, int targetView) {
                ConsensusMessage msg = ConsensusMessage.newBuilder()
                                .setViewNumber(targetView)
                                .setNewView(NewViewMessage.newBuilder()
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                c.onMessage(fromId, msg.toByteArray());
        }

        private void feedVote(Consensus c, String fromId, String phase, int view, byte[] nodeHash) {
                byte[] dummyShare = new byte[128];
                ConsensusMessage msg = ConsensusMessage.newBuilder()
                                .setViewNumber(view)
                                .setVote(VoteMessage.newBuilder()
                                                .setPhase(phase)
                                                .setNodeHash(ByteString.copyFrom(nodeHash))
                                                .setSignatureShare(ByteString.copyFrom(dummyShare))
                                                .build())
                                .build();
                c.onMessage(fromId, msg.toByteArray());
        }

        /** Compute the node_hash for a placeholder command at a given view. */
        private byte[] computeViewNodeHash(String command, int view) {
                byte[] genesisHash = HotStuffNode.genesis().getNodeHash();
                return CryptoUtils.computeHash(genesisHash, command, view);
        }

        // Test 24: Exponential Backoff and Reset with Pacemaker
        // Verifies the 4s timeout fires correctly, the next timeout doubles to 8s,
        // and a DECIDE message resets the backoff back to 4s.
        @Test
        void testExponentialBackoffAndReset() throws Exception {
                ScheduledExecutorService pacemaker = java.util.concurrent.Executors
                                .newSingleThreadScheduledExecutor(r -> {
                                        Thread t = new Thread(r, "TestPacemaker-24");
                                        t.setDaemon(true);
                                        return t;
                                });

                Consensus c = new Consensus("node-3", NODE_IDS, mockLinkManager, service, mockCrypto, pacemaker);
                c.start(null); // View 1, timeout = 4s

                // After 2 seconds, the 4s timeout should NOT have fired yet.
                Thread.sleep(2000);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"),
                                                org.mockito.ArgumentMatchers.any());

                // Wait 3 more seconds for the first 4s timeout to fire (view 1 -> 2)
                Thread.sleep(3000);
                org.mockito.ArgumentCaptor<byte[]> captor1 = org.mockito.ArgumentCaptor.forClass(byte[].class);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor1.capture());

                boolean sentNewView1 = false;
                for (byte[] raw : captor1.getAllValues()) {
                        ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
                        if (msg.hasNewView())
                                sentNewView1 = true;
                }
                assertTrue(sentNewView1, "After 4s timeout, view should advance and NEW-VIEW sent to next leader");

                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Backoff doubled to 8s. At 6s after the first timeout, the second should NOT
                // have fired yet.
                Thread.sleep(6000);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-2"),
                                                org.mockito.ArgumentMatchers.any());

                // Wait 3 more seconds (9s after first timeout, ~14s from start).
                // The 8s backoff should have fired by now (view 2 -> 3).
                Thread.sleep(3000);
                org.mockito.ArgumentCaptor<byte[]> captor2 = org.mockito.ArgumentCaptor.forClass(byte[].class);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-2"), captor2.capture());

                boolean sentNewView2 = false;
                for (byte[] raw : captor2.getAllValues()) {
                        ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
                        if (msg.hasNewView())
                                sentNewView2 = true;
                }
                assertTrue(sentNewView2, "After 8s exponential backoff, view should advance and NEW-VIEW sent");

                // DECIDE in view 3 should reset timeout to 4s.
                // Currently in view 3 (timeout would be 16s without reset).
                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd", 3, "system", 0);
                c.storeBlock(proposed);

                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                c.onMessage("node-2", prepareMsg.toByteArray());

                QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 3,
                                proposed.getNodeHash(), new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setDecide(DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();
                c.onMessage("node-2", decideMsg.toByteArray()); // advances to view 4, resets timeout to 4s

                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // View is now 4, DECIDE should have resetted the timeout to 4s
                // At 3s after reset, timeout should NOT have fired yet.
                Thread.sleep(3000);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"),
                                                org.mockito.ArgumentMatchers.any());

                // Wait 2 more seconds (5s total). The 4s reset timeout should have fired.
                Thread.sleep(2000);
                org.mockito.ArgumentCaptor<byte[]> captor3 = org.mockito.ArgumentCaptor.forClass(byte[].class);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), captor3.capture());

                boolean sentNewView3 = false;
                for (byte[] raw : captor3.getAllValues()) {
                        ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
                        if (msg.hasNewView() && msg.getViewNumber() == 5)
                                sentNewView3 = true;
                }
                assertTrue(sentNewView3,
                                "After DECIDE, backoff should reset to 4s — timeout must fire before the old 16s");

                c.shutdown();
                pacemaker.shutdownNow();
        }

}
