package ist.group29.depchain.server.consensus;

import com.google.protobuf.ByteString;
import ist.group29.depchain.network.ConsensusMessages;
import ist.group29.depchain.server.crypto.CryptoManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Java wrapper around the Protobuf ConsensusMessages.QuorumCertificate.
 */
public class QuorumCertificate {

    public static final String PREPARE = "prepare";
    public static final String PRE_COMMIT = "pre-commit";
    public static final String COMMIT = "commit";

    /** Genesis QC representing the ⊥ (bottom) initial state. */
    private static final QuorumCertificate GENESIS_QC;

    static {
        GENESIS_QC = new QuorumCertificate(
                ConsensusMessages.QuorumCertificate.newBuilder()
                        .setType(PREPARE)
                        .setViewNumber(0)
                        .setNodeHash(ByteString.copyFrom(HotStuffNode.genesis().getNodeHash()))
                        .build());
    }

    private final ConsensusMessages.QuorumCertificate proto;

    public QuorumCertificate(ConsensusMessages.QuorumCertificate proto) {
        this.proto = proto;
    }

    /**
     * Construct a QC from its component parts (used by the leader after
     * accumulating (n-f) shares and generating the combined signature).
     */
    public static QuorumCertificate create(String type, int viewNumber, byte[] nodeHash, byte[] thresholdSignature) {
        ConsensusMessages.QuorumCertificate.Builder b = ConsensusMessages.QuorumCertificate.newBuilder()
                .setType(type)
                .setViewNumber(viewNumber)
                .setNodeHash(ByteString.copyFrom(nodeHash));

        if (thresholdSignature != null) {
            b.setThresholdSignature(ByteString.copyFrom(thresholdSignature));
        }

        return new QuorumCertificate(b.build());
    }


    public static QuorumCertificate genesisQC() {
        return GENESIS_QC;
    }

    /**
     * Reconstruct the byte array that was signed.
     */
    public byte[] getMessageToSign() {
        byte[] phaseBytes = proto.getType().getBytes(StandardCharsets.UTF_8);
        byte[] viewBytes = ByteBuffer.allocate(4).putInt(proto.getViewNumber()).array();
        byte[] nodeHash = proto.getNodeHash().toByteArray();

        byte[] msg = new byte[phaseBytes.length + viewBytes.length + nodeHash.length];
        System.arraycopy(phaseBytes, 0, msg, 0, phaseBytes.length);
        System.arraycopy(viewBytes, 0, msg, phaseBytes.length, viewBytes.length);
        System.arraycopy(nodeHash, 0, msg, phaseBytes.length + viewBytes.length, nodeHash.length);
        return msg;
    }

    /**
     * Check validity of this QC using ThresholdSignatures.
     */
    public boolean isValid(CryptoManager cryptoManager) {
        if (proto.getThresholdSignature().isEmpty())
            return false;

        // Genesis QC is always valid
        if (proto.getViewNumber() == 0)
            return true;

        try {
            return cryptoManager.verifyThresholdSignature(
                    proto.getThresholdSignature().toByteArray(),
                    getMessageToSign());
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }


    public String getType() {
        return proto.getType();
    }

    public int getViewNumber() {
        return proto.getViewNumber();
    }

    public byte[] getNodeHash() {
        return proto.getNodeHash().toByteArray();
    }

    public ConsensusMessages.QuorumCertificate getProto() {
        return proto;
    }

    @Override
    public String toString() {
        return "QC{type=" + proto.getType()
                + ", view=" + proto.getViewNumber()
                + ", hasSig=" + !proto.getThresholdSignature().isEmpty() + "}";
    }
}
