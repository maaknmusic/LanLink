package lanlink.desktop;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import lanlink.core.Discovery;
import lanlink.core.FileReceiver;
import lanlink.core.HostServer;
import lanlink.core.Proto;
import lanlink.core.ViewerClient;

/** The main window: share this PC, or connect to another device. Lives in the tray while in use. */
final class MainWindow implements DesktopHost.Callbacks {
    final Clip clip;
    private final JFrame frame = new JFrame("LanLink");
    private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);
    private final DesktopFiles files = new DesktopFiles(this::notifyUser);
    private final List<ViewerWindow> viewers = new CopyOnWriteArrayList<>();
    private DesktopHost host;
    private HostServer server;
    private TrayIcon tray;
    private boolean trayHintShown;

    private final JToggleButton shareToggle = new JToggleButton();
    private final JLabel shareState = new JLabel();
    private final JLabel pinLabel = new JLabel("------");
    private final JLabel addressLabel = new JLabel();
    private final JCheckBox allowControl = new JCheckBox("Allow viewers to control this PC (mouse and keyboard)");
    private final JCheckBox syncClip = new JCheckBox("Sync copied text between devices");
    private final DefaultListModel<String> viewerNames = new DefaultListModel<>();
    private final DefaultListModel<Discovery.Device> found = new DefaultListModel<>();
    private final JList<Discovery.Device> foundList = new JList<>(found);
    private final JTextField addressField = new JTextField(14);
    private final JButton scanButton = new JButton("Scan network");
    private final JLabel footer = new JLabel(" ");

    MainWindow() {
        clip = new Clip(this::onLocalClipboard);
        frame.setIconImages(Icons.windowIcons());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeWindow();
            }
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Share this PC", sharePanel());
        tabs.addTab("Connect to a device", connectPanel());
        footer.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        frame.add(tabs, BorderLayout.CENTER);
        frame.add(footer, BorderLayout.SOUTH);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationRelativeTo(null);
        setupTray();

        allowControl.setSelected(prefs.getBoolean("allowControl", true));
        syncClip.setSelected(prefs.getBoolean("syncClipboard", true));
        clip.setEnabled(syncClip.isSelected());
        addressField.setText(prefs.get("lastAddress", ""));
        if (prefs.getBoolean("sharing", true)) startSharing();
        refreshShare();
        scan();
    }

    void show() {
        frame.setVisible(true);
        frame.setExtendedState(Frame.NORMAL);
        frame.toFront();
    }

    // ---- Share tab ----

    private JComponent sharePanel() {
        JPanel p = column();
        shareToggle.addActionListener(e -> {
            if (shareToggle.isSelected()) startSharing(); else stopSharing();
            prefs.putBoolean("sharing", server != null);
            refreshShare();
        });
        p.add(row(shareToggle, shareState));
        p.add(Box.createVerticalStrut(10));

        JLabel pinTitle = new JLabel("PIN for this PC");
        pinLabel.setFont(pinLabel.getFont().deriveFont(Font.BOLD, 34f));
        JButton newPin = new JButton("New PIN");
        newPin.addActionListener(e -> {
            if (server != null) server.newPin();
        });
        p.add(row(pinTitle));
        p.add(row(pinLabel, Box.createHorizontalStrut(12), newPin));
        p.add(row(addressLabel));
        p.add(Box.createVerticalStrut(8));

        allowControl.addActionListener(e -> {
            prefs.putBoolean("allowControl", allowControl.isSelected());
            if (host != null) host.setControlAllowed(allowControl.isSelected());
        });
        syncClip.addActionListener(e -> {
            prefs.putBoolean("syncClipboard", syncClip.isSelected());
            clip.setEnabled(syncClip.isSelected());
        });
        p.add(row(allowControl));
        p.add(row(syncClip));
        p.add(Box.createVerticalStrut(8));

        p.add(row(new JLabel("Connected viewers")));
        JList<String> names = new JList<>(viewerNames);
        names.setVisibleRowCount(4);
        JScrollPane scroll = new JScrollPane(names);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(scroll);
        p.add(Box.createVerticalStrut(8));

        JButton sendFile = new JButton("Send file to viewers...");
        sendFile.addActionListener(e -> sendFileToViewers());
        JButton kick = new JButton("Disconnect all");
        kick.addActionListener(e -> {
            if (server != null) server.disconnectAll();
        });
        JButton openFolder = new JButton("Open received files");
        openFolder.addActionListener(e -> openReceivedFolder());
        p.add(row(sendFile, kick, openFolder));
        return p;
    }

    private void startSharing() {
        if (server != null) return;
        try {
            host = new DesktopHost(this);
            host.setControlAllowed(allowControl.isSelected());
            server = new HostServer(host);
            server.start();
            host.start(server);
        } catch (IOException | AWTException e) {
            if (host != null) host.stop();
            server = null;
            host = null;
            JOptionPane.showMessageDialog(frame, "Could not start sharing on port " + Proto.TCP_PORT
                    + ".\nIs LanLink already running?\n\n" + e.getMessage(), "LanLink", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void stopSharing() {
        if (server == null) return;
        host.stop();
        server.stop();
        server = null;
        host = null;
    }

    private void refreshShare() {
        boolean on = server != null;
        shareToggle.setSelected(on);
        shareToggle.setText(on ? "Sharing is ON" : "Sharing is OFF");
        viewerNames.clear();
        if (on) {
            String pin = server.pin();
            pinLabel.setText(pin.substring(0, 3) + " " + pin.substring(3));
            for (HostServer.Client c : server.clients()) viewerNames.addElement(c.name + "  (" + c.address + ")");
            int n = server.clientCount();
            shareState.setText(n == 0 ? "Waiting for a device to connect" : n + " device" + (n == 1 ? "" : "s") + " viewing this PC");
        } else {
            pinLabel.setText("--- ---");
            shareState.setText("Other devices cannot see this PC");
        }
        List<String> ips = Discovery.localAddresses();
        String name = host != null ? host.deviceName() : System.getenv().getOrDefault("COMPUTERNAME", "This PC");
        addressLabel.setText(name + "   |   IP: " + (ips.isEmpty() ? "not on a network" : String.join(", ", ips)));
        if (tray != null) tray.setToolTip(on ? "LanLink - sharing (PIN " + server.pin() + ")" : "LanLink");
        frame.getContentPane().revalidate();
        frame.getContentPane().repaint();
    }

    private void sendFileToViewers() {
        if (server == null || server.clientCount() == 0) {
            JOptionPane.showMessageDialog(frame, "No device is viewing this PC right now.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        final HostServer s = server;
        final File[] chosen = fc.getSelectedFiles();
        Thread t = new Thread(() -> {
            for (File f : chosen) {
                try {
                    s.sendFile(f.getName(), f.length(), () -> new FileInputStream(f), (sent, total) ->
                            setFooter("Sending " + f.getName() + "  " + (total > 0 ? sent * 100 / total : 0) + "%"));
                    setFooter("Sent " + f.getName());
                } catch (IOException e) {
                    setFooter("Could not send " + f.getName() + ": " + e.getMessage());
                }
            }
        }, "lanlink-send");
        t.setDaemon(true);
        t.start();
    }

    private void openReceivedFolder() {
        DesktopFiles.DIR.mkdirs();
        try {
            Desktop.getDesktop().open(DesktopFiles.DIR);
        } catch (IOException | UnsupportedOperationException e) {
            JOptionPane.showMessageDialog(frame, "Received files are saved in\n" + DesktopFiles.DIR);
        }
    }

    // ---- Connect tab ----

    private JComponent connectPanel() {
        JPanel p = column();
        p.add(row(new JLabel("Devices on your network that are sharing:")));
        foundList.setVisibleRowCount(7);
        foundList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && foundList.getSelectedValue() != null) {
                    Discovery.Device d = foundList.getSelectedValue();
                    connect(d.address, d.port, d.name);
                }
            }
        });
        JScrollPane scroll = new JScrollPane(foundList);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(scroll);
        JButton connectSelected = new JButton("Connect");
        connectSelected.addActionListener(e -> {
            Discovery.Device d = foundList.getSelectedValue();
            if (d != null) connect(d.address, d.port, d.name);
        });
        scanButton.addActionListener(e -> scan());
        p.add(row(connectSelected, scanButton));
        p.add(Box.createVerticalStrut(14));
        p.add(row(new JLabel("Not listed? Type the IP address shown in the LanLink app on the other device:")));
        JButton connectIp = new JButton("Connect");
        Runnable byIp = () -> {
            String a = addressField.getText().trim();
            if (!a.isEmpty()) connect(a, Proto.TCP_PORT, a);
        };
        connectIp.addActionListener(e -> byIp.run());
        addressField.addActionListener(e -> byIp.run());
        p.add(row(addressField, connectIp));
        p.add(Box.createVerticalGlue());
        return p;
    }

    private void scan() {
        scanButton.setEnabled(false);
        scanButton.setText("Scanning...");
        final List<String> mine = Discovery.localAddresses();
        new SwingWorker<List<Discovery.Device>, Void>() {
            @Override
            protected List<Discovery.Device> doInBackground() throws Exception {
                return Discovery.scan(1500);
            }

            @Override
            protected void done() {
                scanButton.setEnabled(true);
                scanButton.setText("Scan network");
                found.clear();
                try {
                    for (Discovery.Device d : get()) if (!mine.contains(d.address)) found.addElement(d);
                } catch (Exception e) {
                    setFooter("Scan failed: " + e.getMessage());
                }
                if (found.isEmpty()) {
                    setFooter("Nothing found. On the phone, tap Start sharing, then Scan again (or type its IP below).");
                } else {
                    setFooter("Found " + found.size() + " device" + (found.size() == 1 ? "" : "s") + ". Double-click one to connect.");
                }
            }
        }.execute();
    }

    private void connect(String address, int port, String label) {
        String pin = JOptionPane.showInputDialog(frame, "Enter the 6-digit PIN shown on " + label + ":",
                "Connect to " + label, JOptionPane.QUESTION_MESSAGE);
        if (pin == null) return;
        final String clean = pin.replaceAll("\\s", "");
        final String myName = host != null ? host.deviceName() : System.getenv().getOrDefault("COMPUTERNAME", "Windows PC");
        setFooter("Connecting to " + label + "...");
        new SwingWorker<ViewerClient, Void>() {
            @Override
            protected ViewerClient doInBackground() throws Exception {
                return ViewerClient.connect(address, port, clean, myName, Proto.WINDOWS);
            }

            @Override
            protected void done() {
                try {
                    ViewerClient c = get();
                    prefs.put("lastAddress", address);
                    ViewerWindow w = new ViewerWindow(c, MainWindow.this, address, port, clean, myName);
                    viewers.add(w);
                    w.setVisible(true);
                    setFooter("Connected to " + c.hostName);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String msg = cause instanceof ViewerClient.AuthException ? cause.getMessage()
                            : "Could not connect to " + label + ": " + cause.getMessage();
                    setFooter(msg);
                    JOptionPane.showMessageDialog(frame, msg, "LanLink", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    void viewerClosed(ViewerWindow w) {
        viewers.remove(w);
    }

    // ---- Clipboard, files, notifications ----

    private void onLocalClipboard(String text) {
        if (server != null) server.sendClipboard(text);
        for (ViewerWindow w : viewers) w.sendClipboard(text);
    }

    @Override
    public FileReceiver.Sink fileSink() {
        return files;
    }

    @Override
    public void onClipboardFromViewer(String text) {
        clip.setFromRemote(text);
    }

    @Override
    public void putOnClipboard(String text) {
        clip.writeNow(text);
    }

    @Override
    public void onHostStateChanged() {
        SwingUtilities.invokeLater(this::refreshShare);
    }

    private void notifyUser(String msg) {
        setFooter(msg);
        if (tray != null && !frame.isFocused()) tray.displayMessage("LanLink", msg + "\nSaved in Downloads\\LanLink", TrayIcon.MessageType.INFO);
    }

    private void setFooter(String msg) {
        SwingUtilities.invokeLater(() -> footer.setText(msg));
    }

    // ---- Window and tray ----

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Open LanLink");
        open.addActionListener(e -> SwingUtilities.invokeLater(this::show));
        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> SwingUtilities.invokeLater(this::quit));
        menu.add(open);
        menu.addSeparator();
        menu.add(quit);
        tray = new TrayIcon(Icons.draw(32), "LanLink", menu);
        tray.setImageAutoSize(true);
        tray.addActionListener(e -> SwingUtilities.invokeLater(this::show));
        try {
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException e) {
            tray = null;
        }
    }

    private void closeWindow() {
        if (tray != null && (server != null || !viewers.isEmpty())) {
            frame.setVisible(false);
            if (!trayHintShown) {
                trayHintShown = true;
                tray.displayMessage("LanLink is still running",
                        "Sharing continues in the background. Right-click the tray icon to quit.", TrayIcon.MessageType.INFO);
            }
        } else {
            quit();
        }
    }

    private void quit() {
        stopSharing();
        for (ViewerWindow w : viewers) w.dispose();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        System.exit(0);
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        return p;
    }

    private static JPanel row(Component... parts) {
        // Full width, and only as tall as its content; it re-measures when labels change.
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3)) {
            @Override
            public java.awt.Dimension getMaximumSize() {
                return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (Component c : parts) r.add(c);
        return r;
    }
}
