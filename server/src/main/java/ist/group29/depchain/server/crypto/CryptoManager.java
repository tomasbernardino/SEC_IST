package ist.group29.depchain.server.crypto;

import com.weavechain.curve25519.EdwardsPoint;
import com.weavechain.curve25519.Scalar;
import com.weavechain.sig.ThresholdSigEd25519;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Manages the threshold keys and operations for a specific node.
 */
public class CryptoManager {
    private final int n = 4;
    private final int t = 3;
    private final int myIndex;

    private final byte[] thresholdPublicKey;
    private final Scalar privateShare;
    private final ThresholdSigEd25519 tsig;

    public CryptoManager(String nodeId, String keysDir) throws Exception {
        this.myIndex = Integer.parseInt(nodeId.split("-")[1]); // e.g. "node-0" -> 0
        this.tsig = new ThresholdSigEd25519(t, n);

        File pubFile = new File(keysDir, "threshold_public.key");
        this.thresholdPublicKey = readAllBytes(pubFile);

        File shareFile = new File(keysDir, "node-" + myIndex + "-threshold.key");
        this.privateShare = Scalar.fromBits(readAllBytes(shareFile));
    }

    private byte[] readAllBytes(File file) throws Exception {
        try (InputStream is = new FileInputStream(file);
                ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        }
    }

    public int getMyIndex() {
        return myIndex;
    }

    public byte[] getThresholdPublicKey() {
        return thresholdPublicKey;
    }

    // --- Round 1 (Replica) ---

    /**
     * Compute the R_i point (represented as a byte string in the proto) and the
     * internal scalar rs.
     * The node sends the EdwardsPoint to the leader, but MUST remember the Scalar
     * rs for round 2.
     */
    public Scalar computeRs(String toSign) throws Exception {
        return tsig.computeRi(privateShare, toSign);
    }

    public EdwardsPoint computeRiPoint(Scalar rs) {
        return ThresholdSigEd25519.mulBasepoint(rs);
    }

    // --- Round 2 (Replica) ---

    /**
     * Compute the scalar signature share given the leader's challenge k,
     * the node's remembered rs from round 1, and the set of participating nodes.
     * Note: the lib's internal computeSignature uses (index + 1).
     */
    public Scalar computeSignatureShare(Scalar rs, Scalar k, Set<Integer> participatingNodes) {
        return tsig.computeSignature(myIndex + 1, privateShare, rs, k, participatingNodes);
    }

    // --- Leader Aggregation ---

    public EdwardsPoint aggregateRi(List<EdwardsPoint> points) {
        return tsig.computeR(points);
    }

    public Scalar computeChallengeK(EdwardsPoint aggregatedR, String toSign) throws Exception {
        return tsig.computeK(thresholdPublicKey, aggregatedR, toSign);
    }

    public byte[] aggregateSignatureShares(EdwardsPoint aggregatedR, List<Scalar> shares) throws Exception {
        return tsig.computeSignature(aggregatedR, shares);
    }

    // --- Verification ---

    public boolean verifyThresholdSignature(byte[] signature, byte[] message) throws Exception {
        return ThresholdSigEd25519.verify(thresholdPublicKey, signature, message);
    }
}
