package ist.group29.depchain.server;

import ist.group29.depchain.server.consensus.Consensus;
import ist.group29.depchain.server.config.NodeConfig;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.server.service.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 *
 * Standard usage:
 * App <nodeId> <hostsConfigPath> <keysDir> <password>
 *
 * Example:
 * App node-0 hosts.config keys sec_project_keys
 *
 * Key pre-distribution: For Stage 1, each node generates a
 * fresh RSA key pair on startup. In a real deployment the PKI would be set up
 * offline and the public keys loaded from a trusted directory. The project
 * description states: "blockchain members should use self-generated
 * public/private
 * keys, which are pre-distributed before the start of the system."
 * In Stage 1 we approximate this with in-process key exchange for testing.
 *
 * Note: This class will be significantly extended in Stage 2 to handle
 * client connections and transaction processing.
 */
public class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: App <nodeId> <hostsConfigPath> <keysDir> <password>");
            System.exit(1);
        }

        String selfId = args[0];
        Path configPath = Path.of(args[1]);
        Path keysDir = Path.of(args[2]);
        char[] password = args[3].toCharArray();

        // Load configuration (Network + Keys) from disk
        LOG.info("[App] Loading configuration for " + selfId + "...");
        NodeConfig config = NodeConfig.load(selfId, configPath, keysDir, password);

        // Wire up the layers
        Service service = new Service();

        // All IDs (self + peers) for leader rotation
        List<String> allNodeIds = new ArrayList<>(config.peers().keySet());
        allNodeIds.add(selfId);

        // Network layer (no callback required at construction)
        LinkManager linkManager = new LinkManager(
            config.self(), config.peers(),
            config.identityKeyPair(), config.peerPublicKeys());

        // Consensus layer
        Consensus consensus = new Consensus(selfId, allNodeIds, linkManager, service, keysDir.toString());
        // Wire the layers together, then start the network loop
        linkManager.setMessageListener(consensus::onMessage);

        linkManager.start();

        LOG.info("[App] Node " + selfId + " started on " + config.self().address() + ":" + config.self().port()
                + " with " + config.peers().size() + " peers.");

        // Give APL handshakes time to complete
        Thread.sleep(2000);

        LOG.info("[App] Starting HotStuff consensus view 1...");
        consensus.start("cmd-initial");

        // Keep running
        Thread.currentThread().join();
    }
}
