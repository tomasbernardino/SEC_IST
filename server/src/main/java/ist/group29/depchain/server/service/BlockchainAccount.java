package ist.group29.depchain.server.service;

import java.math.BigInteger;

/**
 * Abstract base class for all blockchain accounts (EOAs and Smart Contracts).
 * 
 * Shared state:
 * - address (unique identifier)
 * - balance (DepCoin)
 */
public abstract class BlockchainAccount {
    protected final String address;
    protected BigInteger balance = BigInteger.ZERO;

    public BlockchainAccount(String address) {
        this.address = address;
    }

    public BlockchainAccount(String address, BigInteger balance) {
        this.address = address;
        this.balance = balance;
    }

    public String getAddress() {
        return address;
    }

    public BigInteger getBalance() {
        return balance;
    }

    public void setBalance(BigInteger balance) {
        this.balance = balance;
    }

    /**
     * Helper to modify the balance safely.
     */
    public void credit(BigInteger amount) {
        if (amount.compareTo(BigInteger.ZERO) < 0) {
            throw new IllegalArgumentException("Cannot credit negative amount.");
        }
        this.balance = this.balance.add(amount);
    }

    public void debit(BigInteger amount) {
        if (amount.compareTo(BigInteger.ZERO) < 0) {
            throw new IllegalArgumentException("Cannot debit negative amount.");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient balance in account " + address);
        }
        this.balance = this.balance.subtract(amount);
    }

    public abstract boolean isContract();

    @Override
    public String toString() {
        return "BlockchainAccount{" +
                "address='" + address + '\'' +
                ", balance=" + balance +
                ", isContract=" + isContract() +
                '}';
    }
}
