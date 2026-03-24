package ist.group29.depchain.common.crypto;

import java.io.Serializable;
import java.util.Arrays;

import org.web3j.crypto.Sign.SignatureData;

/**
 * Serializable wrapper for an ECDSA signature (v, r, s components).
 * Used for signing blockchain transactions with secp256k1.
 */
public record ClientSignature(byte[] v, byte[] r, byte[] s) implements Serializable {

    public ClientSignature(SignatureData data) {
        this(data.getV(), data.getR(), data.getS());
    }

    public SignatureData toSignatureData() {
        return new SignatureData(v, r, s);
    }

    /** Hex-encode v for proto/JSON transport */
    public String vHex() {
        return bytesToHex(v);
    }

    public String rHex() {
        return bytesToHex(r);
    }

    public String sHex() {
        return bytesToHex(s);
    }

    /** Reconstruct from hex strings */
    public static ClientSignature fromHex(String vHex, String rHex, String sHex) {
        return new ClientSignature(hexToBytes(vHex), hexToBytes(rHex), hexToBytes(sHex));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ClientSignature that))
            return false;
        return Arrays.equals(v, that.v) && Arrays.equals(r, that.r) && Arrays.equals(s, that.s);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(v) ^ Arrays.hashCode(r) ^ Arrays.hashCode(s);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte value : b)
            sb.append(String.format("%02x", value));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
