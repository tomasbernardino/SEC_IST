package ist.group29.depchain.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.server.config.NodeConfig;
import ist.group29.depchain.server.consensus.ByzantineConsensus;
import ist.group29.depchain.server.consensus.ByzantineMode;
import ist.group29.depchain.server.consensus.Consensus;
import ist.group29.depchain.server.service.BlockchainState;
import ist.group29.depchain.server.service.Service;
import ist.group29.depchain.server.service.TransactionManager;

/**
 *
 * Standard usage:
 * App <nodeId> <hostsConfigPath> <keysDir> <password> [byzantineMode]
 *
 * Example:
 * App node-0 hosts.config keys sec_project_keys
 * App node-0 hosts.config keys sec_project_keys SILENT
 */
public class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: App <nodeId> <hostsConfigPath> <keysDir> <password> [byzantineMode]");
            System.err.println(
                    "  byzantineMode (optional): CORRECT | SILENT | CRASH | CORRUPT_PROPOSAL | CORRUPT_QC | CORRUPT_VOTE | SLOW_NODE | SELECTIVE_VOTE | REPLAY_LEADER");
            System.exit(1);
        }

        String selfId = args[0];
        Path configPath = Path.of(args[1]);
        Path keysDir = Path.of(args[2]);
        char[] password = args[3].toCharArray();

        // Parse optional Byzantine mode (default: CORRECT)
        ByzantineMode byzantineMode = ByzantineMode.CORRECT;
        if (args.length >= 5) {
            try {
                byzantineMode = ByzantineMode.valueOf(args[4].toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown byzantine mode: " + args[4]);
                System.err.println(
                        "Valid modes: CORRECT, SILENT, CRASH, CORRUPT_PROPOSAL, CORRUPT_QC, CORRUPT_VOTE, SLOW_NODE, SELECTIVE_VOTE, REPLAY_LEADER");
                System.exit(1);
            }
        }

        LOG.info("[App] Loading configuration for " + selfId + "...");
        if (byzantineMode != ByzantineMode.CORRECT) {
            LOG.warning("[App] *** BYZANTINE MODE: " + byzantineMode + " ***");
        }
        NodeConfig config = NodeConfig.load(selfId, configPath, keysDir, password);
        BlockchainState state;
        try {
            state = BlockchainState.loadLatestState();
        } catch (Exception e) {
            LOG.warning("[App] Failed to load existing state, starting fresh: " + e.getMessage());
            state = new BlockchainState();
        }

        LinkManager linkManager = new LinkManager(
                config.self(), config.peers(),
                config.identityKeyPair(), config.peerPublicKeys());

        TransactionManager transactionManager = new TransactionManager(state, linkManager);

        Service service = new Service(state, transactionManager);

        List<String> allNodeIds = new ArrayList<>(config.peers().keySet());
        allNodeIds.add(selfId);

        Consensus consensus;
        if (byzantineMode == ByzantineMode.CORRECT) {
            consensus = new Consensus(selfId, allNodeIds, linkManager, service, transactionManager, keysDir.toString());
        } else {
            consensus = new ByzantineConsensus(byzantineMode, selfId, allNodeIds, linkManager, service,
                    transactionManager, keysDir.toString());
        }

        linkManager.setMessageListener(new MessageRouter(consensus, transactionManager));

        linkManager.start();

        LOG.info("[App] Node " + selfId + " started on " + config.self().address() + ":" + config.self().port()
                + " with " + config.peers().size() + " peers.");

        // Give APL handshakes time to complete
        Thread.sleep(2000);

        LOG.info("[App] Starting HotStuff consensus view 1...");
        consensus.start();

        Thread.currentThread().join();
    }
}
