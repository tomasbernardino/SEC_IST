package ist.group29.depchain.server.crypto;

import com.weavechain.sig.ThresholdSigEd25519;
import com.weavechain.sig.ThresholdSigEd25519Params;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Utility to generate and distribute Threshold Signature shares for DepChain.
 * It acts as a trusted dealer, splitting an Ed25519 private key into n shares,
 * requiring any t shares to reconstruct a valid signature.
 */
public class ThresholdPKISetup {

    public static void main(String[] args) throws Exception {
        int n = 4;
        int t = 3; // Quorum is n - f for n=4 -> 3

        System.out.println("[Threshold PKI] Generating keys for n=" + n + ", t=" + t);

        ThresholdSigEd25519 tsig = new ThresholdSigEd25519(t, n);
        ThresholdSigEd25519Params params = tsig.generate();

        File keysDir = new File("keys");
        if (!keysDir.exists()) {
            keysDir.mkdirs();
        }

        // Save the single aggregate public key
        File pubFile = new File(keysDir, "threshold_public.key");
        try (FileOutputStream fos = new FileOutputStream(pubFile)) {
            fos.write(params.getPublicKey());
        }
        System.out.println("Saved " + pubFile.getName());

        // Save the private shares for each node
        for (int i = 0; i < n; i++) {
            File shareFile = new File(keysDir, "node-" + i + "-threshold.key");
            try (FileOutputStream fos = new FileOutputStream(shareFile)) {
                // Scalar to byte array
                fos.write(params.getPrivateShares().get(i).toByteArray());
            }
            System.out.println("Saved " + shareFile.getName());
        }

        System.out.println("[Threshold PKI] Setup complete.");
    }
}
