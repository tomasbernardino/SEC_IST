package ist.group29.depchain.server.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.common.crypto.CryptoUtils;
/**
 * Advanced Mempool that organizes transactions by sender and nonce.
 * Ensures strict nonce ordering and prevents duplicate hashes or nonce collisions.
 */
public class Mempool {
    private static final Logger LOG = Logger.getLogger(Mempool.class.getName());

    // Main index: Sender Address -> (Nonce -> Transaction)
    private final Map<String, TreeMap<Long, Transaction>> txsBySender = new HashMap<>();
    
    // Hash index: To prevent duplicate transactions
    private final Map<String, Transaction> seenHashes = new HashMap<>();


    public synchronized boolean addTransaction(Transaction tx) {
        String hash = getHash(tx);
        if (seenHashes.containsKey(hash)) {
            LOG.warning("[Mempool] Rejected: Duplicate transaction hash " + hash);
            return false;
        }

        String sender = CryptoUtils.normalizeAddress(tx.getFrom());
        long nonce = tx.getNonce();

        TreeMap<Long, Transaction> senderTxs = txsBySender.computeIfAbsent(sender, k -> new TreeMap<>());

        if (senderTxs.containsKey(nonce)) {
            LOG.warning("[Mempool] Rejected: Sender " + sender + " already has a tx with nonce " + nonce);
            return false;
        }

        senderTxs.put(nonce, tx);
        seenHashes.put(hash, tx);
        return true;
    }

    public synchronized Set<String> getSenders() {
        return txsBySender.keySet();
    }

    public synchronized Transaction getTransaction(String sender, long nonce) {
        TreeMap<Long, Transaction> senderTxs = txsBySender.get(sender.toLowerCase());
        return (senderTxs != null) ? senderTxs.get(nonce) : null;
    }

    public synchronized TreeMap<Long, Transaction> getSenderTxs(String sender) {
        return txsBySender.get(sender.toLowerCase());
    }

    /**
     * Removes committed transactions from all indexes.
     */
    public synchronized void removeCommittedTxs(List<Transaction> transactions) {
        for (Transaction tx : transactions) {
            String hash = getHash(tx);
            seenHashes.remove(hash);
            
            String sender = CryptoUtils.normalizeAddress(tx.getFrom());
            TreeMap<Long, Transaction> senderTxs = txsBySender.get(sender);
            if (senderTxs != null) {
                senderTxs.remove(tx.getNonce());
                if (senderTxs.isEmpty()) {
                    txsBySender.remove(sender);
                }
            }
        }
    }

    public String getHash(Transaction tx) {
        return CryptoUtils.bytesToHex(CryptoUtils.keccakHash(tx.toByteArray()));
    }

    // FIXME: Not used
    public synchronized void clear() {
        txsBySender.clear();
        seenHashes.clear();
    }

    // FIXME: Not used
    public synchronized int size() {
        return seenHashes.size();
    }
}
