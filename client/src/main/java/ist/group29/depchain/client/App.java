package ist.group29.depchain.client;

import ist.group29.depchain.common.network.ProcessInfo;
import ist.group29.depchain.common.keys.ConfigReader;
import ist.group29.depchain.common.keys.KeyStoreManager;

import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.Scanner;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.util.logging.Logger;
import java.util.logging.Handler;
import java.util.logging.Level;

public class App {
    public static void main(String[] args) throws Exception {
        // Silence default logger output on the client terminal for a clean prompt
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            handler.setLevel(Level.WARNING);
        }

        if (args.length < 4) {
            System.out.println("Usage: App <clientId> <hosts.config> <keysDir> <password>");
            return;
        }

        String clientId = args[0];
        Path configPath = Path.of(args[1]);
        Path keysDir = Path.of(args[2]);
        char[] password = args[3].toCharArray();

        // Load network and keys
        Map<String, ProcessInfo> nodes = ConfigReader.parseHosts(configPath);

        // Load Client KeyPair
        Path myKeyPath = keysDir.resolve(clientId + ".p12");
        PrivateKey priv = KeyStoreManager.loadPrivateKey(myKeyPath, clientId, password);
        PublicKey pub = KeyStoreManager.loadPublicKey(myKeyPath, clientId, password);
        KeyPair identityKeyPair = new KeyPair(pub, priv);

        // Load all nodes' PublicKeys
        Map<String, PublicKey> nodeKeys = new HashMap<>();

        for (String nodeId : nodes.keySet()) {
            try {
                // Construct the path to node's .p12 file
                Path nodeKeyPath = keysDir.resolve(nodeId + ".p12");

                // Load the public key using the nodeId as the alia
                PublicKey pk = KeyStoreManager.loadPublicKey(nodeKeyPath, nodeId, password);

                nodeKeys.put(nodeId, pk);
            } catch (Exception e) {
                System.err.println("Failed to load public key for " + nodeId + ": " + e.getMessage());
                throw e;
            }
        }

        // Client identity
        ProcessInfo self = new ProcessInfo(clientId, InetAddress.getLocalHost(), 0);

        // Initialize the module
        ClientLibrary clientLibrary = new ClientLibrary(self, nodes, identityKeyPair, nodeKeys);
        clientLibrary.start();

        System.out.println("DepChain Client '" + clientId + "' ready.");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("> ");
            if (!scanner.hasNextLine())
                break;
            String input = scanner.nextLine();
            if (input.equalsIgnoreCase("exit"))
                break;

            System.out.println("Appending to blockchain...");
            try {
                // Using .get() to block until the quorum is reached (as requested)
                clientLibrary.append(input).get();
                System.out.println("Success: Consensus reached.");
            } catch (Exception e) {
                System.err.println("Failed to reach consensus: " + e.getMessage());
            }
        }

        clientLibrary.stop();
        System.exit(0);
    }
}