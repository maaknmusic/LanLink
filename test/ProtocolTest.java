import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lanlink.core.Discovery;
import lanlink.core.FileReceiver;
import lanlink.core.HostServer;
import lanlink.core.Keys;
import lanlink.core.Proto;
import lanlink.core.ViewerClient;

/**
 * End-to-end check of the shared protocol over real sockets on this machine.
 * Run: java -cp build\check test\ProtocolTest.java   (after compiling core into build\check)
 */
public class ProtocolTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    /** Collects files in memory. */
    static final class MemSink implements FileReceiver.Sink {
        final Map<String, byte[]> done = new ConcurrentHashMap<>();
        final CountDownLatch got = new CountDownLatch(1);
        public OutputStream open(String name, long size) { return new ByteArrayOutputStream(); }
        public void finished(OutputStream s, String name, long size, boolean ok) {
            if (ok) done.put(name, ((ByteArrayOutputStream) s).toByteArray());
            got.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch inputSeen = new CountDownLatch(6);
        MemSink hostFiles = new MemSink();
        boolean[] control = {true};

        HostServer server = new HostServer(new HostServer.Platform() {
            public String deviceName() { return "TestHost"; }
            public String platform() { return Proto.WINDOWS; }
            public boolean controlAllowed() { return control[0]; }
            public void onPointer(int a, int b, float x, float y) { events.add("pointer " + a + " " + b + " " + x + " " + y); inputSeen.countDown(); }
            public void onScroll(float x, float y, int n) { events.add("scroll " + n); inputSeen.countDown(); }
            public void onKey(int vk, boolean d) { events.add("key " + vk + " " + d); inputSeen.countDown(); }
            public void onText(String t) { events.add("text " + t); inputSeen.countDown(); }
            public void onAction(int a) { events.add("action " + a); inputSeen.countDown(); }
            public void onClipboard(String t) { events.add("clip " + t); inputSeen.countDown(); }
            public FileReceiver.Sink fileSink() { return hostFiles; }
            public void onStateChanged() {}
            public void onViewerLeft() { events.add("left"); }
        });
        server.start();
        String pin = server.pin();

        // Wrong PIN is refused
        try {
            ViewerClient.connect("127.0.0.1", Proto.TCP_PORT, "000000".equals(pin) ? "111111" : "000000", "Bad", Proto.ANDROID);
            check(false, "wrong PIN is rejected");
        } catch (ViewerClient.AuthException e) {
            check("Wrong PIN".equals(e.getMessage()), "wrong PIN is rejected (" + e.getMessage() + ")");
        }
        check(server.pin().equals(pin), "one wrong guess does not reset the PIN");

        // Correct PIN
        ViewerClient v = ViewerClient.connect("127.0.0.1", Proto.TCP_PORT, pin, "Phone", Proto.ANDROID);
        check("TestHost".equals(v.hostName) && Proto.WINDOWS.equals(v.hostPlatform) && v.controlAllowed, "correct PIN connects and reports host info");
        Thread.sleep(200);
        check(server.clientCount() == 1 && "Phone".equals(server.clients().get(0).name), "host lists the viewer");
        check(server.takeFreshClient() && !server.takeFreshClient(), "fresh-viewer flag fires once");

        CountDownLatch frames = new CountDownLatch(3);
        CountDownLatch clipToViewer = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        MemSink viewerFiles = new MemSink();
        String[] gotClip = {null};
        int[] frameInfo = new int[3];
        v.start(new ViewerClient.Listener() {
            public void onFrame(int w, int h, byte[] d, int off, int len) {
                frameInfo[0] = w; frameInfo[1] = h; frameInfo[2] = len;
                v.ackFrame();
                frames.countDown();
            }
            public void onClipboard(String t) { gotClip[0] = t; clipToViewer.countDown(); }
            public FileReceiver.Sink fileSink() { return viewerFiles; }
            public void onClosed(String r) { closed.countDown(); }
        });

        // Frames with flow control: at most 2 un-acked, acks free the slots
        byte[] fake = new byte[5000];
        int sent = 0;
        long until = System.currentTimeMillis() + 3000;
        while (frames.getCount() > 0 && System.currentTimeMillis() < until) {
            if (server.wantsFrame()) { server.sendFrame(640, 360, fake, fake.length); sent++; }
            Thread.sleep(5);
        }
        check(frames.getCount() == 0 && frameInfo[0] == 640 && frameInfo[1] == 360 && frameInfo[2] == 5000, "frames arrive intact (" + sent + " sent)");

        // Input and clipboard from viewer to host
        v.pointer(Proto.DOWN, Proto.BTN_LEFT, 0.25f, 0.75f);
        v.scroll(0.5f, 0.5f, 3);
        v.key(Keys.ENTER, true);
        v.text("héllo 👋");
        v.action(Proto.ACT_HOME);
        v.clipboard("copied on phone");
        check(inputSeen.await(3, TimeUnit.SECONDS), "all input events reach the host");
        check(events.contains("pointer 0 0 0.25 0.75"), "pointer position is exact");
        check(events.contains("scroll 3"), "scroll notches");
        check(events.contains("key 10 true"), "key code");
        check(events.contains("text héllo 👋"), "unicode text");
        check(events.contains("action 2"), "system action");
        check(events.contains("clip copied on phone"), "clipboard viewer -> host");

        // Control can be switched off mid-session
        control[0] = false;
        int before = events.size();
        v.text("ignored");
        v.clipboard("still syncs");
        Thread.sleep(300);
        check(!events.contains("text ignored") && events.contains("clip still syncs"), "input ignored when control is off; clipboard still works");
        control[0] = true;

        // Clipboard host -> viewer
        server.sendClipboard("copied on PC");
        check(clipToViewer.await(3, TimeUnit.SECONDS) && "copied on PC".equals(gotClip[0]), "clipboard host -> viewer");

        // Files both ways (300 KB = several chunks), with a hostile file name
        byte[] big = new byte[300_000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        v.sendFile("..\\..\\Windows\\evil.txt", big.length, new ByteArrayInputStream(big), null);
        check(hostFiles.got.await(5, TimeUnit.SECONDS), "file viewer -> host finishes");
        check(hostFiles.done.containsKey("evil.txt") && java.util.Arrays.equals(hostFiles.done.get("evil.txt"), big),
                "file content intact and path stripped from name " + hostFiles.done.keySet());
        byte[] small = "hello from the PC".getBytes(StandardCharsets.UTF_8);
        server.sendFile("note.txt", -1, () -> new ByteArrayInputStream(small), null);
        check(viewerFiles.got.await(5, TimeUnit.SECONDS) && java.util.Arrays.equals(viewerFiles.done.get("note.txt"), small),
                "file host -> viewer (unknown size)");

        check(FileReceiver.safeName("CON.txt").equals("_CON.txt") && FileReceiver.safeName("a:b?.txt").equals("a_b_.txt")
                && FileReceiver.safeName("...").equals("file"), "reserved and invalid names are made safe");

        // Discovery
        List<Discovery.Device> found = Discovery.scan(1500);
        boolean seen = false;
        for (Discovery.Device d : found) if ("TestHost".equals(d.name) && d.port == Proto.TCP_PORT) seen = true;
        check(seen, "discovery finds the host " + found);

        // Brute force protection: 5 wrong guesses replace the PIN
        String pinBefore = server.pin();
        for (int i = 0; i < 5; i++) {
            try { ViewerClient.connect("127.0.0.1", Proto.TCP_PORT, "x" + i, "Bad", Proto.ANDROID); } catch (ViewerClient.AuthException ignored) {}
        }
        check(!server.pin().equals(pinBefore), "PIN is replaced after 5 wrong guesses");

        // Disconnect
        v.close();
        check(closed.await(3, TimeUnit.SECONDS), "viewer sees the close");
        Thread.sleep(300);
        check(server.clientCount() == 0 && events.contains("left"), "host notices the viewer left and releases input");
        server.stop();

        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
