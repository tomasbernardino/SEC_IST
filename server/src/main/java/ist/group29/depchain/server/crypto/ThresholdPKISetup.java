package ist.group29.depchain.server.crypto;

import ist.group29.depchain.server.crypto.threshsig.Dealer;
import ist.group29.depchain.server.crypto.threshsig.GroupKey;
import ist.group29.depchain.server.crypto.threshsig.KeyShare;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;

/**
 * Utility to generate and distribute Threshold Signature shares
 * It uses the threshsig library to generate RSA-based threshold keys.
 */
public class ThresholdPKISetup {

    public static void main(String[] args) throws Exception {
        int n = 4;
        int k = 3; 
        int keysize = 2048;

        String keysPath = (args.length > 0) ? args[0] : "keys";
        File keysDir = new File(keysPath);

        System.out.println("[Threshold PKI] Manual Setup Utility");
        System.out.println("Running this will re-generate all threshold keys.");
        System.out.println(
                "[Threshold PKI] Generating RSA keys for n=" + n + ", k=" + k + " into: " + keysDir.getAbsolutePath());

        Dealer dealer = new Dealer(keysize);
        dealer.generateKeys(k, n);

        GroupKey groupKey = dealer.getGroupKey();
        KeyShare[] shares = dealer.getShares();

        if (!keysDir.exists()) {
            boolean created = keysDir.mkdirs();
            if (created)
                System.out.println("Created directory: " + keysDir.getAbsolutePath());
        }

        File pubFile = new File(keysDir, "threshold_public.key");
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(pubFile))) {
            oos.writeObject(groupKey);
        }
        System.out.println("Saved " + pubFile.getName() + " to " + keysDir.getPath());

        for (int i = 0; i < n; i++) {
            File shareFile = new File(keysDir, "node-" + i + "-threshold.key");
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(shareFile))) {
                oos.writeObject(shares[i]);
            }
            System.out.println("Saved " + shareFile.getName() + " to " + keysDir.getPath());
        }

        System.out.println("[Threshold PKI] Setup complete.");
    }
}
