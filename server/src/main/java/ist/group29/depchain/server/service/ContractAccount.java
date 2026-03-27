package ist.group29.depchain.server.service;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * A ContractAccount holds the basic account info plus contract specific info:
 * - code (EVM bytecode if this is a contract)
 * - storage (the contract's persistent memory map)
 */
public class ContractAccount extends BlockchainAccount {
    // contract specifics
    private String code = ""; 
    private Map<String, String> storage = new HashMap<>();

    public ContractAccount(String address) {
        super(address);
    }

    public ContractAccount(String address, BigInteger balance) {
        super(address, balance);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Map<String, String> getStorage() {
        return storage;
    }

    public void setStorage(Map<String, String> storage) {
        this.storage = storage;
    }

    @Override
    public boolean isContract() {
        return code != null && !code.isEmpty();
    }

    @Override
    public String toString() {
        return "ContractAccount{" +
                "address='" + address + '\'' +
                ", balance=" + balance +
                ", isContract=" + isContract() +
                '}';
    }
}
