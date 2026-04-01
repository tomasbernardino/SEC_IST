package ist.group29.depchain.client;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.web3j.crypto.ECKeyPair;

import ist.group29.depchain.client.ClientMessages.NativeBalanceResponse;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.client.ClientMessages.TransactionStatus;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.keys.ConfigReader;
import ist.group29.depchain.common.keys.KeyStoreManager;
import ist.group29.depchain.common.network.ProcessInfo;

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

        System.out.println("\nDepChain Client '" + clientId + "' ready.");
        String istCoinAddress = "0x1111111111111111111111111111111111111111";

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.println("\n=== Main Menu ===");
                System.out.println("1. Check DepCoin Balance");
                System.out.println("2. DepCoin Transfer");
                System.out.println("3. ISTCoin Operation Menu");
                System.out.println("4. Custom ISTCoin Call (Raw Calldata)");
                System.out.println("5. Exit");
                System.out.print("Select an option: ");

                String choice = scanner.nextLine().trim();
                if (choice.equals("5") || choice.equalsIgnoreCase("exit")) break;

                try {
                    CompletableFuture<TransactionResponse> future = null;

                    if (choice.equals("1")) {
                        System.out.print("Address to check [" + clientLibrary.getMyAddress() + "]: ");
                        String address = scanner.nextLine().trim();
                        if (address.isEmpty()) {
                            address = clientLibrary.getMyAddress();
                        }

                        NativeBalanceResponse balanceResponse = clientLibrary.getNativeBalance(address).get();
                        System.out.println("--- Native Balance ---");
                        System.out.println("Address: " + balanceResponse.getAddress());
                        System.out.println("Balance: " + new BigInteger(balanceResponse.getBalance()));
                        System.out.println("State block: " + balanceResponse.getBlockNumber());
                        continue;

                    } else if (choice.equals("2")) {
                        // Native Transfer
                        System.out.print("To Address: ");
                        String to = scanner.nextLine().trim();
                        System.out.print("Amount (DepCoin units): ");
                        long amount = Long.parseLong(scanner.nextLine().trim());
                        long[] gas = promptGas(scanner, true);
                        future = clientLibrary.submitTransaction(to, amount, null, gas[0], gas[1]);

                    } else if (choice.equals("3")) {
                        // IST Coin Operations
                        System.out.println("\n--- IST Coin Operations ---");
                        System.out.println("1. Balance Of");
                        System.out.println("2. Transfer");
                        System.out.println("3. Transfer From");
                        System.out.println("4. Approve");
                        System.out.println("5. Increase Allowance");
                        System.out.println("6. Decrease Allowance");
                        System.out.println("7. Check Allowance");
                        System.out.println("8. Back");
                        System.out.print("Select: ");
                        String subChoice = scanner.nextLine().trim();
                        if (subChoice.equals("8")) continue;

                        String dataHex = "";

                        switch (subChoice) {
                            case "1": // balanceOf(address)
                                System.out.print("Address to check [" + clientLibrary.getMyAddress() + "]: ");
                                String addr = scanner.nextLine().trim();
                                if (addr.isEmpty()) {
                                    addr = clientLibrary.getMyAddress();
                                }
                                dataHex = "70a08231" + encodeAddress(addr);
                                break;
                            case "2": // transfer(address,uint256)
                                System.out.print("Recipient: ");
                                String recipient = scanner.nextLine().trim();
                                System.out.print("Amount: ");
                                long val = Long.parseLong(scanner.nextLine().trim());
                                dataHex = "a9059cbb" + encodeAddress(recipient) + encodeUint(val);
                                break;
                            case "3": // transferFrom(address,address,uint256)
                                System.out.print("From (owner): ");
                                String from = scanner.nextLine().trim();
                                System.out.print("To (recipient): ");
                                String to = scanner.nextLine().trim();
                                System.out.print("Amount: ");
                                long tfVal = Long.parseLong(scanner.nextLine().trim());
                                dataHex = "23b872dd" + encodeAddress(from) + encodeAddress(to) + encodeUint(tfVal);
                                break;
                            case "4": // approve(address,uint256)
                                System.out.print("Spender: ");
                                String spender = scanner.nextLine().trim();
                                System.out.print("Amount: ");
                                long allowanceVal = Long.parseLong(scanner.nextLine().trim());
                                dataHex = "095ea7b3" + encodeAddress(spender) + encodeUint(allowanceVal);
                                break;
                            case "5": // increaseAllowance(address,uint256)
                                System.out.print("Spender: ");
                                String sInc = scanner.nextLine().trim();
                                System.out.print("Added Value: ");
                                long incVal = Long.parseLong(scanner.nextLine().trim());
                                dataHex = "39509351" + encodeAddress(sInc) + encodeUint(incVal);
                                break;
                            case "6": // decreaseAllowance(address,uint256)
                                System.out.print("Spender (decrease): ");
                                String sDec = scanner.nextLine().trim();
                                System.out.print("Subtracted Value: ");
                                long decVal = Long.parseLong(scanner.nextLine().trim());
                                dataHex = "a457c2d7" + encodeAddress(sDec) + encodeUint(decVal);
                                break;
                            case "7": // allowance(address,address)
                                System.out.print("Owner: ");
                                String owner = scanner.nextLine().trim();
                                System.out.print("Spender: ");
                                String spenderQuery = scanner.nextLine().trim();
                                dataHex = "dd62ed3e" + encodeAddress(owner) + encodeAddress(spenderQuery);
                                break;
                            default:
                                System.out.println("Invalid choice.");
                                continue;
                        }

                        if (!dataHex.isEmpty()) {
                            long[] gas = promptGas(scanner, false);
                            future = clientLibrary.submitTransaction(istCoinAddress, 0, hexStringToByteArray(dataHex), gas[0], gas[1]);
                        }

                    } else if (choice.equals("4")) {
                        // Custom Contract Call
                        System.out.print("Contract Address: ");
                        String contract = scanner.nextLine().trim();
                        System.out.print("Call Data (without 0x): ");
                        String hexData = scanner.nextLine().trim();
                        long[] gas = promptGas(scanner, false);
                        future = clientLibrary.submitTransaction(contract, 0, hexStringToByteArray(hexData), gas[0], gas[1]);
                    } else {
                        System.out.println("Unknown option.");
                        continue;
                    }

                    if (future != null) {
                        System.out.println("Transaction submitted. Waiting for confirmation...");
                        TransactionResponse receipt = future.get();

                        if (receipt.getStatus() == TransactionStatus.SUCCESS) {
                            System.out.println("--- Transaction Successful ---");
                            System.out.println("Block: " + receipt.getBlockNumber());
                            System.out.println("Gas Used: " + receipt.getGasUsed());

                            if (!receipt.getReturnData().isEmpty()) {
                                byte[] bytes = receipt.getReturnData().toByteArray();
                                String returnHex = CryptoUtils.bytesToHex(bytes);
                                System.out.println("Return Data (Hex): 0x" + returnHex);
                                // Try parsing as uint256 if return length is 32 bytes
                                if (bytes.length == 32) {
                                    java.math.BigInteger bi = new java.math.BigInteger(1, bytes);
                                    System.out.println("Return Data (Uint256): " + bi.toString());
                                } else if (bytes.length > 0) {
                                    // For other lengths, just show hex representation
                                    System.out.println("Return Data (Length: " + bytes.length + " bytes)");
                                }
                            } else {
                                System.out.println("Return Data: None"); 
                            }
                        } else {
                            System.err.println("--- Transaction Failed ---");
                            System.err.println("Status: " + receipt.getStatus());
                            System.err.println("Error: " + receipt.getErrorMessage());
                        }
                    }

                } catch (ExecutionException e) {
                    // ExecutionException wraps the actual exception from the CompletableFuture
                    Throwable cause = e.getCause();
                    if (cause instanceof TimeoutException) {
                        System.err.println("Execution failed: Request timed out waiting for f+1 responses from nodes.");
                    } else {
                        System.err.println("Execution failed: " + (cause != null ? cause.getClass().getSimpleName() : "ExecutionException") + " - " + (cause != null ? cause.getMessage() : e.getMessage()));
                    }
                } catch (Exception e) {
                    System.err.println("Execution failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            }
        }

        clientLibrary.stop();
        System.exit(0);
    }

    private static long[] promptGas(Scanner scanner, boolean isNativeTransfer) {
        long defaultGasPrice = 1; // DepCoin units
        long defaultGasLimit = isNativeTransfer ? 21000 : 100000;
        System.out.print("Gas Price [" + defaultGasPrice + "]: ");
        String gp = scanner.nextLine().trim();
        long gasPrice = gp.isEmpty() ? defaultGasPrice : Long.parseLong(gp);

        System.out.print("Gas Limit [" + defaultGasLimit + "]: ");
        String gl = scanner.nextLine().trim();
        long gasLimit = gl.isEmpty() ? defaultGasLimit : Long.parseLong(gl);

        if (gasPrice <= 0 || gasLimit <= 0) {
            throw new IllegalArgumentException("Gas price and gas limit must be positive.");
        }

        return new long[]{gasPrice, gasLimit};
    }

    private static String encodeAddress(String addr) {
        String normalized = normalizeHex(addr);
        if (normalized.length() != 40) {
            throw new IllegalArgumentException("Address must contain exactly 40 hex characters. (without 0x)");
        }
        // ABI-encoded addresses occupy a full 32-byte word, left-padded with zeros.
        return String.format("%64s", normalized).replace(' ', '0').toLowerCase();
    }

    private static String encodeUint(long val) {
        String hex = Long.toHexString(val);
        return String.format("%64s", hex).replace(' ', '0').toLowerCase();
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

    private static String normalizeHex(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
