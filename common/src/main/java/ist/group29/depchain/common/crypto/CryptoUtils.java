package ist.group29.depchain.common.crypto;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

/**
 * Cryptographic utilities for the DepChain network layer
 */
public final class CryptoUtils {

    private static final String RSA_SIGNATURE_ALG = "SHA256withRSA";
    private static final String KEY_AGREE_ALG = "DH";
    private static final String SECRET_KEY_ALG = "AES";
    private static final String MAC_ALG = "HmacSHA256";

    private CryptoUtils() {
    }

    /** Generate an ephemeral DH key pair for session key establishment */
    public static KeyPair generateDHKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_AGREE_ALG);
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    /** Generate an RSA key pair for testing or identity */
    public static KeyPair generateRSAKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    /** Compute an AES session key from a DH key agreement */
    public static SecretKey computeSharedSecret(PrivateKey myDHPrivate, PublicKey otherDHPublic)
            throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance(KEY_AGREE_ALG);
        ka.init(myDHPrivate);
        ka.doPhase(otherDHPublic, true);
        byte[] raw = ka.generateSecret();
        return new SecretKeySpec(raw, 0, Math.min(raw.length, 32), SECRET_KEY_ALG);
    }

    /** Decode an X.509-encoded DH public key from raw bytes */
    public static PublicKey decodeDHPublicKey(byte[] encoded) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance(KEY_AGREE_ALG);
        return kf.generatePublic(new X509EncodedKeySpec(encoded));
    }

    /** Sign with a RSA private key. */
    public static byte[] sign(PrivateKey key, byte[]... parts) throws GeneralSecurityException {
        Signature sig = Signature.getInstance(RSA_SIGNATURE_ALG);
        sig.initSign(key);
        for (byte[] part : parts) {
            sig.update(part);
        }
        return sig.sign();
    }

    /** Verify an RSA signature */
    public static boolean verify(PublicKey key, byte[] signature, byte[]... parts)
            throws GeneralSecurityException {
        Signature sig = Signature.getInstance(RSA_SIGNATURE_ALG);
        sig.initVerify(key);
        for (byte[] part : parts) {
            sig.update(part);
        }
        return sig.verify(signature);
    }

    /** Compute HMAC-SHA256 */
    public static byte[] hmac(SecretKey key, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(MAC_ALG);
        mac.init(key);
        for (byte[] part : parts) {
            mac.update(part);
        }
        return mac.doFinal();
    }

    /** Verify an HMAC-SHA256 */
    public static boolean verifyHmac(SecretKey key, byte[] expected, byte[]... parts)
            throws GeneralSecurityException {
        byte[] computed = hmac(key, parts);
        return MessageDigest.isEqual(computed, expected);
    }

    /**
     * Compute SHA-256
     */
    public static byte[] computeHash(byte[] parentHash, String command, int viewNumber) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(parentHash);
            sha.update(command.getBytes(StandardCharsets.UTF_8));
            sha.update(ByteBuffer.allocate(4).putInt(viewNumber).array());
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] toBytes(long v) {
        return ByteBuffer.allocate(Long.BYTES).putLong(v).array();
    }

    public static String bytesToHex(byte[] b, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, maxBytes); i++) {
            sb.append(String.format("%02x", b[i]));
        }
        if (b.length > maxBytes)
            sb.append("...");
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────
    // ECDSA (secp256k1) — Blockchain transaction signing
    // ────────────────────────────────────────────────────────────

    /**
     * Sign data with an ECDSA private key (secp256k1).
     * The data parts are keccak-hashed before signing.
     */
    public static ClientSignature ecSign(ECKeyPair keyPair, byte[]... parts) {
        byte[] hash = keccakHash(parts);
        Sign.SignatureData sig = Sign.signMessage(hash, keyPair);
        return new ClientSignature(sig);
    }

    /**
     * Recover the signer's public key from an ECDSA signature (ecrecover).
     * Returns the public key as a BigInteger.
     */
    public static BigInteger ecRecover(ClientSignature signature, byte[]... parts)
            throws SignatureException {
        byte[] hash = keccakHash(parts);
        return Sign.signedMessageToKey(hash, signature.toSignatureData());
    }

    /**
     * Verify that the recovered address from an ECDSA signature matches the
     * expected sender.
     */
    public static boolean ecVerify(ClientSignature signature, String expectedAddress, byte[]... parts) {
        try {
            BigInteger recoveredKey = ecRecover(signature, parts);
            String recoveredAddress = Keys.getAddress(recoveredKey);
            String normalizedExpected = expectedAddress.startsWith("0x")
                    ? expectedAddress.substring(2).toLowerCase()
                    : expectedAddress.toLowerCase();
            return recoveredAddress.equalsIgnoreCase(normalizedExpected);
        } catch (SignatureException e) {
            return false;
        }
    }

    /** Derive an Ethereum-standard address from an ECDSA key pair. */
    public static String getAddress(ECKeyPair keyPair) {
        return Keys.getAddress(keyPair);
    }

    /** Load an ECDSA key pair from a .key file (web3j serialized format). */
    public static ECKeyPair loadECKeyPair(Path keyFile) throws IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(keyFile);
        return Keys.deserialize(bytes);
    }

    /** Create a new random ECDSA key pair (secp256k1). */
    public static ECKeyPair createECKeyPair() {
        try {
            return Keys.createEcKeyPair();
        } catch (java.security.InvalidAlgorithmParameterException
                | NoSuchAlgorithmException
                | java.security.NoSuchProviderException e) {
            throw new RuntimeException("Failed to create EC key pair", e);
        }
    }

    /** Keccak-256 hash of concatenated byte arrays. */
    public static byte[] keccakHash(byte[]... parts) {
        byte[] concatenated = concatenate(parts);
        return Hash.sha3(concatenated);
    }

    private static byte[] concatenate(byte[]... arrays) {
        int totalLen = 0;
        for (byte[] a : arrays)
            totalLen += a.length;
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

}
