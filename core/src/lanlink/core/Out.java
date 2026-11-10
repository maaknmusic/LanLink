package lanlink.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Builds a message payload (big-endian, strings as length-prefixed UTF-8). */
public final class Out {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
    private final DataOutputStream d = new DataOutputStream(buf);

    public Out i(int v) {
        try { d.writeInt(v); } catch (IOException e) { throw new IllegalStateException(e); }
        return this;
    }

    public Out l(long v) {
        try { d.writeLong(v); } catch (IOException e) { throw new IllegalStateException(e); }
        return this;
    }

    public Out f(float v) {
        try { d.writeFloat(v); } catch (IOException e) { throw new IllegalStateException(e); }
        return this;
    }

    public Out b(int v) {
        try { d.writeByte(v); } catch (IOException e) { throw new IllegalStateException(e); }
        return this;
    }

    public Out bool(boolean v) {
        return b(v ? 1 : 0);
    }

    public Out str(String s) {
        return bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Length-prefixed bytes. */
    public Out bytes(byte[] v) {
        i(v.length);
        return raw(v, 0, v.length);
    }

    /** Bytes with no length prefix; only valid as the last field. */
    public Out raw(byte[] v, int off, int len) {
        try { d.write(v, off, len); } catch (IOException e) { throw new IllegalStateException(e); }
        return this;
    }

    public Out raw(byte[] v) {
        return raw(v, 0, v.length);
    }

    public byte[] done() {
        return buf.toByteArray();
    }
}
