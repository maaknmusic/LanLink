package lanlink.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds hosts on the local network. A viewer broadcasts "LANLINK?1" on UDP;
 * every sharing host answers with its name, platform and TCP port.
 */
public final class Discovery {
    private Discovery() {}

    private static final String ASK = "LANLINK?1", ANSWER = "LANLINK!1";

    public static final class Device {
        public final String name, platform, address;
        public final int port;

        Device(String name, String platform, String address, int port) {
            this.name = name;
            this.platform = platform;
            this.address = address;
            this.port = port;
        }

        @Override
        public String toString() {
            return name + "  (" + address + ")";
        }
    }

    /** Answers discovery broadcasts until the returned socket is closed. */
    public static DatagramSocket respond(String name, String platform, int port) throws IOException {
        final DatagramSocket sock = new DatagramSocket(null);
        sock.setReuseAddress(true);
        sock.bind(new InetSocketAddress(Proto.UDP_PORT));
        final byte[] reply = (ANSWER + "\n" + name + "\n" + platform + "\n" + port).getBytes(StandardCharsets.UTF_8);
        Thread t = new Thread(() -> {
            byte[] buf = new byte[256];
            while (!sock.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (msg.equals(ASK)) sock.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
                } catch (IOException e) {
                    if (sock.isClosed()) return;
                }
            }
        }, "lanlink-discovery");
        t.setDaemon(true);
        t.start();
        return sock;
    }

    /** Broadcasts a query and collects answers for {@code timeoutMs}. */
    public static List<Device> scan(int timeoutMs) throws IOException {
        Map<String, Device> found = new LinkedHashMap<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            byte[] ask = ASK.getBytes(StandardCharsets.UTF_8);
            for (int round = 0; round < 2; round++) {
                for (InetAddress target : broadcastTargets()) {
                    try {
                        sock.send(new DatagramPacket(ask, ask.length, target, Proto.UDP_PORT));
                    } catch (IOException ignored) {
                        // Some interfaces refuse broadcasts; the others still work.
                    }
                }
            }
            // Many Wi-Fi routers drop broadcasts, so also ask every address on the local network directly.
            for (InetAddress target : subnetHosts()) {
                try {
                    sock.send(new DatagramPacket(ask, ask.length, target, Proto.UDP_PORT));
                } catch (IOException ignored) {
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[512];
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                sock.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String[] parts = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\n");
                if (parts.length < 4 || !parts[0].equals(ANSWER) || p.getAddress().isLoopbackAddress()) continue;
                String addr = p.getAddress().getHostAddress();
                try {
                    found.put(addr, new Device(parts[1], parts[2], addr, Integer.parseInt(parts[3].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private static Set<InetAddress> broadcastTargets() throws IOException {
        Set<InetAddress> out = new LinkedHashSet<>();
        out.add(InetAddress.getByName("255.255.255.255"));
        for (NetworkInterface ni : interfaces()) {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
            }
        }
        return out;
    }

    /** Every host address on this device's local IPv4 networks (up to /22, about 1000 addresses). */
    private static List<InetAddress> subnetHosts() throws IOException {
        List<InetAddress> out = new ArrayList<>();
        for (NetworkInterface ni : interfaces()) {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                byte[] a = ia.getAddress().getAddress();
                int prefix = ia.getNetworkPrefixLength();
                if (a.length != 4 || !ia.getAddress().isSiteLocalAddress() || prefix < 22 || prefix > 30) continue;
                int ip = ((a[0] & 255) << 24) | ((a[1] & 255) << 16) | ((a[2] & 255) << 8) | (a[3] & 255);
                int mask = -1 << (32 - prefix);
                int net = ip & mask, count = ~mask;
                for (int i = 1; i < count; i++) {
                    int h = net | i;
                    if (h == ip) continue;
                    out.add(InetAddress.getByAddress(new byte[] {(byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h}));
                }
            }
        }
        return out;
    }

    /** This device's IPv4 addresses on local networks, for display. */
    public static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : interfaces()) {
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a.getAddress().length == 4 && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static List<NetworkInterface> interfaces() throws IOException {
        List<NetworkInterface> out = new ArrayList<>();
        if (NetworkInterface.getNetworkInterfaces() == null) return out;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (ni.isUp() && !ni.isLoopback()) out.add(ni);
        }
        return out;
    }
}
