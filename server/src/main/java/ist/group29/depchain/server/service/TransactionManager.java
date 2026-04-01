package ist.group29.depchain.server.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.google.protobuf.ByteString;

import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.client.ClientMessages.NativeBalanceRequest;
import ist.group29.depchain.client.ClientMessages.NativeBalanceResponse;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.client.ClientMessages.TransactionStatus;
import ist.group29.depchain.common.crypto.ClientSignature;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.EnvelopeFactory;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.network.NetworkMessages.Envelope;
import ist.group29.depchain.server.service.account.BlockchainAccount;
import ist.group29.depchain.server.service.account.EOA;

public class TransactionManager {

    private static final Logger LOG = Logger.getLogger(TransactionManager.class.getName());
    private static final long BLOCK_GAS_LIMIT = 500000;

    private final BlockchainState state;
    private final Mempool mempool = new Mempool();
    private final LinkManager linkManager;
    private final TransactionExecutor executor;

    // Mapping from Transaction Hash (hex) to the senderId (Process ID) of the client
    private final Map<String, String> txHashToSenderId = new ConcurrentHashMap<>();

    public TransactionManager(BlockchainState state, LinkManager linkManager) {
        this.state = state;
        this.linkManager = linkManager;
        this.executor = new TransactionExecutor(state);
    }

    public Block buildBlock() {
        LOG.info("[TransactionManager] Building consensus block...");

        long blockNumber = state.getBlockNumber() + 1;
        String previousHash = state.getBlockHash();
        List<Transaction> candidateTxs = new ArrayList<>();
        long currentBlockGas = 0;
        Set<String> senders = mempool.getSenders();

        // Prioritized Picking: We maintain a "frontier" of the next executable transaction for each sender.
        // We pick the highest-fee transaction from the frontier, add it, and then advance that sender's frontier.
        
        // Initial frontier (one tx per sender)
        Map<String, Long> expectedNonces = new HashMap<>();
        for (String sender : senders) {
            BlockchainAccount acc = state.getAccount(sender);
            long nextNonce = (acc instanceof EOA eoa) ? eoa.getNonce() : 0;
            expectedNonces.put(sender, nextNonce);
        }

        while (true) {
            Transaction bestTx = null;
            String bestSender = null;

            for (String sender : senders) {
                long nonce = expectedNonces.get(sender);
                Transaction tx = mempool.getTransaction(sender, nonce);
                
                if (tx != null) {
                    if (bestTx == null || tx.getGasPrice() > bestTx.getGasPrice()) {
                        bestTx = tx;
                        bestSender = sender;
                    }
                }
            }

            if (bestTx == null) break; // No more executable txs found

            // Check if this best transaction fits the gas budget
            if (currentBlockGas + bestTx.getGasLimit() <= BLOCK_GAS_LIMIT) {
                candidateTxs.add(bestTx);
                currentBlockGas += bestTx.getGasLimit();
                expectedNonces.put(bestSender, bestTx.getNonce() + 1);
            } else {
                // The block cannot fit the highest-fee transaction
                break;
            }
        }

        // Create block without blockHash to get a stable byte array
        Block.Builder builder = Block.newBuilder()
                .addAllTransactions(candidateTxs)
                .setBlockNumber(blockNumber)
                .setPreviousHash(previousHash);
        
        // Fingerprint the block content and set the current hash
        byte[] contentHash = CryptoUtils.sha256(builder.build().toByteArray());
        builder.setBlockHash(CryptoUtils.bytesToHex(contentHash));
        
        LOG.info("[TransactionManager] Finished building Block #" + blockNumber + " with " + candidateTxs.size() + " transactions.");
        return builder.build();
    }

    public boolean validateBlock(Block block) {
        if (block == null)
            return false;

        long blockGas = 0;

        for (Transaction tx : block.getTransactionsList()) {
            blockGas += tx.getGasLimit();
            if (blockGas > BLOCK_GAS_LIMIT) {
                LOG.warning("[TransactionManager] Block exceeds gas limit: " + blockGas + " > " + BLOCK_GAS_LIMIT);
                return false;
            }

            if (validateIncomingTx(tx) != null) {
                LOG.warning("[TransactionManager] Block contains invalid transaction from " + tx.getFrom());
                return false;
            }
        }
        return true;
    }

    public List<TransactionResponse> executeBlock(Block block, long blockNumber) {
        return executor.executeBlock(block, blockNumber);
    }

    /**
     * Entry point for new client transactions. Sets them to the mempool after validation.
     * If the transaction is rejected, sends the response to the client that sent it.
     */
    public void addPendingTx(String senderId, Transaction tx) {
        TransactionResponse rejection = validateIncomingTx(tx);
        if (rejection != null) {
            Envelope responseEnv = Envelope.newBuilder()
                    .setTransactionResponse(rejection)
                    .build();
            linkManager.send(senderId, responseEnv.toByteArray());
            return;
        }

        String hash = mempool.getHash(tx);
        if (mempool.addTransaction(tx)) {
            LOG.info("[TransactionManager] Added transaction from " + tx.getFrom() + " (nonce=" + tx.getNonce() + ") to mempool");
            txHashToSenderId.put(hash, senderId);
        } else {
            Envelope responseEnv = Envelope.newBuilder()
                    .setTransactionResponse(buildResponse(tx, TransactionStatus.FAILURE, "Duplicate transaction hash " + hash))
                    .build();
            linkManager.send(senderId, responseEnv.toByteArray());
        }
    }

    public String getSenderIdAndRemove(String txHash) {
        return txHashToSenderId.remove(txHash);
    }

    public void handleNativeBalanceRequest(String senderId, NativeBalanceRequest request) {

        String senderAddr = CryptoUtils.normalizeAddress(request.getAddress());
        BlockchainAccount account = state.getAccount(senderAddr);
        BigInteger balance = account != null ? account.getBalance() : BigInteger.ZERO;

        NativeBalanceResponse response = NativeBalanceResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setAddress("0x" + senderAddr)
                .setBalance(balance.toString())
                .setBlockNumber(state.getBlockNumber())
                .build();

        linkManager.send(senderId, EnvelopeFactory.wrap(response));
    }


    /**
     * Removes committed transactions from the mempool.
     */
    public void removeCommittedTxs(List<Transaction> transactions) {
        mempool.removeCommittedTxs(transactions);
    }

    /**
     * Validates an incoming transaction.
     * @return A TransactionResponse if the transaction is invalid, null otherwise.
     */
    private TransactionResponse validateIncomingTx(Transaction tx) {
        LOG.info("[TransactionManager] Validating transaction from " + tx.getFrom() + " (nonce=" + tx.getNonce() + ")");

        // Intrinsic Gas Check
        long intrinsicGas = TransactionExecutor.calculateIntrinsicGas(tx);
        if (tx.getGasLimit() < intrinsicGas) {
            String msg = "gas_limit (" + tx.getGasLimit() + ") < intrinsic_gas (" + intrinsicGas + ")";
            LOG.warning("[TransactionManager] Transaction rejected: " + msg);
            return buildResponse(tx, TransactionStatus.OUT_OF_GAS, msg);
        }

        // Gas Price Check
        if (tx.getGasPrice() <= 0) {
            String msg = "gas price must be positive (got " + tx.getGasPrice() + ")";
            LOG.warning("[TransactionManager] Transaction rejected: " + msg);
            return buildResponse(tx, TransactionStatus.FAILURE, msg);
        }

        // Check if transaction gas limit exceeds block gas limit
        if (tx.getGasLimit() > BLOCK_GAS_LIMIT) {
            String msg = "transaction gas limit (" + tx.getGasLimit() + ") exceeds block gas limit (" + BLOCK_GAS_LIMIT + ")";
            LOG.warning("[TransactionManager] Transaction rejected: " + msg);
            return buildResponse(tx, TransactionStatus.FAILURE, msg);
        }

        String senderAddr = CryptoUtils.normalizeAddress(tx.getFrom());
        BlockchainAccount account = state.getAccount(senderAddr);

        if (account == null) {
            String msg = "sender account " + senderAddr + " does not exist";
            LOG.warning("[TransactionManager] Transaction rejected: " + msg);
            return buildResponse(tx, TransactionStatus.FAILURE, msg); // FIXME: Or custom status
        }

        if (!(account instanceof EOA eoa)) {
            String msg = "sender " + senderAddr + " is a contract, not an EOA";
            LOG.warning("[TransactionManager] Transaction rejected: " + msg);
            return buildResponse(tx, TransactionStatus.FAILURE, msg);
        }

        // Nonce check
        if (tx.getNonce() < eoa.getNonce()) {
            String msg = "nonce too low (expected >= " + eoa.getNonce() + ", got " + tx.getNonce() + ")";
            LOG.warning("[TransactionManager] Transaction rejected: " + msg);
            return buildResponse(tx, TransactionStatus.INVALID_NONCE, msg);
        }

        // Fund check
        BigInteger gasFee = BigInteger.valueOf(tx.getGasLimit()).multiply(BigInteger.valueOf(tx.getGasPrice()));
        BigInteger totalCost = gasFee.add(BigInteger.valueOf(tx.getValue()));

        if (eoa.getBalance().compareTo(totalCost) < 0) {
            String msg = "insufficient funds (cost=" + totalCost + ", balance=" + eoa.getBalance() + ")";
            LOG.warning("[TransactionManager] Transaction rejected: " + msg);
            return buildResponse(tx, TransactionStatus.INSUFFICIENT_FUNDS, msg);
        }

        // reject unknown "to" addresses for native transfers 
        if (!tx.getTo().isEmpty()) {
            String destinationAddr = CryptoUtils.normalizeAddress(tx.getTo());
            BlockchainAccount destAcc = state.getAccount(destinationAddr);
            if (destAcc == null) {
                String msg = "recipient account " + destinationAddr + " does not exist";
                LOG.warning("[TransactionManager] Transaction rejected: " + msg);
                return buildResponse(tx, TransactionStatus.FAILURE, msg);
            }
        }

        // Signature check
        try {
            ClientSignature signature = new ClientSignature(
                    tx.getSigV().toByteArray(),
                    tx.getSigR().toByteArray(),
                    tx.getSigS().toByteArray());

            // Reconstruct unsigned transaction (without signature fields) —
            // must match the bytes the client signed before setting sig_v/r/s.
            Transaction unsignedTx = Transaction.newBuilder()
                    .setFrom(tx.getFrom())
                    .setTo(tx.getTo())
                    .setValue(tx.getValue())
                    .setNonce(tx.getNonce())
                    .setGasPrice(tx.getGasPrice())
                    .setGasLimit(tx.getGasLimit())
                    .setData(tx.getData())
                    .build();

            boolean sigValid = CryptoUtils.ecVerify(signature, tx.getFrom(), unsignedTx.toByteArray());

            if (!sigValid) {
                LOG.warning("[TransactionManager] Transaction rejected: invalid ECDSA signature from sender " + tx.getFrom());
                return buildResponse(tx, TransactionStatus.INVALID_SIGNATURE, "Invalid signature");
            }
        } catch (Exception e) {
            LOG.warning("[TransactionManager] Transaction rejected: error during signature recovery: " + e.getMessage());
            return buildResponse(tx, TransactionStatus.INVALID_SIGNATURE, "Signature error: " + e.getMessage());
        }

        return null; // Valid
    }

    private TransactionResponse buildResponse(Transaction tx, TransactionStatus status, String error) {
        return TransactionResponse.newBuilder()
                .setStatus(status)
                .setTransactionHash(ByteString.copyFrom(CryptoUtils.keccakHash(tx.toByteArray())))
                .setErrorMessage(error)
                .build();
    }

    public void sendResponses(List<TransactionResponse> results) {
        if (linkManager != null) {
            for (TransactionResponse res : results) {
                String txHash = CryptoUtils.bytesToHex(res.getTransactionHash().toByteArray());
                String senderId = getSenderIdAndRemove(txHash);
                if (senderId != null) {
                    LOG.info("[Service] Sending final receipt for " + txHash + " to " + senderId);
                    Envelope env = Envelope.newBuilder()
                            .setTransactionResponse(res)
                            .build();
                    linkManager.send(senderId, env.toByteArray());
                }
            }
        }
    }
}
