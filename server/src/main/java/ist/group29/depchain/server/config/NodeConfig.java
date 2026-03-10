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
        Map<String, PublicKey> peerPublicKeys
) {

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
        for (String peerId : peers.keySet()) {
            PublicKey pubKey = KeyStoreManager.loadPublicKey(truststorePath, peerId, password);
            peerPublicKeys.put(peerId, pubKey);
        }

        PublicKey selfPublicKey = KeyStoreManager.loadPublicKey(truststorePath, selfId, password);
        KeyPair identityKeyPair = new KeyPair(selfPublicKey, privateKey);

        return new NodeConfig(self, peers, identityKeyPair, peerPublicKeys);
    }
}
