package ist.group29.depchain.common.network;

import java.net.InetAddress;

public record ProcessInfo(String id, InetAddress address, int port) {}
