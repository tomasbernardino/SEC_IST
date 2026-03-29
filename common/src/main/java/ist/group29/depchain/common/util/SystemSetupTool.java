package ist.group29.depchain.common.util;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Unified offline tool that generates all static system artifacts:
 * 1. RSA node keys (via keytool subprocess)
 * 2. Client RSA keys for network auth (via keytool subprocess)
 * 3. ECDSA client keys for blockchain identity (via web3j)
 * 4. addresses.config (client blockchain addresses)
 * 5. genesis.json (EOA accounts + contract accounts)
 *
 * Usage: SystemSetupTool <nrNodes> <nrClients> <keysDir> <hostsConfig>
 * <password>
 */
public class SystemSetupTool {

    private static final String DOMAIN = "CN=%s";
    private static final BigInteger INITIAL_BALANCE = BigInteger.valueOf(100_000);
    private static final String IST_COIN_ADDRESS = "1111111111111111111111111111111111111111";

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println(
                    "Usage: SystemSetupTool <nrNodes> <nrClients> <keysDir> <storageDir> <hostsConfig> <outputDir> <password>");
            System.exit(1);
        }

        int nrNodes = Integer.parseInt(args[0]);
        int nrClients = Integer.parseInt(args[1]);
        Path keysDir = Path.of(args[2]);
        Path storageDir = Path.of(args[3]);
        Path hostsConfig = Path.of(args[4]);
        Path outputDir = Path.of(args[5]);
        String password = args[6];

        // Clean and recreate keys directory
        if (Files.exists(keysDir)) {
            Files.walk(keysDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            /* ignore */ }
                    });
        }
        Files.createDirectories(keysDir);

        Path truststore = keysDir.resolve("truststore.p12");

        // ═══════════════════════════════════════════════════════
        // Step 1: Generate RSA node keys
        // ═══════════════════════════════════════════════════════
        System.out.println("=== Generating RSA node keys ===");
        List<String> nodeIds = new ArrayList<>();
        for (int i = 0; i < nrNodes; i++) {
            String nodeId = "node-" + i;
            nodeIds.add(nodeId);
            generateRSAKey(nodeId, keysDir, truststore, password);
        }

        // ═══════════════════════════════════════════════════════
        // Step 2: Generate client RSA keys (for network auth)
        // ═══════════════════════════════════════════════════════
        System.out.println("=== Generating client RSA keys (network auth) ===");
        List<String> clientIds = new ArrayList<>();
        for (int i = 0; i < nrClients; i++) {
            String clientId = "client-" + i;
            clientIds.add(clientId);
            generateRSAKey(clientId, keysDir, truststore, password);
        }

        // ═══════════════════════════════════════════════════════
        // Step 3: Generate ECDSA client keys (blockchain identity)
        // ═══════════════════════════════════════════════════════
        System.out.println("=== Generating ECDSA client keys (blockchain) ===");
        List<ECKeyPair> clientKeyPairs = new ArrayList<>();
        Map<String, String> clientAddresses = new LinkedHashMap<>();

        for (int i = 0; i < nrClients; i++) {
            String clientId = "client-" + i;
            ECKeyPair keyPair = Keys.createEcKeyPair();
            clientKeyPairs.add(keyPair);

            // Save ECDSA key to file
            byte[] serialized = Keys.serialize(keyPair);
            Path keyFile = keysDir.resolve(clientId + ".key");
            Files.write(keyFile, serialized);

            String address = Keys.getAddress(keyPair);
            clientAddresses.put(clientId, address);
            System.out.println("  " + clientId + " → 0x" + address);
        }

        // ═══════════════════════════════════════════════════════
        // Step 4: Write addresses.config
        // ═══════════════════════════════════════════════════════
        Path addressesConfig = outputDir.resolve("addresses.config");
        System.out.println("=== Writing " + addressesConfig + " ===");
        try (PrintWriter writer = new PrintWriter(new FileWriter(addressesConfig.toFile()))) {
            for (Map.Entry<String, String> entry : clientAddresses.entrySet()) {
                writer.println(entry.getKey() + " 0x" + entry.getValue());
            }
        }
        // Clean and recreate storage directory
        if (Files.exists(storageDir)) {
            Files.walk(storageDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            /* ignore */ }
                    });
        }
        Files.createDirectories(storageDir);
        // ═══════════════════════════════════════════════════════
        // Step 5: Generate genesis.json
        // ═══════════════════════════════════════════════════════
        System.out.println("=== Generating genesis.json ===");
        generateGenesis(clientAddresses, storageDir.resolve("genesis.json"));

        System.out.println("\n=== System setup complete! ===");
        System.out.println("Keys directory: " + keysDir);
        System.out.println("Truststore: " + truststore);
        System.out.println("Addresses config: " + addressesConfig);
    }

    private static void generateRSAKey(String alias, Path keysDir, Path truststore, String password)
            throws IOException, InterruptedException {
        Path keystore = keysDir.resolve(alias + ".p12");
        Path certFile = keysDir.resolve(alias + ".cer");

        // Generate RSA key pair
        runKeytool("-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365",
                "-keystore", keystore.toString(),
                "-storepass", password,
                "-dname", String.format(DOMAIN, alias),
                "-storetype", "PKCS12");

        // Export certificate
        runKeytool("-exportcert",
                "-alias", alias,
                "-keystore", keystore.toString(),
                "-storepass", password,
                "-file", certFile.toString());

        // Import into shared truststore
        runKeytool("-importcert",
                "-alias", alias,
                "-keystore", truststore.toString(),
                "-storepass", password,
                "-file", certFile.toString(),
                "-noprompt",
                "-storetype", "PKCS12");

        // Clean up cert file
        Files.deleteIfExists(certFile);
        System.out.println("  Generated RSA key for: " + alias);
    }

    private static void runKeytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("keytool");
        command.addAll(List.of(args));
        Process proc = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = proc.inputReader().lines().collect(Collectors.joining("\n"));
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            System.err.println("keytool failed: " + output);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Genesis generation
    // ═══════════════════════════════════════════════════════════

    private static void generateGenesis(Map<String, String> clientAddresses, Path genesisPath)
            throws IOException {
        GenesisBlock genesis = new GenesisBlock();

        // Target client-0 as the admin who receives the entire token supply
        String client0Address = clientAddresses.get("client-0");

        // Add EOA accounts for each client
        for (Map.Entry<String, String> entry : clientAddresses.entrySet()) {
            GenesisEOA account = new GenesisEOA();
            account.balance = INITIAL_BALANCE;
            genesis.state.put(entry.getValue(), account);
        }

        GenesisContractAccount istCoin = new GenesisContractAccount();

        // 1. Read compiled bytecode
        Path binPath = Path.of("..", "server", "target", "generated-sources", "solidity", "bin", "org", "web3j",
                "model",
                "ISTCoin.bin");
        // Depending on where it runs (from server or root), adjust path
        if (!Files.exists(binPath)) {
            binPath = Path.of("server", "target", "generated-sources", "solidity", "bin", "org", "web3j", "model",
                    "ISTCoin.bin");
        }

        if (Files.exists(binPath)) {
            istCoin.code = Files.readString(binPath).trim();
            System.out.println("  Injected ISTCoin bytecode (" + istCoin.code.length() + " chars)");
        } else {
            System.err.println("  WARNING: ISTCoin.bin not found! Ensure 'mvn compile' ran first. (Looked at: "
                    + binPath.toAbsolutePath() + ")");
            istCoin.code = "";
        }

        istCoin.balance = BigInteger.ZERO;

        // 2. Pre-compute EVM storage slot for client-0's balance
        // ERC20 _balances is the 1st state variable (slot 0)
        // ERC20 _totalSupply is the 3rd state variable (slot 2)
        String totalSupplyHex = "0x" + new BigInteger("10000000000").toString(16); // 100,000,000 * 10^2 decimals

        if (client0Address != null) {
            String paddedAddress = leftPad(client0Address, 64, '0');
            String paddedSlotIndex = leftPad("0", 64, '0'); // slot 0

            byte[] concatenated = org.web3j.utils.Numeric.hexStringToByteArray(paddedAddress + paddedSlotIndex);
            String balanceSlot = org.web3j.utils.Numeric.toHexStringNoPrefix(org.web3j.crypto.Hash.sha3(concatenated));

            istCoin.storage.put(balanceSlot, totalSupplyHex);
            istCoin.storage.put("2", totalSupplyHex); // slot 2 is totalSupply
            System.out.println("  Injected 100,000,000 IST tokens to client-0 (" + client0Address + ")");
        }

        genesis.state.put(IST_COIN_ADDRESS, istCoin);

        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

        // Dynamically calculate the Block Hash mathematically by hashing the genesis
        // state
        String stateJson = gson.toJson(genesis.state);
        byte[] hashBytes = org.web3j.crypto.Hash.sha3(stateJson.getBytes());
        genesis.block_hash = org.web3j.utils.Numeric.toHexStringNoPrefix(hashBytes);

        // Export the final file
        String json = gson.toJson(genesis);
        try (FileWriter writer = new FileWriter(genesisPath.toFile())) {
            writer.write(json);
        }
        System.out.println("  Genesis written to: " + genesisPath);
        System.out.println("  Genesis Hash:       " + genesis.block_hash);
    }

    private static String leftPad(String str, int length, char padChar) {
        if (str.length() >= length)
            return str;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length - str.length(); i++) {
            sb.append(padChar);
        }
        sb.append(str);
        return sb.toString();
    }

    // Genesis data classes
    static class GenesisBlock {
        long block_number = 0;
        String block_hash = "";
        String previous_block_hash = null;
        List<Object> transactions = List.of();
        Map<String, GenesisAccount> state = new LinkedHashMap<>();
    }

    static class GenesisAccount {
        BigInteger balance = BigInteger.ZERO;
    }

    static class GenesisEOA extends GenesisAccount {
        int nonce = 0;
    }

    static class GenesisContractAccount extends GenesisAccount {
        String code = "";
        Map<String, String> storage = new LinkedHashMap<>();
    }
}
