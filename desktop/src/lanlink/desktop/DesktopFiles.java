package lanlink.desktop;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lanlink.core.FileReceiver;

/** Saves received files to Downloads\LanLink without overwriting anything. */
final class DesktopFiles implements FileReceiver.Sink {
    static final File DIR = new File(System.getProperty("user.home"), "Downloads" + File.separator + "LanLink");

    private final Map<OutputStream, File> files = new ConcurrentHashMap<>();
    private final Consumer<String> notify;

    DesktopFiles(Consumer<String> notify) {
        this.notify = notify;
    }

    @Override
    public synchronized OutputStream open(String name, long size) throws IOException {
        if (!DIR.isDirectory() && !DIR.mkdirs()) throw new IOException("Cannot create " + DIR);
        File f = unique(name);
        if (!f.createNewFile()) throw new IOException("Cannot create " + f);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(f), 256 * 1024);
        files.put(out, f);
        return out;
    }

    @Override
    public void finished(OutputStream stream, String name, long size, boolean ok) {
        File f = files.remove(stream);
        if (f == null) return;
        if (ok) {
            notify.accept("Received " + f.getName());
        } else {
            f.delete();
            notify.accept("Transfer of " + name + " failed");
        }
    }

    private static File unique(String name) {
        File f = new File(DIR, name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(DIR, stem + " (" + i + ")" + ext);
        return f;
    }
}
