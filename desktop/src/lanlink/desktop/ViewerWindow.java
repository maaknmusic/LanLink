package lanlink.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import lanlink.core.FileReceiver;
import lanlink.core.Keys;
import lanlink.core.Proto;
import lanlink.core.ViewerClient;

/** Shows another device's screen and forwards mouse and keyboard to it. */
final class ViewerWindow extends JFrame implements ViewerClient.Listener {
    /** How long to keep retrying after the host drops the connection (time to restart sharing on a phone). */
    private static final long RECONNECT_WINDOW_MS = 150_000;

    private volatile ViewerClient client;
    private final MainWindow app;
    private final Screen screen = new Screen();
    private final JLabel status = new JLabel(" ");
    private final Set<Integer> keysDown = new HashSet<>();
    private static final Color BACKGROUND = new Color(0x111318);
    private final JScrollPane scroller;
    private volatile BufferedImage frame;
    private boolean sized;
    /** Image pixels per screen pixel; 0 means fit to the window. */
    private double zoom;

    // For reconnecting after the host (e.g. a phone) briefly drops the link.
    private final String host, pin, myName;
    private final int port;
    private volatile boolean closing;
    private volatile boolean reconnecting;

    ViewerWindow(ViewerClient client, MainWindow app, String host, int port, String pin, String myName) {
        super(client.hostName + " - LanLink");
        this.client = client;
        this.app = app;
        this.host = host;
        this.port = port;
        this.pin = pin;
        this.myName = myName;
        setIconImages(Icons.windowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        if (client.hostIsAndroid()) {
            bar.add(button("Back", "Android Back button (or right-click)", () -> client.action(Proto.ACT_BACK)));
            bar.add(button("Home", "Android Home button", () -> client.action(Proto.ACT_HOME)));
            bar.add(button("Recent apps", "Android app switcher", () -> client.action(Proto.ACT_RECENTS)));
            bar.add(button("Notifications", "Pull down the notification shade", () -> client.action(Proto.ACT_NOTIFICATIONS)));
        } else {
            bar.add(button("Start", "Windows key", () -> client.tapKey(Keys.WINDOWS)));
            bar.add(button("Task view", "Win+Tab", () -> {
                client.key(Keys.WINDOWS, true);
                client.tapKey(Keys.TAB);
                client.key(Keys.WINDOWS, false);
            }));
        }
        bar.addSeparator();
        bar.add(button("-", "Zoom out (Ctrl+minus or Ctrl+wheel)", () -> zoomBy(0.8)));
        bar.add(button("+", "Zoom in (Ctrl+plus or Ctrl+wheel)", () -> zoomBy(1.25)));
        bar.add(button("Fit", "Fit to window (Ctrl+0)", this::fit));
        bar.add(button("Shortcuts", "Keyboard and mouse controls", this::showShortcuts));
        bar.addSeparator();
        bar.add(button("Send file...", "Or drag files onto this window", this::chooseFiles));
        bar.add(button("Send clipboard", "Send this PC's copied text", () -> {
            String text = app.clip.current();
            if (text != null) {
                client.clipboard(text);
                setStatus("Clipboard sent");
            }
        }));
        bar.addSeparator();
        bar.add(status);
        status.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        if (!client.controlAllowed) setStatus("View only: control is turned off on " + client.hostName);

        add(bar, BorderLayout.NORTH);
        scroller = new JScrollPane(screen);
        scroller.setBorder(null);
        scroller.getViewport().setBackground(BACKGROUND);
        scroller.getVerticalScrollBar().setUnitIncrement(24);
        scroller.getHorizontalScrollBar().setUnitIncrement(24);
        scroller.setPreferredSize(new Dimension(960, 600));
        add(scroller, BorderLayout.CENTER);
        screen.setTransferHandler(new FileDrop());
        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                closing = true;
                client.close();
                app.viewerClosed(ViewerWindow.this);
            }

            @Override
            public void windowActivated(WindowEvent e) {
                screen.requestFocusInWindow();
            }
        });
        client.start(this);
    }

    void sendClipboard(String text) {
        client.clipboard(text);
    }

    private JButton button(String label, String tip, Runnable action) {
        JButton b = new JButton(label);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    // ---- Zoom ----

    private double fitScale() {
        BufferedImage f = frame;
        Dimension view = scroller.getViewport().getExtentSize();
        if (f == null || view.width == 0) return 1;
        return Math.min((double) view.width / f.getWidth(), (double) view.height / f.getHeight());
    }

    private void zoomBy(double factor) {
        if (frame == null) return;
        double current = zoom == 0 ? fitScale() : zoom;
        double next = Math.max(0.25, Math.min(4, current * factor));
        // Zooming back out past the window size snaps to fit.
        zoom = factor < 1 && next <= fitScale() ? 0 : next;
        screen.revalidate();
        screen.repaint();
        setStatus(zoom == 0 ? "Fit to window" : "Zoom " + Math.round(zoom * 100) + "%");
    }

    private void fit() {
        zoom = 0;
        screen.revalidate();
        screen.repaint();
        setStatus("Fit to window");
    }

    private void showShortcuts() {
        String text = client.hostIsAndroid()
                ? "Mouse\n"
                + "  Click = tap      Drag = swipe      Wheel = scroll\n"
                + "  Right-click = Back      Middle-click = Home\n\n"
                + "Phone buttons\n"
                + "  Esc or Alt+Left = Back      F1 = Home      F2 = Recent apps\n"
                + "  F3 = Notifications      F4 = Quick settings      PrtSc = Screenshot\n\n"
                + "In a text box (tap it first)\n"
                + "  Type normally, Backspace, Delete, Enter\n"
                + "  Ctrl+A select all      Ctrl+C / Ctrl+X copy / cut to this PC\n"
                + "  Ctrl+V paste this PC's clipboard\n"
                + "  Shift+arrows, Shift+Home/End select      Ctrl+arrows jump words\n"
                + "  Ctrl+Backspace delete word      Tab / Shift+Tab next / previous field\n\n"
                + "Anywhere else (no text box selected)\n"
                + "  Tab or Shift+Tab = Recent apps      Left / Right = previous / next app\n"
                + "  Enter = open the app in the middle\n"
                + "  Up / Down scroll a little      Page Up / Page Down scroll a page\n\n"
                + "This window\n"
                + "  Ctrl+wheel, Ctrl+plus / Ctrl+minus zoom      Ctrl+0 fit to window\n"
                + "  Drag files onto the screen to send them"
                : "Your mouse and keyboard work as if you were at that PC, including shortcuts\n"
                + "like Ctrl+C, Ctrl+V and Alt+Tab (Windows may keep Win and Alt+Tab for this PC).\n"
                + "Ctrl+V pastes this PC's clipboard on the other PC.\n\n"
                + "This window\n"
                + "  Ctrl+wheel, Ctrl+plus / Ctrl+minus zoom      Ctrl+0 fit to window\n"
                + "  Drag files onto the screen to send them";
        javax.swing.JTextArea area = new javax.swing.JTextArea(text);
        area.setEditable(false);
        area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        area.setOpaque(false);
        javax.swing.JOptionPane.showMessageDialog(this, area, "Controls for " + client.hostName, javax.swing.JOptionPane.PLAIN_MESSAGE);
        screen.requestFocusInWindow();
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    private void chooseFiles() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Send files to " + client.hostName);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) sendFiles(java.util.Arrays.asList(fc.getSelectedFiles()));
    }

    private void sendFiles(List<File> files) {
        Thread t = new Thread(() -> {
            for (File f : files) {
                if (!f.isFile()) continue;
                try (InputStream in = new FileInputStream(f)) {
                    client.sendFile(f.getName(), f.length(), in, (sent, total) ->
                            setStatus("Sending " + f.getName() + "  " + (total > 0 ? sent * 100 / total : 0) + "%"));
                    setStatus("Sent " + f.getName());
                } catch (IOException e) {
                    setStatus("Could not send " + f.getName() + ": " + e.getMessage());
                    return;
                }
            }
        }, "lanlink-send");
        t.setDaemon(true);
        t.start();
    }

    // ---- ViewerClient.Listener (background thread) ----

    @Override
    public void onFrame(int width, int height, byte[] data, int off, int len) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(data, off, len));
        } catch (IOException e) {
            img = null;
        }
        client.ackFrame();
        if (img == null) return;
        final BufferedImage next = img;
        SwingUtilities.invokeLater(() -> {
            frame = next;
            if (!sized) {
                sized = true;
                fitTo(next.getWidth(), next.getHeight());
            }
            screen.repaint();
        });
    }

    @Override
    public void onClipboard(String text) {
        app.clip.setFromRemote(text);
    }

    @Override
    public FileReceiver.Sink fileSink() {
        return app.fileSink();
    }

    @Override
    public void onClosed(String reason) {
        if (closing) return;
        // The host dropped us (phone locked, Wi-Fi blip, sharing restarted): keep trying to get back in.
        if (!reconnecting) {
            reconnecting = true;
            Thread t = new Thread(this::reconnectLoop, "lanlink-reconnect");
            t.setDaemon(true);
            t.start();
        }
        final boolean phone = client.hostIsAndroid();
        SwingUtilities.invokeLater(() -> {
            if (isDisplayable()) {
                setTitle(client.hostName + " - reconnecting...");
                status.setText(phone ? "Connection lost - if the phone stopped sharing, tap Start sharing on it"
                        : "Connection lost - reconnecting...");
                screen.repaint();
            }
        });
    }

    private void reconnectLoop() {
        long until = System.currentTimeMillis() + RECONNECT_WINDOW_MS;
        int attempt = 0;
        while (!closing && System.currentTimeMillis() < until) {
            try { Thread.sleep(attempt == 0 ? 500 : 1500); } catch (InterruptedException e) { return; }
            if (closing) return;
            attempt++;
            try {
                ViewerClient c = ViewerClient.connect(host, port, pin, myName, Proto.WINDOWS);
                if (closing) {
                    c.close();
                    return;
                }
                client = c;
                reconnecting = false;
                SwingUtilities.invokeLater(() -> {
                    setTitle(c.hostName + " - LanLink");
                    status.setText("Reconnected");
                    screen.repaint();
                });
                c.start(this);
                return;
            } catch (ViewerClient.AuthException e) {
                // The PIN changed on the host (e.g. after New PIN); no point retrying.
                reconnecting = false;
                SwingUtilities.invokeLater(() -> status.setText("Reconnect failed: " + e.getMessage() + ". Close and connect again."));
                return;
            } catch (IOException e) {
                final int n = attempt;
                SwingUtilities.invokeLater(() -> status.setText("Reconnecting... (try " + n + ")"));
            }
        }
        reconnecting = false;
        SwingUtilities.invokeLater(() -> {
            if (isDisplayable() && !closing) {
                status.setText("Disconnected - close this window and connect again");
                screen.repaint();
            }
        });
    }

    /** True while connected or still trying to get back. */
    private boolean live() {
        return !client.isClosed() || reconnecting;
    }

    /** Sizes the window to the remote screen's shape, within 85% of this screen. */
    private void fitTo(int w, int h) {
        Rectangle avail = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Insets in = getInsets();
        int extraW = in.left + in.right, extraH = in.top + in.bottom + getContentPane().getHeight() - scroller.getHeight();
        double k = Math.min((avail.width * 0.85 - extraW) / w, (avail.height * 0.85 - extraH) / h);
        k = Math.min(k, 1.0);
        setSize((int) (w * k) + extraW, (int) (h * k) + extraH);
        setLocationRelativeTo(null);
    }

    /** The remote screen, letterboxed or zoomed, with mouse and keyboard forwarding. */
    private final class Screen extends JComponent implements Scrollable {
        private final Rectangle img = new Rectangle();

        @Override
        public Dimension getPreferredSize() {
            BufferedImage f = frame;
            if (zoom == 0 || f == null) return scroller.getViewport().getExtentSize();
            return new Dimension((int) (f.getWidth() * zoom), (int) (f.getHeight() * zoom));
        }

        // In fit mode the screen tracks the window size; zoomed, it scrolls.
        @Override public boolean getScrollableTracksViewportWidth() { return zoom == 0 || getPreferredSize().width < scroller.getViewport().getWidth(); }
        @Override public boolean getScrollableTracksViewportHeight() { return zoom == 0 || getPreferredSize().height < scroller.getViewport().getHeight(); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 200; }

        Screen() {
            setFocusable(true);
            setFocusTraversalKeysEnabled(false);
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    pointer(Proto.DOWN, e);
                }
                @Override public void mouseReleased(MouseEvent e) { pointer(Proto.UP, e); }
                @Override public void mouseMoved(MouseEvent e) { pointer(Proto.MOVE, e); }
                @Override public void mouseDragged(MouseEvent e) { pointer(Proto.MOVE, e); }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    if (e.getWheelRotation() == 0) return;
                    if (e.isControlDown()) zoomBy(e.getWheelRotation() < 0 ? 1.25 : 0.8);
                    else client.scroll(nx(e.getX()), ny(e.getY()), e.getWheelRotation());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) { key(e, true); }
                @Override public void keyReleased(KeyEvent e) { key(e, false); }
                @Override public void keyTyped(KeyEvent e) {
                    char c = e.getKeyChar();
                    if (c >= 32 && c != 127 && c != KeyEvent.CHAR_UNDEFINED && !e.isControlDown() && !e.isAltDown()) {
                        client.text(String.valueOf(c));
                    }
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) {
                    for (int vk : keysDown) client.key(vk, false);
                    keysDown.clear();
                }
            });
        }

        private void pointer(int action, MouseEvent e) {
            int button = SwingUtilities.isRightMouseButton(e) ? Proto.BTN_RIGHT
                    : SwingUtilities.isMiddleMouseButton(e) ? Proto.BTN_MIDDLE : Proto.BTN_LEFT;
            // Android only reacts to drags, so skip plain hover moves for it.
            if (action == Proto.MOVE && client.hostIsAndroid() && e.getModifiersEx() == 0) return;
            client.pointer(action, button, nx(e.getX()), ny(e.getY()));
        }

        /** Printable characters go as TEXT from keyTyped; everything else (and shortcuts) as KEY. */
        private void key(KeyEvent e, boolean down) {
            int vk = e.getKeyCode();
            if (e.isControlDown() && !e.isAltDown() && localShortcut(vk, down)) {
                e.consume();
                return;
            }
            if (vk == KeyEvent.VK_PRINTSCREEN) {
                // Windows reports only the release of PrtSc.
                if (!down) client.tapKey(Keys.PRINT_SCREEN);
                e.consume();
                return;
            }
            if (down && vk == KeyEvent.VK_V && e.isControlDown()) {
                // Paste this PC's clipboard on the other device.
                String text = app.clip.current();
                if (text != null) client.clipboard(text);
            }
            if (down) {
                boolean modifier = vk == KeyEvent.VK_CONTROL || vk == KeyEvent.VK_ALT || vk == KeyEvent.VK_SHIFT
                        || vk == KeyEvent.VK_WINDOWS || vk == KeyEvent.VK_META;
                boolean special = e.getKeyChar() == KeyEvent.CHAR_UNDEFINED || vk == KeyEvent.VK_ENTER
                        || vk == KeyEvent.VK_BACK_SPACE || vk == KeyEvent.VK_TAB || vk == KeyEvent.VK_ESCAPE
                        || vk == KeyEvent.VK_DELETE;
                boolean shortcut = e.isControlDown() || e.isAltDown();
                if (modifier || special || shortcut) {
                    keysDown.add(vk);
                    client.key(vk, true);
                }
            } else if (keysDown.remove(vk)) {
                client.key(vk, false);
            }
            e.consume();
        }

        /** Ctrl+plus / Ctrl+minus / Ctrl+0 zoom this window instead of going to the remote device. */
        private boolean localShortcut(int vk, boolean down) {
            boolean in = vk == KeyEvent.VK_EQUALS || vk == KeyEvent.VK_PLUS || vk == KeyEvent.VK_ADD;
            boolean out = vk == KeyEvent.VK_MINUS || vk == KeyEvent.VK_SUBTRACT;
            boolean reset = vk == KeyEvent.VK_0 || vk == KeyEvent.VK_NUMPAD0;
            if (!in && !out && !reset) return false;
            if (down) {
                if (in) zoomBy(1.25);
                else if (out) zoomBy(0.8);
                else fit();
            }
            return true;
        }

        private float nx(int x) {
            return img.width == 0 ? 0 : (float) (x - img.x) / img.width;
        }

        private float ny(int y) {
            return img.height == 0 ? 0 : (float) (y - img.y) / img.height;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage f = frame;
            if (f == null) {
                g.setColor(Color.LIGHT_GRAY);
                String msg = reconnecting ? "Reconnecting..." : client.isClosed() ? "Disconnected" : "Waiting for the screen...";
                g.drawString(msg, (getWidth() - g.getFontMetrics().stringWidth(msg)) / 2, getHeight() / 2);
                return;
            }
            double k = zoom > 0 ? zoom : Math.min((double) getWidth() / f.getWidth(), (double) getHeight() / f.getHeight());
            int w = (int) (f.getWidth() * k), h = (int) (f.getHeight() * k);
            img.setBounds(Math.max(0, (getWidth() - w) / 2), Math.max(0, (getHeight() - h) / 2), w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(f, img.x, img.y, img.width, img.height, null);
            if (!live()) {
                g.setColor(new Color(0, 0, 0, 150));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.WHITE);
                String msg = "Disconnected - close this window and connect again";
                g.drawString(msg, (getWidth() - g.getFontMetrics().stringWidth(msg)) / 2, getHeight() / 2);
            } else if (reconnecting) {
                g.setColor(new Color(0, 0, 0, 120));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.WHITE);
                String msg = "Reconnecting...";
                g.drawString(msg, (getWidth() - g.getFontMetrics().stringWidth(msg)) / 2, getHeight() / 2);
            }
        }
    }

    /** Lets files be dragged from Explorer onto the remote screen. */
    private final class FileDrop extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            try {
                sendFiles((List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
