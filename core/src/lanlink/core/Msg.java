package lanlink.core;

/** One framed message: a type byte plus its payload. */
public final class Msg {
    public final byte type;
    public final byte[] data;

    public Msg(byte type, byte[] data) {
        this.type = type;
        this.data = data;
    }

    public In in() {
        return new In(data);
    }
}
