package ist.group29.depchain.server.service;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.server.service.account.BlockchainAccount;
import ist.group29.depchain.server.service.account.ContractAccount;
import ist.group29.depchain.server.service.account.EOA;

/**
 * World state manager for accounts (EOA and Contract).
 * Manages the transition of balances and code across blocks.
 */
public class BlockchainState {

    private static final Logger LOG = Logger.getLogger(BlockchainState.class.getName());
    private static final Path STORAGE_DIR = Path.of("storage");

    private final Map<String, BlockchainAccount> accounts = new HashMap<>();
    private long lastBlockNumber = 0;
    private String lastBlockHash = "0";

    public BlockchainState() {
        // Fresh state
    }

    public BlockchainAccount getAccount(String address) {
        return accounts.get(address.toLowerCase());
    }

    public BlockchainAccount getOrCreateAccount(String address) {
        String addr = address.toLowerCase();
        if (!accounts.containsKey(addr)) {
            accounts.put(addr, new EOA(addr));
        }
        return accounts.get(addr);
    }

    public void addAccount(BlockchainAccount account) {
        accounts.put(account.getAddress().toLowerCase(), account);
    }

    public java.util.Collection<BlockchainAccount> getAllAccounts() {
        return accounts.values();
    }

    public long getBlockNumber() {
        return lastBlockNumber;
    }

    public String getBlockHash() {
        return lastBlockHash;
    }

    /**
     * Scan the storage directory for the latest block and load the state.
     */
    public static BlockchainState loadLatestState() throws IOException {
        if (!Files.exists(STORAGE_DIR)) {
            throw new IOException("Storage directory not found: " + STORAGE_DIR);
        }

        Optional<Path> latestFile = Files.list(STORAGE_DIR)
                .filter(p -> p.getFileName().toString().startsWith("block") && p.getFileName().toString().endsWith(".json"))
                .max((p1, p2) -> {
                    String n1 = p1.getFileName().toString().replaceAll("[^0-9]", "");
                    String n2 = p2.getFileName().toString().replaceAll("[^0-9]", "");
                    try {
                        return Long.compare(Long.parseLong(n1), Long.parseLong(n2));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                });

        if (latestFile.isPresent()) {
            LOG.info("[State] Resuming from existing block: " + latestFile.get().getFileName());
            return loadFromBlock(latestFile.get());
        } else {
            // Try loading the genesis
            LOG.info("[State] No existing blocks found. Loading genesis.");
            return loadGenesis();
        }
    }

    public static BlockchainState loadFromBlock(Path blockPath) throws IOException {
      LOG.warning("[State] Loading state from block file: " + blockPath);
        BlockchainState state = new BlockchainState();
        try (FileReader reader = new FileReader(blockPath.toFile())) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            state.lastBlockNumber = root.get("block_number").getAsLong();
            state.lastBlockHash = root.get("block_hash").getAsString();

            JsonObject accountsNode = root.getAsJsonObject("state");
            for (Map.Entry<String, JsonElement> entry : accountsNode.entrySet()) {
                String address = entry.getKey();
                LOG.warning("[BlockChainState] Loading account for address: " + address);
                JsonObject accNode = entry.getValue().getAsJsonObject();

                BlockchainAccount acc;
                if (accNode.has("code") && !accNode.get("code").getAsString().isEmpty()) {
                    acc = new ContractAccount(address);
                    ((ContractAccount) acc).setCode(accNode.get("code").getAsString());
                    if (accNode.has("storage")) {
                        JsonObject storageNode = accNode.getAsJsonObject("storage");
                        for (Map.Entry<String, JsonElement> slot : storageNode.entrySet()) {
                            ((ContractAccount) acc).getStorage().put(slot.getKey(), slot.getValue().getAsString());
                        }
                    }
                } else {
                    acc = new EOA(address);
                    if (accNode.has("nonce")) {
                        ((EOA) acc).setNonce(accNode.get("nonce").getAsLong());
                    }
                }

                if (accNode.has("balance")) {
                    acc.setBalance(new BigInteger(accNode.get("balance").getAsString()));
                }

                state.accounts.put(address.toLowerCase(), acc);
            }
        }
        return state;
    }

    private static BlockchainState loadGenesis() throws IOException {
        LOG.warning("biggest BigInteger: " + BigInteger.valueOf(Integer.MAX_VALUE).toString());
        Path genesisPath = STORAGE_DIR.resolve("genesis.json");
        if (!Files.exists(genesisPath)) {
            throw new IOException("Genesis file not found: " + genesisPath);
        }
        LOG.info("[State] Initializing from genesis: " + genesisPath);
        return loadFromBlock(genesisPath);
    }

    /**
     * Persists the current state and block record to a JSON file.
     */
    public void save(BlockRecord record) throws IOException {
        this.lastBlockNumber = record.blockNumber();
        this.lastBlockHash = record.blockHash();

        //Files.createDirectories(STORAGE_DIR);
        Path target = STORAGE_DIR.resolve("block" + record.blockNumber() + ".json");

        Map<String, Object> blockMap = new HashMap<>();
        blockMap.put("block_number", record.blockNumber());
        blockMap.put("block_hash", record.blockHash());
        blockMap.put("previous_block_hash", record.previousBlockHash());

        List<Map<String, Object>> txsList = new ArrayList<>();
        for (int i = 0; i < record.transactions().size(); i++) {
            Map<String, Object> txMap = transactionToMap(record.transactions().get(i));
            if (record.receipts() != null && i < record.receipts().size()) {
                txMap.put("receipt", responseToMap(record.receipts().get(i)));
            }
            txsList.add(txMap);
        }
        blockMap.put("transactions", txsList);

        Map<String, Object> stateMap = new HashMap<>();
        for (BlockchainAccount account : accounts.values()) {
            Map<String, Object> accMap = new HashMap<>();
            accMap.put("balance", account.getBalance());
            if (account instanceof EOA eoa) {
                accMap.put("nonce", eoa.getNonce());
            } else if (account instanceof ContractAccount contract) {
                accMap.put("code", contract.getCode());
                accMap.put("storage", contract.getStorage());
            }
            stateMap.put(account.getAddress(), accMap);
        }

        blockMap.put("state", stateMap);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(target.toFile())) {
            gson.toJson(blockMap, writer);
        }
    }

    private Map<String, Object> transactionToMap(Transaction tx) {
        Map<String, Object> map = new HashMap<>();
        map.put("from", tx.getFrom());
        map.put("to", tx.getTo());
        map.put("nonce", tx.getNonce());
        map.put("value", tx.getValue());
        map.put("gasLimit", tx.getGasLimit());
        map.put("gasPrice", tx.getGasPrice());
        map.put("data", CryptoUtils.bytesToHex(tx.getData().toByteArray()));
        map.put("sigV", CryptoUtils.bytesToHex(tx.getSigV().toByteArray()));
        map.put("sigR", CryptoUtils.bytesToHex(tx.getSigR().toByteArray()));
        map.put("sigS", CryptoUtils.bytesToHex(tx.getSigS().toByteArray()));
        return map;
    }

    private Map<String, Object> responseToMap(TransactionResponse res) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", res.getStatus().name());
        map.put("transactionHash", CryptoUtils.bytesToHex(res.getTransactionHash().toByteArray()));
        map.put("gasUsed", res.getGasUsed());
        map.put("blockNumber", res.getBlockNumber());
        map.put("returnData", CryptoUtils.bytesToHex(res.getReturnData().toByteArray()));
        map.put("errorMessage", res.getErrorMessage());
        return map;
    }
}
