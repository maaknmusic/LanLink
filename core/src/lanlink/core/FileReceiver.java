package lanlink.core;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Reassembles FILE_START / FILE_CHUNK / FILE_END messages from one peer. */
public final class FileReceiver {

    /** Where received files go; supplied by each platform. */
    public interface Sink {
        /** Opens a destination for a file. {@code size} is -1 when unknown. */
        OutputStream open(String name, long size) throws IOException;

        /** Called after the stream is closed; {@code ok} is false for an incomplete transfer. */
        void finished(OutputStream stream, String name, long size, boolean ok);
    }

    private static final class Entry {
        final String name;
        final long size;
        final OutputStream out;
        long received;

        Entry(String name, long size, OutputStream out) {
            this.name = name;
            this.size = size;
            this.out = out;
        }
    }

    private static final int MAX_OPEN = 8;

    private final Sink sink;
    private final Map<Long, Entry> open = new HashMap<>();

    public FileReceiver(Sink sink) {
        this.sink = sink;
    }

    public synchronized void handle(Msg m) throws IOException {
        In in = m.in();
        long id = in.l();
        switch (m.type) {
            case Proto.FILE_START: {
                String name = safeName(in.str());
                long size = in.l();
                if (open.size() >= MAX_OPEN) throw new IOException("Too many files at once");
                Entry old = open.remove(id);
                if (old != null) finish(old, false);
                open.put(id, new Entry(name, size, sink.open(name, size)));
                break;
            }
            case Proto.FILE_CHUNK: {
                Entry e = open.get(id);
                if (e == null) return;
                int n = in.remaining();
                if (e.size >= 0 && e.received + n > e.size) {
                    open.remove(id);
                    finish(e, false);
                    return;
                }
                try {
                    e.out.write(m.data, in.position(), n);
                } catch (IOException ex) {
                    open.remove(id);
                    finish(e, false);
                    return;
                }
                e.received += n;
                break;
            }
            case Proto.FILE_END: {
                Entry e = open.remove(id);
                if (e != null) finish(e, e.size < 0 || e.received == e.size);
                break;
            }
            default:
                break;
        }
    }

    /** Closes any half-received files, e.g. when the connection drops. */
    public synchronized void abortAll() {
        for (Entry e : new ArrayList<>(open.values())) finish(e, false);
        open.clear();
    }

    private void finish(Entry e, boolean ok) {
        try { e.out.close(); } catch (IOException ex) { ok = false; }
        sink.finished(e.out, e.name, e.received, ok);
    }

    /** Strips paths and characters Windows or Android would reject, so a peer cannot write outside the target folder. */
    public static String safeName(String raw) {
        String n = raw == null ? "" : raw;
        int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        n = n.substring(cut + 1);
        StringBuilder b = new StringBuilder();
        for (char c : n.toCharArray()) b.append(c < 32 || "<>:\"|?*".indexOf(c) >= 0 ? '_' : c);
        n = b.toString().trim();
        while (n.startsWith(".")) n = n.substring(1);
        while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1);
        if (n.isEmpty()) n = "file";
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        String stem = n.contains(".") ? n.substring(0, n.indexOf('.')) : n;
        if (stem.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) n = "_" + n;
        return n;
    }
}
