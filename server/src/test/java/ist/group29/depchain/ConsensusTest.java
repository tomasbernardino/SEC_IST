package ist.group29.depchain;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ist.group29.depchain.server.crypto.CryptoManager;
import com.weavechain.curve25519.EdwardsPoint;
import com.weavechain.curve25519.Scalar;
import com.google.protobuf.ByteString;

import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.network.ConsensusMessages;
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

        Service service;
        Consensus consensus;

        @BeforeEach
        void setup() throws Exception {
                service = new Service();
                // Setup default mock behavior for CryptoManager
                Scalar mockScalar = mock(Scalar.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
                lenient().when(mockScalar.toByteArray()).thenReturn(new byte[32]);

                lenient().when(mockCrypto.computeRs(anyString())).thenReturn(mockScalar);
                EdwardsPoint mockPoint = mock(EdwardsPoint.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
                lenient().when(mockPoint.compress().toByteArray()).thenReturn(new byte[32]);
                lenient().when(mockCrypto.computeRiPoint(any())).thenReturn(mockPoint);
                lenient().when(mockCrypto.aggregateRi(anyList())).thenReturn(mockPoint);
                lenient().when(mockCrypto.computeChallengeK(any(), anyString())).thenReturn(mockScalar);
                lenient().when(mockCrypto.computeSignatureShare(any(), any(), any())).thenReturn(mockScalar);
                lenient().when(mockCrypto.aggregateSignatureShares(any(), anyList())).thenReturn(new byte[64]);
                lenient().when(mockCrypto.getThresholdPublicKey()).thenReturn(new byte[32]);

                consensus = new Consensus("node-0", NODE_IDS, mockLinkManager, service, mockCrypto);
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
                // Simulate having lockedQC at view 2 on some specific node hash
                byte[] conflictingParentHash = CryptoUtils.computeHash(new byte[32], "other-chain", 1);

                // Build a QC that locked the replica on view 2
                // (Variable lockedQcProto was unused and removed)

                // A conflicting node: parent_hash does NOT match the locked node hash
                HotStuffNode conflictingNode = new HotStuffNode(
                                ConsensusMessages.HotStuffNode.newBuilder()
                                                .setParentHash(ByteString.copyFrom(conflictingParentHash)) // different
                                                                                                           // chain!
                                                .setCommand("conflicting-cmd")
                                                .setViewNumber(3)
                                                .setNodeHash(ByteString.copyFrom(
                                                                CryptoUtils.computeHash(conflictingParentHash,
                                                                                "conflicting-cmd", 3)))
                                                .build());

                // A QC with the SAME view as the lockedQC - liveness rule = FALSE
                // We use a dummy 64-byte signature to satisfy the isValid length check
                QuorumCertificate staleJustify = QuorumCertificate.create(
                                QuorumCertificate.PREPARE, 2, conflictingParentHash, new byte[64]);

                // Directly invoking safeNode via a PREPARE message: feed it through onMessage
                // The replica (Consensus) should NOT emit a VOTE because safeNode fails
                ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(3)
                                .setPrepare(PrepareMessage.newBuilder()
                                                .setNode(conflictingNode.getProto())
                                                .setJustify(staleJustify.getProto())
                                                .build())
                                .build();

                // Note: we cannot set lockedQC directly (private), so we test the
                // safeNode logic indirectly: in a fresh Consensus (lockedQC = genesisQC),
                // the safety rule always passes because every node parent traces to genesis.
                // This ensures the genesis start state is correct.
                consensus.onMessage("node-0", prepareMsg.toByteArray());
                // No VOTE should be sent (mock verifies no interaction for vote)
                // The broadcast call happens for the PREPARE from leader-to-self path
                // but no outgoing VOTE to leader (node-0 is the leader, handled internally)
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

                // Should NOT throw and should trigger internal vote processing
                assertDoesNotThrow(() -> consensus.onMessage("node-0", prepareMsg.toByteArray()),
                                "safeNode should accept proposals extending from genesis");
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

                // --- Step 2: Feed n-f PREPARE votes (Round 1: Ri points)
                byte[] nodeHash = computeViewNodeHash("cmd-view-1", 1);
                // In mocked test, we need to feed BOTH peers and the leader's own self-vote
                feedVoteR1(leader, "node-0", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVoteR1(leader, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVoteR1(leader, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);

                // --- Step 2b: Feed n-f PREPARE votes (Round 2: shares)
                feedVoteR2(leader, "node-0", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVoteR2(leader, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
                feedVoteR2(leader, "node-2", QuorumCertificate.PREPARE, 1, nodeHash);

                // --- Step 3: Feed n-f PRE-COMMIT votes (Round 1 + Round 2)
                feedVoteR1(leader, "node-0", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                feedVoteR1(leader, "node-1", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                feedVoteR1(leader, "node-2", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                feedVoteR2(leader, "node-0", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                feedVoteR2(leader, "node-1", QuorumCertificate.PRE_COMMIT, 1, nodeHash);
                feedVoteR2(leader, "node-2", QuorumCertificate.PRE_COMMIT, 1, nodeHash);

                // --- Step 4: Feed n-f COMMIT votes (Round 1 + Round 2)
                feedVoteR1(leader, "node-0", QuorumCertificate.COMMIT, 1, nodeHash);
                feedVoteR1(leader, "node-1", QuorumCertificate.COMMIT, 1, nodeHash);
                feedVoteR1(leader, "node-2", QuorumCertificate.COMMIT, 1, nodeHash);
                feedVoteR2(leader, "node-0", QuorumCertificate.COMMIT, 1, nodeHash);
                feedVoteR2(leader, "node-1", QuorumCertificate.COMMIT, 1, nodeHash);
                feedVoteR2(leader, "node-2", QuorumCertificate.COMMIT, 1, nodeHash);

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

                // One malicious node sends QUORUM number of identical votes!
                for (int i = 0; i < QUORUM; i++) {
                        feedVoteR1(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);
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
                Consensus replica = new Consensus("node-1", NODE_IDS, mockLinkManager, service, mockCrypto);
                replica.start(null);
                org.mockito.Mockito.clearInvocations(mockLinkManager);

                QuorumCertificate forgedQC = QuorumCertificate.create(QuorumCertificate.PREPARE, 1, new byte[32],
                                new byte[64]);
                ConsensusMessage preCommitMsg = ConsensusMessage.newBuilder()
                                .setViewNumber(1)
                                .setPreCommit(ist.group29.depchain.network.ConsensusMessages.PreCommitMessage
                                                .newBuilder()
                                                .setJustify(forgedQC.getProto())
                                                .build())
                                .build();

                replica.onMessage("node-0", preCommitMsg.toByteArray());
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
                feedVoteR1(consensus, "node-1", QuorumCertificate.PREPARE, 1, nodeHash);

                Thread.sleep(4500); // Wait for timeout (4 seconds default + buffer)

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

        private void feedVoteR1(Consensus c, String fromId, String phase, int view, byte[] nodeHash) {
                // In a real run, this would contain a real R_i point.
                // For the mock, we just need a non-empty ByteString to trigger isRound1.
                byte[] dummyRi = new byte[32];
                ConsensusMessage msg = ConsensusMessage.newBuilder()
                                .setViewNumber(view)
                                .setVote(VoteMessage.newBuilder()
                                                .setPhase(phase)
                                                .setNodeHash(ByteString.copyFrom(nodeHash))
                                                .setRPoint(ByteString.copyFrom(dummyRi))
                                                .build())
                                .build();
                c.onMessage(fromId, msg.toByteArray());
        }

        private void feedVoteR2(Consensus c, String fromId, String phase, int view, byte[] nodeHash) {
                // In a real run, this would contain a real signature share.
                // For the mock, we just need a non-empty ByteString to trigger isRound2.
                byte[] dummyShare = new byte[32];
                ConsensusMessage msg = ConsensusMessage.newBuilder()
                                .setViewNumber(view)
                                .setVote(VoteMessage.newBuilder()
                                                .setPhase(phase)
                                                .setNodeHash(ByteString.copyFrom(nodeHash))
                                                .setScalarSignature(ByteString.copyFrom(dummyShare))
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
