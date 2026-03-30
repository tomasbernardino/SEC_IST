package ist.group29.depchain.common.network;

import ist.group29.depchain.network.NetworkMessages.Envelope;
import ist.group29.depchain.network.ConsensusMessages.ConsensusMessage;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;

/**
 * Static factory for wrapping application message
 * Senders call {@code EnvelopeFactory.wrap(msg)} before handing bytes to
 * {@link LinkManager}; the router unwraps on the receive side.
 */
public final class EnvelopeFactory {

    private EnvelopeFactory() {}

    public static byte[] wrap(ConsensusMessage msg) {
        return Envelope.newBuilder().setConsensus(msg).build().toByteArray();
    }

    public static byte[] wrap(Transaction tx) {
        return Envelope.newBuilder().setTransaction(tx).build().toByteArray();
    }

    public static byte[] wrap(TransactionResponse resp) {
        return Envelope.newBuilder().setTransactionResponse(resp).build().toByteArray();
    }
}
