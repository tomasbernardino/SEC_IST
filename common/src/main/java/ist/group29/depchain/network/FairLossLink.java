package ist.group29.depchain.network;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FairLossLink {

    private static final Logger LOGGER = Logger.getLogger(FairLossLink.class.getName());
    private static final int BUFFER_SIZE = 65535;

    private final DatagramSocket socket;
    private final Map<String, ProcessInfo> addressMap;

    /**
     * @param port       the UDP port to bind to
     * @param addressMap maps process IDs to their network addresses
     */
    public FairLossLink(int port, Map<String, ProcessInfo> addressMap) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.addressMap = addressMap;
    }

    public void send(byte[] payload, String recipientId) {
        ProcessInfo recipient = addressMap.get(recipientId);
        if (recipient == null) {
            LOGGER.warning("Unknown recipient: " + recipientId);
            return;
        }
        try {
            DatagramPacket packet = new DatagramPacket(
                    payload, payload.length, recipient.address(), recipient.port());
            socket.send(packet);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "FLL send failed (fair-loss drop)", e);
        }
    }

    /**
     * Blocking receive. Returns the next parseable Message from the socket,
     * or {@code null} if parsing fails (corrupted packet).
     */
    public byte[] deliver() {
        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
            return Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "FLL deliver failed (corrupt packet)", e);
            return null;
        }
    }

    public void close() {
        socket.close();
    }
}
