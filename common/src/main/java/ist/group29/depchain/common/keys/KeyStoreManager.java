package ist.group29.depchain.common.keys;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;

/**
 * Utility class that wraps the Java KeyStore API for loading
 * RSA key pairs from encrypted .p12 files.
 */
public class KeyStoreManager {

    private static final String KEYSTORE_TYPE = "PKCS12";

    public static PrivateKey loadPrivateKey(Path path, String alias, char[] password)
            throws GeneralSecurityException, IOException {

        KeyStore ks = loadKeyStore(path, password);
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
                ks.getEntry(alias, new KeyStore.PasswordProtection(password));
        return entry.getPrivateKey();
    }

    public static PublicKey loadPublicKey(Path path, String alias, char[] password)
            throws GeneralSecurityException, IOException {

        KeyStore ks = loadKeyStore(path, password);
        Certificate cert = ks.getCertificate(alias);
        if (cert == null) {
            throw new GeneralSecurityException("No certificate found for alias: " + alias);
        }
        return cert.getPublicKey();
    }

 
    private static KeyStore loadKeyStore(Path path, char[] password)
            throws GeneralSecurityException, IOException {

        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            ks.load(fis, password);
        }
        return ks;
    }
}
