package ist.group29.depchain.server.consensus;

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
    void onDecide(String command, int viewNumber, String clientId, long timestamp);
}
