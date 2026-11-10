package lanlink.desktop;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lanlink.core.FileReceiver;
import lanlink.core.HostServer;
import lanlink.core.Proto;
import lanlink.core.ViewerClient;

/**
 * Checks the real Windows capture path: Robot screenshot -> JPEG -> socket -> decode.
 * Sends no input, so it never moves the mouse or types.
 * Run: java -cp build\desktop\classes test\CaptureTest.java
 */
public class CaptureTest {
    public static void main(String[] args) throws Exception {
        DesktopHost host = new DesktopHost(new DesktopHost.Callbacks() {
            public void onClipboardFromViewer(String t) {}
            public void putOnClipboard(String t) {}
            public FileReceiver.Sink fileSink() { return null; }
            public void onHostStateChanged() {}
        });
        HostServer server = new HostServer(host);
        server.start();
        host.start(server);

        ViewerClient v = ViewerClient.connect("127.0.0.1", Proto.TCP_PORT, server.pin(), "Test", Proto.WINDOWS);
        CountDownLatch got = new CountDownLatch(5);
        BufferedImage[] last = {null};
        long[] bytes = {0};
        long t0 = System.currentTimeMillis();
        v.start(new ViewerClient.Listener() {
            public void onFrame(int w, int h, byte[] d, int off, int len) {
                try { last[0] = ImageIO.read(new ByteArrayInputStream(d, off, len)); } catch (Exception ignored) {}
                bytes[0] += len;
                v.ackFrame();
                got.countDown();
            }
            public void onClipboard(String t) {}
            public FileReceiver.Sink fileSink() { return new FileReceiver.Sink() {
                public OutputStream open(String n, long s) { return OutputStream.nullOutputStream(); }
                public void finished(OutputStream o, String n, long s, boolean ok) {}
            }; }
            public void onClosed(String r) {}
        });
        // Frames repeat at least every 2 s even if the screen is still.
        boolean ok = got.await(15, TimeUnit.SECONDS);
        long ms = System.currentTimeMillis() - t0;
        BufferedImage img = last[0];
        boolean varied = false;
        if (img != null) {
            int first = img.getRGB(0, 0);
            for (int y = 0; y < img.getHeight() && !varied; y += 17)
                for (int x = 0; x < img.getWidth() && !varied; x += 17)
                    varied = img.getRGB(x, y) != first;
        }
        System.out.println("frames received: " + (5 - got.getCount()) + " in " + ms + " ms");
        System.out.println("decoded size: " + (img == null ? "none" : img.getWidth() + "x" + img.getHeight()));
        System.out.println("average JPEG: " + (bytes[0] / Math.max(1, 5 - got.getCount()) / 1024) + " KB");
        System.out.println(ok && img != null && varied ? "CAPTURE OK" : "CAPTURE FAILED");
        v.close();
        host.stop();
        server.stop();
        System.exit(ok && img != null && varied ? 0 : 1);
    }
}
