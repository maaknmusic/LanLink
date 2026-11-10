package com.lanlink.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import lanlink.core.FileReceiver;
import lanlink.core.HostServer;
import lanlink.core.Proto;

/** Shares this phone's screen: MediaProjection capture, JPEG frames, and input via {@link ControlService}. */
public final class HostService extends Service implements HostServer.Platform {
    // The service runs in its own process (":share"), so the screens control it with these
    // intents and learn its state from ACTION_STATE broadcasts instead of calling it directly.
    static final String ACTION_START = "com.lanlink.app.START", ACTION_STOP = "com.lanlink.app.STOP";
    static final String ACTION_QUERY = "com.lanlink.app.QUERY", ACTION_SETTINGS = "com.lanlink.app.SETTINGS";
    static final String ACTION_NEW_PIN = "com.lanlink.app.NEW_PIN", ACTION_CLIP = "com.lanlink.app.CLIP";
    static final String ACTION_FILE = "com.lanlink.app.FILE", ACTION_STATE = "com.lanlink.app.STATE";
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data", EXTRA_TEXT = "text";
    static final String EXTRA_ALLOW_CONTROL = "allowControl", EXTRA_SYNC_CLIP = "syncClipboard", EXTRA_DIM = "dim";
    static final String EXTRA_RUNNING = "running", EXTRA_PIN = "pin", EXTRA_VIEWERS = "viewers";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "sharing";
    /** Long side of streamed frames; high enough to stay sharp when the PC viewer zooms in. */
    private static final int MAX_SIDE = 1600;

    private HostServer server;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private volatile PowerManager.WakeLock screenLock;

    private final Object frameLock = new Object();
    private Bitmap latest;
    private ByteBuffer scratch;
    private int validWidth;
    private boolean dirty;
    /** Side of the square capture surface; 0 until it exists. */
    private volatile int capSide;
    private volatile boolean running;
    private volatile boolean allowControl = true, syncClip = true, dim;
    private android.view.View dimView;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent.hasExtra(EXTRA_ALLOW_CONTROL)) allowControl = intent.getBooleanExtra(EXTRA_ALLOW_CONTROL, true);
        if (intent.hasExtra(EXTRA_SYNC_CLIP)) syncClip = intent.getBooleanExtra(EXTRA_SYNC_CLIP, true);
        if (intent.hasExtra(EXTRA_DIM)) {
            dim = intent.getBooleanExtra(EXTRA_DIM, false);
            Hub.main.post(this::updateDim);
        }
        if (!ACTION_START.equals(action)) {
            command(action, intent);
            broadcastState();
            // Started only to answer a question while not sharing: go away again.
            if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification());
        }
        if (running) return START_NOT_STICKY;

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        projection = data == null ? null : mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data);
        if (projection == null) {
            Hub.toast(this, "Screen sharing was not allowed");
            stopSelf();
            return START_NOT_STICKY;
        }
        thread = new HandlerThread("lanlink-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (running) {
                    // Usually the phone locked: Android 15+ always ends screen sharing then.
                    Hub.toast(HostService.this, "Sharing stopped (the phone locked or sharing was cancelled). Tap Start sharing to share again.");
                }
                Hub.main.post(HostService.this::stopSelf);
            }
        }, handler);

        // Reuse the last PIN so a PC that was viewing can reconnect after a restart without re-pairing.
        server = new HostServer(this, Hub.prefs(this).getString("pin", null));
        try {
            server.start();
        } catch (IOException e) {
            Hub.toast(this, "Could not start sharing: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireLocks();
        handler.post(this::setupCapture);
        running = true;
        Thread sender = new Thread(this::sendLoop, "lanlink-sender");
        sender.setDaemon(true);
        sender.start();
        Hub.host = this;
        broadcastState();
        updateNotification();
        return START_NOT_STICKY;
    }

    private void command(String action, Intent intent) {
        if (!running || server == null) return;
        if (ACTION_NEW_PIN.equals(action)) {
            server.newPin();
        } else if (ACTION_CLIP.equals(action)) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text != null) {
                Hub.lastClip = text;
                sendClipboard(text);
            }
        } else if (ACTION_FILE.equals(action) && intent.getData() != null) {
            sendFile(intent.getData());
        }
    }

    /** Tells the app's screens (in the other process) whether we are sharing, the PIN and who is watching. */
    private void broadcastState() {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        HostServer s = server;
        boolean on = running && s != null;
        i.putExtra(EXTRA_RUNNING, on);
        if (on) {
            ArrayList<String> names = new ArrayList<>();
            for (HostServer.Client c : s.clients()) names.add(c.name);
            i.putExtra(EXTRA_PIN, s.pin());
            i.putStringArrayListExtra(EXTRA_VIEWERS, names);
            // Persist the current PIN so the next start (e.g. after HyperOS kills us) keeps it.
            Hub.prefs(this).edit().putString("pin", s.pin()).apply();
        }
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        running = false;
        Hub.host = null;
        if (server != null) server.stop();
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (thread != null) thread.quitSafely();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (screenLock != null && screenLock.isHeld()) screenLock.release();
        if (dimView != null) {
            try { ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(dimView); } catch (RuntimeException ignored) {}
            dimView = null;
        }
        ControlService c = ControlService.instance;
        if (c != null) c.reset();
        broadcastState();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            // Needed to receive discovery broadcasts on many phones.
            multicastLock = wifi.createMulticastLock("lanlink");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lanlink");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanlink:share");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        // Deprecated for apps, but the only way for a service to stop the screen timeout.
        screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "lanlink:viewer");
        screenLock.setReferenceCounted(false);
    }

    /**
     * Keeps the screen on while someone is viewing. Android 15+ ends screen sharing when
     * the phone locks, and with a viewer in control nobody touches the phone to stop the timeout.
     */
    private void updateScreenLock() {
        PowerManager.WakeLock l = screenLock;
        HostServer s = server;
        if (l == null) return;
        boolean viewers = running && s != null && s.clientCount() > 0;
        if (viewers && !l.isHeld()) l.acquire();
        else if (!viewers && l.isHeld()) l.release();
    }

    /**
     * Shows or hides a transparent overlay that lowers the panel brightness while a PC is viewing.
     * The overlay is see-through, so the screen the PC receives is unchanged; only the phone's own
     * backlight dims, which saves battery and screen wear. Needs the "Display over other apps" permission.
     */
    private void updateDim() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        HostServer s = server;
        boolean want = running && dim && s != null && s.clientCount() > 0
                && (Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this));
        if (want && dimView == null) {
            dimView = new android.view.View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.screenBrightness = 0.02f; // Panel to minimum; the captured image is not affected.
            try {
                wm.addView(dimView, lp);
            } catch (RuntimeException e) {
                dimView = null;
            }
        } else if (!want && dimView != null) {
            try { wm.removeView(dimView); } catch (RuntimeException ignored) {}
            dimView = null;
        }
    }

    // ---- Capture ----

    private DisplayMetrics realMetrics() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    /**
     * Creates the capture surface once, as a square as wide as the screen's long side.
     * Android mirrors the screen into it scaled to fit and centred, in either orientation,
     * and {@link #contentRect} crops the screen back out. Resizing the surface on rotation
     * instead leaves the mirror drawn in the wrong place on some phones.
     */
    private void setupCapture() {
        if (projection == null) return;
        DisplayMetrics m = realMetrics();
        int longSide = Math.max(m.widthPixels, m.heightPixels);
        int side = Math.round(longSide * Math.min(1f, (float) MAX_SIDE / longSide)) & ~1;
        reader = ImageReader.newInstance(side, side, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);
        display = projection.createVirtualDisplay("LanLink", side, side, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        capSide = side;
    }

    /** Where the screen sits inside the square capture: {x, y, width, height}. */
    private int[] contentRect(int screenW, int screenH) {
        float k = (float) capSide / Math.max(screenW, screenH);
        int w = Math.min(capSide, Math.round(screenW * k)) & ~1, h = Math.min(capSide, Math.round(screenH * k)) & ~1;
        return new int[] {(capSide - w) / 2, (capSide - h) / 2, w, h};
    }

    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (RuntimeException e) {
            return;
        }
        if (img == null) return;
        try {
            Image.Plane plane = img.getPlanes()[0];
            int stridePx = plane.getRowStride() / plane.getPixelStride();
            int h = img.getHeight();
            ByteBuffer src = plane.getBuffer();
            synchronized (frameLock) {
                if (latest == null || latest.getWidth() != stridePx || latest.getHeight() != h) {
                    latest = Bitmap.createBitmap(stridePx, h, Bitmap.Config.ARGB_8888);
                }
                int need = stridePx * h * 4;
                if (src.remaining() >= need) {
                    latest.copyPixelsFromBuffer(src);
                } else {
                    // The last row can be shorter than the stride; pad it out.
                    if (scratch == null || scratch.capacity() < need) scratch = ByteBuffer.allocateDirect(need);
                    scratch.clear();
                    scratch.put(src);
                    scratch.rewind();
                    latest.copyPixelsFromBuffer(scratch);
                }
                validWidth = img.getWidth();
                dirty = true;
            }
        } catch (RuntimeException ignored) {
        } finally {
            img.close();
        }
    }

    /** Encodes and sends the newest frame whenever a viewer is ready for one. */
    private void sendLoop() {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(256 * 1024);
        long lastSizeCheck = 0;
        int screenW = 0, screenH = 0;
        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastSizeCheck > 300) {
                lastSizeCheck = now;
                DisplayMetrics m = realMetrics();
                if (m.widthPixels != screenW || m.heightPixels != screenH) {
                    // Rotated: resend with the new crop even if no new image arrives.
                    screenW = m.widthPixels;
                    screenH = m.heightPixels;
                    synchronized (frameLock) {
                        dirty = true;
                    }
                }
            }
            HostServer s = server;
            boolean sent = false;
            if (s != null && s.clientCount() > 0 && s.wantsFrame() && capSide > 0) {
                boolean fresh = s.takeFreshClient();
                int w = 0, h = 0;
                synchronized (frameLock) {
                    if (latest != null && (dirty || fresh) && latest.getHeight() >= capSide && validWidth >= capSide) {
                        dirty = false;
                        int[] r = contentRect(screenW, screenH);
                        w = r[2];
                        h = r[3];
                        Bitmap frame = Bitmap.createBitmap(latest, r[0], r[1], w, h);
                        jpeg.reset();
                        frame.compress(Bitmap.CompressFormat.JPEG, 60, jpeg);
                        if (frame != latest) frame.recycle();
                    }
                }
                if (w > 0) {
                    s.sendFrame(w, h, jpeg.toByteArray(), jpeg.size());
                    sent = true;
                }
            }
            if (!sent) {
                try { Thread.sleep(15); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ---- Called from the main screen ----

    HostServer server() {
        return server;
    }

    void sendClipboard(String text) {
        if (server != null) server.sendClipboard(text);
    }

    void sendFile(final Uri uri) {
        Object[] info = AndroidFiles.describe(this, uri);
        final String name = (String) info[0];
        final long size = (Long) info[1];
        Hub.background(() -> {
            try {
                server.sendFile(name, size, () -> {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("Cannot open " + name);
                    return in;
                }, null);
                Hub.toast(this, "Sent " + name);
            } catch (IOException e) {
                Hub.toast(this, "Could not send " + name + ": " + e.getMessage());
            }
        });
    }

    // ---- Notification ----

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        String text;
        if (server == null) {
            text = "Starting...";
        } else {
            int n = server.clientCount();
            text = "PIN " + server.pin() + "  |  " + (n == 0 ? "no viewers yet" : n + " viewer" + (n == 1 ? "" : "s") + " connected");
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), immutable);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, HostService.class).setAction(ACTION_STOP), immutable);
        return b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Sharing this phone's screen")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Stop sharing", stop).build())
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (running) nm.notify(NOTIFICATION_ID, notification());
    }

    // ---- HostServer.Platform ----

    @Override
    public String deviceName() {
        return Hub.deviceName();
    }

    @Override
    public String platform() {
        return Proto.ANDROID;
    }

    @Override
    public boolean controlAllowed() {
        // Settings arrive by intent: preferences written in the app's other process are not seen here.
        return ControlService.enabled() && allowControl;
    }

    @Override
    public void onPointer(int action, int button, float x, float y) {
        ControlService c = ControlService.instance;
        if (c != null) c.pointer(action, button, x, y);
    }

    @Override
    public void onScroll(float x, float y, int notches) {
        ControlService c = ControlService.instance;
        if (c != null) c.scroll(x, y, notches);
    }

    @Override
    public void onKey(int vk, boolean down) {
        ControlService c = ControlService.instance;
        if (c != null) c.key(vk, down);
    }

    @Override
    public void onText(String text) {
        ControlService c = ControlService.instance;
        if (c != null) c.text(text);
    }

    @Override
    public void onAction(int action) {
        ControlService c = ControlService.instance;
        if (c != null) c.action(action);
    }

    @Override
    public void onClipboard(String text) {
        if (syncClip) Hub.setClipboard(this, text);
    }

    @Override
    public FileReceiver.Sink fileSink() {
        return new AndroidFiles(this);
    }

    @Override
    public void onStateChanged() {
        Hub.main.post(this::updateNotification);
        Hub.main.post(this::updateScreenLock);
        Hub.main.post(this::updateDim);
        broadcastState();
    }

    @Override
    public void onViewerLeft() {
        ControlService c = ControlService.instance;
        if (c != null) c.reset();
    }
}
