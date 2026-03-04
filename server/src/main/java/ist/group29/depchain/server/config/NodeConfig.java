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
 *
 * <p>Holds the node's own identity ({@link ProcessInfo}), its RSA key pair,
 * the network information for all peers, and their public keys.
 * This object is the single parameter needed to instantiate a
 * {@link pt.tecnico.depchain.network.LinkManager}.
 *
 * <p>Usage:
 * <pre>
 *   NodeConfig config = NodeConfig.load("node-0", Path.of("hosts.config"),
 *                                       Path.of("keys"), "changeit".toCharArray());
 *   LinkManager lm = new LinkManager(config.self(), config.peers(),
 *                                     config.identityKeyPair(), config.peerPublicKeys(),
 *                                     myListener);
 * </pre>
 */
public record NodeConfig(
        ProcessInfo self,
        Map<String, ProcessInfo> peers,
        KeyPair identityKeyPair,
        Map<String, PublicKey> peerPublicKeys
) {

    /**
     * Loads a complete node configuration from disk.
     *
     * @param selfId     the ID of this node (must match an entry in hosts.config)
     * @param configPath path to the hosts.config file
     * @param keysDir    directory containing the per-node keystores and truststore
     * @param password   password for all keystores
     * @return fully populated NodeConfig
     */
    public static NodeConfig load(String selfId, Path configPath, Path keysDir, char[] password)
            throws IOException, GeneralSecurityException {

        // 1. Parse the network configuration
        Map<String, ProcessInfo> allNodes = ConfigReader.parseHosts(configPath);

        ProcessInfo self = allNodes.get(selfId);
        if (self == null) {
            throw new IllegalArgumentException(
                    "Node ID '" + selfId + "' not found in config file: " + configPath);
        }

        // 2. Build the peers map (everyone except self)
        Map<String, ProcessInfo> peers = new HashMap<>(allNodes);
        peers.remove(selfId);

        // 3. Load this node's private key from its own keystore
        Path selfKeyStore = keysDir.resolve(selfId + ".p12");
        PrivateKey privateKey = KeyStoreManager.loadPrivateKey(selfKeyStore, selfId, password);

        // 4. Load all peer public keys from the shared truststore
        Path truststorePath = keysDir.resolve("truststore.p12");
        Map<String, PublicKey> peerPublicKeys = new HashMap<>();
        for (String peerId : peers.keySet()) {
            PublicKey pubKey = KeyStoreManager.loadPublicKey(truststorePath, peerId, password);
            peerPublicKeys.put(peerId, pubKey);
        }

        // 5. Also load our own public key for the KeyPair
        PublicKey selfPublicKey = KeyStoreManager.loadPublicKey(truststorePath, selfId, password);
        KeyPair identityKeyPair = new KeyPair(selfPublicKey, privateKey);

        return new NodeConfig(self, peers, identityKeyPair, peerPublicKeys);
    }
}
