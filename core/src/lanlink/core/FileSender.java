package lanlink.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ThreadLocalRandom;

/** Streams one file to a peer as FILE_START, FILE_CHUNK..., FILE_END. */
public final class FileSender {
    private FileSender() {}

    public interface Progress {
        void update(long sent, long total);
    }

    /** Blocks until sent. {@code size} may be -1 to read until end of stream. */
    public static void send(Conn c, String name, long size, InputStream in, Progress progress) throws IOException {
        long id = ThreadLocalRandom.current().nextLong();
        c.send(Proto.FILE_START, new Out().l(id).str(name).l(size).done());
        byte[] buf = new byte[Proto.FILE_CHUNK_SIZE];
        long sent = 0;
        while (size < 0 || sent < size) {
            int want = size < 0 ? buf.length : (int) Math.min(buf.length, size - sent);
            int n = in.read(buf, 0, want);
            if (n <= 0) break;
            c.send(Proto.FILE_CHUNK, new Out().l(id).raw(buf, 0, n).done());
            sent += n;
            if (progress != null) progress.update(sent, size);
        }
        c.send(Proto.FILE_END, new Out().l(id).done());
    }
}
