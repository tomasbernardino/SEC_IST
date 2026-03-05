package ist.group29.depchain.server.consensus;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import ist.group29.depchain.common.network.MessageListener;
import java.nio.ByteBuffer;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.network.ConsensusMessages;
import ist.group29.depchain.network.ConsensusMessages.ConsensusMessage;
import ist.group29.depchain.network.ConsensusMessages.VoteMessage;
import ist.group29.depchain.network.ConsensusMessages.NewViewMessage;
import ist.group29.depchain.network.ConsensusMessages.PrepareMessage;
import ist.group29.depchain.network.ConsensusMessages.PreCommitMessage;
import ist.group29.depchain.network.ConsensusMessages.CommitMessage;
import ist.group29.depchain.network.ConsensusMessages.DecideMessage;
import ist.group29.depchain.common.crypto.CryptoUtils;

import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;

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
    // HMAC session key for signing vote partial signatures. Null until APL
    // handshake.
    private volatile SecretKey signingKey;

    // Algorithm 2 bookkeeping

    private volatile int curView = 1;
    private QuorumCertificate lockedQC = QuorumCertificate.genesisQC();
    private QuorumCertificate prepareQC = QuorumCertificate.genesisQC();

    // The Vote Accumulator is the "waiting room" for votes that the leader uses to
    // reach a quorum.
    private final Map<Integer, Map<String, List<VoteMessage>>> voteAccumulator = new ConcurrentHashMap<>();

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

    // Constructor

    /**
     * Create a new Consensus instance.
     *
     * @param selfId         this node's ID string
     * @param allNodeIds     all participating node IDs - sorted to define leader()
     * @param linkManager    to send/broadcast messages
     * @param decideListener callback invoked on each DECIDE (upcall to Service)
     */
    public Consensus(String selfId, List<String> allNodeIds,
            LinkManager linkManager, DecideListener decideListener) {
        this.selfId = selfId;
        this.sortedNodeIds = new ArrayList<>(allNodeIds);
        Collections.sort(this.sortedNodeIds);
        this.n = allNodeIds.size();
        this.f = (n - 1) / 3;
        this.quorum = n - f;
        this.linkManager = linkManager;
        this.decideListener = decideListener;
    }

    /**
     * Provide the HMAC signing key once the APL session is established.
     * This key is used to produce the partial signatures in VoteMessage.
     */
    public void setSigningKey(SecretKey key) {
        this.signingKey = key;
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

        if (isLeader(curView)) {
            // Inject a synthetic NEW-VIEW from ourselves into the accumulator
            // (we count our own contribution so we only need n-f-1 from peers)
            storeNewView(selfId, curView - 1, NewViewMessage.newBuilder()
                    .setPrepareQc(prepareQC.getProto())
                    .build());
            this.currentProposal = null; // will be set when we build the leaf
        }
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

            // Silently ignore messages from a stale view due to the safety rule: never act on old views
            if (msgView < curView - 1) {
                LOG.fine("[Consensus] Ignoring stale message from view " + msgView);
                return;
            }

            switch (msg.getTypeCase()) {
                case NEW_VIEW -> onNewView(senderId, msg.getNewView(), msgView + 1);
                case PREPARE -> onPrepare(senderId, msg.getPrepare(), msgView);
                case VOTE -> onVote(senderId, msg.getVote(), msgView);
                case PRE_COMMIT -> onPreCommit(senderId, msg.getPreCommit(), msgView);
                case COMMIT -> onCommit(senderId, msg.getCommit(), msgView);
                case DECIDE -> onDecide(senderId, msg.getDecide(), msgView);
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
            QuorumCertificate qc = new QuorumCertificate(nv.getPrepareQc());
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
        currentProposal = parentNode.createChild(command, targetView);
        LOG.info("[Consensus] Leader view " + targetView + " - proposing: " + currentProposal);

        // broadcast Msg(prepare, curProposal, highQC)
        ConsensusMessage prepare = ConsensusMessage.newBuilder()
                .setSenderId(selfId)
                .setViewNumber(targetView)
                .setPrepare(PrepareMessage.newBuilder()
                        .setNode(currentProposal.getProto())
                        .setHighQc(highQC.getProto())
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
        if (view != curView)
            return;
        if (!senderId.equals(leader(view)) && !senderId.equals(selfId))
            return;

        HotStuffNode proposedNode = new HotStuffNode(msg.getNode());
        QuorumCertificate justify = new QuorumCertificate(msg.getHighQc());

        // Reject if the proposal is not safe (safety + liveness check)
        if (!safeNode(proposedNode, justify)) {
            LOG.warning("[Consensus] PREPARE rejected by safeNode in view " + view);
            return;
        }

        currentProposal = proposedNode;
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
        voteAccumulator
                .computeIfAbsent(view, v -> new ConcurrentHashMap<>())
                .computeIfAbsent(phase, p -> new ArrayList<>())
                .add(vote);

        List<VoteMessage> votes = voteAccumulator.get(view).get(phase);
        if (votes.size() < quorum)
            return; // not enough yet

        LOG.info("[Consensus] Leader formed QC for phase=" + phase + " view=" + view);
        QuorumCertificate qc = buildQC(phase, view, votes);

        switch (phase) {
            case QuorumCertificate.PREPARE -> {
                prepareQC = qc;
                ConsensusMessage preCommit = ConsensusMessage.newBuilder()
                        .setSenderId(selfId).setViewNumber(view)
                        .setPreCommit(PreCommitMessage.newBuilder()
                                .setPrepareQc(qc.getProto()).build())
                        .build();
                linkManager.broadcast(preCommit.toByteArray());
                onPreCommit(selfId, preCommit.getPreCommit(), view);
            }
            case QuorumCertificate.PRE_COMMIT -> {
                ConsensusMessage commit = ConsensusMessage.newBuilder()
                        .setSenderId(selfId).setViewNumber(view)
                        .setCommit(CommitMessage.newBuilder()
                                .setPrecommitQc(qc.getProto()).build())
                        .build();
                linkManager.broadcast(commit.toByteArray());
                onCommit(selfId, commit.getCommit(), view);
            }
            case QuorumCertificate.COMMIT -> {
                ConsensusMessage decide = ConsensusMessage.newBuilder()
                        .setSenderId(selfId).setViewNumber(view)
                        .setDecide(DecideMessage.newBuilder()
                                .setCommitQc(qc.getProto()).build())
                        .build();
                linkManager.broadcast(decide.toByteArray());
                onDecide(selfId, decide.getDecide(), view);
            }
        }
    }

    /**
     * Handle an incoming PRE-COMMIT message.
     *
     * Replica checks the prepareQC embedded in the PRE-COMMIT, updates its
     * local prepareQC, and casts a pre-commit vote.
     */
    private synchronized void onPreCommit(String senderId, PreCommitMessage msg, int view) {
        if (view != curView)
            return;
        if (!senderId.equals(leader(view)) && !senderId.equals(selfId))
            return;
        if (currentProposal == null)
            return;

        QuorumCertificate qc = new QuorumCertificate(msg.getPrepareQc());
        if (!qc.isValid(quorum)) {
            LOG.warning("[Consensus] PRE-COMMIT has invalid QC in view " + view);
            return;
        }

        prepareQC = qc;
        LOG.info("[Consensus] Accepted PRE-COMMIT in view " + view);
        sendVote(QuorumCertificate.PRE_COMMIT, view, currentProposal.getNodeHash());
    }

    /**
     * Handle an incoming COMMIT message.
     *
     * The replica sets lockedQC <= m.justify (the precommitQC).
     * This lock is the core safety mechanism: once locked on a branch,
     * a replica will only vote for a conflicting branch if a fresher QC
     * justifies it (the liveness rule in safeNode).
     */
    private synchronized void onCommit(String senderId, CommitMessage msg, int view) {
        if (view != curView)
            return;
        if (!senderId.equals(leader(view)) && !senderId.equals(selfId))
            return;
        if (currentProposal == null)
            return;

        QuorumCertificate qc = new QuorumCertificate(msg.getPrecommitQc());
        if (!qc.isValid(quorum)) {
            LOG.warning("[Consensus] COMMIT has invalid QC in view " + view);
            return;
        }

        lockedQC = qc;
        LOG.info("[Consensus] Locked on view " + view + " - lockedQC=" + lockedQC);
        sendVote(QuorumCertificate.COMMIT, view, currentProposal.getNodeHash());
    }

    /**
     * Handle an incoming DECIDE message.
     *
     * The commitQC proves that n-f replicas voted commit on this branch - it is
     * now irrevocably decided. Execute the command via the DecideListener upcall
     * and advance to the next view.
     */
    private synchronized void onDecide(String senderId, DecideMessage msg, int view) {
        if (view != curView)
            return;
        if (!senderId.equals(leader(view)) && !senderId.equals(selfId))
            return;
        if (currentProposal == null)
            return;

        if (!new QuorumCertificate(msg.getCommitQc()).isValid(quorum)) {
            LOG.warning("[Consensus] DECIDE has invalid QC in view " + view);
            return;
        }

        LOG.info("[Consensus] DECIDED view " + view + " - command: " + currentProposal.getCommand());

        // Cancel the view timeout - succeeded
        cancelTimer();

        // Upcall to Service
        decideListener.onDecide(currentProposal.getCommand(), view);

        // Advance to the next view
        advanceView();
    }

    // SafeNode predicate

    /**
     * A replica votes on a PREPARE proposal only if at least ONE of the
     * following rules holds:
     * Safety rule: The proposed node extends from the
     * locked node. This ensures that if a branch was committed in a
     * previous view, no conflicting branch can ever be voted on again.
     * Liveness rule: The justification QC has a higher
     * view than the current lockedQC. This allows progress even when a
     * replica is locked on a stale branch that no longer has quorum support,
     * preventing deadlock while maintaining safety through the three-phase
     * structure.
     *
     * @param node the proposed node from a PREPARE message
     * @param qc   the justification QC carried in the PREPARE message (m.justify)
     * @return true iff the replica should vote for this proposal
     */
    private boolean safeNode(HotStuffNode node, QuorumCertificate qc) {
        // Reconstruct the locked node from the lockedQC node_hash.
        // In Step 3, we compare only node hashes (no full tree store).
        // A node is considered to extend from lockedQC.node if its parent_hash
        // matches the lockedQC's node_hash, or if the lockedQC is the genesis QC.
        boolean isGenesis = Arrays.equals(lockedQC.getNodeHash(), new byte[32]);

        boolean safetyRule = isGenesis
                || Arrays.equals(
                        node.getProto().getParentHash().toByteArray(),
                        lockedQC.getNodeHash());

        boolean livenessRule = qc.getViewNumber() > lockedQC.getViewNumber();

        LOG.fine("[Consensus] safeNode: safety=" + safetyRule + " liveness=" + livenessRule);
        return safetyRule || livenessRule;
    }

    // Pacemaker / view-change

    /**
     * Advance to the next view - called either on DECIDE (normal) or by the
     * pacemaker timer (timeout / nextView interrupt from Algorithm 2 line 35).
     *
     * After the advance:
     * - curView is incremented
     * - NEW-VIEW is sent to the next leader
     * - The pacemaker timer is reset
     */
    private void advanceView() {
        curView++;
        currentProposal = null;
        LOG.info("[Consensus] Advancing to view " + curView + " - leader: " + leader(curView));
        sendNewView(curView);
        resetTimer();

        if (isLeader(curView)) {
            storeNewView(selfId, curView - 1, NewViewMessage.newBuilder()
                    .setPrepareQc(prepareQC.getProto())
                    .build());
        }
    }

    /**
     * Pacemaker timeout handler - "nextView interrupt"
     *
     * If no DECIDE is reached within the current timeout window, this fires
     * and triggers a view-change. The timeout doubles on every subsequent
     * failure (exponential backoff), ensuring that correct replicas eventually
     * have a long enough overlap to reach consensus.
     */
    private synchronized void onTimeout() {
        LOG.warning("[Consensus] Timeout in view " + curView + " - triggering view-change");
        timeoutMs *= 2; // exponential backoff
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

    // Leader selection - deterministic round-robin

    /**
     * Map a view number to its designated leader.
     *
     * Uses round-robin over the sorted list of node IDs:
     * leader(v) = sortedNodeIds[(v-1) % n]
     * This is consistent across all nodes because every node sorts the same
     * static membership list identically.
     *
     * @param view the view number (1-indexed)
     * @return the ID string of the designated leader
     */
    public String leader(int view) {
        return sortedNodeIds.get((view - 1) % n);
    }

    private boolean isLeader(int view) {
        return selfId.equals(leader(view));
    }

    // Helpers - message construction and sending

    // Send a NEW-VIEW to the leader of the given view
    private void sendNewView(int targetView) {
        String nextLeader = leader(targetView);
        ConsensusMessage msg = ConsensusMessage.newBuilder()
                .setSenderId(selfId)
                .setViewNumber(targetView - 1) // NEW-VIEW carries the previous view number
                .setNewView(NewViewMessage.newBuilder()
                        .setPrepareQc(prepareQC.getProto())
                        .build())
                .build();
        if (nextLeader.equals(selfId)) {
            // Deliver to self - the onNewView handler will pick it up
            storeNewView(selfId, targetView, msg.getNewView());
        } else {
            linkManager.send(nextLeader, msg.toByteArray());
        }
    }

    /**
     * Build and send a vote to the leader for the given phase.
     *
     * The partial_sig field is an HMAC over (phase || view || nodeHash)
     * using the APL session key - the Step 3 substitute for the threshold
     * partial signature tsign_r(m.type, m.viewNumber, m.node) from
     * Algorithm 1 line 9 of the paper.
     */
    private void sendVote(String phase, int view, byte[] nodeHash) {
        byte[] partialSig = new byte[0];
        if (signingKey != null) {
            try {
                partialSig = CryptoUtils.hmac(
                        signingKey,
                        phase.getBytes(StandardCharsets.UTF_8),
                        ByteBuffer.allocate(4).putInt(view).array(),
                        nodeHash);
            } catch (GeneralSecurityException e) {
                LOG.log(Level.SEVERE, "Failed to sign vote", e);
            }
        }

        ConsensusMessage msg = ConsensusMessage.newBuilder()
                .setSenderId(selfId)
                .setViewNumber(view)
                .setVote(VoteMessage.newBuilder()
                        .setPhase(phase)
                        .setViewNumber(view)
                        .setNodeHash(ByteString.copyFrom(nodeHash))
                        .setPartialSig(ByteString.copyFrom(partialSig))
                        .setSenderId(selfId)
                        .build())
                .build();

        String currentLeader = leader(view);
        if (currentLeader.equals(selfId)) {
            // Deliver vote to ourselves synchronously (leader is also a replica)
            onVote(selfId, msg.getVote(), view);
        } else {
            linkManager.send(currentLeader, msg.toByteArray());
        }
    }

    /**
     * Form a QC from a list of votes - corresponds to the paper's QC(V) utility.
     *
     * In the paper: qc.sig = tcombine(type,view,node, {partialSig | m ∈ V})
     * In Step 3: we simply collect the individual HMAC partial signatures into the
     * repeated bytes signatures field of the QC proto.
     */
    private QuorumCertificate buildQC(String phase, int view, List<VoteMessage> votes) {
        List<byte[]> sigs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        byte[] nodeHash = new byte[0];
        for (VoteMessage v : votes) {
            sigs.add(v.getPartialSig().toByteArray());
            ids.add(v.getSenderId().isEmpty() ? "unknown" : v.getSenderId());
            if (nodeHash.length == 0)
                nodeHash = v.getNodeHash().toByteArray();
        }
        return QuorumCertificate.create(phase, view, nodeHash, sigs, ids);
    }

    /** Store a NEW-VIEW message in the per-view accumulator. */
    private void storeNewView(String senderId, int targetView, NewViewMessage msg) {
        newViewAccumulator
                .computeIfAbsent(targetView, v -> new ConcurrentHashMap<>())
                .put(senderId, msg);
    }

    /** Gracefully shut down the pacemaker thread pool. */
    public void shutdown() {
        cancelTimer();
        pacemaker.shutdownNow();
    }
}
