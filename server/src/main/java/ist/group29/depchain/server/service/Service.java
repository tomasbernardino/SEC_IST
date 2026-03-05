package ist.group29.depchain.server.service;

import ist.group29.depchain.server.consensus.DecideListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * The blockchain service - the upper application layer that the consensus
 * engine
 * notifies via the DecideListener upcall.
 *
 * where the blockchain implementation resides.
 * It maintains an in-memory, append-only array of decided string commands - the
 * "simplified blockchain" for Stage 1.
 */
public class Service implements DecideListener {

    private static final Logger LOG = Logger.getLogger(Service.class.getName());

    /** The in-memory ordered log of all decided commands. */
    private final List<String> blockchain = new ArrayList<>();

    /**
     * Called by the consensus engine every time a command is decided
     * (Algorithm 2, line 34: "execute new commands through m.justify.node").
     *
     * Appends the decided command to the blockchain log and logs a confirmation.
     * In a full implementation this is also where client response callbacks would
     * be triggered.
     *
     * Thread safety: onDecide is synchronized to protect the blockchain list from
     * concurrent access if called from different consensus threads.
     *
     * @param command    the decided client string command
     * @param viewNumber the consensus view in which the decision occurred
     */
    @Override
    public synchronized void onDecide(String command, int viewNumber) {
        blockchain.add(command);
        LOG.info("[Service] Decided at view " + viewNumber
                + " - blockchain[" + (blockchain.size() - 1) + "] = \"" + command + "\"");
    }

    /**
     * Return an unmodifiable snapshot of the current blockchain log.
     * Used by tests and future client-facing query endpoints.
     *
     * @return immutable view of all decided commands in order
     */
    public synchronized List<String> getChain() {
        return Collections.unmodifiableList(new ArrayList<>(blockchain));
    }

    /** Return the current blockchain length. */
    public synchronized int size() {
        return blockchain.size();
    }
}
