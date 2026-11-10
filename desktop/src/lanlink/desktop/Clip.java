package lanlink.desktop;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lanlink.core.Proto;

/** Watches the Windows clipboard for text changes and applies text from other devices. */
final class Clip {
    private final Clipboard sys = Toolkit.getDefaultToolkit().getSystemClipboard();
    private final Consumer<String> onLocalChange;
    private volatile boolean enabled = true;
    private String last;

    Clip(Consumer<String> onLocalChange) {
        this.onLocalChange = onLocalChange;
        last = read();
        new Timer(800, e -> poll()).start();
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    boolean isEnabled() {
        return enabled;
    }

    private void poll() {
        String text = read();
        if (text == null || text.equals(last)) return;
        last = text;
        if (enabled) onLocalChange.accept(text);
    }

    /** Puts text from another device on the clipboard without echoing it back. */
    void setFromRemote(String text) {
        if (!enabled) return;
        SwingUtilities.invokeLater(() -> write(text));
    }

    /** Current clipboard text, or null. */
    String current() {
        return read();
    }

    /** Sets the clipboard immediately (for paste-to-type); the caller is on any thread. */
    void writeNow(String text) {
        try {
            SwingUtilities.invokeAndWait(() -> write(text));
        } catch (Exception ignored) {
        }
    }

    private void write(String text) {
        last = text;
        try {
            sys.setContents(new StringSelection(text), null);
        } catch (IllegalStateException ignored) {
            // Another app has the clipboard open; skip this update.
        }
    }

    private String read() {
        try {
            if (!sys.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
            String s = (String) sys.getData(DataFlavor.stringFlavor);
            return s != null && s.length() <= Proto.MAX_CLIPBOARD ? s : null;
        } catch (Exception e) {
            return null;
        }
    }
}
