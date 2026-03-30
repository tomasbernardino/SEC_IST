package ist.group29.depchain.client;

import ist.group29.depchain.common.network.ProcessInfo;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.client.ClientMessages.TransactionStatus;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.keys.ConfigReader;
import ist.group29.depchain.common.keys.KeyStoreManager;

import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.util.logging.Logger;

import org.web3j.crypto.ECKeyPair;

import java.util.logging.Handler;
import java.util.logging.Level;

public class App {
    public static void main(String[] args) throws Exception {

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

        // Load Client Network Authentication KeyPair
        Path myKeyPath = keysDir.resolve(clientId + ".p12");
        PrivateKey priv = KeyStoreManager.loadPrivateKey(myKeyPath, clientId, password);
        PublicKey pub = KeyStoreManager.loadPublicKey(myKeyPath, clientId, password);
        KeyPair identityKeyPair = new KeyPair(pub, priv);

        // Load all nodes' PublicKeys
        Map<String, PublicKey> nodeKeys = new HashMap<>();


        // Load Client ECDSA Blockchain Identity (same directory, .key file)
        Path ecdsaKeyPath = keysDir.resolve(clientId + ".key");
        ECKeyPair blockchainId = CryptoUtils.loadECKeyPair(ecdsaKeyPath);

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
        ClientLibrary clientLibrary = new ClientLibrary(self, nodes, identityKeyPair, nodeKeys, blockchainId);
        clientLibrary.start();

        System.out.println("DepChain Client '" + clientId + "' ready.");
        System.out.println("Commands: ");
        System.out.println("  transfer <to_address> <amount>");
        System.out.println("  contract <contract_address> <hex_data>");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine())
                    break;
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("exit"))
                    break;
                if (input.isEmpty())
                    continue;

                String[ ] parts = input.split("\\s+");
                String cmd = parts[0].toLowerCase();

                try {
                    CompletableFuture<TransactionResponse> future = null;
                    switch (cmd) {
                        case "transfer":
                            if (parts.length != 3) {
                                System.out.println("Usage: transfer <to_address> <amount>");
                                break;
                            }
                            String to = parts[1];
                            long amount = Long.parseLong(parts[2]);
                            System.out.println("Submitting transfer of " + amount + " to " + to);
                            future = clientLibrary.submitTransaction(to, amount, null);
                            break;
                        case "contract":
                            if (parts.length != 3) {
                                System.out.println("Usage: contract <contract_address> <hex_data>");
                                break;
                            }
                            String contract = parts[1];
                            byte[] data = hexStringToByteArray(parts[2]);
                            System.out.println("Submitting contract to " + contract);
                            future = clientLibrary.submitTransaction(contract, 0, data);
                            break;
                        default:
                            System.out.println("Unknown command: " + cmd);
                            continue;
                    }

                    // Block until f + 1 nodes confirm
                    TransactionResponse receipt = future.get();

                    if (receipt.getStatus() == TransactionStatus.SUCCESS) {
                        System.out.println("Success! Block: " + receipt.getBlockNumber() +
                                " | Gas Used: " + receipt.getGasUsed());
                    } else {
                        System.err.println("Transaction failed: " + receipt.getStatus() + 
                                " - : " + receipt.getErrorMessage());
                    }
                    
                } catch (Exception e) {
                    System.err.println("Request failed: " + e.getMessage());
                }
            }
        }

        clientLibrary.stop();
        System.exit(0);
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
}