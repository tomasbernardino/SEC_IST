package ist.group29.depchain.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.server.config.NodeConfig;
import ist.group29.depchain.server.consensus.Consensus;
import ist.group29.depchain.server.service.Service;
import ist.group29.depchain.server.service.TransactionManager;

/**
 *
 * Standard usage:
 * App <nodeId> <hostsConfigPath> <keysDir> <password>
 *
 * Example:
 * App node-0 hosts.config keys sec_project_keys
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

        LOG.info("[App] Loading configuration for " + selfId + "...");
        NodeConfig config = NodeConfig.load(selfId, configPath, keysDir, password);

        Service service = new Service(); //TODO should have a linkManager to send messages to the client
        TransactionManager transactionManager = new TransactionManager(service.getState());
        List<String> allNodeIds = new ArrayList<>(config.peers().keySet());
        allNodeIds.add(selfId);

        LinkManager linkManager = new LinkManager(
                config.self(), config.peers(),
                config.identityKeyPair(), config.peerPublicKeys());

        Consensus consensus = new Consensus(selfId, allNodeIds, linkManager, service, transactionManager, keysDir.toString());

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
