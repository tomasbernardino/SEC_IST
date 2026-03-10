package ist.group29.depchain.server.service;

import ist.group29.depchain.server.consensus.DecideListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * The blockchain service - the upper application layer that the consensus
 * engine notifies via the DecideListener upcall.
 */
public class Service implements DecideListener {

    private static final Logger LOG = Logger.getLogger(Service.class.getName());

    /** The in-memory ordered log of all decided commands. */
    private final List<String> blockchain = new ArrayList<>();

    /**
     * Called by the consensus engine every time a command is decided
     */
    @Override
    public synchronized void onDecide(String command, int viewNumber, String clientId, long timestamp) {
        LOG.info("[Service] Command decided: " + command + " (view=" + viewNumber + ", client=" + clientId + ")");
        blockchain.add(command);
        LOG.info("[Service] Decided at view " + viewNumber
                + " - blockchain[" + (blockchain.size() - 1) + "] = \"" + command + "\"");
    }

    public synchronized List<String> getChain() {
        return Collections.unmodifiableList(new ArrayList<>(blockchain));
    }

    public synchronized int size() {
        return blockchain.size();
    }
}
