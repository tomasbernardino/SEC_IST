package ist.group29.depchain;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

import com.google.protobuf.ByteString;

import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.client.ClientMessages.TransactionStatus;
import ist.group29.depchain.common.crypto.ClientSignature;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.EnvelopeFactory;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.network.NetworkMessages.Envelope;
import ist.group29.depchain.server.MessageRouter;
import ist.group29.depchain.server.consensus.Consensus;
import ist.group29.depchain.server.service.BlockchainState;
import ist.group29.depchain.server.service.TransactionManager;
import ist.group29.depchain.server.service.account.EOA;

public class ReplayAttackTest {

    private static final String CLIENT_ID = "test-client";
    private static final long TRANSFER_VALUE = 25;
    private static final long GAS_PRICE = 1;
    private static final long GAS_LIMIT = 21_000;

    private BlockchainState state;
    private LinkManager linkManager;
    private TransactionManager transactionManager;
    private MessageRouter router;

    private ECKeyPair senderKeys;
    private String senderAddress;
    private String receiverAddress;

    @BeforeEach
    void setup() throws Exception {
        state = new BlockchainState();
        linkManager = mock(LinkManager.class);
        transactionManager = new TransactionManager(state, linkManager);
        router = new MessageRouter(mock(Consensus.class), transactionManager);

        senderKeys = Keys.createEcKeyPair();
        senderAddress = "0x" + CryptoUtils.getAddress(senderKeys);
        receiverAddress = "0x2222222222222222222222222222222222222222";

        state.addAccount(new EOA(CryptoUtils.normalizeAddress(senderAddress), BigInteger.valueOf(1_000_000), 0));
        state.addAccount(new EOA(CryptoUtils.normalizeAddress(receiverAddress), BigInteger.ZERO, 0));
    }

    @Test
    void testReplayAttack() throws Exception {
        Transaction originalTx = buildSignedTransfer(senderKeys, senderAddress, receiverAddress, 0, TRANSFER_VALUE);
        byte[] originalEnvelope = EnvelopeFactory.wrap(originalTx);
        byte[] replayEnvelope = originalEnvelope.clone();

        router.onMessage(CLIENT_ID, originalEnvelope);
        // Verify that no message was sent to the link manager yet,
        // as the original transaction was not validated successfully.
        // This is because the replay attack test case is currently not
        // implemented, and the original transaction is not verified
        // by the server.
        verify(linkManager, never()).send(eq(CLIENT_ID), any(byte[].class));

        Block block = transactionManager.buildBlock();
        assertEquals(1, block.getTransactionsCount(), "Original transaction should be included in the block");
        assertEquals(
                CryptoUtils.bytesToHex(originalTx.toByteArray()),
                CryptoUtils.bytesToHex(Envelope.parseFrom(replayEnvelope).getTransaction().toByteArray()),
                "Replay should resend the exact same serialized transaction bytes");

        TransactionResponse firstReceipt = transactionManager.executeBlock(block, block.getBlockNumber()).get(0);
        transactionManager.removeCommittedTxs(block.getTransactionsList());

        assertEquals(TransactionStatus.SUCCESS, firstReceipt.getStatus());
        assertEquals(BigInteger.valueOf(TRANSFER_VALUE), state.getAccount(CryptoUtils.normalizeAddress(receiverAddress)).getBalance());
        assertEquals(1, ((EOA) state.getAccount(CryptoUtils.normalizeAddress(senderAddress))).getNonce());

        router.onMessage(CLIENT_ID, replayEnvelope);
        // Verify that a replay rejection response was sent back to the client. 
        ArgumentCaptor<byte[]> responseCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(linkManager).send(eq(CLIENT_ID), responseCaptor.capture());

        Envelope responseEnvelope = Envelope.parseFrom(responseCaptor.getValue());
        assertTrue(responseEnvelope.hasTransactionResponse(), "Replay rejection should be sent back as a transaction response");

        TransactionResponse replayResponse = responseEnvelope.getTransactionResponse();
        assertNotNull(replayResponse);
        assertEquals(TransactionStatus.INVALID_NONCE, replayResponse.getStatus());
        assertTrue(replayResponse.getErrorMessage().contains("nonce too low"));

        assertEquals(BigInteger.valueOf(TRANSFER_VALUE), state.getAccount(CryptoUtils.normalizeAddress(receiverAddress)).getBalance(),
                "Replay must not transfer funds a second time");
        assertEquals(1, ((EOA) state.getAccount(CryptoUtils.normalizeAddress(senderAddress))).getNonce(),
                "Replay must not advance the sender nonce again");
        assertEquals(
                CryptoUtils.bytesToHex(CryptoUtils.keccakHash(originalTx.toByteArray())),
                CryptoUtils.bytesToHex(replayResponse.getTransactionHash().toByteArray()),
                "Replay rejection should refer to the exact same signed transaction");
    }

    private Transaction buildSignedTransfer(ECKeyPair keyPair, String from, String to, long nonce, long value) {
        Transaction.Builder txBuilder = Transaction.newBuilder()
                .setFrom(from)
                .setTo(to)
                .setValue(value)
                .setNonce(nonce)
                .setGasPrice(GAS_PRICE)
                .setGasLimit(GAS_LIMIT)
                .setData(ByteString.EMPTY);

        Transaction unsignedTx = txBuilder.build();
        ClientSignature signature = CryptoUtils.ecSign(keyPair, unsignedTx.toByteArray());

        return txBuilder
                .setSigV(ByteString.copyFrom(signature.v()))
                .setSigR(ByteString.copyFrom(signature.r()))
                .setSigS(ByteString.copyFrom(signature.s()))
                .build();
    }
}
