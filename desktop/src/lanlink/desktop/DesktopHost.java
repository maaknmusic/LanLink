package lanlink.desktop;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import lanlink.core.FileReceiver;
import lanlink.core.HostServer;
import lanlink.core.Keys;
import lanlink.core.Proto;

/** Shares this PC: captures the primary screen and replays viewers' input with java.awt.Robot. */
final class DesktopHost implements HostServer.Platform {
    private static final int MAX_WIDTH = 1600;
    private static final long FRAME_MS = 66; // about 15 fps

    private final Robot robot;
    private final Rectangle bounds;
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Set<Integer> heldButtons = new HashSet<>();
    private final Callbacks app;
    private volatile boolean controlAllowed = true;
    private volatile HostServer server;
    private volatile boolean running;

    /** What the host needs from the main window. */
    interface Callbacks {
        void onClipboardFromViewer(String text);
        /** Puts text on the clipboard (without syncing it) so it can be pasted. */
        void putOnClipboard(String text);
        FileReceiver.Sink fileSink();
        void onHostStateChanged();
    }

    DesktopHost(Callbacks app) throws AWTException {
        this.app = app;
        this.robot = new Robot();
        robot.setAutoDelay(0);
        bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
    }

    void setControlAllowed(boolean allowed) {
        controlAllowed = allowed;
    }

    void start(HostServer s) {
        server = s;
        running = true;
        Thread t = new Thread(this::captureLoop, "lanlink-capture");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
    }

    private void captureLoop() {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.65f);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(256 * 1024);
        int[] last = null;
        Point lastCursor = null;
        long lastSent = 0;
        while (running) {
            long t0 = System.currentTimeMillis();
            HostServer s = server;
            if (s == null || s.clientCount() == 0 || !s.wantsFrame()) {
                sleep(25);
                continue;
            }
            boolean fresh = s.takeFreshClient();
            BufferedImage shot = robot.createScreenCapture(bounds);
            int[] px = pixels(shot);
            Point cursor = cursor();
            boolean changed = last == null || !Arrays.equals(px, last) || !cursor.equals(lastCursor);
            if (changed || fresh || t0 - lastSent > 2000) {
                BufferedImage frame = scale(shot, cursor);
                jpeg.reset();
                try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(jpeg)) {
                    writer.setOutput(out);
                    writer.write(null, new IIOImage(frame, null, null), param);
                } catch (IOException e) {
                    continue;
                }
                s.sendFrame(frame.getWidth(), frame.getHeight(), jpeg.toByteArray(), jpeg.size());
                lastSent = t0;
            }
            last = px;
            lastCursor = cursor;
            sleep(FRAME_MS - (System.currentTimeMillis() - t0));
        }
        writer.dispose();
    }

    private static int[] pixels(BufferedImage img) {
        if (img.getRaster().getDataBuffer() instanceof DataBufferInt) {
            return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        }
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private Point cursor() {
        PointerInfo info = MouseInfo.getPointerInfo();
        return info == null ? new Point(-1, -1) : info.getLocation();
    }

    /** Downscales to MAX_WIDTH and draws the mouse pointer, which Robot does not capture. */
    private BufferedImage scale(BufferedImage shot, Point cursor) {
        double k = Math.min(1.0, (double) MAX_WIDTH / bounds.width);
        int w = (int) Math.round(bounds.width * k), h = (int) Math.round(bounds.height * k);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(shot, 0, 0, w, h, null);
        if (bounds.contains(cursor)) {
            int x = (int) ((cursor.x - bounds.x) * k), y = (int) ((cursor.y - bounds.y) * k);
            Polygon arrow = new Polygon(new int[] {x, x, x + 4, x + 7, x + 9, x + 6, x + 11},
                    new int[] {y, y + 16, y + 12, y + 18, y + 17, y + 11, y + 11}, 7);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fill(arrow);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1.2f));
            g.draw(arrow);
        }
        g.dispose();
        return out;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ---- HostServer.Platform ----

    @Override
    public String deviceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return System.getenv().getOrDefault("COMPUTERNAME", "Windows PC");
        }
    }

    @Override
    public String platform() {
        return Proto.WINDOWS;
    }

    @Override
    public boolean controlAllowed() {
        return controlAllowed;
    }

    private void moveTo(float x, float y) {
        robot.mouseMove(bounds.x + Math.round(clamp(x) * (bounds.width - 1)),
                bounds.y + Math.round(clamp(y) * (bounds.height - 1)));
    }

    @Override
    public synchronized void onPointer(int action, int button, float x, float y) {
        moveTo(x, y);
        int mask = InputEvent.getMaskForButton(button == Proto.BTN_RIGHT ? 3 : button == Proto.BTN_MIDDLE ? 2 : 1);
        if (action == Proto.DOWN) {
            robot.mousePress(mask);
            heldButtons.add(mask);
        } else if (action == Proto.UP) {
            robot.mouseRelease(mask);
            heldButtons.remove(mask);
        }
    }

    @Override
    public synchronized void onScroll(float x, float y, int notches) {
        moveTo(x, y);
        robot.mouseWheel(notches);
    }

    @Override
    public synchronized void onKey(int vk, boolean down) {
        try {
            if (down) {
                robot.keyPress(vk);
                heldKeys.add(vk);
            } else {
                robot.keyRelease(vk);
                heldKeys.remove(vk);
            }
        } catch (IllegalArgumentException ignored) {
            // A key this keyboard does not have.
        }
    }

    private static final String PLAIN = "`-=[]\\;',./";
    private static final String SHIFTED = "~_+{}|:\"<>?";
    private static final int[] SYMBOL_VK = {KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS,
        KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_BACK_SLASH, KeyEvent.VK_SEMICOLON,
        KeyEvent.VK_QUOTE, KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_SLASH};
    private static final String SHIFTED_DIGITS = ")!@#$%^&*(";

    @Override
    public synchronized void onText(String text) {
        StringBuilder unmapped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!typeChar(c)) {
                unmapped.append(c);
                continue;
            }
            if (unmapped.length() > 0) {
                paste(unmapped.toString());
                unmapped.setLength(0);
            }
        }
        if (unmapped.length() > 0) paste(unmapped.toString());
    }

    /** Types characters with no key on a US layout (accents, emoji, other scripts) by pasting them. */
    private void paste(String text) {
        app.putOnClipboard(text);
        combo(KeyEvent.VK_CONTROL, KeyEvent.VK_V);
        sleep(30);
    }

    /** Types one character with a US keyboard layout; false if it has no key. */
    private boolean typeChar(char c) {
        int vk;
        boolean shift = false;
        int at;
        if (c >= 'a' && c <= 'z') {
            vk = KeyEvent.VK_A + (c - 'a');
        } else if (c >= 'A' && c <= 'Z') {
            vk = KeyEvent.VK_A + (c - 'A');
            shift = true;
        } else if (c >= '0' && c <= '9') {
            vk = KeyEvent.VK_0 + (c - '0');
        } else if (c == ' ') {
            vk = KeyEvent.VK_SPACE;
        } else if (c == '\n') {
            vk = KeyEvent.VK_ENTER;
        } else if (c == '\t') {
            vk = KeyEvent.VK_TAB;
        } else if ((at = PLAIN.indexOf(c)) >= 0) {
            vk = SYMBOL_VK[at];
        } else if ((at = SHIFTED.indexOf(c)) >= 0) {
            vk = SYMBOL_VK[at];
            shift = true;
        } else if ((at = SHIFTED_DIGITS.indexOf(c)) >= 0) {
            vk = KeyEvent.VK_0 + at;
            shift = true;
        } else {
            return false;
        }
        if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(vk);
        robot.keyRelease(vk);
        if (shift && !heldKeys.contains(Keys.SHIFT)) robot.keyRelease(KeyEvent.VK_SHIFT);
        return true;
    }

    @Override
    public synchronized void onAction(int action) {
        // Android's system buttons, mapped to the closest Windows equivalent.
        switch (action) {
            case Proto.ACT_BACK:
                combo(KeyEvent.VK_ALT, KeyEvent.VK_LEFT);
                break;
            case Proto.ACT_HOME:
                combo(KeyEvent.VK_WINDOWS, KeyEvent.VK_D);
                break;
            case Proto.ACT_RECENTS:
                combo(KeyEvent.VK_WINDOWS, KeyEvent.VK_TAB);
                break;
            case Proto.ACT_NOTIFICATIONS:
                combo(KeyEvent.VK_WINDOWS, KeyEvent.VK_N);
                break;
            default:
                break;
        }
    }

    private void combo(int modifier, int key) {
        robot.keyPress(modifier);
        robot.keyPress(key);
        robot.keyRelease(key);
        robot.keyRelease(modifier);
    }

    @Override
    public void onClipboard(String text) {
        app.onClipboardFromViewer(text);
    }

    @Override
    public FileReceiver.Sink fileSink() {
        return app.fileSink();
    }

    @Override
    public void onStateChanged() {
        app.onHostStateChanged();
    }

    @Override
    public synchronized void onViewerLeft() {
        for (Iterator<Integer> it = heldKeys.iterator(); it.hasNext(); ) {
            robot.keyRelease(it.next());
            it.remove();
        }
        for (Iterator<Integer> it = heldButtons.iterator(); it.hasNext(); ) {
            robot.mouseRelease(it.next());
            it.remove();
        }
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
