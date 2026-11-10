package lanlink.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The sharing side. Accepts viewers that know the PIN, streams them frames,
 * and hands their input, clipboard and files to the platform.
 */
public final class HostServer {

    /** What a platform (Windows or Android) provides to be shared. */
    public interface Platform {
        String deviceName();
        String platform();
        /** Checked for every input event, so it can change during a session. */
        boolean controlAllowed();
        void onPointer(int action, int button, float x, float y);
        void onScroll(float x, float y, int notches);
        void onKey(int vk, boolean down);
        void onText(String text);
        void onAction(int action);
        void onClipboard(String text);
        FileReceiver.Sink fileSink();
        /** Viewers joined or left, or the PIN changed. */
        void onStateChanged();
        /** A viewer left; release anything it was holding down. */
        void onViewerLeft();
    }

    public interface Opener {
        InputStream open() throws IOException;
    }

    public static final class Client {
        public final String name, platform, address;
        final Conn conn;
        final AtomicInteger inFlight = new AtomicInteger();

        Client(Conn conn, String name, String platform) {
            this.conn = conn;
            this.name = name;
            this.platform = platform;
            this.address = conn.remoteAddress();
        }
    }

    /** Frames a viewer may have unacknowledged before we stop sending it more. */
    private static final int MAX_IN_FLIGHT = 2;
    private static final int MAX_FAILURES = 5;

    private final Platform p;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private DatagramSocket discovery;
    private volatile boolean running;
    private volatile boolean freshClient;
    private volatile String pin = Auth.newPin();
    private int failures;

    public HostServer(Platform platform) {
        this.p = platform;
    }

    /** Reuses a saved 6-digit PIN so a viewer can reconnect after sharing restarts. */
    public HostServer(Platform platform, String savedPin) {
        this.p = platform;
        if (savedPin != null && savedPin.matches("\\d{6}")) this.pin = savedPin;
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(Proto.TCP_PORT));
        running = true;
        try {
            discovery = Discovery.respond(p.deviceName(), p.platform(), Proto.TCP_PORT);
        } catch (IOException e) {
            discovery = null; // Viewers can still connect by IP address.
        }
        Thread t = new Thread(this::acceptLoop, "lanlink-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        if (discovery != null) discovery.close();
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        for (Client c : clients) c.conn.close();
    }

    public boolean isRunning() {
        return running;
    }

    public String pin() {
        return pin;
    }

    public synchronized void newPin() {
        pin = Auth.newPin();
        failures = 0;
        p.onStateChanged();
    }

    public List<Client> clients() {
        return new ArrayList<>(clients);
    }

    public int clientCount() {
        return clients.size();
    }

    public void disconnectAll() {
        for (Client c : clients) c.conn.close();
    }

    /** True if at least one viewer is ready for another frame. */
    public boolean wantsFrame() {
        for (Client c : clients) if (c.inFlight.get() < MAX_IN_FLIGHT) return true;
        return false;
    }

    /** True once after a viewer joins, so the capture loop sends it a frame even if nothing changed. */
    public boolean takeFreshClient() {
        boolean f = freshClient;
        freshClient = false;
        return f;
    }

    /** Sends a JPEG frame to every viewer that is ready for one. */
    public void sendFrame(int width, int height, byte[] jpeg, int len) {
        byte[] payload = null;
        for (Client c : clients) {
            if (c.inFlight.get() >= MAX_IN_FLIGHT) continue;
            if (payload == null) payload = new Out().i(width).i(height).raw(jpeg, 0, len).done();
            c.inFlight.incrementAndGet();
            try {
                c.conn.send(Proto.FRAME, payload);
            } catch (IOException e) {
                c.conn.close();
            }
        }
    }

    public void sendClipboard(String text) {
        if (text == null || text.length() > Proto.MAX_CLIPBOARD) return;
        byte[] payload = new Out().str(text).done();
        for (Client c : clients) c.conn.post(Proto.CLIPBOARD, payload);
    }

    /** Sends a file to every viewer. Blocks; call from a background thread. */
    public void sendFile(String name, long size, Opener opener, FileSender.Progress progress) throws IOException {
        for (Client c : clients) {
            try (InputStream in = opener.open()) {
                FileSender.send(c.conn, name, size, in, progress);
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "lanlink-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void serve(Socket s) {
        Conn c;
        try {
            c = new Conn(s);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            return;
        }
        Client client = null;
        FileReceiver files = null;
        try {
            client = handshake(c);
            if (client == null) return;
            files = new FileReceiver(p.fileSink());
            clients.add(client);
            freshClient = true;
            p.onStateChanged();
            while (running) dispatch(client, files, c.read());
        } catch (IOException | RuntimeException e) {
            // Connection closed or the peer sent garbage; either way the session ends.
        } finally {
            c.close();
            if (files != null) files.abortAll();
            if (client != null && clients.remove(client)) {
                p.onViewerLeft();
                p.onStateChanged();
            }
        }
    }

    private Client handshake(Conn c) throws IOException {
        c.socket().setSoTimeout(30_000);
        Msg m = c.read();
        if (m.type != Proto.HELLO) return null;
        In in = m.in();
        int version = in.i();
        String name = in.str();
        String platform = in.str();
        if (version != Proto.VERSION) {
            c.send(Proto.AUTH_RESULT, new Out().bool(false).str("Version mismatch: update LanLink on both devices.").done());
            return null;
        }
        byte[] nonce = Auth.nonce();
        c.send(Proto.CHALLENGE, new Out().i(Proto.VERSION).str(p.deviceName()).str(p.platform()).bytes(nonce).done());
        m = c.read();
        if (m.type != Proto.AUTH) return null;
        byte[] proof = m.in().rest();
        boolean ok;
        synchronized (this) {
            ok = Auth.check(pin, nonce, proof);
            if (ok) {
                failures = 0;
            } else if (++failures >= MAX_FAILURES) {
                // Too many guesses: replace the PIN so guessing cannot continue.
                pin = Auth.newPin();
                failures = 0;
                p.onStateChanged();
            }
        }
        if (!ok) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            c.send(Proto.AUTH_RESULT, new Out().bool(false).str("Wrong PIN").done());
            return null;
        }
        c.send(Proto.AUTH_RESULT, new Out().bool(true).str("").bool(p.controlAllowed()).done());
        c.socket().setSoTimeout(0);
        c.setLimit(Proto.MAX_MESSAGE);
        return new Client(c, name, platform);
    }

    private void dispatch(Client client, FileReceiver files, Msg m) throws IOException {
        In in = m.in();
        switch (m.type) {
            case Proto.FRAME_ACK:
                if (client.inFlight.decrementAndGet() < 0) client.inFlight.set(0);
                break;
            case Proto.POINTER: {
                int action = in.b(), button = in.b();
                float x = in.f(), y = in.f();
                if (p.controlAllowed()) p.onPointer(action, button, x, y);
                break;
            }
            case Proto.SCROLL: {
                float x = in.f(), y = in.f();
                int notches = in.i();
                if (p.controlAllowed()) p.onScroll(x, y, notches);
                break;
            }
            case Proto.KEY: {
                int vk = in.i();
                boolean down = in.bool();
                if (p.controlAllowed()) p.onKey(vk, down);
                break;
            }
            case Proto.TEXT: {
                String text = in.str();
                if (p.controlAllowed()) p.onText(text);
                break;
            }
            case Proto.ACTION: {
                int action = in.i();
                if (p.controlAllowed()) p.onAction(action);
                break;
            }
            case Proto.CLIPBOARD:
                p.onClipboard(in.str());
                break;
            case Proto.FILE_START:
            case Proto.FILE_CHUNK:
            case Proto.FILE_END:
                files.handle(m);
                break;
            default:
                break;
        }
    }
}
