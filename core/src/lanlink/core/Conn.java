package lanlink.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A framed TCP connection. Each message is [int length][byte type][payload],
 * where length counts the type byte and the payload.
 */
public final class Conn implements Closeable {
    private static final Msg STOP = new Msg((byte) 0, new byte[0]);

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final BlockingQueue<Msg> queue = new LinkedBlockingQueue<>();
    private Thread writer;
    private volatile boolean closed;
    private volatile int limit = Proto.MAX_PREAUTH;

    public Conn(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
    }

    public Socket socket() {
        return socket;
    }

    public String remoteAddress() {
        return socket.getInetAddress().getHostAddress();
    }

    /** Raises the accepted message size once the peer has authenticated. */
    public void setLimit(int limit) {
        this.limit = limit;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Sends on the calling thread. Do not call from an Android UI thread. */
    public void send(byte type, byte[] payload) throws IOException {
        synchronized (out) {
            out.writeInt(payload.length + 1);
            out.writeByte(type);
            out.write(payload);
            out.flush();
        }
    }

    /** Queues a message for a background writer thread; safe from any thread. */
    public void post(byte type, byte[] payload) {
        if (closed) return;
        synchronized (this) {
            if (writer == null) {
                writer = new Thread(this::drain, "lanlink-writer");
                writer.setDaemon(true);
                writer.start();
            }
        }
        queue.offer(new Msg(type, payload));
    }

    private void drain() {
        try {
            while (true) {
                Msg m = queue.take();
                if (m == STOP) return;
                send(m.type, m.data);
            }
        } catch (InterruptedException | IOException e) {
            close();
        }
    }

    public Msg read() throws IOException {
        int len = in.readInt();
        if (len < 1 || len > limit) throw new IOException("Bad message size " + len);
        byte type = in.readByte();
        byte[] data = new byte[len - 1];
        in.readFully(data);
        return new Msg(type, data);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        queue.offer(STOP);
        try { socket.close(); } catch (IOException ignored) {}
    }
}
