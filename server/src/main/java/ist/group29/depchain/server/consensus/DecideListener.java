package ist.group29.depchain.server.consensus;

import ist.group29.depchain.client.ClientMessages.Block;

/**
 * Upcall interface invoked by Consensus whenever a command is decided.
 */
public interface DecideListener {
    /**
     * Called once per decided view when a valid DECIDE message
     * carrying a commitQC is received and verified
     */
    void onDecide(Block block, int viewNumber);

}
