package ist.group29.depchain.server.config;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

import ist.group29.depchain.common.keys.ConfigReader;
import ist.group29.depchain.common.keys.KeyStoreManager;
import ist.group29.depchain.common.network.ProcessInfo;

/**
 * Immutable configuration bundle for a single DepChain node.
 */
public record NodeConfig(
        ProcessInfo self,
        Map<String, ProcessInfo> peers,
        KeyPair identityKeyPair,
        Map<String, PublicKey> peerPublicKeys) {

    public static NodeConfig load(String selfId, Path configPath, Path keysDir, char[] password)
            throws IOException, GeneralSecurityException {

        Map<String, ProcessInfo> allNodes = ConfigReader.parseHosts(configPath);

        ProcessInfo self = allNodes.get(selfId);
        if (self == null) {
            throw new IllegalArgumentException(
                    "Node ID '" + selfId + "' not found in config file: " + configPath);
        }

        Map<String, ProcessInfo> peers = new HashMap<>(allNodes);
        peers.remove(selfId);

        Path selfKeyStore = keysDir.resolve(selfId + ".p12");
        PrivateKey privateKey = KeyStoreManager.loadPrivateKey(selfKeyStore, selfId, password);

        Path truststorePath = keysDir.resolve("truststore.p12");
        Map<String, PublicKey> peerPublicKeys = new HashMap<>();

        // Load all node public keys from the truststore
        for (String id : allNodes.keySet()) {
            if (!id.equals(selfId)) {
                PublicKey pubKey = KeyStoreManager.loadPublicKey(truststorePath, id, password);
                peerPublicKeys.put(id, pubKey);
            }
        }

        // Proactively load client RSA keys for network auth (dynamic APL creation)
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(keysDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("client-") && p.toString().endsWith(".p12"))
                    .forEach(p -> {
                        String clientId = p.getFileName().toString().replace(".p12", "");
                        try {
                            PublicKey clientPubKey = KeyStoreManager.loadPublicKey(truststorePath, clientId, password);
                            peerPublicKeys.put(clientId, clientPubKey);
                        } catch (Exception e) {
                            // Ignore clients that fail to load
                        }
                    });
        }

        PublicKey selfPublicKey = KeyStoreManager.loadPublicKey(truststorePath, selfId, password);
        KeyPair identityKeyPair = new KeyPair(selfPublicKey, privateKey);

        return new NodeConfig(self, peers, identityKeyPair, peerPublicKeys);
    }
}
