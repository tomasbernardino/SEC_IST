package ist.group29.depchain.client;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.web3j.crypto.ECKeyPair;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import ist.group29.depchain.client.ClientMessages.NativeBalanceRequest;
import ist.group29.depchain.client.ClientMessages.NativeBalanceResponse;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.common.crypto.ClientSignature;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.EnvelopeFactory;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.common.network.MessageListener;
import ist.group29.depchain.common.network.ProcessInfo;
import ist.group29.depchain.network.NetworkMessages.Envelope;

public class ClientLibrary implements MessageListener {
    private static final Logger LOG = Logger.getLogger(ClientLibrary.class.getName());

    private final LinkManager linkManager;
    private final int quorumSize; // f + 1

    // Key: the nonce sent; Value: set of node IDs that sent a DECIDED confirmation
    private final Map<String, Set<String>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TransactionResponse>> futures = new ConcurrentHashMap<>();

    private final Map<ByteBuffer, String> txHashToReqKey = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> pendingBalanceRequests = new ConcurrentHashMap<>();
    private final Map<String, NativeBalanceResponse> bestBalanceResponses = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<NativeBalanceResponse>> balanceFutures = new ConcurrentHashMap<>();
    private volatile byte[] lastSentEnvelope;

    private final AtomicLong nonce = new AtomicLong(0);
    // Use a separate counter for balance queries to avoid nonce collision with transactions
    private final AtomicLong queryNonce = new AtomicLong(0);


    private ECKeyPair myBlockchainKeys;
    private String myAddress;

    public ClientLibrary(ProcessInfo self, Map<String, ProcessInfo> nodes,
            KeyPair myKeys, Map<String, PublicKey> nodeKeys, ECKeyPair blockchainKeys) throws Exception {

        int f = nodes.size() / 3;
        this.quorumSize = f + 1;
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


    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    public CompletableFuture<TransactionResponse> submitTransaction(String to, long value, byte[] data) {
        long defaultGasLimit = (data == null || data.length == 0) ? 21000 : 100000;
        return submitTransaction(to, value, data, 1, defaultGasLimit, DEFAULT_TIMEOUT_SECONDS);
    }

    public CompletableFuture<TransactionResponse> submitTransaction(String to, long value, byte[] data, long gasPrice, long gasLimit) {
        return submitTransaction(to, value, data, gasPrice, gasLimit, DEFAULT_TIMEOUT_SECONDS);
    }

    public CompletableFuture<TransactionResponse> submitTransaction(
            String to,
            long value,
            byte[] data,
            long gasPrice,
            long gasLimit,
            long timeoutSeconds) {

        long currentNonce = nonce.getAndIncrement();

        Transaction.Builder txBuilder = Transaction.newBuilder()
                .setFrom(myAddress)
                .setTo(to == null ? "" : to)
                .setValue(value)
                .setNonce(currentNonce)
                .setGasPrice(gasPrice)
                .setGasLimit(gasLimit)
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

        // Broadcast the signed transaction wrapped in an Envelope and store it for potential replay
        broadcastAndRemember(EnvelopeFactory.wrap(signedTx));

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

    public CompletableFuture<NativeBalanceResponse> getNativeBalance(String address) {
        String normalizedAddress = address == null ? "" : address.trim().toLowerCase();
        if (normalizedAddress.startsWith("0x")) {
            normalizedAddress = normalizedAddress.substring(2);
        }
        if (normalizedAddress.length() != 40) {
            throw new IllegalArgumentException("Invalid address: " + address + ". Must contain exactly 40 hex characters.");
        }
        String requestId = myAddress + ":balance:" + queryNonce.getAndIncrement();
        NativeBalanceRequest request = NativeBalanceRequest.newBuilder()
                .setRequestId(requestId)
                .setAddress("0x" + normalizedAddress)
                .build();

        CompletableFuture<NativeBalanceResponse> future = new CompletableFuture<>();
        balanceFutures.put(requestId, future);
        pendingBalanceRequests.put(requestId, ConcurrentHashMap.newKeySet());

        broadcastAndRemember(EnvelopeFactory.wrap(request));

        return future
                .orTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((resp, ex) -> {
                    if (future.isDone() || ex instanceof TimeoutException) {
                        balanceFutures.remove(requestId);
                        pendingBalanceRequests.remove(requestId);
                        bestBalanceResponses.remove(requestId);
                        LOG.info("[Client] Balance request timed out, cleaned up: " + requestId);
                    }
                });
    }

    @Override
    public void onMessage(String senderId, byte[] payload) {
        try {
            // Unwrap the Envelope to extract the TransactionResponse
            Envelope env = Envelope.parseFrom(payload);
            switch (env.getPayloadCase()) {
                case TRANSACTION_RESPONSE -> handleTransactionResponse(senderId, env.getTransactionResponse());
                case NATIVE_BALANCE_RESPONSE -> handleNativeBalanceResponse(senderId, env.getNativeBalanceResponse());
                // default -> LOG.warning("[Client] Unexpected envelope payload from " + senderId + ": " + env.getPayloadCase()); // Uncomment for debugging
            }
        } catch (InvalidProtocolBufferException e) {
            LOG.warning("[Client] Malformed envelope from " + senderId + ": " + e.getMessage());
        } catch (Exception e) {
            LOG.warning("[Client] Failed to process message from " + senderId + ": " + e.getMessage());
        }
    }

    private void handleTransactionResponse(String senderId, TransactionResponse response) {
        ByteBuffer hash = ByteBuffer.wrap(response.getTransactionHash().toByteArray());
        String requestKey = txHashToReqKey.get(hash);
        if (requestKey == null) {
            //LOG.warning("[Client] Received response for unknown transaction hash: " + hash); // Uncomment for debugging
            return;
        }

        Set<String> confirmers = pendingRequests.get(requestKey);
        if (confirmers != null) {
            confirmers.add(senderId);
            LOG.info(String.format("[Client] Received receipt from %s for tx: %s",
                    senderId, requestKey));

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
    }

    private void handleNativeBalanceResponse(String senderId, NativeBalanceResponse response) {
        String requestId = response.getRequestId();
        Set<String> confirmers = pendingBalanceRequests.get(requestId);
        if (confirmers == null) {
            //LOG.warning("[Client] Received balance response for unknown request: " + requestId); // Uncomment for debugging
            return;
        }

        confirmers.add(senderId);
        bestBalanceResponses.compute(requestId, (key, currentBest) -> chooseNewerResponse(currentBest, response));

        if (confirmers.size() >= quorumSize) {
            // Quorum reached - complete the future with the most up-to-date response
            CompletableFuture<NativeBalanceResponse> future = balanceFutures.remove(requestId);
            NativeBalanceResponse bestResponse = bestBalanceResponses.remove(requestId);
            pendingBalanceRequests.remove(requestId);
            if (future != null && bestResponse != null) {
                future.complete(bestResponse);
            }
        }
    }

    private NativeBalanceResponse chooseNewerResponse(NativeBalanceResponse currentBest, NativeBalanceResponse candidate) {
        if (currentBest == null) {
            return candidate;
        }

        return candidate.getBlockNumber() > currentBest.getBlockNumber() ? candidate : currentBest;
    }

    public String getMyAddress() {
        return myAddress;
    }

    public boolean replayLastMessage() {
        byte[] raw = lastSentEnvelope;
        if (raw == null) {
            return false;
        }

        linkManager.broadcast(raw.clone());
        return true;
    }

    public void stop() {
        linkManager.shutdown();
    }

    private void broadcastAndRemember(byte[] envelopeBytes) {
        lastSentEnvelope = envelopeBytes.clone();
        linkManager.broadcast(envelopeBytes);
    }
}
