package lanlink.core;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Reads a payload written by {@link Out}. Malformed input throws a RuntimeException. */
public final class In {
    private final ByteBuffer b;

    public In(byte[] data) {
        b = ByteBuffer.wrap(data);
    }

    public int i() { return b.getInt(); }
    public long l() { return b.getLong(); }
    public float f() { return b.getFloat(); }
    public int b() { return b.get(); }
    public boolean bool() { return b.get() != 0; }

    public String str() {
        int n = length();
        String s = new String(b.array(), b.position(), n, StandardCharsets.UTF_8);
        b.position(b.position() + n);
        return s;
    }

    public byte[] bytes() {
        byte[] v = new byte[length()];
        b.get(v);
        return v;
    }

    public byte[] rest() {
        byte[] v = new byte[b.remaining()];
        b.get(v);
        return v;
    }

    public int position() { return b.position(); }
    public int remaining() { return b.remaining(); }

    private int length() {
        int n = b.getInt();
        if (n < 0 || n > b.remaining()) throw new BufferUnderflowException();
        return n;
    }
}
