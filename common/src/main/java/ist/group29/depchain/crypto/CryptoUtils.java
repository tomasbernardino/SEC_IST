package ist.group29.depchain.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic utilities for the DepChain network layer.
 * <p>
 * Identity keys: RSA (pre-distributed via PKI).
 * Session keys:  ECDH key agreement -> HMAC-SHA256.
 */
public final class CryptoUtils {

    private static final String RSA_SIGNATURE_ALG = "SHA256withRSA";
    private static final String KEY_AGREE_ALG    = "DH";
    private static final String SECRET_KEY_ALG   = "AES";
    private static final String MAC_ALG          = "HmacSHA256";

    private CryptoUtils() {} // utility class

    // ======================== Key Generation ========================

    /** Generate an ephemeral DH key pair for session key establishment. */
    public static KeyPair generateDHKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_AGREE_ALG);
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    /** Generate an RSA identity key pair (for testing / key generation scripts). */
    public static KeyPair generateRSAKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    // ======================== Key Agreement ========================

    /** Compute an AES session key from a DH key agreement. */
    public static SecretKey computeSharedSecret(PrivateKey myDHPrivate, PublicKey otherDHPublic)
            throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance(KEY_AGREE_ALG);
        ka.init(myDHPrivate);
        ka.doPhase(otherDHPublic, true);
        byte[] raw = ka.generateSecret();
        return new SecretKeySpec(raw, 0, Math.min(raw.length, 32), SECRET_KEY_ALG);
    }

    /** Decode an X.509-encoded DH public key from raw bytes. */
    public static PublicKey decodeDHPublicKey(byte[] encoded) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance(KEY_AGREE_ALG);
        return kf.generatePublic(new X509EncodedKeySpec(encoded));
    }

    // ======================== RSA Signatures ========================

    /** Sign concatenated data parts with an RSA private key. */
    public static byte[] sign(PrivateKey key, byte[]... parts) throws GeneralSecurityException {
        Signature sig = Signature.getInstance(RSA_SIGNATURE_ALG);
        sig.initSign(key);
        for (byte[] part : parts) {
            sig.update(part);
        }
        return sig.sign();
    }

    /** Verify an RSA signature over concatenated data parts. */
    public static boolean verify(PublicKey key, byte[] signature, byte[]... parts)
            throws GeneralSecurityException {
        Signature sig = Signature.getInstance(RSA_SIGNATURE_ALG);
        sig.initVerify(key);
        for (byte[] part : parts) {
            sig.update(part);
        }
        return sig.verify(signature);
    }

    // ======================== HMAC ========================

    /** Compute HMAC-SHA256 over concatenated data parts. */
    public static byte[] hmac(SecretKey key, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(MAC_ALG);
        mac.init(key);
        for (byte[] part : parts) {
            mac.update(part);
        } // What does this do? Why use the loop?
        return mac.doFinal();
    }

    /** Verify an HMAC-SHA256 in constant time. */
    public static boolean verifyHmac(SecretKey key, byte[] expected, byte[]... parts)
            throws GeneralSecurityException {
        byte[] computed = hmac(key, parts);
        return MessageDigest.isEqual(computed, expected);
    }

    // ======================== Encoding Helpers ========================

    public static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] toBytes(long v) {
        return ByteBuffer.allocate(Long.BYTES).putLong(v).array();
    }
}
