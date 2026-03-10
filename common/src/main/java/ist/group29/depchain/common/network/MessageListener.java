package ist.group29.depchain.common.network;

/**
 * Callback interface for messages delivered by the Authenticated Perfect Link layer.
 */
@FunctionalInterface
public interface MessageListener {

    void onMessage(String senderId, byte[] payload);
}
