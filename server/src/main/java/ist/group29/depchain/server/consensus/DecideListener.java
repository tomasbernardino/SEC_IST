package ist.group29.depchain.server.consensus;

import ist.group29.depchain.client.ClientMessages.Block;

/**
 * Upcall interface invoked by Consensus whenever a command is decided.
 *
 * This is the bridge between the consensus layer and the application layer
 * (the blockchain service).
 *
 * Implementations must be thread-safe: the consensus layer may call this
 * from its internal event-processing thread.
 */
public interface DecideListener {
    /**
     * Called once per decided view, exactly when a valid DECIDE message
     * carrying a commitQC is received and verified.
     */
    void onDecide(Block block, int viewNumber);

    // TODO should be TxManager
    /** Called by leader to assemble a block of transactions. */
    Block buildBlock();

    // TODO should be TxManager
    /** Called by replicas on PREPARE to validate transactions before voting. */
    boolean validateBlock(Block block);
    

}
