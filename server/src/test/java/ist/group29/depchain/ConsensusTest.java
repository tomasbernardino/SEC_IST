package ist.group29.depchain;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
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
import ist.group29.depchain.server.service.BlockchainState;
import ist.group29.depchain.server.service.TransactionManager;
import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.client.ClientMessages.Transaction;
import com.google.protobuf.ByteString;
import ist.group29.depchain.network.ConsensusMessages.PreCommitMessage;
import ist.group29.depchain.network.ConsensusMessages.CommitMessage;
import ist.group29.depchain.network.ConsensusMessages.DecideMessage;
import ist.group29.depchain.network.ConsensusMessages.SyncResponse;
import ist.group29.depchain.network.NetworkMessages.Envelope;

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

        // Default block returned by mockTransactionManager.buildBlock()
        static final Block DEFAULT_BLOCK = Block
                        .newBuilder()
                        .addTransactions(Transaction.newBuilder()
                                        .setFrom("test").setTo("recipient").setValue(1)
                                        .setNonce(0).setGasPrice(1).setGasLimit(21000).build())
                        .build();
        static final byte[] DEFAULT_BLOCK_BYTES = DEFAULT_BLOCK.toByteArray();

        @Mock
        LinkManager mockLinkManager;

        @Mock
        CryptoManager mockCrypto;

        @Mock
        TransactionManager mockTransactionManager;

        @Mock
        ScheduledExecutorService mockPacemaker;

        @Mock
        ScheduledFuture<?> mockFuture;

        Service service;
        Consensus consensus;
        Runnable scheduledViewTask;
        Runnable scheduledRetryTask;

        @BeforeEach
        void setup() throws Exception {
                BlockchainState testState = new BlockchainState();
                service = new Service(testState, mockTransactionManager);
                // Setup default mock behavior for CryptoManager (RSA)
                lenient().when(mockCrypto.computeSignatureShare(any())).thenReturn(new byte[128]);
                lenient().when(mockCrypto.aggregateSignatureShares(any(), anyList(), anyList()))
                        .thenReturn(new byte[128]);
                lenient().when(mockCrypto.verifyThresholdSignature(any(), any())).thenReturn(true);

                // Setup manual pacemaker — distinguish view timer from retry timer by delay
                lenient().doAnswer((InvocationOnMock inv) -> {
                        Runnable task = inv.getArgument(0);
                        long delay = inv.getArgument(1);
                        if (delay > 1_000L) {
                                scheduledViewTask = task;
                        } else {
                                scheduledRetryTask = task;
                        }
                        return mockFuture;
                }).when(mockPacemaker).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

                consensus = new Consensus("node-0", NODE_IDS, mockLinkManager, service, mockTransactionManager,
                        mockCrypto, mockPacemaker);

                // Default mock behavior for TransactionManager — return non-empty block so
                // proposals work
                lenient().when(mockTransactionManager.buildBlock()).thenReturn(DEFAULT_BLOCK);
                lenient().when(mockTransactionManager.validateBlock(any())).thenReturn(true);
                lenient().when(mockTransactionManager.executeBlock(any(), anyLong()))
                        .thenReturn(java.util.Collections.emptyList());
        }

        void triggerTimeout() {
                if (scheduledViewTask != null) {
                        Runnable task = scheduledViewTask;
                        scheduledViewTask = null;
                        task.run();
                }
        }

        void triggerRetry() {
                if (scheduledRetryTask != null) {
                        Runnable task = scheduledRetryTask;
                        scheduledRetryTask = null;
                        task.run();
                }
        }

        @Nested
        class ConsensusSafetyTest {

                // Test 1: safeNode - safety rule (branch extension check)

                /**
                 * Verify that safeNode rejects a node that does NOT extend from
                 * the lockedQC node AND whose QC is not fresher.
                 */
                @Test
                void testSafeNodeSafetyRuleRejectsConflict() {

                        consensus.start(); // starts view 1

                        HotStuffNode validProposal = leaf(HotStuffNode.genesis(), "valid-cmd", 1);
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
                        consensus.onMessage("node-0", prepareMsgV1);

                        // Receive PRE-COMMIT (to set prepareQC)
                        QuorumCertificate validPrepareQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 1, validProposal.getNodeHash(), new byte[64]);
                        ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage.newBuilder()
                                        .setJustify(validPrepareQC.getProto())
                                        .build())
                                .build();
                        consensus.onMessage("node-0", preCommitMsg);

                        // Receive COMMIT (to set lockedQC)
                        QuorumCertificate validPreCommitQC = QuorumCertificate.create(
                                QuorumCertificate.PRE_COMMIT, 1, validProposal.getNodeHash(), new byte[64]);
                        ConsensusMessage commitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setCommit(CommitMessage
                                        .newBuilder()
                                        .setJustify(validPreCommitQC.getProto())
                                        .build())
                                .build();
                        consensus.onMessage("node-0", commitMsg);

                        // Receive DECIDE
                        QuorumCertificate validCommitQC = QuorumCertificate.create(
                                QuorumCertificate.COMMIT, 1, validProposal.getNodeHash(), new byte[64]);
                        ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(DecideMessage
                                        .newBuilder()
                                        .setJustify(validCommitQC.getProto())
                                        .build())
                                .build();
                        consensus.onMessage("node-0", decideMsg);

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // Simulate a conflicting PREPARE in view 2
                        // The conflicting node extends from genesis (view 0), NOT from the lockedQC
                        // node
                        HotStuffNode conflictingNode = leaf(HotStuffNode.genesis(), "conflicting-cmd", 2);

                        QuorumCertificate staleJustify = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 1,
                                conflictingNode.getProto().getParentHash().toByteArray(),
                                new byte[64]);

                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(2)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(conflictingNode.getProto())
                                        .setJustify(staleJustify.getProto())
                                        .build())
                                .build();

                        consensus.onMessage("node-1", prepareMsg);

                        // VOTE should NOT be sent because safeNode fails
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"),
                                        org.mockito.ArgumentMatchers.any());

                }

                // Test 2: safeNode - liveness rule overrides old lock

                /**
                 * Verify that safeNode accepts a conflicting proposal when the
                 * QC is fresher than the lockedQC (liveness rule).
                 */
                @Test
                void testSafeNodeLivenessRuleAcceptsFresherQC() {
                        Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();

                        // Build a valid proposal from node-0
                        HotStuffNode v1Proposal = HotStuffNode.genesis()
                                .createLeaf(Block.newBuilder()
                                        .addTransactions(Transaction.newBuilder().setFrom("test")
                                                .setData(ByteString.copyFromUtf8("hello-world"))
                                                .build())
                                        .build(), 1);

                        QuorumCertificate freshJustify = QuorumCertificate.genesisQC();

                        ConsensusMessage prepareMsg1 = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(v1Proposal.getProto())
                                        .setJustify(freshJustify.getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", prepareMsg1);

                        QuorumCertificate v1PrepareQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 1, v1Proposal.getNodeHash(), new byte[64]);

                        ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage
                                        .newBuilder()
                                        .setJustify(v1PrepareQC.getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", preCommitMsg);

                        // Receive COMMIT
                        QuorumCertificate v1PreCommitQC = QuorumCertificate.create(
                                QuorumCertificate.PRE_COMMIT, 1, v1Proposal.getNodeHash(), new byte[64]);
                        ConsensusMessage commitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setCommit(CommitMessage.newBuilder()
                                        .setJustify(v1PreCommitQC.getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", commitMsg);

                        // Replica is now locked on v1Proposal (view 1)

                        triggerTimeout();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        triggerTimeout();

                        HotStuffNode conflictingProposal = leaf(HotStuffNode.genesis(), "conflicting-cmd", 3);
                        replica.storeBlock(conflictingProposal);
                        assertFalse(replica.extendsFrom(conflictingProposal, v1Proposal.getNodeHash()));

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
                        replica.onMessage("node-2", prepareMsg2);

                        // Verify the liveness rule passed by checking that a VOTE was sent back to
                        // the leader
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.times(1))
                                .send(org.mockito.ArgumentMatchers.eq("node-2"),
                                        org.mockito.ArgumentMatchers.any());

                        replica.shutdown();
                }

                // Test 3: Old View Proposal Test
                @Test
                void testOldViewProposal() {
                        consensus.start();

                        // Advance to view 2
                        triggerTimeout();

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        HotStuffNode oldNode = leaf(HotStuffNode.genesis(), "old", 2);
                        ConsensusMessage oldPrepare = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(oldNode.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();

                        consensus.onMessage("node-1", oldPrepare);

                        // Replica should ignore old view 1 and not send any vote
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
                }

                // Test 4: Imposter Leader Test
                @Test
                void testImposterLeader() {
                        Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service,
                                        mockTransactionManager,
                                        mockCrypto);
                        replica.start();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);
                        HotStuffNode proposed = leaf(HotStuffNode.genesis(), "cmd", 0);
                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposed.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();

                        replica.onMessage("node-2", prepareMsg); // node-2 is not leader for view 1
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
                        replica.shutdown();
                }

                // Test 5: Forged/Invalid QC Signature Test
                @Test
                void testForgedQC() {
                        // Ensure the cryptographic validation FAILS for forged signatures
                        lenient().when(mockCrypto.verifyThresholdSignature(any(), any())).thenReturn(false);

                        Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();

                        HotStuffNode proposed = leaf(HotStuffNode.genesis(), "cmd", 1);
                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposed.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", prepareMsg);
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        QuorumCertificate forgedQC = QuorumCertificate.create(QuorumCertificate.PREPARE, 1,
                                proposed.getNodeHash(), new byte[64]);
                        ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage
                                        .newBuilder()
                                        .setJustify(forgedQC.getProto())
                                        .build())
                                .build();

                        replica.onMessage("node-0", preCommitMsg);

                        // Should reject due to invalid signature, no vote sent
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"),
                                        org.mockito.ArgumentMatchers.any());
                        replica.shutdown();
                }

        }

        @Nested
        class ConsensusByzantineTest {

                // Test 6: Duplicate Vote Spammer
                @Test
                void testDuplicateVoteSpammer() {
                        consensus.start();

                        byte[] nodeHash = computeViewNodeHash(DEFAULT_BLOCK_BYTES, 1);

                        feedNewView(consensus, "node-1", 1);
                        feedNewView(consensus, "node-2", 1);

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // One malicious node sends QUORUM number of identical votes
                        for (int i = 0; i < QUORUM; i++) {
                                feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                        }

                        // The leader should NOT have reached quorum because all votes came from "node-1"
                        // It should NOT broadcast PRE-COMMIT
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
                }

                // Test 7: Leader Equivocation (Double Prepare)
                @Test
                void testLeaderEquivocation() {
                        Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto);
                        replica.start();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // Leader sends first valid PREPARE
                        HotStuffNode proposedA = leaf(HotStuffNode.genesis(), "cmd-A", 1);
                        ConsensusMessage prepareMsgA = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposedA.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", prepareMsgA);

                        // Replica should have voted for A
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.times(1))
                                .send(org.mockito.ArgumentMatchers.eq("node-0"),
                                        org.mockito.ArgumentMatchers.any());

                        // Leader equivocation sends a second PREPARE for the same view
                        HotStuffNode proposedB = leaf(HotStuffNode.genesis(), "cmd-B", 1);
                        ConsensusMessage prepareMsgB = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposedB.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", prepareMsgB);

                        // Replica should REJECT B, so it should NOT vote a second time
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.times(1))
                                .send(org.mockito.ArgumentMatchers.eq("node-0"),
                                        org.mockito.ArgumentMatchers.any());
                        replica.shutdown();
                }

                // Test 8: Duplicate DECIDE Race Condition
                @Test
                void testDuplicateDecide() {
                        int[] decideCalls = { 0 };
                        Service trackingService = new Service(new BlockchainState(), mockTransactionManager) {
                                @Override
                                public synchronized void onDecide(Block block, int viewNumber) {
                                        super.onDecide(block, viewNumber);
                                        decideCalls[0]++;
                                }
                        };
                        Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService,
                                mockTransactionManager, mockCrypto);
                        replica.start(); // Join view 1

                        // Feed a PREPARE so currentProposal is set
                        HotStuffNode proposed = leaf(HotStuffNode.genesis(), "cmd", 1);
                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposed.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", prepareMsg);

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
                        replica.onMessage("node-0", preCommitMsg);

                        // COMMIT
                        QuorumCertificate preCommitQC = QuorumCertificate.create(QuorumCertificate.PRE_COMMIT, 1,
                                proposed.getNodeHash(), new byte[64]);
                        ConsensusMessage commitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setCommit(CommitMessage.newBuilder()
                                        .setJustify(preCommitQC.getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", commitMsg);

                        QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 1,
                                proposed.getNodeHash(), new byte[64]);
                        ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(DecideMessage.newBuilder()
                                        .setJustify(commitQC.getProto())
                                        .build())
                                .build();

                        // Leader fires DECIDE multiple times
                        replica.onMessage("node-0", decideMsg);
                        replica.onMessage("node-0", decideMsg);
                        replica.onMessage("node-0", decideMsg);

                        // The service should have only executed the command ONCE
                        assertEquals(1, decideCalls[0],
                                "Decide should be strictly idempotent, ignoring duplicate DECIDE messages.");
                        replica.shutdown();
                }

                // Test 9: Byzantine replica replays an old view vote in a new view
                @Test
                void testByzantineReplayOldViewVote() {
                        consensus.start();

                        feedNewView(consensus, "node-1", 1);
                        feedNewView(consensus, "node-2", 1);
                        feedNewView(consensus, "node-3", 1);

                        byte[] hash = computeViewNodeHash(DEFAULT_BLOCK_BYTES, 1);

                        ConsensusMessage replayedVote = ConsensusMessage.newBuilder()
                                .setViewNumber(0)
                                .setVote(VoteMessage.newBuilder()
                                        .setPhase(QuorumCertificate.PREPARE)
                                        .setNodeHash(ByteString.copyFrom(hash))
                                        .setSignatureShare(ByteString.copyFrom(new byte[128]))
                                        .build())
                                .build();

                        org.mockito.Mockito.clearInvocations(mockLinkManager);
                        consensus.onMessage("node-1", replayedVote);

                        // The vote for view 0 should be ignored
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
                }

                // Test 10: Cross-view replay of DECIDE message
                // Scenario: Node advances to view 2 via timeout, then a DECIDE from view 1
                // arrives. Should be ignored because view != curView.
                @Test
                void testCrossViewReplayDecide() {
                        consensus.start();

                        // Advance to view 2
                        triggerTimeout();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        HotStuffNode oldNode = leaf(HotStuffNode.genesis(), "old-decide", 1);
                        consensus.storeBlock(oldNode);

                        QuorumCertificate oldCommitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 1,
                                oldNode.getNodeHash(), new byte[64]);
                        ConsensusMessage replayedDecide = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(DecideMessage.newBuilder()
                                        .setJustify(oldCommitQC.getProto())
                                        .build())
                                .build();

                        consensus.onMessage("node-0", replayedDecide);

                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.anyString(),
                                        org.mockito.ArgumentMatchers.any());
                }

                // Test 11: Cross-view replay of NEW-VIEW message
                // Scenario: A NEW-VIEW for view 1 arrives when node is already in view 2.
                // Should be processed only if it helps the leader form a quorum for view 1,
                // but since we're past view 1, it should be ignored.
                @Test
                void testCrossViewReplayNewView() {
                        consensus.start();

                        feedNewView(consensus, "node-1", 1);
                        feedNewView(consensus, "node-2", 1);

                        triggerTimeout();

                        feedNewView(consensus, "node-1", 2);

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        ConsensusMessage replayedNewView = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setNewView(NewViewMessage.newBuilder()
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();

                        consensus.onMessage("node-3", replayedNewView);

                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
                }

                // Test 12: Replay of PRE-COMMIT message in same view
                // Scenario: Leader sends PRE-COMMIT twice for the same view.
                // Replica should accept the first and ignore the duplicate.
                @Test
                void testReplayPreCommitSameView() {
                        Consensus replica = new Consensus("node-3", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        HotStuffNode proposed = leaf(HotStuffNode.genesis(), "cmd", 1);
                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposed.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", prepareMsg);

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        QuorumCertificate prepareQC = QuorumCertificate.create(QuorumCertificate.PREPARE, 1,
                                proposed.getNodeHash(), new byte[64]);
                        ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage.newBuilder()
                                        .setJustify(prepareQC.getProto())
                                        .build())
                                .build();

                        replica.onMessage("node-0", preCommitMsg);

                        int voteCountAfterFirst = org.mockito.Mockito.mockingDetails(mockLinkManager).getInvocations()
                                .stream()
                                .filter(inv -> inv.getMethod().getName().equals("send"))
                                .filter(inv -> "node-0".equals(inv.getArgument(0)))
                                .mapToInt(inv -> 1).sum();

                        replica.onMessage("node-0", preCommitMsg);

                        int voteCountAfterReplay = org.mockito.Mockito.mockingDetails(mockLinkManager).getInvocations()
                                .stream()
                                .filter(inv -> inv.getMethod().getName().equals("send"))
                                .filter(inv -> "node-0".equals(inv.getArgument(0)))
                                .mapToInt(inv -> 1).sum();

                        assertEquals(voteCountAfterFirst, voteCountAfterReplay,
                                "Replayed PRE-COMMIT should not trigger a second vote");
                        replica.shutdown();
                }

                // Test 13: Replay of COMMIT message in same view
                // Scenario: Leader sends COMMIT twice for the same view.
                // Replica should accept the first and ignore the duplicate.
                @Test
                void testReplayCommitSameView() {
                        Consensus replica = new Consensus("node-3", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        HotStuffNode proposed = leaf(HotStuffNode.genesis(), "cmd", 1);
                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposed.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", prepareMsg);

                        QuorumCertificate prepareQC = QuorumCertificate.create(QuorumCertificate.PREPARE, 1,
                                proposed.getNodeHash(), new byte[64]);
                        replica.onMessage("node-0", ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage.newBuilder()
                                        .setJustify(prepareQC.getProto())
                                        .build())
                                .build());

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        QuorumCertificate preCommitQC = QuorumCertificate.create(QuorumCertificate.PRE_COMMIT, 1,
                                proposed.getNodeHash(), new byte[64]);
                        ConsensusMessage commitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setCommit(CommitMessage.newBuilder()
                                        .setJustify(preCommitQC.getProto())
                                        .build())
                                .build();

                        replica.onMessage("node-0", commitMsg);

                        int voteCountAfterFirst = org.mockito.Mockito.mockingDetails(mockLinkManager).getInvocations()
                                .stream()
                                .filter(inv -> inv.getMethod().getName().equals("send"))
                                .filter(inv -> "node-0".equals(inv.getArgument(0)))
                                .mapToInt(inv -> 1).sum();

                        replica.onMessage("node-0", commitMsg);

                        int voteCountAfterReplay = org.mockito.Mockito.mockingDetails(mockLinkManager).getInvocations()
                                .stream()
                                .filter(inv -> inv.getMethod().getName().equals("send"))
                                .filter(inv -> "node-0".equals(inv.getArgument(0)))
                                .mapToInt(inv -> 1).sum();

                        assertEquals(voteCountAfterFirst, voteCountAfterReplay,
                                "Replayed COMMIT should not trigger a second vote");
                        replica.shutdown();
                }

        }

        @Nested
        class ConsensusExecutionTest {

                // Test 14: Happy-path leader

                /**
                 * Simulate a complete, happy-path view for node-0 (the leader for view 1).
                 */
                @Test
                void testHappyPathLeaderCompletesOneView() throws Exception {
                        // Use a latch to detect the decide upcall asynchronously
                        CountDownLatch decideLatch = new CountDownLatch(1);
                        Service trackingService = new Service(new BlockchainState(), mockTransactionManager) {
                                @Override
                                public synchronized void onDecide(Block block, int viewNumber) {
                                        super.onDecide(block, viewNumber);
                                        decideLatch.countDown();
                                }
                        };

                        Consensus leader = new Consensus("node-0", NODE_IDS, mockLinkManager, trackingService,
                                        mockTransactionManager, mockCrypto);

                        // Mock buildBlock() to return a block with a transaction
                        Block testBlock = Block.newBuilder()
                                .addTransactions(Transaction.newBuilder()
                                        .setFrom("test").setTo("recipient").setValue(100)
                                        .setNonce(0).setGasPrice(1).setGasLimit(21000)
                                        .build())
                                .build();
                        lenient().when(mockTransactionManager.buildBlock()).thenReturn(testBlock);
                        lenient().when(mockTransactionManager.validateBlock(any())).thenReturn(true);

                        // Feed n-f NEW-VIEW messages — triggers triggerProposal() which calls
                        // buildBlock()
                        leader.start(); // leader sends new view to itself on start

                        feedNewView(leader, "node-1", 1);
                        feedNewView(leader, "node-2", 1);

                        // Feed n-f PREPARE votes — leader self-votes automatically via sendVote()
                        byte[] nodeHash = computeViewNodeHash(testBlock.toByteArray(), 1);
                        feedVote(leader, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                        feedVote(leader, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);

                        // Feed n-f PRE-COMMIT votes — leader self-votes automatically via sendVote()
                        feedVote(leader, "node-1", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                        feedVote(leader, "node-2", QuorumCertificate.PRE_COMMIT, 1, nodeHash);

                        // Feed n-f COMMIT votes — leader self-votes automatically via sendVote()
                        feedVote(leader, "node-1", QuorumCertificate.COMMIT, 1, nodeHash);
                        feedVote(leader, "node-2", QuorumCertificate.COMMIT, 1, nodeHash);

                        // Wait for the decide upcall
                        boolean decided = decideLatch.await(5, TimeUnit.SECONDS);
                        assertTrue(decided, "DECIDE should have been reached within timeout");

                        leader.shutdown();
                }

                // Test 15: Incremental Branch Execution
                @Test
                void testIncrementalExecution() {
                        HotStuffNode genesis = HotStuffNode.genesis();
                        HotStuffNode nodeV1 = leaf(genesis, "cmd-view-1", 1);
                        HotStuffNode nodeV2 = leaf(nodeV1, "cmd-view-2", 2);
                        HotStuffNode nodeV3 = leaf(nodeV2, "cmd-view-3", 3);

                        List<Block> blocks = new ArrayList<>();
                        Service trackingService = new Service(new BlockchainState(), mockTransactionManager) {
                                @Override
                                public synchronized void onDecide(Block block, int viewNumber) {
                                        super.onDecide(block, viewNumber);
                                        blocks.add(block);
                                }
                        };

                        Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();

                        replica.storeBlock(nodeV1);
                        replica.storeBlock(nodeV2);
                        replica.storeBlock(nodeV3);

                        // Advance replica's view to 3
                        triggerTimeout();
                        triggerTimeout();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // node-2 is the leader for view 3:
                        // leader(3) = sortedNodeIds[(3-1)%4] = "node-2"
                        QuorumCertificate v2QC = QuorumCertificate.create(QuorumCertificate.PREPARE, 2,
                                nodeV2.getNodeHash(), new byte[64]);
                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(nodeV3.getProto())
                                        .setJustify(v2QC.getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-2", prepareMsg);

                        // DECIDE for View 3
                        QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 3,
                                nodeV3.getNodeHash(), new byte[64]);
                        ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setDecide(DecideMessage.newBuilder()
                                        .setJustify(commitQC.getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-2", decideMsg);

                        // Verify incremental chronological execution
                        assertEquals(3, blocks.size(), "Should incrementally execute all 3 commands from the branch");

                        replica.shutdown();
                }

                // Test 16: Orphaned Branch Not Executed
                // Scenario: forks from nodeA — one branch is committed, the other is orphaned.
                // Verifies executeCommittedBranch only walks the decided path.
                @Test
                void testOrphanedBranchNotExecuted() {
                        List<Block> commands = new ArrayList<>();
                        Service trackingService = new Service(new BlockchainState(), mockTransactionManager) {
                                @Override
                                public synchronized void onDecide(Block block, int viewNumber) {
                                        super.onDecide(block, viewNumber);
                                        commands.add(block);
                                }
                        };

                        Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();

                        // Build forked tree:
                        // genesis ← nodeA("cmd-a") ← nodeOrphan("cmd-orphan") [orphaned branch]
                        // ↖ nodeCommitted("cmd-committed") [committed branch]
                        HotStuffNode genesis = HotStuffNode.genesis();
                        HotStuffNode nodeA = leaf(genesis, "cmd-a", 1);
                        HotStuffNode nodeOrphan = leaf(nodeA, "cmd-orphan", 2);
                        HotStuffNode nodeCommitted = leaf(nodeA, "cmd-committed", 3);

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
                        replica.onMessage("node-0", prepareMsg);

                        QuorumCertificate commitQC = QuorumCertificate.create(
                                QuorumCertificate.COMMIT, 0, nodeCommitted.getNodeHash(), new byte[64]);
                        ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(DecideMessage.newBuilder()
                                        .setJustify(commitQC.getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", decideMsg);

                        assertEquals(2, commands.size(), "Should execute 2 commands on committed branch");
                }

                // Test 17: Skipped Phases
                @Test
                void testSkippedPhases() {
                        int[] decideCalls = { 0 };
                        Service trackingService = new Service(new BlockchainState(), mockTransactionManager) {
                                @Override
                                public synchronized void onDecide(Block block, int viewNumber) {
                                        super.onDecide(block, viewNumber);
                                        decideCalls[0]++;
                                }
                        };
                        Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService,
                                mockTransactionManager, mockCrypto);
                        replica.start();

                        byte[] nodeHash = computeViewNodeHash(DEFAULT_BLOCK_BYTES, 1);
                        QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 1, nodeHash, new byte[64]);
                        ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(DecideMessage.newBuilder()
                                        .setJustify(commitQC.getProto())
                                        .build())
                                .build();

                        replica.onMessage("node-0", decideMsg);
                        assertEquals(0, decideCalls[0],
                                "Replica cannot decide without the block (currentProposal == null)");
                        replica.shutdown();
                }

                // Test 18: Leader waits for exactly n-f NEW-VIEW before proposing and selects
                // highest QC received
                // Scenario: With n=4, f=1, quorum=3. Leader needs 3 NEW-VIEW messages.
                // Leader sends its own NEW-VIEW on start(), so we need 2 more from replicas.
                // Leader must select the highest-view QC from NEW-VIEW messages.
                @Test
                void testLeaderWaitsForNMinusFNewViews() {
                        lenient().when(mockTransactionManager.buildBlock()).thenReturn(DEFAULT_BLOCK);
                        lenient().when(mockTransactionManager.validateBlock(any())).thenReturn(true);

                        Consensus leader = new Consensus("node-0", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        leader.start();

                        // Leader already sent its own NEW-VIEW in start().
                        // Feed 1 external NEW-VIEW (total = 2, below quorum of 3)
                        feedNewView(leader, "node-1", 1);

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // Should NOT broadcast PREPARE yet
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());

                        // Feed another NEW-VIEW with a fresher QC (view 1)
                        HotStuffNode v1Node = leaf(HotStuffNode.genesis(), "v1-cmd", 1);
                        QuorumCertificate freshQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 1, v1Node.getNodeHash(), new byte[64]);
                        ConsensusMessage newViewFresh = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setNewView(NewViewMessage.newBuilder()
                                        .setJustify(freshQC.getProto())
                                        .build())
                                .build();
                        leader.onMessage("node-2", newViewFresh);

                        // Now leader should broadcast PREPARE
                        org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .broadcast(captor.capture());

                        boolean hasPrepare = false;
                        boolean usedFreshQC = false;
                        for (byte[] raw : captor.getAllValues()) {
                                try {
                                        Envelope env = Envelope.parseFrom(raw);
                                        ConsensusMessage msg = env.getConsensus();
                                        if (msg.hasPrepare()) {
                                                hasPrepare = true;
                                                QuorumCertificate justify = new QuorumCertificate(
                                                        msg.getPrepare().getJustify());
                                                if (justify.getViewNumber() == 1) {
                                                        usedFreshQC = true;
                                                }
                                        }
                                } catch (Exception ignored) {
                                }
                        }
                        assertTrue(hasPrepare, "Leader should broadcast PREPARE after receiving n-f NEW-VIEW messages");
                        assertTrue(usedFreshQC,
                                "Leader must justify proposal with highest-view QC from NEW-VIEW messages");
                        leader.shutdown();
                }

        }

        @Nested
        class ConsensusSyncTest {

                // Test 19: HotStuffNode hash chaining

                /**
                 * Verify that createLeaf produces deterministic hash
                 * chains and that extendsFrom() correctly checks ancestry.
                 */
                @Test
                void testHotStuffNodeHashChaining() {
                        HotStuffNode genesis = HotStuffNode.genesis();
                        HotStuffNode child1 = leaf(genesis, "cmd", 1);
                        HotStuffNode child2 = leaf(child1, "cmd", 2);

                        assertArrayEquals(genesis.getNodeHash(),
                                child1.getProto().getParentHash().toByteArray(),
                                "child1.parentHash must equal genesis.nodeHash");

                        assertArrayEquals(child1.getNodeHash(),
                                child2.getProto().getParentHash().toByteArray(),
                                "child2.parentHash must equal child1.nodeHash");

                        // extendsFrom checks
                        consensus.storeBlock(child1);
                        consensus.storeBlock(child2);
                        assertTrue(consensus.extendsFrom(child2, child1.getNodeHash()),
                                "child2 must extend from child1");

                        // Negative test: unrelated node should NOT extend from child1
                        HotStuffNode unrelated = leaf(genesis, "unrelated", 99);
                        consensus.storeBlock(unrelated);
                        assertFalse(consensus.extendsFrom(unrelated, child1.getNodeHash()),
                                "unrelated node must NOT extend from child1");

                        // Determinism: same inputs must produce same hash
                        HotStuffNode child1b = leaf(genesis, "cmd", 1);
                        assertArrayEquals(child1.getNodeHash(), child1b.getNodeHash(),
                                "Hash must be deterministic for the same inputs");
                }

                // Test 20: Sync Request Triggered on Missing Block
                @Test
                void testSyncRequestTriggeredOnMissingBlock() {
                        // Create a replica at view 1 with no extra blocks in blockStore
                        Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service,
                                        mockTransactionManager,
                                        mockCrypto);
                        replica.start();
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
                        byte[] catchUpBlockBytes = blockBytes("cmd-catch-up");
                        Block catchUpBlock = Block.newBuilder().addTransactions(
                                Transaction.newBuilder().setFrom("test")
                                        .setData(ByteString.copyFromUtf8("cmd-catch-up")).build())
                                .build();
                        HotStuffNode proposed = new HotStuffNode(
                                ist.group29.depchain.network.ConsensusMessages.HotStuffNode.newBuilder()
                                        .setParentHash(ByteString.copyFrom(unknownHash))
                                        .setBlock(catchUpBlock)
                                        .setViewNumber(1)
                                        .setNodeHash(ByteString.copyFrom(
                                                CryptoUtils.computeHash(unknownHash, catchUpBlockBytes, 1)))
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

                        replica.onMessage("node-0", prepareMsg);

                        // Verify a SyncRequest was sent to the leader (node-0)
                        org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), captor.capture());

                        boolean sentSyncRequest = false;
                        for (byte[] raw : captor.getAllValues()) {
                                try {
                                        Envelope env = Envelope.parseFrom(raw);
                                        ConsensusMessage msg = env.getConsensus();
                                        if (msg.hasSyncRequest()) {
                                                sentSyncRequest = true;
                                        }
                                } catch (Exception ignored) {
                                }
                        }
                        assertTrue(sentSyncRequest, "Replica should send a SyncRequest when blocks are missing");
                        replica.shutdown();
                }

                // Test 21: Sync Response Populates BlockStore
                @Test
                void testSyncResponsePopulatesBlockStore() {
                        HotStuffNode genesis = HotStuffNode.genesis();
                        HotStuffNode nodeV1 = leaf(genesis, "cmd", 0);
                        HotStuffNode nodeV2 = leaf(nodeV1, "cmd", 0);

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

                        consensus.onMessage("node-1", syncResp);

                        // Verify the nodes are now in the blockStore via extendsFrom
                        assertTrue(consensus.extendsFrom(nodeV2, nodeV1.getNodeHash()),
                                "nodeV2 should extend from nodeV1 after sync");
                        // A node NOT in the sync response should not be reachable
                        HotStuffNode unrelated = leaf(genesis, "unrelated", 99);
                        consensus.storeBlock(unrelated);
                        assertFalse(consensus.extendsFrom(unrelated, nodeV1.getNodeHash()),
                                "unrelated node should NOT extend from nodeV1");
                }

        }

        @Nested
        class ConsensusLivenessTest {

                // Test 22: Insufficient votes
                @Test
                void testInsufficientSignatureShares() {
                        consensus.start();
                        feedNewView(consensus, "node-1", 1);
                        feedNewView(consensus, "node-2", 1);

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        byte[] nodeHash = computeViewNodeHash(DEFAULT_BLOCK_BYTES, 1);
                        // Only 1 external vote — self-vote + 1 = 2, below quorum of 3
                        feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);

                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
                }

                // Test 23: Faulty Signature Share
                @Test
                void testFaultySignatureShare() throws Exception {
                        // Simulate faulty aggregation rejection
                        lenient().when(mockCrypto.aggregateSignatureShares(any(), anyList(), anyList()))
                                .thenThrow(new RuntimeException("Invalid share detected"));

                        consensus.start();
                        feedNewView(consensus, "node-1", 1);
                        feedNewView(consensus, "node-2", 1);
                        byte[] nodeHash = computeViewNodeHash(DEFAULT_BLOCK_BYTES, 1);

                        org.mockito.Mockito.clearInvocations(mockLinkManager);
                        // Nodes send votes, but aggregation fails
                        feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                        feedVote(consensus, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);
                        feedVote(consensus, "node-3", QuorumCertificate.PREPARE, 1, nodeHash);

                        // No broadcast should occur as aggregation failed
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
                }

                // Test 24: Selective drop of PRE-COMMIT prevents lockedQC update
                // Scenario: Replica receives PREPARE and votes, but PRE-COMMIT is dropped
                // lockedQC should remain at genesis, prepareQC should not be updated.
                @Test
                void testSelectiveDropPreCommitPreventsLockedQCUpdate() {
                        Consensus replica = new Consensus("node-3", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        HotStuffNode proposed = leaf(HotStuffNode.genesis(), "cmd", 1);
                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposed.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", prepareMsg);

                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"),
                                        org.mockito.ArgumentMatchers.any());

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // Drop PRE-COMMIT from node-0, advances view because of timeout
                        triggerTimeout();

                        // Verify NEW-VIEW with genesisQC is sent to next leader
                        org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor.capture());

                        boolean carriesGenesisQC = false;
                        for (byte[] raw : captor.getAllValues()) {
                                try {
                                        Envelope env = Envelope.parseFrom(raw);
                                        ConsensusMessage msg = env.getConsensus();
                                        if (msg.hasNewView()) {
                                                QuorumCertificate qc = new QuorumCertificate(
                                                        msg.getNewView().getJustify());
                                                if (qc.getViewNumber() == 0) {
                                                        carriesGenesisQC = true;
                                                }
                                        }
                                } catch (Exception ignored) {
                                }
                        }
                        assertTrue(carriesGenesisQC,
                                "After PRE-COMMIT drop, NEW-VIEW should carry genesis QC because prepareQC was never updated");
                        replica.shutdown();
                }

                // Test 25: Drop all PREPARE messages — leader broadcasts but replicas never receive
                // Scenario: Leader broadcasts PREPARE, but all replicas drop it.
                // Replicas timeout and trigger view change.
                @Test
                void testDropAllPrepareMessages() {
                        // Use node-2: view 1 leader is node-0, view 2 leader is node-1
                        Consensus replica = new Consensus("node-2", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        triggerTimeout();

                        // node-2 advances from view 1 → view 2, sends NEW-VIEW to node-1 (view 2 leader)
                        org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor.capture());

                        boolean hasNewView = false;
                        for (byte[] raw : captor.getAllValues()) {
                                try {
                                        Envelope env = Envelope.parseFrom(raw);
                                        ConsensusMessage msg = env.getConsensus();
                                        if (msg.hasNewView()) {
                                                hasNewView = true;
                                                break;
                                        }
                                } catch (Exception ignored) {
                                }
                        }
                        assertTrue(hasNewView, "After PREPARE drop, replica should timeout and send NEW-VIEW");
                }

                // Test 26: Drop all DECIDE messages — leader decided but replicas timeout
                // Scenario: Leader completes all phases and broadcasts DECIDE, but replicas
                // never receive it.
                // Replicas timeout and advance view while leader already committed.
                @Test
                void testDropAllDecideMessages() {
                        // Use node-2 as replica: view 1 leader is node-0, view 2 leader is node-1
                        Consensus replica = new Consensus("node-2", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        HotStuffNode proposed = leaf(HotStuffNode.genesis(), "cmd", 1);
                        replica.onMessage("node-0", ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposed.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                .build()));

                        QuorumCertificate prepareQC = QuorumCertificate.create(QuorumCertificate.PREPARE, 1,
                                        proposed.getNodeHash(), new byte[64]);
                        replica.onMessage("node-0", ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage.newBuilder()
                                        .setJustify(prepareQC.getProto())
                                        .build())
                                .build());

                        QuorumCertificate preCommitQC = QuorumCertificate.create(QuorumCertificate.PRE_COMMIT, 1,
                                        proposed.getNodeHash(), new byte[64]);
                        replica.onMessage("node-0",
                                ConsensusMessage.newBuilder()
                                        .setViewNumber(1)
                                        .setCommit(CommitMessage.newBuilder()
                                                .setJustify(preCommitQC.getProto())
                                                .build())
                                        .build());

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // DECIDE is dropped — replica never receives it
                        triggerTimeout();

                        // node-2 advances from view 1 → view 2. View 2 leader is node-1.
                        org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor.capture());

                        boolean foundNewView = false;
                        for (byte[] raw : captor.getAllValues()) {
                                try {
                                        Envelope env = Envelope.parseFrom(raw);
                                        ConsensusMessage msg = env.getConsensus();
                                        if (msg.hasNewView()) {
                                                foundNewView = true;
                                                break;
                                        }
                                } catch (Exception ignored) {
                                }
                        }
                        assertTrue(foundNewView, "After DECIDE drop, replica should timeout and send NEW-VIEW");
                        replica.shutdown();
                }

                // Test 27: Partial vote drops — only 2 of 3 COMMIT votes arrive
                // Scenario: Leader collects 2 of 3 COMMIT votes (below quorum).
                // Leader cannot form commit QC and cannot broadcast DECIDE.
                @Test
                void testPartialCommitVoteDrop() {
                        Consensus leader = new Consensus("node-0", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        leader.start();

                        feedNewView(leader, "node-1", 1);
                        feedNewView(leader, "node-2", 1);

                        byte[] hash = computeViewNodeHash(DEFAULT_BLOCK_BYTES, 1);

                        // Feed prepare votes — node-0 self-votes automatically, need 2 more for quorum
                        feedVote(leader, "node-1", QuorumCertificate.PREPARE, 1, hash);
                        feedVote(leader, "node-2", QuorumCertificate.PREPARE, 1, hash);

                        // Feed pre-commit votes — node-0 self-votes automatically, need 2 more for
                        // quorum
                        feedVote(leader, "node-1", QuorumCertificate.PRE_COMMIT, 1, hash);
                        feedVote(leader, "node-2", QuorumCertificate.PRE_COMMIT, 1, hash);

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // Only 1 COMMIT vote arrives (node-0 self-vote = 1, node-1 = 2, node-2 dropped)
                        feedVote(leader, "node-1", QuorumCertificate.COMMIT, 1, hash);

                        // Leader should NOT have broadcast DECIDE (only 2 votes, below quorum)
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());

                        leader.shutdown();
                }

        }

        @Nested
        class ConsensusPacemakerTest {

                // Test 28: Pacemaker recovers timeoutTriggered
                @Test
                void testPacemakerRecovery() {
                        consensus.start();

                        feedNewView(consensus, "node-1", 1);
                        feedNewView(consensus, "node-2", 1);

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        byte[] hash = computeViewNodeHash(DEFAULT_BLOCK_BYTES, 1);
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
                                        Envelope env = Envelope.parseFrom(raw);
                                        ConsensusMessage msg = env.getConsensus();
                                        if (msg.hasNewView() && msg.getViewNumber() == 2) {
                                                foundNewView = true;
                                                break;
                                        }
                                } catch (Exception ignored) {
                                }
                        }
                        assertTrue(foundNewView, "Should send NEW-VIEW for view 2 after partition timeout");
                }

                // Test 29: Exponential Backoff and Reset with Pacemaker
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

                        Consensus c = new Consensus("node-3", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, pacemaker);
                        c.start(); // View 1, timeout = 4s

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
                                Envelope env = Envelope.parseFrom(raw);
                                ConsensusMessage msg = env.getConsensus();
                                if (msg.hasNewView())
                                        sentNewView1 = true;
                        }
                        assertTrue(sentNewView1,
                                "After 4s timeout, view should advance and NEW-VIEW sent to next leader");

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // Backoff doubled to 8s. At 6s after the first timeout, the second should not
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
                                Envelope env = Envelope.parseFrom(raw);
                                ConsensusMessage msg = env.getConsensus();
                                if (msg.hasNewView())
                                        sentNewView2 = true;
                        }
                        assertTrue(sentNewView2, "After 8s exponential backoff, view should advance and NEW-VIEW sent");

                        // DECIDE in view 3 should reset timeout to 4s.
                        // Currently in view 3 (timeout would be 16s without reset).
                        HotStuffNode proposed = leaf(HotStuffNode.genesis(), "cmd", 3);
                        c.storeBlock(proposed);

                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposed.getProto())
                                        .setJustify(QuorumCertificate.genesisQC().getProto())
                                        .build())
                                .build();
                        c.onMessage("node-2", prepareMsg);

                        QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 3,
                                proposed.getNodeHash(), new byte[64]);
                        ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setDecide(DecideMessage.newBuilder()
                                        .setJustify(commitQC.getProto())
                                        .build())
                                .build();
                        c.onMessage("node-2", decideMsg); // advances to view 4, resets timeout to 4s

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
                                Envelope env = Envelope.parseFrom(raw);
                                ConsensusMessage msg = env.getConsensus();
                                if (msg.hasNewView() && msg.getViewNumber() == 5)
                                        sentNewView3 = true;
                        }
                        assertTrue(sentNewView3,
                                "After DECIDE, backoff should reset to 4s — timeout must fire before the old 16s");

                        c.shutdown();
                        pacemaker.shutdownNow();
                }

                // Test 30: Proposal retry fires when mempool fills after empty buildBlock()
                @Test
                void testProposalRetryFiresWhenMempoolFills() throws Exception {
                        ScheduledExecutorService pacemaker = java.util.concurrent.Executors
                                .newSingleThreadScheduledExecutor(r -> {
                                        Thread t = new Thread(r, "TestPacemaker-retry");
                                        t.setDaemon(true);
                                        return t;
                                });

                        // Mock buildBlock() to return empty first, then non-empty
                        lenient().when(mockTransactionManager.buildBlock())
                                .thenReturn(Block.getDefaultInstance()) // empty on first call
                                .thenReturn(DEFAULT_BLOCK); // non-empty on retry
                        lenient().when(mockTransactionManager.validateBlock(any())).thenReturn(true);

                        Consensus leader = new Consensus("node-0", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, pacemaker);
                        leader.start();

                        feedNewView(leader, "node-1", 1);
                        feedNewView(leader, "node-2", 1);
                        // triggerProposal() fires — mempool empty, no proposal, scheduleRetry() called
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());

                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // Wait for retry timer (timeoutMs/2 = 2s) to fire
                        Thread.sleep(3000);

                        org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .broadcast(captor.capture());

                        boolean hasPrepare = false;
                        for (byte[] raw : captor.getAllValues()) {
                                try {
                                        Envelope env = Envelope.parseFrom(raw);
                                        if (env.getConsensus().hasPrepare())
                                                hasPrepare = true;
                                } catch (Exception ignored) {
                                }
                        }
                        assertTrue(hasPrepare, "Retry timer should trigger proposal when mempool fills after NEW-VIEW");

                        leader.shutdown();
                        pacemaker.shutdownNow();
                }

                // Test 31: After PRE-COMMIT, prepareQC is updated — branch preserved
                // Scenario: replica receives PREPARE and PRE-COMMIT (prepareQC updated to view 1).
                // On timeout, NEW-VIEW carries view-1 prepareQC — next leader must build on this branch.
                @Test
                void testViewChangeAfterPreCommitUpdatesPrepareQC() {
                        Consensus replica = new Consensus("node-3", NODE_IDS, mockLinkManager, service,
                                mockTransactionManager, mockCrypto, mockPacemaker);
                        replica.start();
                        org.mockito.Mockito.clearInvocations(mockLinkManager);

                        // Feed PREPARE from node-0 (view 1 leader)
                        HotStuffNode proposed = leaf(HotStuffNode.genesis(), "cmd-v1", 1);
                        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                        .setNode(proposed.getProto())
                                        .setJustify(QuorumCertificate.genesisQC()
                                        .getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", prepareMsg);

                        QuorumCertificate phaseQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 0, proposed.getNodeHash(), new byte[64]);
                        ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(PreCommitMessage.newBuilder()
                                        .setJustify(phaseQC.getProto())
                                        .build())
                                .build();
                        replica.onMessage("node-0", preCommitMsg);

                        triggerTimeout();

                        // NEW-VIEW to node-1 should now carry the UPDATED prepareQC
                        org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                        org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor.capture());

                        boolean foundUpdatedNewView = false;
                        for (byte[] raw : captor.getAllValues()) {
                                try {
                                        Envelope env = Envelope.parseFrom(raw);
                                        ConsensusMessage msg = env.getConsensus();
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

        }

        // Helpers
        static HotStuffNode leaf(HotStuffNode parent, String data, int view) {
                Block block = Block.newBuilder()
                        .addTransactions(Transaction.newBuilder()
                                .setFrom("test")
                                .setTo("recipient")
                                .setValue(1)
                                .setNonce(0)
                                .setGasPrice(1)
                                .setGasLimit(21000)
                                .setData(ByteString.copyFromUtf8(data))
                                .build())
                        .build();
                return parent.createLeaf(block, view);
        }

        private void feedNewView(Consensus c, String fromId, int targetView) {
                ConsensusMessage msg = ConsensusMessage.newBuilder()
                        .setViewNumber(targetView)
                        .setNewView(NewViewMessage.newBuilder()
                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                .build())
                        .build();
                c.onMessage(fromId, msg);
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
                c.onMessage(fromId, msg);
        }

        /** Build block bytes with a proper Stage 2 transaction for hash computation. */
        private static byte[] blockBytes(String label) {
                return Block.newBuilder()
                        .addTransactions(Transaction.newBuilder()
                                .setFrom("test")
                                .setTo("recipient")
                                .setValue(1)
                                .setNonce(0)
                                .setGasPrice(1)
                                .setGasLimit(21000)
                                .setData(ByteString.copyFromUtf8(label))
                                .build())
                        .build().toByteArray();
        }

        private byte[] computeViewNodeHash(byte[] blockBytes, int view) {
                byte[] genesisHash = HotStuffNode.genesis().getNodeHash();
                return CryptoUtils.computeHash(genesisHash, blockBytes, view);
        }
}
