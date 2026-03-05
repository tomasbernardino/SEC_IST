package ist.group29.depchain.server.consensus;

/**
 * Upcall interface invoked by Consensus whenever a command is decided.
 *
 * This is the bridge between the consensus layer and the application layer
 * (the blockchain service). In Algorithm 2 of the HotStuff paper, this
 * corresponds to the "execute new commands through m.justify.node, respond to
 * clients" step.
 *
 * Implementations must be thread-safe: the consensus layer may call this
 * from its internal event-processing thread.
 */
public interface DecideListener {
    /**
     * Called once per decided view, exactly when a valid DECIDE message
     * carrying a commitQC is received and verified.
     *
     * @param command     the client command string that was committed
     * @param viewNumber  the consensus view number in which the decision was made
     */
    void onDecide(String command, int viewNumber);
}
