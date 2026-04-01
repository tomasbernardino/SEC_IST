package ist.group29.depchain.server.consensus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.protobuf.ByteString;

import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.EnvelopeFactory;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.network.ConsensusMessages;
import ist.group29.depchain.network.ConsensusMessages.CommitMessage;
import ist.group29.depchain.network.ConsensusMessages.ConsensusMessage;
import ist.group29.depchain.network.ConsensusMessages.DecideMessage;
import ist.group29.depchain.network.ConsensusMessages.NewViewMessage;
import ist.group29.depchain.network.ConsensusMessages.PreCommitMessage;
import ist.group29.depchain.network.ConsensusMessages.PrepareMessage;
import ist.group29.depchain.network.ConsensusMessages.SyncRequest;
import ist.group29.depchain.network.ConsensusMessages.SyncResponse;
import ist.group29.depchain.network.ConsensusMessages.VoteMessage;
import ist.group29.depchain.server.crypto.CryptoManager;
import ist.group29.depchain.server.service.TransactionManager;

/**
 * Basic HotStuff consensus engine - Algorithm 2 of the paper.
 *
 * Messages arrive via MessageRouter
 */
public class Consensus {

    private static final Logger LOG = Logger.getLogger(Consensus.class.getName());

    private static final long INITIAL_TIMEOUT_MS = 4_000;
    private static final long PROPOSAL_RETRY_MS = 1_000;

    private final String selfId;
    private final List<String> sortedNodeIds;
    private final int n;
    private final int f;
    private final int quorum;
    private final LinkManager linkManager;
    private final DecideListener decideListener;
    private final TransactionManager transactionManager;
    private CryptoManager cryptoManager;

    private final Map<ByteString, HotStuffNode> blockStore = new ConcurrentHashMap<>();

    private volatile int curView = 1;
    private QuorumCertificate lockedQC = QuorumCertificate.genesisQC();
    private QuorumCertificate prepareQC = QuorumCertificate.genesisQC();

    // Composite key for the vote accumulator: groups votes by (view, phase+round).
    private record PhaseRound(int view, String phase) {
    }

    private final Map<PhaseRound, Map<String, VoteMessage>> voteAccumulator = new ConcurrentHashMap<>();

    // Stores NewViewMessages for each view
    private final Map<Integer, Map<String, NewViewMessage>> newViewAccumulator = new ConcurrentHashMap<>();

    private volatile HotStuffNode currentProposal = null;

    private QuorumCertificate highQC = null;

    private final ScheduledExecutorService pacemaker;

    private ScheduledFuture<?> viewTimer = null;
    private ScheduledFuture<?> blockProposalTimer = null;
    private long timeoutMs = INITIAL_TIMEOUT_MS;

    private int lastVotedPrepareView = 0;

    private int lastPreCommitView = 0;
    private int lastCommitView = 0;

    private ByteString lastExecutedNodeHash = ByteString.copyFrom(HotStuffNode.genesis().getNodeHash());

    // Buffers a PREPARE message while waiting for a sync response.
    private record PendingPrepare(String senderId, PrepareMessage msg, int view) {
    }

    private volatile PendingPrepare pendingPrepare = null;

    public Consensus(String selfId, List<String> allNodeIds,
            LinkManager linkManager, DecideListener decideListener, TransactionManager transactionManager, String keysDir) {
        this(selfId, allNodeIds, linkManager, decideListener, transactionManager, createDefaultCrypto(selfId, keysDir),
                createDefaultPacemaker());
    }

    public Consensus(String selfId, List<String> allNodeIds,
            LinkManager linkManager, DecideListener decideListener, TransactionManager transactionManager, CryptoManager cryptoManager) {
        this(selfId, allNodeIds, linkManager, decideListener, transactionManager, cryptoManager, createDefaultPacemaker());
    }

    public Consensus(String selfId, List<String> allNodeIds,
            LinkManager linkManager, DecideListener decideListener, TransactionManager transactionManager, CryptoManager cryptoManager,
            ScheduledExecutorService pacemaker) {
        this.selfId = selfId;
        this.sortedNodeIds = new ArrayList<>(allNodeIds);
        Collections.sort(this.sortedNodeIds);
        this.n = allNodeIds.size();
        this.f = (n - 1) / 3;
        this.quorum = n - f;
        this.linkManager = linkManager;
        this.decideListener = decideListener;
        this.transactionManager = transactionManager;
        this.cryptoManager = cryptoManager;
        this.pacemaker = pacemaker;

        HotStuffNode genesis = HotStuffNode.genesis();
        storeBlock(genesis);
    }

    private static ScheduledExecutorService createDefaultPacemaker() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Pacemaker");
            t.setDaemon(true);
            return t;
        });
    }

    private static CryptoManager createDefaultCrypto(String selfId, String keysDir) {
        try {
            return new CryptoManager(selfId, keysDir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private volatile int lastDecidedView = 0;

    public CryptoManager getCryptoManager() {
        return cryptoManager;
    }

    public int getCurrentView() {
        return curView;
    }

    public int getLastDecidedView() {
        return lastDecidedView;
    }

    /**
     * Start consensus for view 1.
     */
    public synchronized void start() {
        LOG.info("[Consensus] Starting view " + curView + " - leader is " + leader(curView));
        sendNewView(curView);
        resetTimer();
    }

    /**
     * Called by MessageRouter when a ConsensusMessage arrives
     */
    public void onMessage(String senderId, ConsensusMessage msg) {
        int msgView = msg.getViewNumber();
        switch (msg.getTypeCase()) {
            case NEW_VIEW -> onNewView(senderId, msg.getNewView(), msgView);
            case PREPARE -> onPrepare(senderId, msg.getPrepare(), msgView);
            case VOTE -> onVote(senderId, msg.getVote(), msgView);
            case PRE_COMMIT -> onPreCommit(senderId, msg.getPreCommit(), msgView);
            case COMMIT -> onCommit(senderId, msg.getCommit(), msgView);
            case DECIDE -> onDecide(senderId, msg.getDecide(), msgView);
            case SYNC_REQUEST -> onSyncRequest(senderId, msg.getSyncRequest());
            case SYNC_RESPONSE -> onSyncResponse(senderId, msg.getSyncResponse());
            default -> LOG.warning("[Consensus] Unknown message type from " + senderId);
        }
    }

    /**
     * Handle an incoming NEW-VIEW message.
     */
    private synchronized void onNewView(String senderId, NewViewMessage msg, int view) {
        if (!isLeader(view))
            return;
        LOG.info("[Consensus] Received NEW-VIEW from " + senderId + " for view " + view);
        newViewAccumulator.computeIfAbsent(view, k -> new ConcurrentHashMap<>()).put(senderId, msg);

        Map<String, NewViewMessage> newViewMessages = newViewAccumulator.get(view);
        if (newViewMessages == null || newViewMessages.size() < quorum)
            return;

        if (view != curView) {
            LOG.fine("[Consensus] Quorum reached for old view " + view + " - ignoring as we are in " + curView);
            return;
        }

        LOG.info("[Consensus] Reached NEW-VIEW quorum for view " + view + "! Proposing...");

        // Extract the QC with the highest view number from all NEW-VIEW messages
        // Skip QCs with invalid signatures (e.g., corrupted by a Byzantine leader)
        highQC = QuorumCertificate.genesisQC();
        for (NewViewMessage nv : newViewMessages.values()) {
            QuorumCertificate qc = new QuorumCertificate(nv.getJustify());
            if (!qc.isValid(cryptoManager)) {
                LOG.warning("[Consensus] Skipping QC from " + view + " with invalid signature (view " + qc.getViewNumber() + ")");
                continue;
            }
            if (qc.getViewNumber() > highQC.getViewNumber()) {
                highQC = qc;
            }
        }

        triggerProposal();
    }

    /**
     * Trigger a proposal by asking the Service to build a block.
     * Called on NEW-VIEW quorum and by the proposal retry timer.
     */
    public synchronized void triggerProposal() {
        if (!isLeader(curView))
            return;
        if (currentProposal != null)
            return;
        Map<String, NewViewMessage> newViewMessages = newViewAccumulator.get(curView);
        // Only build if there is a quorum of NEW-VIEW messages and an already defined highQC
        if (newViewMessages == null ||newViewMessages.size() < quorum || highQC == null)
            return;

        Block block = transactionManager.buildBlock();
        if (block == null || block.getTransactionsCount() == 0) {
            scheduleRetry();
            return;
        }

        // Create new proposal
        HotStuffNode parentNode = new HotStuffNode(
                ConsensusMessages.HotStuffNode.newBuilder()
                        .setNodeHash(ByteString.copyFrom(highQC.getNodeHash()))
                        .build());
        currentProposal = parentNode.createLeaf(block, curView);

        storeBlock(currentProposal);
        LOG.info("[Consensus] Leader view triggered proactive proposal for view " + curView + ": " + currentProposal);

        PrepareMessage prepare = PrepareMessage.newBuilder()
                .setNode(currentProposal.getProto())
                .setJustify(highQC.getProto())
                .build();

        ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
                .setViewNumber(curView)
                .setPrepare(prepare)
                .build();
        linkManager.broadcast(EnvelopeFactory.wrap(prepareMsg));
        
        cancelBlockProposalTimer(); // Proposal is out, no need for retry
        onPrepare(selfId, prepare, curView);
        highQC = null;
    }

    /**
     * Handle an incoming PREPARE message.
     */
    private synchronized void onPrepare(String senderId, PrepareMessage msg, int view) {
        if (view != curView) {
            return;
        }
        if (!senderId.equals(leader(view))) {
            return;
        }

        HotStuffNode proposedNode = new HotStuffNode(msg.getNode());
        QuorumCertificate justify = new QuorumCertificate(msg.getJustify());

        if (view <= lastVotedPrepareView) {
            LOG.warning("[Consensus] Equivocation detected! Leader sent multiple PREPAREs for view " + view);
            return;
        }

        if (!Arrays.equals(proposedNode.getProto().getParentHash().toByteArray(),
                justify.getNodeHash())) {
            LOG.warning("[Consensus] PREPARE rejected: node does not extend from justify QC in view " + view);
            return;
        }

        // Branch catch-up
        if (!blockStore.containsKey(ByteString.copyFrom(justify.getNodeHash()))
                && !Arrays.equals(justify.getNodeHash(), HotStuffNode.genesis().getNodeHash())) {
            LOG.info("[Consensus] Missing blocks — requesting sync from " + senderId
                    + " for hash " + CryptoUtils.bytesToHex(
                            justify.getNodeHash()));
            requestSync(senderId, justify.getNodeHash());
            pendingPrepare = new PendingPrepare(senderId, msg, view);
            return;
        }

        if (!transactionManager.validateBlock(proposedNode.getBlock())) {
            LOG.warning("[Consensus] PREPARE rejected by TransactionManager validation in view " + view);
            return;
        }

        if (!safeNode(proposedNode, justify)) {
            LOG.warning("[Consensus] PREPARE rejected by safeNode in view " + view);
            return;
        }

        storeBlock(proposedNode);
        currentProposal = proposedNode;
        lastVotedPrepareView = view;
        LOG.info("[Consensus] Accepted PREPARE in view " + view + " for: " + proposedNode);

        sendVote(QuorumCertificate.PREPARE, view, proposedNode.getNodeHash());
    }

    /**
     * Handle a VOTE message (covers prepare, pre-commit, and commit votes).
     */
    private synchronized void onVote(String senderId, VoteMessage vote, int view) {
        if (!isLeader(view))
            return;
        if (view != curView)
            return;

        String phase = vote.getPhase();
        PhaseRound key = new PhaseRound(view, phase);
        voteAccumulator
                .computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .put(senderId, vote);

        Map<String, VoteMessage> votes = voteAccumulator.get(key);
        LOG.info("[Consensus] Vote from " + senderId + " for phase=" + phase
                + " count=" + votes.size() + "/" + quorum);
        if (votes.size() < quorum)
            return;

        try {

            LOG.info("[Consensus] Leader formed QC for phase=" + phase + " view=" + view);

            QuorumCertificate dummyQc = QuorumCertificate.create(phase, view, vote.getNodeHash().toByteArray(), null);
            byte[] dataToSign = dummyQc.getMessageToSign();

            List<byte[]> shares = new ArrayList<>();
            List<Integer> participants = new ArrayList<>();
            for (Map.Entry<String, VoteMessage> entry : votes.entrySet()) {
                shares.add(entry.getValue().getSignatureShare().toByteArray());
                participants.add(sortedNodeIds.indexOf(entry.getKey()) + 1);
            }

            byte[] combinedSig = cryptoManager.aggregateSignatureShares(dataToSign, shares, participants);
            QuorumCertificate qc = QuorumCertificate.create(phase, view, vote.getNodeHash().toByteArray(),
                    combinedSig);

            switch (phase) {
                case QuorumCertificate.PREPARE -> {
                    prepareQC = qc;
                    ConsensusMessage preCommit = ConsensusMessage.newBuilder()
                            .setViewNumber(view)
                            .setPreCommit(PreCommitMessage.newBuilder()
                                    .setJustify(qc.getProto()).build())
                            .build();
                    linkManager.broadcast(EnvelopeFactory.wrap(preCommit));
                    onPreCommit(selfId, preCommit.getPreCommit(), view);
                }
                case QuorumCertificate.PRE_COMMIT -> {
                    ConsensusMessage commit = ConsensusMessage.newBuilder()
                            .setViewNumber(view)
                            .setCommit(CommitMessage.newBuilder()
                                    .setJustify(qc.getProto()).build())
                            .build();
                    linkManager.broadcast(EnvelopeFactory.wrap(commit));
                    onCommit(selfId, commit.getCommit(), view);
                }
                case QuorumCertificate.COMMIT -> {
                    ConsensusMessage decide = ConsensusMessage.newBuilder()
                            .setViewNumber(view)
                            .setDecide(DecideMessage.newBuilder()
                                    .setJustify(qc.getProto()).build())
                            .build();
                    linkManager.broadcast(EnvelopeFactory.wrap(decide));
                    onDecide(selfId, decide.getDecide(), view);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed during threshold signing aggregation", e);
        }
    }

    /**
     * Handle an incoming PRE-COMMIT message.
     */
    private synchronized void onPreCommit(String senderId, PreCommitMessage msg, int view) {
        if (view != curView)
            return;
        if (!senderId.equals(leader(view)))
            return;
        if (currentProposal == null)
            return;
        if (view <= lastPreCommitView)
            return;

        QuorumCertificate qc = new QuorumCertificate(msg.getJustify());
        if (!qc.isValid(cryptoManager)) {
            LOG.warning("[Consensus] PRE-COMMIT has invalid QC in view " + view);
            return;
        }
        if (!QuorumCertificate.PREPARE.equals(qc.getType())) {
            LOG.warning("[Consensus] PRE-COMMIT rejected: expected PREPARE QC but got " + qc.getType());
            return;
        }

        prepareQC = qc;
        lastPreCommitView = view;
        LOG.info("[Consensus] Accepted PRE-COMMIT in view " + view);
        sendVote(QuorumCertificate.PRE_COMMIT, view, currentProposal.getNodeHash());
    }

    /**
     * Handle an incoming COMMIT message.
     */
    private synchronized void onCommit(String senderId, CommitMessage msg, int view) {
        if (view != curView)
            return;
        if (!senderId.equals(leader(view)))
            return;
        if (currentProposal == null)
            return;
        if (view <= lastCommitView)
            return;

        QuorumCertificate qc = new QuorumCertificate(msg.getJustify());
        if (!qc.isValid(cryptoManager)) {
            LOG.warning("[Consensus] COMMIT has invalid QC in view " + view);
            return;
        }

        if (!QuorumCertificate.PRE_COMMIT.equals(qc.getType())) {
            LOG.warning("[Consensus] COMMIT rejected: expected PRE_COMMIT QC but got " + qc.getType());
            return;
        }

        lockedQC = qc;
        lastCommitView = view;
        LOG.info("[Consensus] Locked on view " + view + " - lockedQC=" + lockedQC);
        sendVote(QuorumCertificate.COMMIT, view, currentProposal.getNodeHash());
    }

    /**
     * Handle an incoming DECIDE message.
     */
    private synchronized void onDecide(String senderId, DecideMessage msg, int view) {
        if (view != curView) {
            return;
        }
        if (!senderId.equals(leader(view))) {
            return;
        }
        if (currentProposal == null) {
            return;
        }

        QuorumCertificate qc = new QuorumCertificate(msg.getJustify());
        if (!qc.isValid(cryptoManager)) {
            LOG.warning("[Consensus] DECIDE has invalid QC in view " + view);
            return;
        }
        if (!QuorumCertificate.COMMIT.equals(qc.getType())) {
            LOG.warning("[Consensus] DECIDE rejected: expected COMMIT QC but got " + qc.getType());
            return;
        }

        LOG.info("[Consensus] DECIDED view " + view + " - txs: " + currentProposal.getBlock().getTransactionsCount());
        cancelTimer();

        timeoutMs = INITIAL_TIMEOUT_MS;

        executeCommittedBranch(currentProposal);
        advanceView();
    }

    /**
     * Safety rule: the proposed node must be on the same branch as the locked
     * block.
     * Liveness rule: if the justify QC is fresher than lockedQC, accept anyway
     */
    private boolean safeNode(HotStuffNode node, QuorumCertificate qc) {

        boolean safetyRule = extendsFrom(node, lockedQC.getNodeHash());
        boolean livenessRule = qc.getViewNumber() > lockedQC.getViewNumber();
        LOG.fine("[Consensus] safeNode: safety=" + safetyRule + " liveness=" + livenessRule);
        return safetyRule || livenessRule;
    }

    /**
     * Check whether node is a descendant of the block identified by
     * ancestorHash by walking the blockStore hash chain backwards.
     */
    public boolean extendsFrom(HotStuffNode node, byte[] ancestorHash) {

        if (Arrays.equals(ancestorHash, HotStuffNode.genesis().getNodeHash())) {
            return true;
        }

        if (Arrays.equals(node.getNodeHash(), ancestorHash)) {
            return true;
        }

        byte[] currentHash = node.getProto().getParentHash().toByteArray();
        for (int depth = 0; depth < 1000; depth++) {
            if (Arrays.equals(currentHash, ancestorHash)) {
                return true;
            }

            HotStuffNode current = blockStore.get(ByteString.copyFrom(currentHash));
            if (current == null) {
                return false;
            }

            currentHash = current.getProto().getParentHash().toByteArray();
        }

        return false;
    }

    private void advanceView() {
        curView++;
        currentProposal = null;
        highQC = null;
        LOG.info("[Consensus] Advancing to view " + curView + " - leader: " + leader(curView));

        // Clean up old accumulator entries to prevent unbounded memory growth.
        int staleThreshold = curView - 5;
        if (staleThreshold > 0) {
            voteAccumulator.keySet().removeIf(k -> k.view() < staleThreshold);
            newViewAccumulator.keySet().removeIf(v -> v < staleThreshold);
        }

        sendNewView(curView);
        resetTimer();
    }

    private synchronized void onTimeout() {
        LOG.warning("[Consensus] Timeout in view " + curView + " - triggering view-change");
        timeoutMs = Math.min(timeoutMs * 2, 60_000);
        advanceView();
    }

    private void resetTimer() {
        cancelTimer();
        viewTimer = pacemaker.schedule(this::onTimeout, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void cancelTimer() {
        if (viewTimer != null) {
            viewTimer.cancel(false);
        }
        cancelBlockProposalTimer();
    }

    private void scheduleRetry() {
        cancelBlockProposalTimer();
        blockProposalTimer = pacemaker.schedule(this::triggerProposal, PROPOSAL_RETRY_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelBlockProposalTimer() {
        if (blockProposalTimer != null) {
            blockProposalTimer.cancel(false);
        }
    }

    public String leader(int view) {
        if (view <= 0)
            return null;
        return sortedNodeIds.get((view - 1) % n);
    }

    private boolean isLeader(int view) {
        return selfId.equals(leader(view));
    }

    private void sendNewView(int targetView) {
        String nextLeader = leader(targetView);
        ConsensusMessage msg = ConsensusMessage.newBuilder()
                .setViewNumber(targetView)
                .setNewView(NewViewMessage.newBuilder()
                        .setJustify(prepareQC.getProto())
                        .build())
                .build();
        if (nextLeader.equals(selfId)) {
            onNewView(selfId, msg.getNewView(), targetView);
        } else {
            linkManager.send(nextLeader, EnvelopeFactory.wrap(msg));
        }
    }

    private synchronized void sendVote(String phase, int view, byte[] nodeHash) {
        LOG.info("[Consensus] Node " + selfId + " casting vote for phase=" + phase + " view=" + view);

        try {
            QuorumCertificate dummyQc = QuorumCertificate.create(phase, view, nodeHash, null);
            byte[] signatureShare = cryptoManager.computeSignatureShare(dummyQc.getMessageToSign());

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
            if (currentLeader.equals(selfId)) {
                onVote(selfId, msg.getVote(), view);
            } else {
                linkManager.send(currentLeader, EnvelopeFactory.wrap(msg));
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to send vote", e);
        }
    }

    /**
     * Walk the committed branch from decidedNode back to lastExecutedNodeHash,
     * then execute all un-executed commands in forward order (root → leaf).
     */
    private void executeCommittedBranch(HotStuffNode decidedNode) {
        List<HotStuffNode> toExecute = new ArrayList<>();
        HotStuffNode current = decidedNode;

        int maxDepth = 1000;
        while (current != null && maxDepth-- > 0
                && !ByteString.copyFrom(current.getNodeHash()).equals(lastExecutedNodeHash)) {
            if (current.getBlock().getTransactionsCount() > 0) {
                toExecute.add(current);
            }
            current = blockStore.get(current.getProto().getParentHash());
        }

        Collections.reverse(toExecute);
        for (HotStuffNode node : toExecute) {
            LOG.info("[Consensus] DECIDE View " + decidedNode.getViewNumber() + " finalized block with "
                    + node.getBlock().getTransactionsCount() + " txs");
            // Execute the upcall to Service 
            decideListener.onDecide(node.getBlock(), node.getViewNumber());
        }
        lastExecutedNodeHash = ByteString.copyFrom(decidedNode.getNodeHash());
        lastDecidedView = decidedNode.getViewNumber();
    }

    /**
     * Store a block in the local block store.
     */
    public void storeBlock(HotStuffNode node) {
        blockStore.put(ByteString.copyFrom(node.getNodeHash()), node);
    }

    /**
     * Send a SyncRequest to a peer asking for the chain of blocks
     * leading up to the given nodeHash.
     */
    private void requestSync(String peerId, byte[] nodeHash) {
        ConsensusMessage msg = ConsensusMessage.newBuilder()
                .setViewNumber(curView)
                .setSyncRequest(SyncRequest.newBuilder()
                        .setNodeHash(ByteString.copyFrom(nodeHash))
                        .build())
                .build();
        linkManager.send(peerId, EnvelopeFactory.wrap(msg));
    }

    /**
     * Handle an incoming SyncRequest: walk the blockStore from the requested
     * hash back to genesis and return all found blocks.
     */
    private synchronized void onSyncRequest(String senderId, SyncRequest req) {
        List<ConsensusMessages.HotStuffNode> nodes = new ArrayList<>();
        byte[] currentHash = req.getNodeHash().toByteArray();
        int maxDepth = 1000;
        for (int i = 0; i < maxDepth; i++) {
            HotStuffNode node = blockStore.get(ByteString.copyFrom(currentHash));
            if (node == null)
                break;
            nodes.add(node.getProto());
            currentHash = node.getProto().getParentHash().toByteArray();
        }
        if (nodes.isEmpty()) {
            LOG.fine("[Consensus] SyncRequest from " + senderId + " — no blocks found");
            return;
        }
        ConsensusMessage resp = ConsensusMessage.newBuilder()
                .setViewNumber(curView)
                .setSyncResponse(SyncResponse.newBuilder()
                        .addAllNodes(nodes)
                        .build())
                .build();
        linkManager.send(senderId, EnvelopeFactory.wrap(resp));
        LOG.info("[Consensus] Sent " + nodes.size() + " blocks to " + senderId + " in sync response");
    }

    /**
     * Handle an incoming SyncResponse: store all received blocks in the
     * blockStore, then re-process any buffered PREPARE message.
     */
    private synchronized void onSyncResponse(String senderId, SyncResponse resp) {
        int stored = 0;
        for (ConsensusMessages.HotStuffNode protoNode : resp.getNodesList()) {
            HotStuffNode node = new HotStuffNode(protoNode);
            ByteString hash = ByteString.copyFrom(node.getNodeHash());
            if (blockStore.putIfAbsent(hash, node) == null) {
                stored++;
            }
        }
        LOG.info("[Consensus] Received sync response with " + resp.getNodesCount()
                + " blocks (" + stored + " new) from " + senderId);

        if (pendingPrepare != null) {
            PendingPrepare pp = pendingPrepare;
            pendingPrepare = null;
            onPrepare(pp.senderId(), pp.msg(), pp.view());
        }
    }

    public void shutdown() {
        cancelTimer();
        pacemaker.shutdownNow();
    }
}
