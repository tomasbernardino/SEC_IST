package ist.group29.depchain.network;

/**
 * Callback interface for messages delivered by the Authenticated Perfect Link layer.
 */
@FunctionalInterface
public interface MessageListener {
    /**
     * Called when a verified, deduplicated application-layer payload arrives.
     *
     * @param senderId the authenticated sender process ID
     * @param payload  the raw application-level bytes
     */
    void onMessage(String senderId, byte[] payload);
}
