package ist.group29.depchain.server.consensus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

import com.google.protobuf.ByteString;

import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.common.network.EnvelopeFactory;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.network.ConsensusMessages;
import ist.group29.depchain.network.ConsensusMessages.ConsensusMessage;
import ist.group29.depchain.network.ConsensusMessages.PrepareMessage;
import ist.group29.depchain.network.ConsensusMessages.VoteMessage;
import ist.group29.depchain.server.crypto.CryptoManager;
import ist.group29.depchain.server.service.TransactionManager;

/**
 * A subclass of Consensus that injects configurable Byzantine behaviors.
 *
 * This class overrides the onMessage() dispatch entry point to
 * intercept, alter, or drop messages before they reach the honest consensus
 * logic.
 */
public class ByzantineConsensus extends Consensus {

    private static final Logger LOG = Logger.getLogger(ByzantineConsensus.class.getName());

    private static final long CRASH_DELAY_MS = 30_000;
    private static final long SLOW_NODE_DELAY_MS = 3_000;

    private final ByzantineMode mode;

    public ByzantineConsensus(ByzantineMode mode, String selfId, List<String> allNodeIds,
            LinkManager linkManager, DecideListener decideListener,
            TransactionManager transactionManager, String keysDir) {
        super(selfId, allNodeIds, linkManager, decideListener, transactionManager, keysDir);
        this.mode = mode;
        initMode();
    }

    private void initMode() {
        LOG.warning("[ByzantineConsensus] Node " + selfId + " running in mode: " + mode);

        if (mode == ByzantineMode.CRASH) {
            LOG.warning("[ByzantineConsensus] Scheduling JVM crash in " + CRASH_DELAY_MS + " ms");
            new Timer(true).schedule(new TimerTask() {
                @Override
                public void run() {
                    LOG.severe("[ByzantineConsensus] CRASHING NOW");
                    System.exit(1);
                }
            }, CRASH_DELAY_MS);
        }

        if (mode == ByzantineMode.CORRUPT_QC) {
            wrapCryptoManagerForCorruptQC();
        }
    }

    @Override
    public void onMessage(String senderId, ConsensusMessage msg) {
        switch (mode) {
            case SILENT -> {
                LOG.fine("[ByzantineConsensus] SILENT mode — dropping message from " + senderId);
            }
            case SLOW_NODE -> {
                LOG.warning("[ByzantineConsensus] SLOW_NODE: delaying message from " + senderId + " by "
                        + SLOW_NODE_DELAY_MS + " ms (async)");
                final String capSender = senderId;
                final ConsensusMessage capMsg = msg;
                new Thread(() -> {
                    try {
                        Thread.sleep(SLOW_NODE_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.warning("[ByzantineConsensus] SLOW_NODE: sleep interrupted");
                    }
                    ByzantineConsensus.super.onMessage(capSender, capMsg);
                }, "slow-node-" + senderId).start();
            }

            default -> {
                super.onMessage(senderId, msg);
            }
        }
    }

    /**
     * CORRUPT_VOTE: corrupt the signature share so that other nodes cannot form a
     * valid QC from this node's vote.
     *
     * SELECTIVE_VOTE: only vote in PREPARE phase (drop PRE_COMMIT and COMMIT).
     */
    @Override
    protected synchronized void sendVote(String phase, int view, byte[] nodeHash) {
        if (mode == ByzantineMode.SELECTIVE_VOTE) {
            if (QuorumCertificate.PREPARE.equals(phase)) {
                LOG.warning("[ByzantineConsensus] SELECTIVE_VOTE: sending PREPARE vote, view=" + view);
                super.sendVote(phase, view, nodeHash);
            } else {
                LOG.warning("[ByzantineConsensus] SELECTIVE_VOTE: dropping " + phase + " vote, view=" + view);
            }
            return;
        }

        if (mode == ByzantineMode.CORRUPT_VOTE) {
            LOG.warning("[ByzantineConsensus] Sending CORRUPT vote for phase=" + phase + " view=" + view);
            try {
                QuorumCertificate dummyQc = QuorumCertificate.create(phase, view, nodeHash, null);
                byte[] signatureShare = cryptoManager.computeSignatureShare(dummyQc.getMessageToSign());

                // Corrupt the share
                if (signatureShare.length > 0) {
                    signatureShare[0] ^= 0xFF;
                    signatureShare[signatureShare.length / 2] ^= 0xFF;
                }

                VoteMessage vote = VoteMessage.newBuilder()
                        .setPhase(phase)
                        .setNodeHash(ByteString.copyFrom(nodeHash))
                        .setSignatureShare(ByteString.copyFrom(signatureShare))
                        .build();

                ConsensusMessage msg = ConsensusMessage.newBuilder()
                        .setViewNumber(view)
                        .setVote(vote)
                        .build();

                String currentLeader = leader(view);
                linkManager.send(currentLeader, EnvelopeFactory.wrap(msg));
            } catch (Exception e) {
                LOG.severe("[ByzantineConsensus] Failed to send corrupt vote: " + e.getMessage());
            }
        } else {
            super.sendVote(phase, view, nodeHash);
        }
    }

    /**
     * Overrides triggerProposal for leader-side Byzantine modes:
     * CORRUPT_PROPOSAL: propose an invalid block
     * REPLAY_LEADER: re-propose a previously committed block
     */
    @Override
    public synchronized void triggerProposal() {
        if (!isLeader(curView)) {
            super.triggerProposal();
            return;
        }

        switch (mode) {
            case SILENT -> {
                LOG.fine("[ByzantineConsensus] SILENT mode — suppressing proposal in view " + curView);
            }
            case CORRUPT_PROPOSAL -> triggerCorruptProposal();
            case REPLAY_LEADER -> triggerReplayProposal();
            default -> super.triggerProposal();
        }
    }

    /**
     * CORRUPT_PROPOSAL: replace the legitimate block with an arbitrary invalid one.
     */
    private void triggerCorruptProposal() {
        LOG.warning("[ByzantineConsensus] Leader corrupting proposal in view " + curView);

        Block corruptBlock = Block.newBuilder()
                .addTransactions(Transaction.newBuilder()
                        .setFrom("byzantine-fake")
                        .setTo("byzantine-fake")
                        .setValue(999_999)
                        .setNonce(-1)
                        .setGasPrice(0)
                        .setGasLimit(0)
                        .setData(ByteString.copyFromUtf8("BYZANTINE_CORRUPT_BLOCK"))
                        .build())
                .build();

        QuorumCertificate justifyQC = (highQC != null) ? highQC : QuorumCertificate.genesisQC();

        HotStuffNode parentNode = new HotStuffNode(
                ConsensusMessages.HotStuffNode.newBuilder()
                        .setNodeHash(ByteString.copyFrom(justifyQC.getNodeHash()))
                        .build());
        currentProposal = parentNode.createLeaf(corruptBlock, curView);
        storeBlock(currentProposal);

        broadcastPrepare(currentProposal, justifyQC);
        LOG.warning("[ByzantineConsensus] Broadcast corrupt PREPARE for view " + curView);
    }

    /**
     * REPLAY_LEADER: content-reuse attack.
     *
     * Takes the transaction payload of a previously committed block and proposes
     * it again under a new view and a new nodeHash.
     * Replicas won't recognize it as
     * previously seen (different hash), so this tests whether the service layer
     * detects and rejects duplicate transaction execution rather than the consensus
     * layer. safeNode() may also reject it if the parent chain doesn't extend
     * correctly from the current lockedQC.
     */
    private void triggerReplayProposal() {
        LOG.warning("[ByzantineConsensus] REPLAY_LEADER: attempting to replay a previously committed block in view "
                + curView);

        // Find a non-genesis block to replay
        HotStuffNode replayTarget = null;
        byte[] genesisHash = HotStuffNode.genesis().getNodeHash();
        for (Map.Entry<ByteString, HotStuffNode> entry : blockStore.entrySet()) {
            HotStuffNode node = entry.getValue();
            if (!java.util.Arrays.equals(node.getNodeHash(), genesisHash) && node.getBlock() != null) {
                replayTarget = node;
                break;
            }
        }

        if (replayTarget == null) {
            // No previously committed block to replay — fall back to honest behavior
            LOG.warning("[ByzantineConsensus] REPLAY_LEADER: no block to replay, falling back to honest proposal");
            super.triggerProposal();
            return;
        }

        QuorumCertificate justifyQC = (highQC != null) ? highQC : QuorumCertificate.genesisQC();
        HotStuffNode parentNode = new HotStuffNode(
                ConsensusMessages.HotStuffNode.newBuilder()
                        .setNodeHash(ByteString.copyFrom(justifyQC.getNodeHash()))
                        .build());

        // Re-propose the old block as a new leaf with the current view
        currentProposal = parentNode.createLeaf(replayTarget.getBlock(), curView);
        storeBlock(currentProposal);

        broadcastPrepare(currentProposal, justifyQC);
        LOG.warning("[ByzantineConsensus] Broadcast REPLAYED PREPARE for view " + curView
                + " (original block from view " + replayTarget.getProto().getViewNumber() + ")");
    }

    // Helpers

    private void broadcastPrepare(HotStuffNode proposal,
            QuorumCertificate justify) {
        PrepareMessage prepare = PrepareMessage.newBuilder()
                .setNode(proposal.getProto())
                .setJustify(justify.getProto())
                .build();

        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                .setViewNumber(curView)
                .setPrepare(prepare)
                .build();

        linkManager.broadcast(EnvelopeFactory.wrap(prepareMsg));
        super.onMessage(selfId, prepareMsg);
    }

    /**
     * Replaces the CryptoManager with a wrapper that corrupts aggregated
     * threshold signatures, causing QCs to fail validation on other nodes.
     */
    private void wrapCryptoManagerForCorruptQC() {
        CryptoManager original = this.cryptoManager;
        this.cryptoManager = new CryptoManager(original) {
            @Override
            public byte[] aggregateSignatureShares(byte[] data, java.util.List<byte[]> sharesData,
                    java.util.List<Integer> participantIds) throws Exception {
                byte[] realAgg = super.aggregateSignatureShares(data, sharesData, participantIds);
                if (realAgg.length > 0) {
                    realAgg[realAgg.length / 2] ^= 0xFF;
                }
                LOG.warning("[ByzantineConsensus] Corrupted aggregated QC signature");
                return realAgg;
            }
        };
    }

    public ByzantineMode getMode() {
        return mode;
    }
}
