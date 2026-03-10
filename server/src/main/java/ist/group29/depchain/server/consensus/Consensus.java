package ist.group29.depchain.server.consensus;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import ist.group29.depchain.common.network.MessageListener;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.network.ConsensusMessages;
import ist.group29.depchain.network.ConsensusMessages.ConsensusMessage;
import ist.group29.depchain.network.ConsensusMessages.VoteMessage;
import ist.group29.depchain.network.ConsensusMessages.NewViewMessage;
import ist.group29.depchain.network.ConsensusMessages.PrepareMessage;
import ist.group29.depchain.network.ConsensusMessages.PreCommitMessage;
import ist.group29.depchain.network.ConsensusMessages.CommitMessage;
import ist.group29.depchain.network.ConsensusMessages.DecideMessage;
import ist.group29.depchain.network.ConsensusMessages.SyncRequest;
import ist.group29.depchain.network.ConsensusMessages.SyncResponse;
import ist.group29.depchain.server.crypto.CryptoManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Basic HotStuff consensus engine - Algorithm 2 of the paper.
 *
 * Implements MessageListener so it plugs directly into the
 * LinkManager receive callback without any coupling between the
 * network layer and the consensus protocol.
 *
 * Protocol Overview (paper Section 4.1)
 * Each view proceeds through four phases. The leader{(view)} node
 * drives the protocol; all other nodes are replicas:
 * 
 * PREPARE: Leader collects n-f NEW-VIEW messages, picks the
 * highest prepareQC (highQC), extends its node, broadcasts PREPARE.
 * PRE-COMMIT: Leader collects n-f PREPARE votes, forms prepareQC,
 * broadcasts PRE-COMMIT. Replicas update their prepareQC.
 * COMMIT: Leader collects n-f PRE-COMMIT votes, forms precommitQC,
 * broadcasts COMMIT. Replicas set lockedQC <= precommitQC.
 * DECIDE: Leader collects n-f COMMIT votes, forms commitQC,
 * broadcasts DECIDE. All replicas execute the command (upcall to Service).
 *
 * Key Bookkeeping Variables
 * 
 * curView - monotonically increasing view counter.
 * lockedQC - the highest QC for which this replica voted commit.
 * Guards safety: a replica only votes on a branch extending from
 * lockedQC.node, or on a fresher QC (liveness rule).
 * prepareQC - the highest QC for which this replica voted pre-commit.
 * Sent in NEW-VIEW so the next leader can reconstruct highQC.
 * 
 *
 * Step 3 Simplifications
 * 
 * Signatures are HMACs computed over the APL session key, not real
 * threshold partial signatures (Step 5 TODO).
 * The pacemaker fires a view-change timeout but crash/Byzantine fault
 * detection is added in Steps 4/5.
 */
public class Consensus implements MessageListener {

    private static final Logger LOG = Logger.getLogger(Consensus.class.getName());

    // Constants

    // Initial pacemaker timeout in milliseconds. Doubled on each view-change.
    private static final long INITIAL_TIMEOUT_MS = 4_000;

    // Configuration (set once at construction)

    private final String selfId;
    private final List<String> sortedNodeIds; // deterministic order for leader()
    private final int n; // total replicas
    private final int f; // max faulty: f = (n-1)/3
    private final int quorum; // n - f
    private final LinkManager linkManager;
    private final DecideListener decideListener;
    private CryptoManager cryptoManager;

    // Local block store: maps nodeHash -> HotStuffNode for recursive extendsFrom
    // chain traversal. Required by the safeNode predicate (Algorithm 1, line 26)
    // to walk backwards through the hash chain when verifying ancestry under BFT.
    // NOTE: The Service's blockchain (List<String>) cannot be used here because it
    // only stores decided command strings, not HotStuffNode objects, and
    // extendsFrom
    // needs access to in-flight proposals that haven't been decided yet.
    private final Map<ByteString, HotStuffNode> blockStore = new ConcurrentHashMap<>();

    // Algorithm 2 bookkeeping

    private volatile int curView = 1;
    private QuorumCertificate lockedQC = QuorumCertificate.genesisQC();
    private QuorumCertificate prepareQC = QuorumCertificate.genesisQC();

    // Composite key for the vote accumulator: groups votes by (view, phase+round).
    private record PhaseRound(int view, String phase) {
    }

    // The Vote Accumulator is the "waiting room" for votes that the leader uses to
    // reach a quorum. Keyed by: PhaseRound(view, phase+round) -> senderId ->
    // VoteMessage.
    // Using senderId as key prevents quorum stuffing (duplicate votes from same
    // sender).
    private final Map<PhaseRound, Map<String, VoteMessage>> voteAccumulator = new ConcurrentHashMap<>();

    // The leader collects n-f new views before starting the PREPARE phase.
    private final Map<Integer, Map<String, NewViewMessage>> newViewAccumulator = new ConcurrentHashMap<>();

    // Block we are currently trying to commit, volatile tells the compiler to
    // always read the value
    // from memory instead of using a cached value to ensure visibility of changes
    // across threads
    private volatile HotStuffNode currentProposal = null;

    // Pacemaker - responsible for view changes
    private final ScheduledExecutorService pacemaker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Pacemaker");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> viewTimer = null;
    private long timeoutMs = INITIAL_TIMEOUT_MS;
    // --- State variables for anti-equivocation ---
    private int lastVotedPrepareView = 0;

    // --- State for incremental branch execution (paper Section 4.2) ---
    // Tracks the highest node whose branch has been fully executed.
    private ByteString lastExecutedNodeHash = ByteString.copyFrom(HotStuffNode.genesis().getNodeHash());

    // --- State for branch catch-up (paper Section 4.2) ---
    // Buffers a PREPARE message while waiting for a sync response.
    private record PendingPrepare(String senderId, PrepareMessage msg, int view) {
    }

    private volatile PendingPrepare pendingPrepare = null;

    // Constructor

    /**
     * Create a new Consensus instance.
     *
     * @param selfId         this node's ID string
     * @param allNodeIds     all participating node IDs - sorted to define leader()
     * @param linkManager    to send/broadcast messages
     * @param decideListener callback invoked on each DECIDE (upcall to Service)
     * @param keysDir        the directory where cryptographic keys are stored
     */
    public Consensus(String selfId, List<String> allNodeIds,
            LinkManager linkManager, DecideListener decideListener, String keysDir) {
        this(selfId, allNodeIds, linkManager, decideListener, createDefaultCrypto(selfId, keysDir));
    }

    public Consensus(String selfId, List<String> allNodeIds,
            LinkManager linkManager, DecideListener decideListener, CryptoManager cryptoManager) {
        this.selfId = selfId;
        this.sortedNodeIds = new ArrayList<>(allNodeIds);
        Collections.sort(this.sortedNodeIds);
        this.n = allNodeIds.size();
        this.f = (n - 1) / 3;
        this.quorum = n - f;
        this.linkManager = linkManager;
        this.decideListener = decideListener;
        this.cryptoManager = cryptoManager;

        // Seed the block store with the genesis node so extendsFrom can always
        // trace the hash chain back to the root of the command tree.
        HotStuffNode genesis = HotStuffNode.genesis();
        blockStore.put(ByteString.copyFrom(genesis.getNodeHash()), genesis);
    }

    private static CryptoManager createDefaultCrypto(String selfId, String keysDir) {
        try {
            return new CryptoManager(selfId, keysDir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public CryptoManager getCryptoManager() {
        return cryptoManager;
    }

    // Entry point

    /**
     * Start consensus for view 1.
     *
     * All nodes send a NEW-VIEW to the view-1 leader, carrying genesisQC
     * as their prepareQC. The leader will collect n-f and start the PREPARE phase.
     *
     * @param initialCommand the command to propose (only meaningful for the leader;
     *                       non-leaders pass null - they receive the command
     *                       in the leader's PREPARE broadcast)
     */
    public synchronized void start(String initialCommand) {
        LOG.info("[Consensus] Starting view " + curView + " - leader is " + leader(curView));
        sendNewView(curView);
        resetTimer();
    }

    // MessageListener - entry point from LinkManager

    /**
     * Called by LinkManager when an authenticated, deduplicated payload
     * arrives. Parses the ConsensusMessage and dispatches to the
     * appropriate handler.
     *
     * This method is not synchronized: parsing and dispatch are fast.
     * The individual handlers synchronize on this only where they mutate
     * shared state.
     */
    @Override
    public void onMessage(String senderId, byte[] raw) {
        try {
            ConsensusMessage msg = ConsensusMessage.parseFrom(raw);
            int msgView = msg.getViewNumber();

            // Silently ignore messages from a stale view due to the safety rule: never act
            // on old views
            if (msgView < curView - 1) {
                LOG.fine("[Consensus] Ignoring stale message from view " + msgView);
                return;
            }

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
        } catch (InvalidProtocolBufferException e) {
            LOG.log(Level.WARNING, "[Consensus] Failed to parse ConsensusMessage", e);
        }
    }

    // Phase handlers

    /**
     * Handle an incoming NEW-VIEW message.
     *
     * The leader waits for (n-f) NEW-VIEW messages from view (curView-1),
     * picks highQC = max{m.justify.viewNumber} among them, extends its head,
     * and broadcasts PREPARE.
     *
     * @param senderId   the ID of the replica that sent this message
     * @param msg        the deserialized NewViewMessage
     * @param targetView the view this message is voting to enter (= msg.viewNumber
     *                   + 1)
     */
    private synchronized void onNewView(String senderId, NewViewMessage msg, int targetView) {
        if (!isLeader(targetView))
            return; // only the leader processes NEW-VIEW
        storeNewView(senderId, targetView, msg);

        Map<String, NewViewMessage> received = newViewAccumulator.get(targetView);
        if (received == null || received.size() < quorum)
            return;

        // highQC = argmax{m.justify.viewNumber} among n-f NEW-VIEW messages
        QuorumCertificate highQC = QuorumCertificate.genesisQC();
        for (NewViewMessage nv : received.values()) {
            QuorumCertificate qc = new QuorumCertificate(nv.getJustify());
            if (qc.getViewNumber() > highQC.getViewNumber()) {
                highQC = qc;
            }
        }

        // For Step 3, the command is hardcoded as a placeholder; in Step 6
        // this will be taken from the pending client-request queue.
        HotStuffNode parentNode = new HotStuffNode(
                ConsensusMessages.HotStuffNode.newBuilder()
                        .setNodeHash(ByteString.copyFrom(highQC.getNodeHash()))
                        .build());
        String command = "cmd-view-" + targetView; // placeholder for Step 3
        currentProposal = parentNode.createLeaf(command, targetView);
        blockStore.put(ByteString.copyFrom(currentProposal.getNodeHash()), currentProposal);
        LOG.info("[Consensus] Leader view " + targetView + " - proposing: " + currentProposal);

        // broadcast Msg(prepare, curProposal, highQC)
        ConsensusMessage prepare = ConsensusMessage.newBuilder()
                .setViewNumber(targetView)
                .setPrepare(PrepareMessage.newBuilder()
                        .setNode(currentProposal.getProto())
                        .setJustify(highQC.getProto())
                        .build())
                .build();

        linkManager.broadcast(prepare.toByteArray());
        // Also deliver to ourselves (broadcast does not include self in LinkManager)
        onPrepare(selfId, prepare.getPrepare(), targetView);
    }

    /**
     * Handle an incoming PREPARE message.
     *
     * A replica accepts the proposal iff it passes the safeNode check,
     * then sends a PREPARE vote to the leader.
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

        // Anti-Equivocation Guard (Step 5 fix): A replica must never accept two
        // different proposals in the same view. If we already voted in this view,
        // reject subsequent proposals.
        if (view <= lastVotedPrepareView) {
            System.out.println("DEBUG onPrepare: Equivocation view <= lastVotedPrepareView (" + view + " <= "
                    + lastVotedPrepareView + ")");
            LOG.warning("[Consensus] Equivocation detected! Leader sent multiple PREPAREs for view " + view);
            return;
        }

        if (!Arrays.equals(proposedNode.getProto().getParentHash().toByteArray(),
                justify.getNodeHash())) {
            LOG.warning("[Consensus] PREPARE rejected: node does not extend from justify QC in view " + view);
            return;
        }

        // Branch catch-up (paper Section 4.2): if the justify QC references
        // a block not in our blockStore, request it before proceeding.
        // Without the missing block(s), safeNode can't reliably verify ancestry
        // and incremental execution can't walk the full chain.
        if (!blockStore.containsKey(ByteString.copyFrom(justify.getNodeHash()))
                && !Arrays.equals(justify.getNodeHash(), HotStuffNode.genesis().getNodeHash())) {
            LOG.info("[Consensus] Missing blocks — requesting sync from " + senderId
                    + " for hash " + ist.group29.depchain.common.crypto.CryptoUtils.bytesToHex(
                            justify.getNodeHash(), 4));
            requestSync(senderId, justify.getNodeHash());
            pendingPrepare = new PendingPrepare(senderId, msg, view);
            return;
        }

        // Paper (Algorithm 1, line 26): "... AND safeNode(m.node, m.justify)"
        if (!safeNode(proposedNode, justify)) {
            LOG.warning("[Consensus] PREPARE rejected by safeNode in view " + view);
            return;
        }

        // Store the accepted block in our local block store for future
        // extendsFrom chain traversals.
        blockStore.put(ByteString.copyFrom(proposedNode.getNodeHash()), proposedNode);
        currentProposal = proposedNode;
        lastVotedPrepareView = view;
        LOG.info("[Consensus] Accepted PREPARE in view " + view + " for: " + proposedNode);

        // Send vote to leader
        sendVote(QuorumCertificate.PREPARE, view, proposedNode.getNodeHash());
    }

    /**
     * Handle a VOTE message (covers prepare, pre-commit, and commit votes).
     *
     * When n-f votes for the same phase and view accumulate, the leader:
     * - PREPARE votes → form prepareQC, broadcast PRE-COMMIT
     * - PRE-COMMIT votes → form precommitQC, broadcast COMMIT
     * - COMMIT votes → form commitQC, broadcast DECIDE
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
            return; // not enough yet

        try {
            // We just hit quorum, aggregate signatures and form QC
            LOG.info("[Consensus] Leader formed QC for phase=" + phase + " view=" + view);

            QuorumCertificate dummyQc = QuorumCertificate.create(phase, view, vote.getNodeHash().toByteArray(), null);
            byte[] dataToSign = dummyQc.getMessageToSign();

            List<byte[]> shares = new ArrayList<>();
            List<Integer> participants = new ArrayList<>();
            for (Map.Entry<String, VoteMessage> entry : votes.entrySet()) {
                shares.add(entry.getValue().getSignatureShare().toByteArray());
                participants.add(sortedNodeIds.indexOf(entry.getKey()));
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
                    linkManager.broadcast(preCommit.toByteArray());
                    onPreCommit(selfId, preCommit.getPreCommit(), view);
                }
                case QuorumCertificate.PRE_COMMIT -> {
                    ConsensusMessage commit = ConsensusMessage.newBuilder()
                            .setViewNumber(view)
                            .setCommit(CommitMessage.newBuilder()
                                    .setJustify(qc.getProto()).build())
                            .build();
                    linkManager.broadcast(commit.toByteArray());
                    onCommit(selfId, commit.getCommit(), view);
                }
                case QuorumCertificate.COMMIT -> {
                    ConsensusMessage decide = ConsensusMessage.newBuilder()
                            .setViewNumber(view)
                            .setDecide(DecideMessage.newBuilder()
                                    .setJustify(qc.getProto()).build())
                            .build();
                    linkManager.broadcast(decide.toByteArray());
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

        QuorumCertificate qc = new QuorumCertificate(msg.getJustify());
        if (!qc.isValid(cryptoManager)) {
            LOG.warning("[Consensus] PRE-COMMIT has invalid QC in view " + view);
            return;
        }
        // QC-Swap prevention: PRE-COMMIT must carry a PREPARE QC
        if (!QuorumCertificate.PREPARE.equals(qc.getType())) {
            LOG.warning("[Consensus] PRE-COMMIT rejected: expected PREPARE QC but got " + qc.getType());
            return;
        }

        prepareQC = qc;
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

        QuorumCertificate qc = new QuorumCertificate(msg.getJustify());
        if (!qc.isValid(cryptoManager)) {
            LOG.warning("[Consensus] COMMIT has invalid QC in view " + view);
            return;
        }
        // QC-Swap prevention: COMMIT must carry a PRE_COMMIT QC
        if (!QuorumCertificate.PRE_COMMIT.equals(qc.getType())) {
            LOG.warning("[Consensus] COMMIT rejected: expected PRE_COMMIT QC but got " + qc.getType());
            return;
        }

        lockedQC = qc;
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
        // QC-Swap prevention: DECIDE must carry a COMMIT QC
        if (!QuorumCertificate.COMMIT.equals(qc.getType())) {
            LOG.warning("[Consensus] DECIDE rejected: expected COMMIT QC but got " + qc.getType());
            return;
        }

        LOG.info("[Consensus] DECIDED view " + view + " - command: " + currentProposal.getCommand());
        cancelTimer();
        // Reset timeout to initial value on successful decide — the exponential
        // backoff from onTimeout should not permanently degrade latency after
        // the system recovers from transient faults.
        timeoutMs = INITIAL_TIMEOUT_MS;
        // Paper Section 4.2: "execute new commands through m.justify.node"
        // Walk the branch from the decided node back to the last-executed node
        // and execute all un-executed commands in forward order.
        executeCommittedBranch(currentProposal);
        advanceView();
    }

    /**
     * Paper: safeNode(node, qc) := (node extends from lockedQC.node) \/
     * (qc.viewNumber > lockedQC.viewNumber)
     *
     * Safety rule: the proposed node must be on the same branch as the locked
     * block.
     * Liveness rule: if the justify QC is fresher than lockedQC, accept anyway
     * (prevents deadlock when replicas are locked on stale branches).
     */
    private boolean safeNode(HotStuffNode node, QuorumCertificate qc) {
        // Safety rule: node extends from lockedQC.node (recursive chain walk)
        boolean safetyRule = extendsFrom(node, lockedQC.getNodeHash());
        // Liveness rule: qc.viewNumber > lockedQC.viewNumber
        boolean livenessRule = qc.getViewNumber() > lockedQC.getViewNumber();
        LOG.fine("[Consensus] safeNode: safety=" + safetyRule + " liveness=" + livenessRule);
        return safetyRule || livenessRule;
    }

    /**
     * Store a block in the local block store.
     * Used internally when accepting proposals, and exposed for testing.
     */
    public void storeBlock(HotStuffNode node) {
        blockStore.put(ByteString.copyFrom(node.getNodeHash()), node);
    }

    /**
     * Check whether {@code node} is a descendant of the block identified by
     * {@code ancestorHash} by walking the blockStore hash chain backwards.
     *
     * Special case: if ancestorHash is all-zeros (the genesis QC's nodeHash,
     * representing ⊥), returns true immediately since every block extends from
     * the initial empty state.
     *
     * @param node         the node to check
     * @param ancestorHash the hash of the ancestor block to look for
     * @return true if node is equal to, or a descendant of, the ancestor
     */
    public boolean extendsFrom(HotStuffNode node, byte[] ancestorHash) {
        // Everything extends from genesis, matching the paper's initial state.
        if (Arrays.equals(ancestorHash, HotStuffNode.genesis().getNodeHash())) {
            return true;
        }

        if (Arrays.equals(node.getNodeHash(), ancestorHash)) {
            return true;
        }

        // Walk backwards through the blockStore following parent_hash links.
        // We start from the node's parent hash since the node itself might not be in
        // the store yet!
        byte[] currentHash = node.getProto().getParentHash().toByteArray();
        for (int depth = 0; depth < 1000; depth++) { // depth limit prevents infinite loops
            if (Arrays.equals(currentHash, ancestorHash)) {
                return true;
            }

            HotStuffNode current = blockStore.get(ByteString.copyFrom(currentHash));
            if (current == null) {
                return false; // block not in store, ancestry not provable
            }

            currentHash = current.getProto().getParentHash().toByteArray();
        }

        return false;
    }

    private void advanceView() {
        curView++;
        currentProposal = null;
        LOG.info("[Consensus] Advancing to view " + curView + " - leader: " + leader(curView));

        // Clean up old accumulator entries to prevent unbounded memory growth.
        // Use a 5-view window as a safety margin for late-arriving messages.
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
        timeoutMs *= 2;
        advanceView();
    }

    private void resetTimer() {
        cancelTimer();
        viewTimer = pacemaker.schedule(this::onTimeout, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void cancelTimer() {
        if (viewTimer != null) {
            viewTimer.cancel(false);
            viewTimer = null;
        }
    }

    public String leader(int view) {
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
            storeNewView(selfId, targetView, msg.getNewView());
        } else {
            linkManager.send(nextLeader, msg.toByteArray());
        }
    }

    private synchronized void sendVote(String phase, int view, byte[] nodeHash) {
        LOG.info("[Consensus] Node " + selfId + " casting vote for phase=" + phase + " view=" + view);

        try {
            // HotStuff Step 5: Sign the QC we would form (hash + view + phase)
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
                linkManager.send(currentLeader, msg.toByteArray());
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to send vote", e);
        }
    }

    private void storeNewView(String senderId, int targetView, NewViewMessage msg) {
        newViewAccumulator
                .computeIfAbsent(targetView, v -> new ConcurrentHashMap<>())
                .put(senderId, msg);
    }

    // --- Incremental branch execution (paper Section 4.2) ---

    /**
     * Walk the committed branch from decidedNode back to lastExecutedNodeHash,
     * then execute all un-executed commands in forward order (root → leaf).
     *
     * The paper says: "a replica considers the proposal embodied in the commitQC
     * a committed decision, and executes the commands in the committed branch."
     * "additionally, in order to incrementally execute a committed log of commands,
     * the replica maintains the highest node whose branch has been executed."
     */
    private void executeCommittedBranch(HotStuffNode decidedNode) {
        List<HotStuffNode> toExecute = new ArrayList<>();
        HotStuffNode current = decidedNode;

        // Walk backwards collecting un-executed nodes
        int maxDepth = 1000; // safety limit, same as extendsFrom
        while (current != null && maxDepth-- > 0
                && !ByteString.copyFrom(current.getNodeHash()).equals(lastExecutedNodeHash)) {
            if (!current.getCommand().isEmpty()) { // skip genesis
                toExecute.add(current);
            }
            current = blockStore.get(current.getProto().getParentHash());
        }

        // Execute in forward order (root → leaf)
        Collections.reverse(toExecute);
        for (HotStuffNode node : toExecute) {
            decideListener.onDecide(node.getCommand(), node.getViewNumber());
        }

        lastExecutedNodeHash = ByteString.copyFrom(decidedNode.getNodeHash());
    }

    // --- Branch catch-up / state sync (paper Section 4.2) ---

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
        linkManager.send(peerId, msg.toByteArray());
    }

    /**
     * Handle an incoming SyncRequest: walk the blockStore from the requested
     * hash back to genesis and return all found blocks.
     */
    private synchronized void onSyncRequest(String senderId, SyncRequest req) {
        List<ConsensusMessages.HotStuffNode> nodes = new ArrayList<>();
        byte[] currentHash = req.getNodeHash().toByteArray();
        int maxDepth = 1000; // same safety limit as extendsFrom
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
        linkManager.send(senderId, resp.toByteArray());
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

        // Re-process the buffered PREPARE if present
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
