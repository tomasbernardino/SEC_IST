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
 *
 * Tests use Mockito to substitute a real LinkManager with a mock, making it
 * possible to feed crafted messages directly to the
 * consensus state machine without actually opening UDP sockets. This mirrors
 * the approach used in the old project's ByzantineConsensusTest, but
 * adapted to the HotStuff protocol and our event-driven (callback)
 * architecture.
 *
 * System parameters: n=4, f=1, quorum=3 (smallest BFT cluster).
 */
@ExtendWith(MockitoExtension.class)
class ConsensusTest {

        // 4-node BFT cluster: n=4, f=1, quorum=3
        static final int N = 4;
        static final int F = (N - 1) / 3; // = 1
        static final int QUORUM = N - F; // = 3

        static final List<String> NODE_IDS = List.of("node-0", "node-1", "node-2", "node-3");
        // Sorted: node-0 is leader(1), node-1 is leader(2), etc.

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
                        scheduledTimeoutTask.run();
                        scheduledTimeoutTask = null;
                }
        }

        // Test 1: Leader rotation

        /**
         * Verify that leader(v) maps view numbers to node IDs via round-robin
         * over the sorted node list.
         *
         * The algorithm requires that every node derives the same leader for
         * any given view since round-robin over sorted IDs is deterministic and fair.
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
         * the lockedQC node AND whose justification QC is not fresher.
         * According to:
         * Safety rule: node extends from lockedQC.node => FALSE (conflicting branch)
         * Liveness rule: qc.viewNumber > lockedQC.viewNumber => FALSE (same or older)
         * 
         * Both rules false => safeNode returns false => replica must NOT vote.
         *
         * This test exercises the core safety property of HotStuff: once locked
         * on a branch, a replica should never vote for a conflicting branch that
         * cannot prove it has wider support.
         */
        @Test
        void testSafeNodeSafetyRuleRejectsConflict() {
                // First, we need to advance the replica's lockedQC to view 1
                // We do this by feeding a PRE-COMMIT and then COMMIT for a valid branch
                consensus.start(null); // starts view 1

                HotStuffNode validProposal = HotStuffNode.genesis().createLeaf("valid-cmd", 1);
                consensus.storeBlock(validProposal); // Ensure it's in the block store

                // Feed PRE-COMMIT for view 1 to set prepareQC
                QuorumCertificate validPrepareQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 0, validProposal.getNodeHash(), new byte[64]);
                ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(ist.group29.depchain.network.ConsensusMessages.PreCommitMessage
                                                .newBuilder()
                                                .setJustify(validPrepareQC.getProto())
                                                .build())
                                .build();
                consensus.onMessage("node-0", preCommitMsg.toByteArray());

                // Feed COMMIT for view 1 to set lockedQC to view 1
                QuorumCertificate validPreCommitQC = QuorumCertificate.create(
                                QuorumCertificate.PRE_COMMIT, 0, validProposal.getNodeHash(), new byte[64]);
                ConsensusMessage commitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setCommit(ist.group29.depchain.network.ConsensusMessages.CommitMessage.newBuilder()
                                                .setJustify(validPreCommitQC.getProto())
                                                .build())
                                .build();
                consensus.onMessage("node-0", commitMsg.toByteArray());

                // Now advance view to 2 so it can receive proposals
                consensus.onMessage("node-0", ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
                                                .setJustify(QuorumCertificate.create(QuorumCertificate.COMMIT, 0,
                                                                validProposal.getNodeHash(), new byte[64]).getProto())
                                                .build())
                                .build().toByteArray());

                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Now simulate a conflicting PREPARE in view 2
                // The conflicting node extends from genesis (view 0), NOT from the lockedQC
                // node
                HotStuffNode conflictingNode = HotStuffNode.genesis().createLeaf("conflicting-cmd", 2);

                // The justify QC has view 1 (same as lockedQC), so liveness rule is FALSE
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

                // Consensus (replica at view 2) receives PREPARE from leader node-1
                consensus.onMessage("node-1", prepareMsg.toByteArray());

                // VOTE should NOT be sent because safeNode fails
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), org.mockito.ArgumentMatchers.any());
        }

        // Test 3: safeNode - liveness rule overrides stale lock

        /**
         * Verify that safeNode accepts a conflicting proposal when the
         * justification QC is fresher than the lockedQC (liveness rule).
         *
         * <p>
         * This corresponds to Algorithm 1 line 27: even if a node conflicts with
         * the locked node, if the justification QC has a higher view number than
         * lockedQC, the replica must accept it. This is what makes HotStuff live
         * even when some replicas are locked on stale branches (Theorem 4).
         *
         * <p>
         * In this test the node easily extends from genesis (safety rule = true)
         * and we confirm the consensus accepts PREPARE without error.
         */
        @Test
        void testSafeNodeLivenessRuleAcceptsFresherQC() {
                // In a freshly initialized Consensus, lockedQC is genesisQC (view 0).
                // Any node with a justify QC at view >= 1 satisfies the liveness rule.
                // Build a valid proposal from node-0 (leader(1)) to node-0 (ourselves):

                HotStuffNode proposed = HotStuffNode.genesis()
                                .createLeaf("hello-world", 1);

                QuorumCertificate freshJustify = QuorumCertificate.genesisQC();

                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(freshJustify.getProto())
                                                .build())
                                .build();

                consensus.start(null); // View 1, leader is node-0
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Feed PREPARE from node-0 to replica
                consensus.onMessage("node-0", prepareMsg.toByteArray());

                // Should trigger VOTE to be sent to leader (node-0)
                // Since this replica IS node-0, the vote is internal, but sending votes
                // internally
                // uses onVote directly. Let's start the consensus on a replica that is NOT the
                // leader
                // so we can verify the linkManager.send() call.
        }

        @Test
        void testSafeNodeLivenessRuleAcceptsFresherQCReplica() {
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null); // Joins view 1
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                HotStuffNode proposed = HotStuffNode.genesis()
                                .createLeaf("hello-world", 1);
                QuorumCertificate freshJustify = QuorumCertificate.genesisQC();

                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(freshJustify.getProto())
                                                .build())
                                .build();

                replica.onMessage("node-0", prepareMsg.toByteArray());

                // Verify the liveness rule passed by checking that a VOTE was sent back to the
                // leader
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.times(1))
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), org.mockito.ArgumentMatchers.any());
                replica.shutdown();
        }

        // Test 4: Happy-path leader (drive one complete view)

        /**
         * Simulate a complete, happy-path view for node-0 (the leader for view 1).
         *
         * Feeds:
         * n-f=3 NEW-VIEW messages (triggers leader to broadcast PREPARE)
         * n-f=3 PREPARE votes (triggers PRE-COMMIT broadcast)
         * n-f=3 PRE-COMMIT votes (triggers COMMIT broadcast)
         * n-f=3 COMMIT votes (triggers DECIDE broadcast + upcall to Service)
         * After feeding all messages, the service's blockchain must contain exactly
         * the decided command string.
         *
         * This is the most important test: it validates the entire Algorithm 2
         * flow end-to-end without network I/O.
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

                // --- Step 1: Feed n-f NEW-VIEW messages from three different peers
                // (node-0 injects its own synthetic NEW-VIEW in start(); we feed 2 more)
                leader.start(null);

                feedNewView(leader, "node-1", 1);
                feedNewView(leader, "node-2", 1);
                // This triggers the leader to broadcast PREPARE

                // --- Step 2: Feed n-f PREPARE votes
                byte[] nodeHash = computeViewNodeHash("cmd-view-1", 1);
                feedVote(leader, "node-0", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(leader, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(leader, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);

                // --- Step 3: Feed n-f PRE-COMMIT votes
                feedVote(leader, "node-0", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                feedVote(leader, "node-1", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                feedVote(leader, "node-2", QuorumCertificate.PRE_COMMIT, 1, nodeHash);

                // --- Step 4: Feed n-f COMMIT votes
                feedVote(leader, "node-0", QuorumCertificate.COMMIT, 1, nodeHash);
                feedVote(leader, "node-1", QuorumCertificate.COMMIT, 1, nodeHash);
                feedVote(leader, "node-2", QuorumCertificate.COMMIT, 1, nodeHash);

                // Wait for the decide upcall (max 5 s)
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
         *
         * This validates the hash-chaining property described in Section 4.2:
         * "The method creates a new leaf node as a child and embeds a digest of
         * the parent in the child node." A child's parent_hash must exactly equal
         * the parent's node_hash.
         *
         * Ancestry checking (extendsFrom) is done at the Consensus level
         * using the blockStore for recursive chain traversal (Step 5 BFT).
         */
        @Test
        void testHotStuffNodeHashChaining() {
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode child1 = genesis.createLeaf("cmd-A", 1);
                HotStuffNode child2 = child1.createLeaf("cmd-B", 2);

                // Parent hash linkage
                assertArrayEquals(genesis.getNodeHash(),
                                child1.getProto().getParentHash().toByteArray(),
                                "child1.parentHash must equal genesis.nodeHash");

                assertArrayEquals(child1.getNodeHash(),
                                child2.getProto().getParentHash().toByteArray(),
                                "child2.parentHash must equal child1.nodeHash");

                // extendsFrom checks (Consensus-level, uses blockStore)
                // Genesis is already in the blockStore; add child1 and child2
                consensus.storeBlock(child1);
                consensus.storeBlock(child2);
                assertTrue(consensus.extendsFrom(child1, genesis.getNodeHash()),
                                "child1 must extend from genesis");
                assertTrue(consensus.extendsFrom(child2, child1.getNodeHash()),
                                "child2 must extend from child1");
                assertTrue(consensus.extendsFrom(child2, genesis.getNodeHash()),
                                "child2 must extend from genesis (recursive)");

                // Determinism: same inputs must produce same hash
                HotStuffNode child1b = genesis.createLeaf("cmd-A", 1);
                assertArrayEquals(child1.getNodeHash(), child1b.getNodeHash(),
                                "Hash must be deterministic for the same inputs");
        }

        // Test 6: Bait-and-Switch (extendsFrom failure)
        @Test
        void testBaitAndSwitchRejectsInvalidParentHash() {
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null); // Join view 1
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

                replica.onMessage("node-0", prepareMsg.toByteArray()); // "node-0" is leader for view 1
                // Replica should reject and NOT send a VOTE back to node-0
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), org.mockito.ArgumentMatchers.any());
                replica.shutdown();
        }

        // Test 7: Duplicate Vote Spammer
        @Test
        void testDuplicateVoteSpammer() {
                // "node-0" is leader for view 1
                consensus.start(null);

                byte[] nodeHash = computeViewNodeHash("cmd-test", 1);

                // Feed NEW-VIEW messages so the leader correctly broadcasts PREPARE
                feedNewView(consensus, "node-1", 1);
                feedNewView(consensus, "node-2", 1);

                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // One malicious node sends QUORUM number of identical votes!
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
                replica.start(null); // Join view 1
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
                                super.onDecide(command, viewNumber);
                                decideCalls[0]++;
                        }
                };
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, trackingService, mockCrypto);
                replica.start(null); // Join view 1

                // First, feed a PREPARE so currentProposal is set!
                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd", 1);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());

                byte[] nodeHash = proposed.getNodeHash();
                QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 0, nodeHash,
                                new byte[64]);

                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();

                // Leader rapidly fires DECIDE multiple times
                replica.onMessage("node-0", decideMsg.toByteArray());
                replica.onMessage("node-0", decideMsg.toByteArray());
                replica.onMessage("node-0", decideMsg.toByteArray());

                // The service should have only executed the command ONCE
                assertEquals(1, decideCalls[0], "Decide should be strictly idempotent");
                replica.shutdown();
        }

        // Test 10: Skipped Phases (Out-of-Order Delivery)
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
                QuorumCertificate commitQC = QuorumCertificate.create(QuorumCertificate.COMMIT, 0, nodeHash,
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

        // Test 11: Forged / Invalid QC Signature Test
        @Test
        void testForgedQC() {
                // Ensure the cryptographic validation FAILS for forged signatures
                lenient().when(mockCrypto.verifyThresholdSignature(any(), any())).thenReturn(false);

                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null);

                // Feed PREPARE so currentProposal is set (otherwise the message drops for
                // missing proposal)
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

                // Forged QC for PRE-COMMIT phase
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

        // Test 12: Stale View Proposal Test
        @Test
        void testStaleViewProposal() {
                consensus.start(null);
                feedNewView(consensus, "node-1", 10);
                feedNewView(consensus, "node-2", 10); // Now in View 10
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                HotStuffNode oldNode = HotStuffNode.genesis().createLeaf("old", 2);
                ConsensusMessage oldPrepare = ConsensusMessage.newBuilder()
                                .setViewNumber(2)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(oldNode.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();

                consensus.onMessage("node-1", oldPrepare.toByteArray());
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

        // Test 14: The f+1 Faults Complete Failure Test
        @Test
        void testPacemakerTimeoutOnFaults() throws InterruptedException {
                consensus.start(null);
                byte[] nodeHash = computeViewNodeHash("cmd", 1);
                feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);

                triggerTimeout(); // Wait for timeout (4 seconds default + buffer)

                org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor.capture());

                boolean sentNewView = false;
                for (byte[] raw : captor.getAllValues()) {
                        try {
                                ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
                                if (msg.hasNewView() && msg.getViewNumber() == 2)
                                        sentNewView = true;
                        } catch (Exception ignored) {
                        }
                }
                assertTrue(sentNewView, "Pacemaker must trigger a view change on timeout");
        }

        // Test 15: Incremental Branch Execution
        @Test
        void testIncrementalExecution() {
                // Build a chain: genesis <- node_v1 <- node_v2 <- node_v3
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode nodeV1 = genesis.createLeaf("cmd-view-1", 1);
                HotStuffNode nodeV2 = nodeV1.createLeaf("cmd-view-2", 2);
                HotStuffNode nodeV3 = nodeV2.createLeaf("cmd-view-3", 3);

                // Populate blockStore with all nodes
                consensus.storeBlock(nodeV1);
                consensus.storeBlock(nodeV2);
                consensus.storeBlock(nodeV3);

                // Create a replica at view 3 with currentProposal set to nodeV3
                List<String> commands = new ArrayList<>();
                Service trackingService = new Service() {
                        @Override
                        public synchronized void onDecide(String command, int viewNumber) {
                                super.onDecide(command, viewNumber);
                                commands.add(command);
                        }
                };

                // We need a replica that is in view 3 and has nodeV3 as currentProposal.
                // We'll simulate this by creating a replica, feeding a PREPARE for view 1
                // to set currentProposal, then triggering a DECIDE.
                Consensus replica = new Consensus("node-2", NODE_IDS, mockLinkManager, trackingService, mockCrypto);
                replica.start(null);

                // Store the full chain in replica's blockStore
                replica.storeBlock(nodeV1);
                replica.storeBlock(nodeV2);
                replica.storeBlock(nodeV3);

                // Feed a PREPARE from the view-1 leader (node-0) so currentProposal is set
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(nodeV1.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());

                // Now DECIDE for view 1 - this should only execute cmd-view-1
                QuorumCertificate commitQC = QuorumCertificate.create(
                                QuorumCertificate.COMMIT, 0, nodeV1.getNodeHash(), new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", decideMsg.toByteArray());

                assertEquals(1, commands.size(), "Should execute exactly 1 command for single-node decide");
                assertEquals("cmd-view-1", commands.get(0));

                replica.shutdown();
        }

        // Test 16: Sync Request Triggered on Missing Block
        @Test
        void testSyncRequestTriggeredOnMissingBlock() {
                // Create a replica at view 1 with no extra blocks in blockStore
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Build a PREPARE that references a block we DON'T have
                // The justify QC has view > 0 (fresher than lockedQC which is genesis)
                // and references a node hash NOT in the blockStore
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

                // Justify QC with view 1 (> lockedQC view 0) referencing the unknown hash
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

        // Test 17: Sync Response Populates BlockStore
        @Test
        void testSyncResponsePopulatesBlockStore() {
                // Build a chain: genesis <- node_v1 <- node_v2
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

        // Test 18: Orphaned Branch Not Executed
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

                // PREPARE for nodeCommitted — justify references nodeA (its parent)
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

                // DECIDE (viewNumber=0 bypasses threshold sig verification)
                QuorumCertificate commitQC = QuorumCertificate.create(
                                QuorumCertificate.COMMIT, 0, nodeCommitted.getNodeHash(), new byte[64]);
                ConsensusMessage decideMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
                                                .setJustify(commitQC.getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", decideMsg.toByteArray());

                // cmd-a and cmd-committed executed; cmd-orphan NOT (wrong branch)
                assertEquals(2, commands.size(), "Should execute 2 commands on committed branch");
                assertEquals("cmd-a", commands.get(0));
                assertEquals("cmd-committed", commands.get(1));

                replica.shutdown();
        }

        // Test 19: Multi-Command Incremental Execution
        // Scenario: chain of 3 nodes decided at once — all 3 commands must execute in
        // order.
        @Test
        void testMultiCommandIncrementalExecution() {
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

                // Build chain: genesis ← nodeA ← nodeB ← nodeC
                HotStuffNode genesis = HotStuffNode.genesis();
                HotStuffNode nodeA = genesis.createLeaf("cmd-a", 1);
                HotStuffNode nodeB = nodeA.createLeaf("cmd-b", 2);
                HotStuffNode nodeC = nodeB.createLeaf("cmd-c", 3);

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
                                .setDecide(ist.group29.depchain.network.ConsensusMessages.DecideMessage.newBuilder()
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

        // Test Edge Case: Insufficient Signature Shares
        @Test
        void testInsufficientSignatureShares() throws InterruptedException {
                consensus.start(null);
                feedNewView(consensus, "node-1", 1);
                feedNewView(consensus, "node-2", 1);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                byte[] nodeHash = computeViewNodeHash("cmd", 1);

                // Only 2 nodes vote (below threshold 3)
                feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(consensus, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);

                // The leader should NOT have reached quorum, so it should NOT broadcast any
                // PRE-COMMIT
                // Note: The leader exactly broadcasts once: the PREPARE message itself
                // (triggered by NEW-VIEW quorum)
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.times(1))
                                .broadcast(org.mockito.ArgumentMatchers.any());
        }

        // Test Edge Case: Faulty Signature Share
        @Test
        void testFaultySignatureShare() throws Exception {
                // Simulate faulty aggregation rejection
                lenient().when(mockCrypto.aggregateSignatureShares(any(), anyList(), anyList()))
                                .thenThrow(new RuntimeException("Invalid share detected"));

                consensus.start(null);
                byte[] nodeHash = computeViewNodeHash("cmd", 1);

                // Nodes send votes, but aggregation fails
                feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(consensus, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVote(consensus, "node-3", QuorumCertificate.PREPARE, 1, nodeHash);

                // No broadcast should occur as aggregation failed
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
        }

        // Test 20: After PREPARE-only failure, prepareQC stays at genesis
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
                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd-v1", 1);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());

                // Wait for pacemaker timeout
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

        // Test 21: After PRE-COMMIT, prepareQC is updated — branch preserved
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
                HotStuffNode proposed = HotStuffNode.genesis().createLeaf("cmd-v1", 1);
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(proposed.getProto())
                                                .setJustify(QuorumCertificate.genesisQC().getProto())
                                                .build())
                                .build();
                replica.onMessage("node-0", prepareMsg.toByteArray());

                // Feed PRE-COMMIT with a prepareQC (viewNumber=0 bypasses sig verification)
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

                // Wait for pacemaker timeout
                triggerTimeout();

                // NEW-VIEW to node-1 should now carry the UPDATED prepareQC
                // (which has the proposed node's hash, not genesis)
                org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.atLeastOnce())
                                .send(org.mockito.ArgumentMatchers.eq("node-1"), captor.capture());

                boolean foundUpdatedNewView = false;
                for (byte[] raw : captor.getAllValues()) {
                        try {
                                ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
                                if (msg.hasNewView()) {
                                        QuorumCertificate qc = new QuorumCertificate(msg.getNewView().getJustify());
                                        // The prepareQC should reference our proposed node, not genesis
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

        // Test 22: Byzantine leader sending PREPARE with wrong node hash (doesn't match
        // justification QC)
        @Test
        void testByzantineLeaderWrongNodeHash() {
                consensus.start(null); // start view 1

                HotStuffNode validProposal = HotStuffNode.genesis().createLeaf("valid-cmd", 1);
                consensus.storeBlock(validProposal);

                // Create a QC that hashes one thing
                QuorumCertificate properQC = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 0, validProposal.getNodeHash(), new byte[64]);

                // But the leader proposes a different node hash in the message
                HotStuffNode maliciousNode = validProposal.createLeaf("malicious-cmd", 1);
                consensus.storeBlock(maliciousNode);

                ConsensusMessage maliciousPrepare = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(maliciousNode.getProto())
                                                .setJustify(properQC.getProto())
                                                .build())
                                .build();

                org.mockito.Mockito.clearInvocations(mockLinkManager);
                consensus.onMessage("node-0", maliciousPrepare.toByteArray());

                // Replica should reject and not vote because the node hash doesn't match the QC
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .send(org.mockito.ArgumentMatchers.eq("node-0"), org.mockito.ArgumentMatchers.any());
        }

        // Test 23: Byzantine replica replays an old valid vote in a new view
        @Test
        void testByzantineReplayOldValidVote() {
                consensus.start(null);

                // Feed NEW-VIEWs to node-0 so it becomes active leader
                feedNewView(consensus, "node-1", 1);
                feedNewView(consensus, "node-2", 1);
                feedNewView(consensus, "node-3", 1);

                byte[] hash = computeViewNodeHash("cmd", 1);

                // Let's assume view 1 had votes, and the attacker tries to replay a view 0 vote
                // in view 1
                // or similar. The consensus checks `msg.getViewNumber()` against `curView`.
                ConsensusMessage replayedVote = ConsensusMessage.newBuilder()
                                .setViewNumber(0) // Old view
                                .setVote(VoteMessage.newBuilder()
                                                .setPhase(QuorumCertificate.PREPARE)
                                                .setNodeHash(ByteString.copyFrom(hash))
                                                .setSignatureShare(ByteString.copyFrom(new byte[128]))
                                                .build())
                                .build();

                org.mockito.Mockito.clearInvocations(mockLinkManager);
                consensus.onMessage("node-1", replayedVote.toByteArray());

                // The vote for view 0 should be ignored, the leader should NOT broadcast
                // PRE-COMMIT
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());
        }

        // Test 24: Network partition prevents quorum, pacemaker recovers
        @Test
        void testNetworkPartitionAndRecovery() {
                consensus.start(null);

                // Feed NEW-VIEWs to node-0 so it proposes
                feedNewView(consensus, "node-1", 1);
                feedNewView(consensus, "node-2", 1);

                org.mockito.Mockito.clearInvocations(mockLinkManager);

                // Partition: Only 1 vote reaches the leader (needs 3)
                byte[] hash = computeViewNodeHash("cmd", 1);
                feedVote(consensus, "node-1", QuorumCertificate.PREPARE, 1, hash);

                // No PRE-COMMIT broad cast should happen because 2 < 3 (including leader's own
                // vote)
                org.mockito.Mockito.verify(mockLinkManager, org.mockito.Mockito.never())
                                .broadcast(org.mockito.ArgumentMatchers.any());

                // Wait for timeout
                triggerTimeout();

                // After timeout, consensus should advance to view 2 and send NEW-VIEW to node-1
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
                // In a real run, this would contain a real signature share.
                // For the mock, we just need a non-empty ByteString.
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
                // Genesis node_hash is the parent
                byte[] genesisHash = HotStuffNode.genesis().getNodeHash();
                return CryptoUtils.computeHash(genesisHash, command, view);
        }
}
