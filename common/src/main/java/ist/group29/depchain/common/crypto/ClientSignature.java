package ist.group29.depchain.common.crypto;

import java.io.Serializable;
import java.util.Arrays;

import org.web3j.crypto.Sign.SignatureData;

/**
 * Serializable wrapper for an ECDSA signature (v, r, s components)
 * Used for signing blockchain transactions
 */
public record ClientSignature(byte[] v, byte[] r, byte[] s) implements Serializable {

    public ClientSignature(SignatureData data) {
        this(data.getV(), data.getR(), data.getS());
    }

    public SignatureData toSignatureData() {
        return new SignatureData(v, r, s);
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

}
