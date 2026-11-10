package lanlink.desktop;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new MainWindow().show();
        });
    }
}
