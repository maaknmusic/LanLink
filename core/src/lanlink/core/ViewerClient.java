package lanlink.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** The viewing side: connects to a host, receives frames, sends input. */
public final class ViewerClient {

    public interface Listener {
        /** A JPEG frame in {@code data[off, off+len)}. Call {@link #ackFrame()} once it is decoded. */
        void onFrame(int width, int height, byte[] data, int off, int len);
        void onClipboard(String text);
        FileReceiver.Sink fileSink();
        void onClosed(String reason);
    }

    /** The host rejected us (wrong PIN or version mismatch). */
    public static final class AuthException extends IOException {
        public AuthException(String message) {
            super(message);
        }
    }

    public final String hostName, hostPlatform, address;
    public final boolean controlAllowed;
    private final Conn conn;

    private ViewerClient(Conn conn, String hostName, String hostPlatform, boolean controlAllowed) {
        this.conn = conn;
        this.hostName = hostName;
        this.hostPlatform = hostPlatform;
        this.controlAllowed = controlAllowed;
        this.address = conn.remoteAddress();
    }

    /** Connects and proves the PIN. Blocks; call from a background thread. */
    public static ViewerClient connect(String host, int port, String pin, String myName, String myPlatform) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(15_000);
            Conn c = new Conn(s);
            c.send(Proto.HELLO, new Out().i(Proto.VERSION).str(myName).str(myPlatform).done());
            Msg m = c.read();
            if (m.type == Proto.AUTH_RESULT) {
                In in = m.in();
                in.bool();
                throw new AuthException(in.str());
            }
            if (m.type != Proto.CHALLENGE) throw new IOException("Not a LanLink device");
            In in = m.in();
            int version = in.i();
            String hostName = in.str();
            String hostPlatform = in.str();
            byte[] nonce = in.bytes();
            if (version != Proto.VERSION) throw new AuthException("Version mismatch: update LanLink on both devices.");
            c.send(Proto.AUTH, new Out().raw(Auth.proof(pin, nonce)).done());
            m = c.read();
            if (m.type != Proto.AUTH_RESULT) throw new IOException("Unexpected reply");
            in = m.in();
            if (!in.bool()) throw new AuthException(in.str());
            in.str();
            boolean control = in.bool();
            s.setSoTimeout(0);
            c.setLimit(Proto.MAX_MESSAGE);
            return new ViewerClient(c, hostName, hostPlatform, control);
        } catch (IOException | RuntimeException e) {
            try { s.close(); } catch (IOException ignored) {}
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Bad reply from host", e);
        }
    }

    public boolean hostIsAndroid() {
        return Proto.ANDROID.equals(hostPlatform);
    }

    /** Starts the receive loop on a background thread. */
    public void start(final Listener l) {
        Thread t = new Thread(() -> receive(l), "lanlink-viewer");
        t.setDaemon(true);
        t.start();
    }

    private void receive(Listener l) {
        FileReceiver files = new FileReceiver(l.fileSink());
        String reason = "Connection closed";
        try {
            while (true) {
                Msg m = conn.read();
                switch (m.type) {
                    case Proto.FRAME: {
                        In in = m.in();
                        int w = in.i(), h = in.i();
                        l.onFrame(w, h, m.data, in.position(), in.remaining());
                        break;
                    }
                    case Proto.CLIPBOARD:
                        l.onClipboard(m.in().str());
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
        } catch (IOException | RuntimeException e) {
            if (conn.isClosed()) reason = "Disconnected";
        } finally {
            conn.close();
            files.abortAll();
            l.onClosed(reason);
        }
    }

    public void ackFrame() {
        conn.post(Proto.FRAME_ACK, new byte[0]);
    }

    /** x and y are 0..1 across the host's screen. */
    public void pointer(int action, int button, float x, float y) {
        conn.post(Proto.POINTER, new Out().b(action).b(button).f(x).f(y).done());
    }

    /** Positive notches scroll down, like a mouse wheel turned towards you. */
    public void scroll(float x, float y, int notches) {
        conn.post(Proto.SCROLL, new Out().f(x).f(y).i(notches).done());
    }

    public void key(int vk, boolean down) {
        conn.post(Proto.KEY, new Out().i(vk).bool(down).done());
    }

    public void tapKey(int vk) {
        key(vk, true);
        key(vk, false);
    }

    public void text(String text) {
        if (!text.isEmpty()) conn.post(Proto.TEXT, new Out().str(text).done());
    }

    public void action(int action) {
        conn.post(Proto.ACTION, new Out().i(action).done());
    }

    public void clipboard(String text) {
        if (text != null && text.length() <= Proto.MAX_CLIPBOARD) conn.post(Proto.CLIPBOARD, new Out().str(text).done());
    }

    /** Blocks until sent; call from a background thread. */
    public void sendFile(String name, long size, InputStream in, FileSender.Progress progress) throws IOException {
        FileSender.send(conn, name, size, in, progress);
    }

    public boolean isClosed() {
        return conn.isClosed();
    }

    public void close() {
        conn.close();
    }
}
