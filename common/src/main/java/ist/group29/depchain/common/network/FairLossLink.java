package ist.group29.depchain.common.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FairLossLink {

    private static final Logger LOGGER = Logger.getLogger(FairLossLink.class.getName());
    private static final int BUFFER_SIZE = 65535;

    private final DatagramSocket socket;
    private final Map<String, ProcessInfo> addressMap;

    /** Wraps a received UDP payload together with the sender's network address. */
    public record ReceivedPacket(byte[] data, InetAddress address, int port) {
    }

    public FairLossLink(int port, Map<String, ProcessInfo> addressMap) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.addressMap = new ConcurrentHashMap<>(addressMap);
    }

    /** Dynamically register a new peer so we can send back to them. */
    public void registerPeer(String id, InetAddress address, int port) {
        addressMap.put(id, new ProcessInfo(id, address, port));
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

    public ReceivedPacket deliver() {
        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
            byte[] data = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
            return new ReceivedPacket(data, packet.getAddress(), packet.getPort());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "FLL deliver failed (corrupt packet)", e);
            return null;
        }
    }

    public void close() {
        socket.close();
    }
}
