package ist.group29.depchain.server.consensus;

import java.util.List;

import com.google.protobuf.ByteString;

import ist.group29.depchain.network.ConsensusMessages;

/**
 * Thin Java wrapper around the Protobuf ConsensusMessages.QuorumCertificate.
 *
 * A Quorum Certificate (QC) is the central data structure in HotStuff.
 * The paper defines it as:
 * A collection of (n-f) votes over a tuple <type, viewNumber, node>.
 * The tcombine utility employs a threshold signature scheme to generate a
 * representation of (n-f) signed votes as a single authenticator.
 *
 * Step 3 approach (no threshold signatures yet):
 * Instead of a real threshold signature (tcombine/tverify), the QC stores
 * a list of individual HMAC partial signatures - one per voter - alongside
 * a parallel list of voter IDs. Validity is checked simply by counting:
 * if signatures.size() >= n - f, the QC is accepted.
 * This is intentionally simplified for Step 3's "no security guarantees" scope.
 *
 * Step 5 upgrade path:
 * Replace repeated bytes signatures with a single combined
 * threshold signature and replace isValid with a call to
 * tverify(type, view, nodeHash, combinedSig).
 */
public class QuorumCertificate {

    // Phase type constants - match the paper's phase names exactly
    public static final String PREPARE = "prepare";
    public static final String PRE_COMMIT = "pre-commit";
    public static final String COMMIT = "commit";

    /** Singleton genesis QC representing the ⊥ (bottom) initial state. */
    private static final QuorumCertificate GENESIS_QC;

    static {
        GENESIS_QC = new QuorumCertificate(
                ConsensusMessages.QuorumCertificate.newBuilder()
                        .setType(PREPARE)
                        .setViewNumber(0)
                        .setNodeHash(ByteString.copyFrom(new byte[32]))
                        .build());
    }

    private final ConsensusMessages.QuorumCertificate proto;

    /** Wrap an existing Protobuf QuorumCertificate. */
    public QuorumCertificate(ConsensusMessages.QuorumCertificate proto) {
        this.proto = proto;
    }

    /**
     * Construct a QC from its component parts (used by the leader after
     * accumulating enough votes to call the paper's QC(V) function).
     *
     * @param type       phase type constant (e.g. PREPARE)
     * @param viewNumber the current view
     * @param nodeHash   hash of the proposed node being certified
     * @param sigs       list of individual HMAC partial signatures (Step 3)
     * @param voterIds   parallel list of IDs of the signing replicas
     */
    public static QuorumCertificate create(String type, int viewNumber, byte[] nodeHash,
            List<byte[]> sigs, List<String> voterIds) {
        ConsensusMessages.QuorumCertificate.Builder b = ConsensusMessages.QuorumCertificate.newBuilder()
                .setType(type)
                .setViewNumber(viewNumber)
                .setNodeHash(ByteString.copyFrom(nodeHash));
        for (byte[] sig : sigs)
            b.addSignatures(ByteString.copyFrom(sig));
        for (String id : voterIds)
            b.addVoterIds(id);
        return new QuorumCertificate(b.build());
    }

    /**
     * The genesis QC representing the initial ⊥ state.
     *
     * In Algorithm 2, both lockedQC and prepareQC start as ⊥.
     * Using a well-defined genesis object instead of null avoids
     * NullPointerExceptions and simplifies the safeNode predicate:
     * any node extends from the genesis node's (zero) hash, so view 1 always
     * passes the safety rule.
     */
    public static QuorumCertificate genesisQC() {
        return GENESIS_QC;
    }

    /**
     * Check validity of this QC for a given quorum size.
     *
     * Step 3: A QC is valid iff it carries at least
     * quorumSize signatures. No cryptographic verification is
     * performed (added in Step 5).
     *
     * @param quorumSize the required quorum = n - f
     * @return true if this QC has at least quorumSize votes
     */
    public boolean isValid(int quorumSize) {
        return proto.getSignaturesCount() >= quorumSize;
    }

    /** Phase type ("prepare", "pre-commit", or "commit"). */
    public String getType() {
        return proto.getType();
    }

    /** The view number in which this QC was formed. */
    public int getViewNumber() {
        return proto.getViewNumber();
    }

    /** Node hash that this QC certifies. */
    public byte[] getNodeHash() {
        return proto.getNodeHash().toByteArray();
    }

    /** Raw Protobuf message (for serialisation into ConsensusMessage). */
    public ConsensusMessages.QuorumCertificate getProto() {
        return proto;
    }

    @Override
    public String toString() {
        return "QC{type=" + proto.getType()
                + ", view=" + proto.getViewNumber()
                + ", votes=" + proto.getSignaturesCount() + "}";
    }
}
