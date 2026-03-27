package ist.group29.depchain.server.service;

import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.server.consensus.DecideListener;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.crypto.ClientSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
/**
 * The blockchain service - the upper application layer that the consensus
 * engine notifies via the DecideListener upcall.
 */
public class Service implements DecideListener {

    private static final Logger LOG = Logger.getLogger(Service.class.getName());
    private static final long BLOCK_GAS_LIMIT = 100000;

    // The in-memory representation of all decided blocks
    private final List<Block> blockchain = new ArrayList<>();
    private final TransactionExecutor executor;
    private final BlockchainState state;
    private final Mempool mempool = new Mempool();

    public Service() {
        LOG.info("[Service] Initializing. Checking storage directory...");

        BlockchainState loaded = null;
        try {
            // Try recovery from latest block record
            loaded = BlockchainState.loadLatestState();
        } catch (Exception e) {
            LOG.warning("[Service] Failed to load existing state, starting fresh: " + e.getMessage());
        }

        this.state = (loaded != null) ? loaded : new BlockchainState();
        this.executor = new TransactionExecutor(state);
    }

    @Override
    public Block buildBlock() {
        LOG.info("[Service] Building consensus block...");
        
        long blockNumber = state.getBlockNumber() + 1;
        String previousHash = state.getBlockHash();
        
        List<Transaction> candidateTxs = new ArrayList<>();
        long currentBlockGas = 0;
        
        // Prioritized Picking: We maintain a "frontier" of the next executable transaction for each sender.
        // We pick the highest-fee transaction from the frontier, add it, and then advance that sender's frontier.
        
        // Initial frontier (one tx per sender)
        java.util.Map<String, Long> expectedNonces = new java.util.HashMap<>();
        for (String sender : mempool.getSenders()) {
            BlockchainAccount acc = state.getAccount(sender);
            long nextNonce = (acc instanceof EOA eoa) ? eoa.getNonce() : 0;
            expectedNonces.put(sender, nextNonce);
        }
        // 
        while (true) {
            Transaction bestTx = null;
            String bestSender = null;

            for (String sender : mempool.getSenders()) {
                long nonce = expectedNonces.get(sender);
                Transaction tx = mempool.getTransaction(sender, nonce);
                
                if (tx != null) {
                    if (bestTx == null || tx.getGasPrice() > bestTx.getGasPrice()) {
                        bestTx = tx;
                        bestSender = sender;
                    }
                }
            }

            if (bestTx == null) break; // No more executable txs found

            // Check if this best transaction fits the gas budget
            if (currentBlockGas + bestTx.getGasLimit() <= BLOCK_GAS_LIMIT) {
                candidateTxs.add(bestTx);
                currentBlockGas += bestTx.getGasLimit();
                expectedNonces.put(bestSender, bestTx.getNonce() + 1);
            } else {
                // If the most expensive tx doesn't fit, we stop picking to keep the order logic simple
                // (Optimally, we could try smaller txs, but Ethereum usually fills greedily)
                break;
            }
        }
        
        // Order by highest gas price
        candidateTxs.sort((t1, t2) -> Long.compare(t2.getGasPrice(), t1.getGasPrice()));

        // Create block without blockHash to get a stable byte array
        Block.Builder builder = Block.newBuilder()
                .addAllTransactions(candidateTxs)
                .setBlockNumber(blockNumber)
                .setPreviousHash(previousHash);
        
        // Fingerprint the block content and set the current hash
        byte[] contentHash = CryptoUtils.sha256(builder.build().toByteArray());
        builder.setBlockHash(CryptoUtils.bytesToHex(contentHash));
        
        LOG.info("[Service] Proposed Block #" + blockNumber + " with " + candidateTxs.size() + " transactions.");
        return builder.build();
    }

    @Override
    public synchronized void onDecide(Block block, int viewNumber) {
        LOG.info("[Service] Deciding Block #" + block.getBlockNumber() + " (Decided Hash: " + block.getBlockHash() + ")");

        // Execute transactions
        List<TransactionResponse> results = executor.executeBlock(block, block.getBlockNumber());

        // Prepare BlockRecord for persistence
        BlockRecord record = new BlockRecord(
                block.getBlockNumber(),
                block.getBlockHash(),
                block.getPreviousHash(),
                block.getTransactionsList(),
                results);

        // Persist the block and the resulting world state (JSON)
        try {
            state.save(record);
        } catch (java.io.IOException e) {
            LOG.warning("[Service] Critical failure: failed to persist block JSON " + e.getMessage());
        }

        // Remove the block's committed transactions from the mempool
        mempool.removeCommittedTxs(block.getTransactionsList());
        blockchain.add(block);
    }

    @Override
    public boolean validateBlock(Block block) {
        if (block == null || block.getTransactionsCount() == 0)
            return false;
        for (Transaction tx : block.getTransactionsList()) {
            if (!isTransactionValid(tx)) {
                LOG.warning("[Service] Block contains invalid transaction from " + tx.getFrom());
                return false;
            }
        }
        return true;
    }

    /**
     * Entry point for new client transactions. Sets them to the mempool after validation.
     */
    public boolean addPendingTx(Transaction tx) {
        if (isTransactionValid(tx)) {
            return mempool.addTransaction(tx);
        }
        return false;
    }

    private boolean isTransactionValid(Transaction tx) {
        LOG.info("[Service] Pure-validating transaction from " + tx.getFrom() + " (nonce=" + tx.getNonce() + ")");

        // Intrinsic Gas Check
        long intrinsicGas = TransactionExecutor.calculateIntrinsicGas(tx);
        if (tx.getGasLimit() < intrinsicGas) {
            LOG.warning("[Service] Transaction rejected: gas_limit (" + tx.getGasLimit() + ") < intrinsic_gas ("
                    + intrinsicGas + ")");
            return false;
        }

        String senderAddr = tx.getFrom().replace("0x", "").toLowerCase();
        BlockchainAccount account = state.getAccount(senderAddr);

        if (account == null) {
            LOG.warning("[Service] Transaction rejected: sender account " + senderAddr + " does not exist");
            return false;
        }

        if (!(account instanceof EOA eoa)) {
            LOG.warning("[Service] Transaction rejected: sender " + senderAddr + " is a contract, not an EOA");
            return false;
        }

        // Nonce check
        if (tx.getNonce() < eoa.getNonce()) {
            LOG.warning("[Service] Transaction rejected: nonce too low (expected >= " + eoa.getNonce() + ", got "
                    + tx.getNonce() + ")");
            return false;
        }

        // Fund check: balance >= value + (gas_limit * gas_price)
        BigInteger gasFee = BigInteger.valueOf(tx.getGasLimit())
                .multiply(BigInteger.valueOf(tx.getGasPrice()));
        BigInteger totalCost = gasFee.add(BigInteger.valueOf(tx.getValue()));

        if (eoa.getBalance().compareTo(totalCost) < 0) {
            LOG.warning("[Service] Transaction rejected: insufficient funds (cost=" + totalCost + ", balance="
                    + eoa.getBalance() + ")");
            return false;
        }

        // Signature check
        try {
            ClientSignature signature = new ClientSignature(
                    tx.getSigV().toByteArray(),
                    tx.getSigR().toByteArray(),
                    tx.getSigS().toByteArray());

            boolean sigValid = CryptoUtils.ecVerify(
                    signature,
                    tx.getFrom(),
                    tx.getFrom().getBytes(StandardCharsets.UTF_8),
                    tx.getTo().getBytes(StandardCharsets.UTF_8),
                    CryptoUtils.toBytes(tx.getValue()),
                    CryptoUtils.toBytes(tx.getNonce()),
                    CryptoUtils.toBytes(tx.getGasPrice()),
                    CryptoUtils.toBytes(tx.getGasLimit()),
                    tx.getData().toByteArray());

            if (!sigValid) {
                LOG.warning("[Service] Transaction rejected: invalid ECDSA signature from sender " + tx.getFrom());
                return false;
            }
        } catch (Exception e) {
            LOG.warning("[Service] Transaction rejected: error during signature recovery: " + e.getMessage());
            return false;
        }

        return mempool.addTransaction(tx);
    }
}
