package ist.group29.depchain;


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

                HotStuffNode validProposal = HotStuffNode.genesis().createLeaf("valid-cmd", 1);
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
                                .setPreCommit(ist.group29.depchain.network.ConsensusMessages.PreCommitMessage
                                                .newBuilder()
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
                HotStuffNode conflictingNode = HotStuffNode.genesis().createLeaf("conflicting-cmd", 2);

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

                HotStuffNode v1Proposal = HotStuffNode.genesis().createLeaf("v1-cmd", 1);
                replica.storeBlock(v1Proposal);

                // Receive PREPARE
                QuorumCertificate genesisQC = QuorumCertificate.genesisQC();
                ConsensusMessage prepareMsgV1 = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(v1Proposal.getProto())
                                                .setJustify(genesisQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsgV1.toByteArray());

                // Receive PRE-COMMIT
                QuorumCertificate v1PrepareQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 1, v1Proposal.getNodeHash(), new byte[64]);
                ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(ist.group29.depchain.network.ConsensusMessages.PreCommitMessage
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
                                .setCommit(ist.group29.depchain.network.ConsensusMessages.CommitMessage.newBuilder()
                                                .setJustify(v1PreCommitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", commitMsg.toByteArray());

                // Replica is now locked on v1Proposal (view 1)

                triggerTimeout();
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                triggerTimeout();

                HotStuffNode conflictingProposal = HotStuffNode.genesis().createLeaf("conflicting-cmd", 3);
                replica.storeBlock(conflictingProposal);

                QuorumCertificate fresherQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 2,
                                conflictingProposal.getProto().getParentHash().toByteArray(), new byte[64]);

                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(conflictingProposal.getProto())
                                                .setJustify(fresherQC.getProto())
                                                .build())
                                .build();

                org.mockito.Mockito.clearInvocations(mockLinkManager);
                replica.onMessage("node-2", prepareMsg.toByteArray());

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
        void testHappyPathLeaderCompletesOneView() throws InterruptedException {
                // Use a latch to detect the decide upcall asynchronously
                CountDownLatch decideLatch = new CountDownLatch(1);
                Service trackingService = new Service() {
                        @Override
                        public synchronized void onDecide(String command, int viewNumber) {
                                super.onDecide(command, viewNumber);
                                decideLatch.countDown();
                        }
                };

                Consensus leader = new Consensus("node-0", NODE_IDS, mockLinkManager, trackingService, mockCrypto);

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
         * Verify that createLeaf() produces deterministic hash chains
         * and that extendsFrom() correctly checks ancestry.
         */
        @Test
        void testHotStuffNodeHashChaining() {
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode child1 = genesis.createLeaf("cmd-A", 1);
                HotStuffNode child2 = child1.createLeaf("cmd-B", 2);

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

                HotStuffNode child1b = genesis.createLeaf("cmd-A", 1);
                assertArrayEquals(child1.getNodeHash(), child1b.getNodeHash(),
                                "Hash must be deterministic for the same inputs");
        }

        // Test 6: extendsFrom failure
        @Test
        void testRejectsInvalidParentHash() {
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("evil-cmd", 1);
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
                HotStuffNode proposedA = HotStuffNode.genesis().createLeaf("cmd-A", 1);
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
                HotStuffNode proposedB = HotStuffNode.genesis().createLeaf("cmd-B", 1);
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
                        public synchronized void onDecide(String command, int viewNumber) {
                                if ("cmd-duplicate-test".equals(command)) {
                                        decideCalls[0]++;
                                }
                        }
                };

                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService, mockCrypto,
                                mockPacemaker);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd-duplicate-test", 1);

                // PREPARE
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
                                .setPreCommit(ist.group29.depchain.network.ConsensusMessages.PreCommitMessage
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
                                .setCommit(ist.group29.depchain.network.ConsensusMessages.CommitMessage.newBuilder()
                                                .setJustify(preCommitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", commitMsg.toByteArray());

                QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 1,
                                proposed.getNodeHash(),
                                new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
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
                        public synchronized void onDecide(String command, int viewNumber) {
                                super.onDecide(command, viewNumber);
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
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
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

                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd", 1);
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
                                .setPreCommit(ist.group29.depchain.network.ConsensusMessages.PreCommitMessage
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

                // PREPARE message for an OLD view (1)
                HotStuffNode oldNode = HotStuffNode.genesis().createLeaf("old", 1);
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
                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd", 1);
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
                HotStuffNode nodeV1 = genesis.createLeaf("cmd-view-1", 1);
                HotStuffNode nodeV2 = nodeV1.createLeaf("cmd-view-2", 2);
                HotStuffNode nodeV3 = nodeV2.createLeaf("cmd-view-3", 3);

                List<String> commands = new ArrayList<>();
                Service trackingService = new Service() {
                        @Override
                        public synchronized void onDecide(String command, int viewNumber) {
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
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
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
                HotStuffNode nodeV1 = genesis.createLeaf("cmd-view-1", 1);
                HotStuffNode nodeV2 = nodeV1.createLeaf("cmd-view-2", 2);

                // Initially consensus only has genesis in blockStore
                // Send a SyncResponse containing nodeV1 and nodeV2
                ConsensusMessage syncResp = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setSyncResponse(
                                                ist.group29.depchain.network.ConsensusMessages.SyncResponse.newBuilder()
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
                        public synchronized void onDecide(String command, int viewNumber) {
                                super.onDecide(command, viewNumber);
                                commands.add(command);
                        }
                };

                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService, mockCrypto);
                replica.start(null);

                // Build forked tree:
                // genesis ← nodeA("cmd-a") ← nodeOrphan("cmd-orphan") [orphaned branch]
                // ↖ nodeCommitted("cmd-committed") [committed branch]
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode nodeA = genesis.createLeaf("cmd-a", 1);
                HotStuffNode nodeOrphan = nodeA.createLeaf("cmd-orphan", 2);
                HotStuffNode nodeCommitted = nodeA.createLeaf("cmd-committed", 3);

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
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", decideMsg.toByteArray());

                assertEquals(2, commands.size(), "Should execute 2 commands on committed branch");
                assertEquals("cmd-a", commands.get(0));
                assertEquals("cmd-committed", commands.get(1));

                replica.shutdown();
        }

        // Test 18: Faulty Signature Share
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

        // Test 19: After PREPARE-only failure, prepareQC stays at genesis
        // Scenario: replica accepts PREPARE but never receives PRE-COMMIT. On timeout,
        // the NEW-VIEW carries genesis prepareQC — next leader won't preserve the
        // failed branch.
        @Test
        void testViewChangeAfterPrepareKeepsGenesisPrepareQC() {
                Consensus replica = new Consensus("node-3", NODE_IDS, mockLinkManager, service, mockCrypto,
                                mockPacemaker);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd-v1", 1);
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

        // Test 20: After PRE-COMMIT, prepareQC is updated — branch preserved
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

                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd-v1", 1);
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
                                .setPreCommit(ist.group29.depchain.network.ConsensusMessages.PreCommitMessage
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

        // Test 21: Byzantine replica replays an old view vote in a new view
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

        // Test 22: Pacemaker recovers timeoutTriggered
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
}
