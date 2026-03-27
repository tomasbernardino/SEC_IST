package ist.group29.depchain.server;

import com.google.protobuf.InvalidProtocolBufferException;
import ist.group29.depchain.common.network.MessageListener;
import ist.group29.depchain.network.NetworkMessages.Envelope;
import ist.group29.depchain.server.consensus.Consensus;
import ist.group29.depchain.server.service.Service;

import java.util.logging.Logger;

/**
 * Dispatches incoming network payloads to the correct handler.
 *
 * All messages arrive wrapped in an {@link Envelope}. The router parses the
 * envelope and switches on the payload case for deterministic routing:
 *
 *   CONSENSUS   → consensus.onMessage()
 *   TRANSACTION → service.addPendingTx()
 */
public class MessageRouter implements MessageListener {

    private static final Logger LOG = Logger.getLogger(MessageRouter.class.getName());

    private final Consensus consensus;
    private final Service service;

    public MessageRouter(Consensus consensus, Service service) {
        this.consensus = consensus;
        this.service = service;
    }

    @Override
    public void onMessage(String senderId, byte[] raw) {
        Envelope env;
        try {
            env = Envelope.parseFrom(raw);
        } catch (InvalidProtocolBufferException e) {
            LOG.warning("[Router] Malformed envelope from " + senderId);
            return;
        }

        switch (env.getPayloadCase()) {
            case CONSENSUS    -> consensus.onMessage(senderId, env.getConsensus());
            case TRANSACTION  -> service.addPendingTx(env.getTransaction());
            case TRANSACTION_RESPONSE -> LOG.fine("[Router] TransactionResponse received (no handler yet)");
            case PAYLOAD_NOT_SET -> LOG.warning("[Router] Empty envelope from " + senderId);
        }
    }
}
