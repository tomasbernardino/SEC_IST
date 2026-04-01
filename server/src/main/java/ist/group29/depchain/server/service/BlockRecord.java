package ist.group29.depchain.server.service;

import java.io.Serializable;
import java.util.List;

import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;

/**
 * Data record for persistence of blocks and execution results.
 */
public record BlockRecord(
        long blockNumber,
        String blockHash,
        String previousBlockHash,
        List<Transaction> transactions,
        List<TransactionResponse> receipts
) implements Serializable {

    public Block toProtoBlock() {
        Block.Builder builder = Block.newBuilder()
                .setBlockNumber(blockNumber)
                .setBlockHash(blockHash)
                .setPreviousHash(previousBlockHash)
                .addAllTransactions(transactions)
                .addAllReceipts(receipts);
        
        return builder.build();
    }
}
