package ist.group29.depchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.math.BigInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

import com.google.protobuf.ByteString;

import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.client.ClientMessages.TransactionStatus;
import ist.group29.depchain.common.crypto.ClientSignature;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.network.NetworkMessages.Envelope;
import ist.group29.depchain.server.service.BlockchainState;
import ist.group29.depchain.server.service.TransactionManager;
import ist.group29.depchain.server.service.account.EOA;

public class ByzantineClientValidationTest {

    private static final String CLIENT_ID = "test-client";

    private BlockchainState state;
    private LinkManager linkManager;
    private TransactionManager transactionManager;

    private ECKeyPair honestSenderKeys;
    private String honestSenderAddress;
    private String receiverAddress;

    @BeforeEach
    void setup() throws Exception {
        state = new BlockchainState();
        linkManager = mock(LinkManager.class);
        transactionManager = new TransactionManager(state, linkManager);

        honestSenderKeys = Keys.createEcKeyPair();
        honestSenderAddress = "0x" + CryptoUtils.getAddress(honestSenderKeys);
        receiverAddress = "0x2222222222222222222222222222222222222222";

        state.addAccount(new EOA(CryptoUtils.normalizeAddress(honestSenderAddress), BigInteger.valueOf(1_000_000), 0));
        state.addAccount(new EOA(CryptoUtils.normalizeAddress(receiverAddress), BigInteger.ZERO, 0));
    }

    // Gas validation

    @Test
    void testGasValidationRejectsZeroGasPrice() throws Exception {
        Transaction tx = buildSignedTransfer(honestSenderKeys, honestSenderAddress, receiverAddress, 0, 10, 0, 21_000);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.FAILURE, response.getStatus());
        assertTrue(response.getErrorMessage().contains("gas price must be positive"));
    }

    @Test
    void testGasValidationRejectsNegativeGasPrice() throws Exception {
        Transaction tx = buildSignedTransfer(honestSenderKeys, honestSenderAddress, receiverAddress, 0, 10, -1, 21_000);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.FAILURE, response.getStatus());
        assertTrue(response.getErrorMessage().contains("gas price must be positive"));
    }

    @Test
    void testGasValidationRejectsZeroGasLimit() throws Exception {
        Transaction tx = buildSignedTransfer(honestSenderKeys, honestSenderAddress, receiverAddress, 0, 10, 1, 0);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.OUT_OF_GAS, response.getStatus());
        assertTrue(response.getErrorMessage().contains("intrinsic_gas"));
    }

    @Test
    void testGasValidationRejectsIntrinsicGasTooLow() throws Exception {
        Transaction tx = buildSignedTransfer(honestSenderKeys, honestSenderAddress, receiverAddress, 0, 10, 1, 20_000);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.OUT_OF_GAS, response.getStatus());
        assertTrue(response.getErrorMessage().contains("intrinsic_gas"));
    }

    @Test
    void testGasValidationRejectsBlockGasLimitExceeded() throws Exception {
        Transaction tx = buildSignedTransfer(honestSenderKeys, honestSenderAddress, receiverAddress, 0, 10, 1, 600_000);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.FAILURE, response.getStatus());
        assertTrue(response.getErrorMessage().contains("exceeds block gas limit"));
    }

    // Authorization and funds

    @Test
    void testByzantineClientRejectsUnknownSender() throws Exception {
        ECKeyPair strangerKeys = Keys.createEcKeyPair();
        String strangerAddress = "0x" + CryptoUtils.getAddress(strangerKeys);
        Transaction tx = buildSignedTransfer(strangerKeys, strangerAddress, receiverAddress, 0, 10, 1, 21_000);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.FAILURE, response.getStatus());
        assertTrue(response.getErrorMessage().contains("does not exist"));
    }

    @Test
    void testByzantineClientRejectsInsufficientFunds() throws Exception {
        Transaction tx = buildSignedTransfer(honestSenderKeys, honestSenderAddress, receiverAddress, 0, 980_000, 1, 21_000);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, response.getStatus());
        assertTrue(response.getErrorMessage().contains("insufficient funds"));
    }

    @Test
    void testByzantineClientRejectsUnknownRecipient() throws Exception {
        Transaction tx = buildSignedTransfer(
                honestSenderKeys,
                honestSenderAddress,
                "0x3333333333333333333333333333333333333333",
                0,
                10,
                1,
                21_000);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.FAILURE, response.getStatus());
        assertTrue(response.getErrorMessage().contains("recipient account"));
    }

    // Signature and nonce checks

    @Test
    void testByzantineClientRejectsInvalidSignature() throws Exception {
        ECKeyPair attackerKeys = Keys.createEcKeyPair();
        Transaction tx = buildSignedTransfer(attackerKeys, honestSenderAddress, receiverAddress, 0, 10, 1, 21_000);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.INVALID_SIGNATURE, response.getStatus());
        assertTrue(response.getErrorMessage().contains("Invalid signature"));
    }

    @Test
    void testByzantineClientRejectsReplayNonceTooLow() throws Exception {
        state.addAccount(new EOA(CryptoUtils.normalizeAddress(honestSenderAddress), BigInteger.valueOf(1_000_000), 1));
        Transaction tx = buildSignedTransfer(honestSenderKeys, honestSenderAddress, receiverAddress, 0, 10, 1, 21_000);

        TransactionResponse response = submitAndCaptureResponse(tx);

        assertEquals(TransactionStatus.INVALID_NONCE, response.getStatus());
        assertTrue(response.getErrorMessage().contains("nonce too low"));
    }

    private TransactionResponse submitAndCaptureResponse(Transaction tx) throws Exception {
        reset(linkManager);
        transactionManager.addPendingTx(CLIENT_ID, tx);

        ArgumentCaptor<byte[]> responseCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(linkManager).send(eq(CLIENT_ID), responseCaptor.capture());

        Envelope responseEnvelope = Envelope.parseFrom(responseCaptor.getValue());
        return responseEnvelope.getTransactionResponse();
    }

    private Transaction buildSignedTransfer(
            ECKeyPair signingKeys,
            String from,
            String to,
            long nonce,
            long value,
            long gasPrice,
            long gasLimit) {
        Transaction.Builder txBuilder = Transaction.newBuilder()
                .setFrom(from)
                .setTo(to)
                .setValue(value)
                .setNonce(nonce)
                .setGasPrice(gasPrice)
                .setGasLimit(gasLimit)
                .setData(ByteString.EMPTY);

        Transaction unsignedTx = txBuilder.build();
        ClientSignature signature = CryptoUtils.ecSign(signingKeys, unsignedTx.toByteArray());

        return txBuilder
                .setSigV(ByteString.copyFrom(signature.v()))
                .setSigR(ByteString.copyFrom(signature.r()))
                .setSigS(ByteString.copyFrom(signature.s()))
                .build();
    }
}
