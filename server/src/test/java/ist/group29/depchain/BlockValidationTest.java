package ist.group29.depchain;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

import com.google.protobuf.ByteString;

import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.common.crypto.ClientSignature;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.server.service.BlockchainState;
import ist.group29.depchain.server.service.TransactionManager;
import ist.group29.depchain.server.service.account.EOA;

class BlockValidationTest {

    private BlockchainState state;
    private TransactionManager transactionManager;

    private ECKeyPair senderKeys;
    private String senderAddress;
    private String receiverAddress;

    @BeforeEach
    void setup() throws Exception {
        state = new BlockchainState();
        transactionManager = new TransactionManager(state, mock(LinkManager.class));

        senderKeys = Keys.createEcKeyPair();
        senderAddress = "0x" + CryptoUtils.getAddress(senderKeys);
        receiverAddress = "0x2222222222222222222222222222222222222222";

        state.addAccount(new EOA(CryptoUtils.normalizeAddress(senderAddress), BigInteger.valueOf(120_000), 0));
        state.addAccount(new EOA(CryptoUtils.normalizeAddress(receiverAddress), BigInteger.ZERO, 0));
    }

    @Test
    void testValidateBlockRejectsNonceGapInsideBlock() {
        Transaction tx0 = buildSignedTransfer(0, 1, 21_000, 1);
        Transaction tx2 = buildSignedTransfer(2, 1, 21_000, 1);

        Block block = buildBlock(tx0, tx2);

        assertFalse(transactionManager.validateBlock(block));
    }

    @Test
    void testValidateBlockRejectsDuplicateSenderNoncePair() {
        Transaction txA = buildSignedTransfer(0, 1, 21_000, 1);
        Transaction txB = buildSignedTransfer(0, 2, 21_000, 1);

        Block block = buildBlock(txA, txB);

        assertFalse(transactionManager.validateBlock(block));
    }

    @Test
    void testValidateBlockRejectsDuplicateSignedTransaction() {
        Transaction tx = buildSignedTransfer(0, 1, 21_000, 1);

        Block block = buildBlock(tx, tx);

        assertFalse(transactionManager.validateBlock(block));
    }

    @Test
    void testValidateBlockRejectsWrongPreviousHash() {
        Transaction tx = buildSignedTransfer(0, 1, 21_000, 1);
        Block validBlock = buildBlock(tx);

        Block tampered = validBlock.toBuilder()
                .setPreviousHash("wrong-parent-hash")
                .build();

        assertFalse(transactionManager.validateBlock(tampered));
    }

    @Test
    void testValidateBlockRejectsTamperedBlockHash() {
        // The block hash must still match the serialized block content.
        Transaction tx = buildSignedTransfer(0, 1, 21_000, 1);
        Block validBlock = buildBlock(tx);

        Block tampered = validBlock.toBuilder()
                .setBlockHash("changed-block-hash")
                .build();

        assertFalse(transactionManager.validateBlock(tampered));
    }

    private Block buildBlock(Transaction... transactions) {
        Block.Builder builder = Block.newBuilder()
                .setBlockNumber(state.getBlockNumber() + 1)
                .setPreviousHash(state.getBlockHash())
                .addAllTransactions(java.util.List.of(transactions));

        String blockHash = CryptoUtils.bytesToHex(CryptoUtils.sha256(builder.build().toByteArray()));
        return builder.setBlockHash(blockHash).build();
    }

    private Transaction buildSignedTransfer(long nonce, long value, long gasLimit, long gasPrice) {
        Transaction.Builder txBuilder = Transaction.newBuilder()
                .setFrom(senderAddress)
                .setTo(receiverAddress)
                .setValue(value)
                .setNonce(nonce)
                .setGasPrice(gasPrice)
                .setGasLimit(gasLimit)
                .setData(ByteString.EMPTY);

        Transaction unsignedTx = txBuilder.build();
        ClientSignature signature = CryptoUtils.ecSign(senderKeys, unsignedTx.toByteArray());

        return txBuilder
                .setSigV(ByteString.copyFrom(signature.v()))
                .setSigR(ByteString.copyFrom(signature.r()))
                .setSigS(ByteString.copyFrom(signature.s()))
                .build();
    }
}
