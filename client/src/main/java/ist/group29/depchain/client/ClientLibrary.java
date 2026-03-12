package ist.group29.depchain.client;

import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.common.network.MessageListener;
import ist.group29.depchain.common.network.ProcessInfo;
import ist.group29.depchain.client.ClientMessages.ClientRequest;
import ist.group29.depchain.client.ClientMessages.ClientResponse;
import ist.group29.depchain.common.crypto.CryptoUtils;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class ClientLibrary implements MessageListener {
    private static final Logger LOG = Logger.getLogger(ClientLibrary.class.getName());

    private final LinkManager linkManager;
    private final int quorumSize; // f + 1

    // Key: the string sent; Value: set of node IDs that sent a DECIDED confirmation
    private final Map<String, Set<String>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();
    private final KeyPair myKeys;

    public ClientLibrary(ProcessInfo self, Map<String, ProcessInfo> nodes,
            KeyPair myKeys, Map<String, PublicKey> nodeKeys) throws Exception {

        int f = nodes.size() / 3;
        this.quorumSize = f + 1;
        this.myKeys = myKeys;
        this.linkManager = new LinkManager(self, nodes, myKeys, nodeKeys);
        this.linkManager.setMessageListener(this);
    }

    public void start() {
        linkManager.start();
    }

    /** Broadcasts the string to all nodes and returns a Future. */
    public CompletableFuture<Void> append(String value) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        futures.put(value, future);
        pendingRequests.put(value, ConcurrentHashMap.newKeySet());

        long timestamp = System.currentTimeMillis();

        try {
            // Build the ClientRequest (protobuf)
            ClientRequest.Builder reqBuilder = ClientRequest.newBuilder()
                    .setOp(ClientRequest.Operation.APPEND)
                    .setValue(value)
                    .setTimestamp(timestamp);

            // Sign: (op, value, timestamp)
            byte[] signature = CryptoUtils.sign(myKeys.getPrivate(),
                    CryptoUtils.toBytes(ClientRequest.Operation.APPEND.name()),
                    CryptoUtils.toBytes(value),
                    CryptoUtils.toBytes(timestamp));

            ClientRequest req = reqBuilder.setSignature(com.google.protobuf.ByteString.copyFrom(signature)).build();

            LOG.fine("[Client] Sending signed request for: " + value);
            // Wrap proto in top level Message for linkManager
            linkManager.broadcast(req.toByteArray());

        } catch (GeneralSecurityException e) {
            LOG.severe("Failed to sign client request: " + e.getMessage());
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void onMessage(String senderId, byte[] payload) {
        try {
            // Parse response
            ClientResponse response = ClientResponse.parseFrom(payload);
            String value = response.getValue();

            // Check if we are actually waiting for this value
            Set<String> confirmers = pendingRequests.get(value);
            if (confirmers != null) {
                confirmers.add(senderId);
                LOG.info(String.format("[Client] Received DECIDED from %s for value: %s", senderId, value));

                // Quorum Check: Have we heard from f+1 different nodes?
                if (confirmers.size() >= quorumSize) {
                    CompletableFuture<Void> future = futures.remove(value);
                    if (future != null && !future.isDone()) {
                        LOG.info("[Client] Quorum reached for: " + value);
                        pendingRequests.remove(value);
                        future.complete(null); // This unlocks client.append(val).get()
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to process message from " + senderId + ": " + e.getMessage());
        }
    }

    public void stop() {
        linkManager.shutdown();
    }
}