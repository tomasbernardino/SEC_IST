package ist.group29.depchain.server.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.server.consensus.DecideListener;

/**
 * The blockchain service - the upper application layer that the consensus
 * engine notifies via the DecideListener upcall.
 */
public class Service implements DecideListener {

    private static final Logger LOG = Logger.getLogger(Service.class.getName());

    // The in-memory representation of all decided blocks
    private final List<Block> blockchain = new ArrayList<>();
    private final BlockchainState state;
    private final TransactionManager transactionManager;

    public Service(BlockchainState state, TransactionManager transactionManager) {
        LOG.info("[Service] Initializing. Checking storage directory...");

        this.state = state;
        this.transactionManager = transactionManager;
    }

    @Override
    public synchronized void onDecide(Block block, int viewNumber) {
        LOG.info("[Service] Deciding Block #" + block.getBlockNumber() + " (Decided Hash: " + block.getBlockHash() + ")");

        // Execute transactions
        List<TransactionResponse> results = transactionManager.executeBlock(block, block.getBlockNumber());

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
        } catch (IOException e) {
            LOG.warning("[Service] Critical failure: failed to persist block JSON " + e.getMessage());
        }

        // Remove the block's committed transactions from the mempool
        transactionManager.removeCommittedTxs(block.getTransactionsList());
        blockchain.add(block);
        transactionManager.sendResponses(results);
    }

    public BlockchainState getState() {
        return state;
    }
}
