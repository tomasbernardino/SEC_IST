package ist.group29.depchain.server.crypto;

import ist.group29.depchain.server.crypto.threshsig.GroupKey;
import ist.group29.depchain.server.crypto.threshsig.KeyShare;
import ist.group29.depchain.server.crypto.threshsig.SigShare;
import ist.group29.depchain.server.crypto.threshsig.Verifier;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.io.ObjectInputStream;

/**
 * Manages the threshold keys and operations for a specific node using the
 * threshsig library (RSA).
 */
public class CryptoManager {
    private final int n = 4;
    private final int t = 3;
    private final int myIndex;

    private final GroupKey groupKey;
    private final KeyShare privateShare;

    public CryptoManager(String nodeId, String keysDir) throws Exception {
        this.myIndex = Integer.parseInt(nodeId.split("-")[1]); // e.g. "node-0" -> 0

        File pubFile = new File(keysDir, "threshold_public.key");
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(pubFile))) {
            this.groupKey = (GroupKey) ois.readObject();
        }

        File shareFile = new File(keysDir, "node-" + myIndex + "-threshold.key");
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(shareFile))) {
            this.privateShare = (KeyShare) ois.readObject();
        }
    }

    public int getMyIndex() {
        return myIndex;
    }

    public byte[] getThresholdPublicKey() {
        // Return modulus or some identifier if needed, for now just use the object
        return groupKey.getModulus().toByteArray();
    }

    public GroupKey getGroupKey() {
        return groupKey;
    }

    /**
     * Compute a signature share for the given data.
     */
    public byte[] computeSignatureShare(byte[] data) {
        SigShare share = privateShare.sign(data);
        return share.getBytes();
    }

    /**
     * Aggregate k signature shares into a full signature.
     */
    public byte[] aggregateSignatureShares(byte[] data, List<byte[]> sharesData, List<Integer> participantIds)
            throws Exception {
        SigShare[] shares = new SigShare[sharesData.size()];
        for (int i = 0; i < sharesData.size(); i++) {
            shares[i] = new SigShare(participantIds.get(i), sharesData.get(i));
        }

        // In Shoup's RSA Threshold scheme, the final signature is aggregated
        // using Lagrange interpolation in the exponent. Although the library's
        // SigShare.verify() method contains this logic, it is designed to return
        // a boolean rather than the raw signature bytes.
        // We therefore use the extracted combineShares() method to produce the
        // aggregated RSA signature (x^d mod n) which can then be verified by
        // all replicas and clients using the group public key.

        return combineShares(shares);
    }

    private byte[] combineShares(SigShare[] S) {
        int k = groupKey.getK();
        BigInteger mod = groupKey.getModulus();
        BigInteger delta = privateShare.getDelta();

        BigInteger w = BigInteger.valueOf(1l);
        for (int i = 0; i < k; i++) {
            w = w.multiply(S[i].getSig().modPow(lambda(S[i].getId(), S, delta), mod));
        }
        return w.mod(mod).toByteArray();
    }

    private BigInteger lambda(int ik, SigShare[] S, BigInteger delta) {
        BigInteger value = delta;
        for (SigShare element : S) {
            if (element.getId() != ik) {
                value = value.multiply(BigInteger.valueOf(element.getId()));
            }
        }
        for (SigShare element : S) {
            if (element.getId() != ik) {
                value = value.divide(BigInteger.valueOf((element.getId() - ik)));
            }
        }
        return value;
    }

    public boolean verifyThresholdSignature(byte[] signature, byte[] data) {
        BigInteger sig = new BigInteger(signature);
        BigInteger mod = groupKey.getModulus();
        BigInteger x = (new BigInteger(data)).mod(mod);
        BigInteger delta = privateShare.getDelta();

        // Shoup verification: w^e = x^(delta^2 * 4) mod n
        // Where w is the combined signature.

        BigInteger eprime = delta.multiply(delta).shiftLeft(2);
        BigInteger xeprime = x.modPow(eprime, mod);
        BigInteger we = sig.modPow(groupKey.getExponent(), mod);

        return (xeprime.compareTo(we) == 0);
    }
}
