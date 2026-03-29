package ist.group29.depchain.client;

import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.common.network.MessageListener;
import ist.group29.depchain.common.network.ProcessInfo;
import ist.group29.depchain.common.network.EnvelopeFactory;
import ist.group29.depchain.network.NetworkMessages.Envelope;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.common.crypto.ClientSignature;
import ist.group29.depchain.common.crypto.CryptoUtils;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.web3j.crypto.ECKeyPair;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

public class ClientLibrary implements MessageListener {
    private static final Logger LOG = Logger.getLogger(ClientLibrary.class.getName());

    private final LinkManager linkManager;
    private final int quorumSize; // f + 1

    // Key: the nonce sent; Value: set of node IDs that sent a DECIDED confirmation
    private final Map<String, Set<String>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TransactionResponse>> futures = new ConcurrentHashMap<>();

    private final Map<ByteBuffer, String> txHashToReqKey = new ConcurrentHashMap<>();
    // private final KeyPair myKeys;

    private final AtomicLong nonce = new AtomicLong(0);


    private ECKeyPair myBlockchainKeys;
    private String myAddress;

    public ClientLibrary(ProcessInfo self, Map<String, ProcessInfo> nodes,
            KeyPair myKeys, Map<String, PublicKey> nodeKeys, ECKeyPair blockchainKeys) throws Exception {

        int f = nodes.size() / 3;
        this.quorumSize = f + 1;
        // this.myKeys = myKeys;
        // Network Layer Keys
        this.linkManager = new LinkManager(self, nodes, myKeys, nodeKeys);
        this.linkManager.setMessageListener(this);

        // Blockchain Layer Keys
        this.myBlockchainKeys = blockchainKeys;
        this.myAddress = "0x" + CryptoUtils.getAddress(this.myBlockchainKeys);

    }

    public void start() {
        linkManager.start();
    }


    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    public CompletableFuture<TransactionResponse> submitTransaction(String to, long value, byte[] data) {
        return submitTransaction(to, value, data, DEFAULT_TIMEOUT_SECONDS);
    }

    public CompletableFuture<TransactionResponse> submitTransaction(String to, long value, byte[] data, long timeoutSeconds) {
        long currentNonce = nonce.getAndIncrement();

        Transaction.Builder txBuilder = Transaction.newBuilder()
                .setFrom(myAddress)
                .setTo(to == null ? "" : to)
                .setValue(value)
                .setNonce(currentNonce)
                .setGasPrice(1) 
                .setGasLimit(21000)
                .setData(data == null ? ByteString.EMPTY : ByteString.copyFrom(data));

        Transaction unsignedTx = txBuilder
            .build();
        
        byte[] unsignedBytes = unsignedTx.toByteArray();
        ClientSignature sig = CryptoUtils.ecSign(myBlockchainKeys, unsignedBytes);

        Transaction signedTx = txBuilder
            .setSigV(ByteString.copyFrom(sig.v()))
            .setSigR(ByteString.copyFrom(sig.r()))
            .setSigS(ByteString.copyFrom(sig.s()))
            .build();

        byte[] txBytes = signedTx.toByteArray();
        byte[] txHash = CryptoUtils.keccakHash(txBytes);

        CompletableFuture<TransactionResponse> future = new CompletableFuture<>();
        String requestKey = this.myAddress + ":" + currentNonce;
        ByteBuffer txHashKey = ByteBuffer.wrap(txHash);

        // track transaction completion
        futures.put(requestKey, future); 
        pendingRequests.put(requestKey, ConcurrentHashMap.newKeySet());
        txHashToReqKey.put(txHashKey, requestKey);

        // Broadcast the signed transaction wrapped in an Envelope
        linkManager.broadcast(EnvelopeFactory.wrap(signedTx));

        // Wrap with timeout + cleanup on expiry
        return future
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((resp, ex) -> {
                    if (ex instanceof TimeoutException) {
                        futures.remove(requestKey);
                        pendingRequests.remove(requestKey);
                        txHashToReqKey.remove(txHashKey);
                        LOG.info("[Client] Transaction timed out, cleaned up: " + requestKey);
                    }
                });
    }

    @Override
    public void onMessage(String senderId, byte[] payload) {
        try {
            // Unwrap the Envelope to extract the TransactionResponse
            Envelope env = Envelope.parseFrom(payload);
            if (env.getPayloadCase() != Envelope.PayloadCase.TRANSACTION_RESPONSE) {
                LOG.warning("[Client] Unexpected envelope payload from " + senderId + ": " + env.getPayloadCase());
                return;
            }
            TransactionResponse response = env.getTransactionResponse();

            ByteBuffer hash = ByteBuffer.wrap(response.getTransactionHash().toByteArray());
            String requestKey = txHashToReqKey.get(hash);
            if (requestKey == null) {
                LOG.warning("[Client] Received response for unknown transaction hash: " + hash);
                return;
            }

            Set<String> confirmers = pendingRequests.get(requestKey);
            if (confirmers != null) {
                confirmers.add(senderId);
                LOG.info(String.format("[Client] Received receipt from %s for tx: %s", 
                            senderId, requestKey));

                // Quorum Check: Have we heard from f+1 different nodes?
                if (confirmers.size() >= quorumSize) {
                    CompletableFuture<TransactionResponse> future = futures.remove(requestKey);
                    if (future != null) {
                        pendingRequests.remove(requestKey);
                        txHashToReqKey.remove(hash);
                        LOG.info("[Client] Quorum reached for tx: " + requestKey);
                        future.complete(response);
                    }
                }
            }
        } catch (InvalidProtocolBufferException e) {
            LOG.warning("[Client] Malformed envelope from " + senderId + ": " + e.getMessage());
        } catch (Exception e) {
            LOG.warning("[Client] Failed to process message from " + senderId + ": " + e.getMessage());
        }
    }

    public void stop() {
        linkManager.shutdown();
    }
}