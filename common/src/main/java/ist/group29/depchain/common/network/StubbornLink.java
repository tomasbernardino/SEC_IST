package ist.group29.depchain.common.network;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import ist.group29.depchain.network.NetworkMessages.Message;

public class StubbornLink {

    private static final Logger LOGGER = Logger.getLogger(StubbornLink.class.getName());
    private static final long RESEND_INTERVAL_MS = 500;

    private final FairLossLink fll;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingMessages;

    public StubbornLink(FairLossLink fll) {
        this.fll = fll;
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "SL-Resend");
            t.setDaemon(true);
            return t;
        });
        this.pendingMessages = new ConcurrentHashMap<>();
    }

    /**
     * Send a message and schedule periodic retransmissions until
     * {@link #cancelRetransmission} is called for this (recipient, seq) pair.
     */
    public void send(Message message, String recipientId) {
        String key = recipientId + ":" + message.getSequenceNumber();
        byte[] payload = message.toByteArray();

        fll.send(payload, recipientId);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> fll.send(payload, recipientId),
                RESEND_INTERVAL_MS, RESEND_INTERVAL_MS, TimeUnit.MILLISECONDS
        );

        ScheduledFuture<?> old = pendingMessages.put(key, future);
        if (old != null) {
            old.cancel(false);
        }
    }

    /**
     * No retransmission.
     * Used for ACKs and HandshakeAcks — their reliability is guaranteed
     * implicitly by the sender's retransmission of the original message.
     */
    public void sendOnce(Message message, String recipientId) {
        fll.send(message.toByteArray(), recipientId);
    }

    /**
     * Cancel retransmission for a specific (recipient, sequenceNumber) pair.
     * Called by APL when a valid ACK is received.
     */
    public void cancelRetransmission(String recipientId, long sequenceNumber) {
        String key = recipientId + ":" + sequenceNumber;
        ScheduledFuture<?> future = pendingMessages.remove(key);
        if (future != null) {
            future.cancel(false);
            LOGGER.fine("Cancelled retransmission: " + key);
        }
    }

    public void shutdown() {
        pendingMessages.values().forEach(f -> f.cancel(false));
        pendingMessages.clear();
        scheduler.shutdownNow();
    }
}
