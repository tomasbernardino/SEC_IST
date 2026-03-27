package ist.group29.depchain.server.service.account;

import java.math.BigInteger;

/**
 * An Externally Owned Account (EOA) holds the basic account info plus client specific info:
 * - nonce (sequential counter for replay protection)
 */

public class EOA extends BlockchainAccount {
    private long nonce = 0;

    public EOA(String address) {
        super(address);
    }

    public EOA(String address, BigInteger balance, long nonce) {
        super(address, balance);
        this.nonce = nonce;
    }

    public long getNonce() {
        return nonce;
    }

    public void setNonce(long nonce) {
        this.nonce = nonce;
    }

    public void incrementNonce() {
        this.nonce++;
    }

    @Override
    public boolean isContract() {
        return false;
    }

    @Override
    public String toString() {
        return "EOA{" +
                "address='" + address + '\'' +
                ", balance=" + balance +
                ", nonce=" + nonce +
                '}';
    }
}
