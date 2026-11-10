package com.lanlink.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import lanlink.core.FileReceiver;
import lanlink.core.Keys;
import lanlink.core.Proto;
import lanlink.core.ViewerClient;

/** Shows a PC or another phone and controls it. */
public final class ViewerActivity extends Activity implements ViewerClient.Listener {
    static final String EXTRA_HOST = "host", EXTRA_PORT = "port", EXTRA_PIN = "pin";
    private static final int REQ_FILE = 1;

    private RemoteView view;
    private TextView overlay;
    private LinearLayout bar;
    private volatile ViewerClient client;
    private boolean keyboardShown;
    private volatile boolean leaving;
    private volatile boolean reconnecting;
    private String host, pin;
    private int port;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111318);
        FrameLayout stage = new FrameLayout(this);
        view = new RemoteView(this);
        stage.addView(view, new FrameLayout.LayoutParams(-1, -1));
        overlay = Ui.text(this, "Connecting...", 16, 0xFFE5E7EB);
        overlay.setGravity(Gravity.CENTER);
        stage.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF1F2937);
        HorizontalScrollView barScroll = new HorizontalScrollView(this);
        barScroll.addView(bar);
        root.addView(barScroll, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        host = getIntent().getStringExtra(EXTRA_HOST);
        port = getIntent().getIntExtra(EXTRA_PORT, Proto.TCP_PORT);
        pin = getIntent().getStringExtra(EXTRA_PIN);
        Hub.background(() -> {
            try {
                ViewerClient c = ViewerClient.connect(host, port, pin == null ? "" : pin, Hub.deviceName(), Proto.ANDROID);
                if (leaving) {
                    c.close();
                    return;
                }
                client = c;
                runOnUiThread(this::onConnected);
            } catch (ViewerClient.AuthException e) {
                fail(e.getMessage());
            } catch (IOException e) {
                fail("Could not connect to " + host + ": " + e.getMessage());
            }
        });
    }

    private void fail(String msg) {
        Hub.toast(this, msg);
        runOnUiThread(this::finish);
    }

    private void onConnected() {
        ViewerClient c = client;
        overlay.setText("Waiting for " + c.hostName + "'s screen...");
        view.setClient(c);
        buildBar(c);
        c.start(this);
        if (!c.controlAllowed) {
            Hub.toast(this, c.hostIsAndroid()
                    ? "View only: remote control is off on " + c.hostName
                    : "View only: control is turned off on " + c.hostName);
        }
    }

    private void buildBar(final ViewerClient c) {
        bar.removeAllViews();
        addBarButton("Keyboard", v -> toggleKeyboard());
        if (c.hostIsAndroid()) {
            addBarButton("Back", v -> c.action(Proto.ACT_BACK));
            addBarButton("Home", v -> c.action(Proto.ACT_HOME));
            addBarButton("Recents", v -> c.action(Proto.ACT_RECENTS));
            addBarButton("Notifs", v -> c.action(Proto.ACT_NOTIFICATIONS));
        } else {
            final boolean pad = Hub.prefs(this).getBoolean("touchpad", false);
            view.setTouchpad(pad);
            final TextView mouseMode = addBarButton(pad ? "Mode: Touchpad" : "Mode: Touch", null);
            mouseMode.setOnClickListener(v -> {
                boolean on = !view.isTouchpad();
                view.setTouchpad(on);
                Hub.prefs(this).edit().putBoolean("touchpad", on).apply();
                mouseMode.setText(on ? "Mode: Touchpad" : "Mode: Touch");
                Hub.toast(this, on
                        ? "Touchpad: slide to move the pointer, tap to click, two-finger tap to right-click, long-press then slide to drag"
                        : "Touch: tap where you want to click, long-press to right-click");
            });
            addBarButton("Esc", v -> c.tapKey(Keys.ESCAPE));
            addBarButton("Tab", v -> c.tapKey(Keys.TAB));
            addBarButton("Win", v -> c.tapKey(Keys.WINDOWS));
        }
        addBarButton("Clipboard", v -> {
            String t = Hub.readClipboard(this);
            if (t == null) {
                Hub.toast(this, "Nothing copied");
                return;
            }
            Hub.lastClip = t;
            c.clipboard(t);
            Hub.toast(this, "Clipboard sent");
        });
        addBarButton("File", v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE));
        addBarButton("Close", v -> finish());
    }

    private TextView addBarButton(String label, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFFF9FAFB);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int px = Ui.dp(this, 14), py = Ui.dp(this, 12);
        b.setPadding(px, py, px, py);
        b.setOnClickListener(l);
        b.setBackgroundResource(android.R.drawable.list_selector_background);
        bar.addView(b);
        return b;
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboardShown) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        } else {
            view.requestFocus();
            imm.restartInput(view);
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
        keyboardShown = !keyboardShown;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean system = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_POWER;
        if (!system && client != null && KeyMap.send(client, e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setMessage("Disconnect from " + (client != null ? client.hostName : "this device") + "?")
                .setPositiveButton("Disconnect", (d, w) -> finish())
                .setNegativeButton("Stay", null)
                .show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        ViewerClient c = client;
        if (hasFocus && c != null && Hub.syncClipboard(this)) {
            String t = Hub.newClipboardText(this);
            if (t != null) c.clipboard(t);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        final ViewerClient c = client;
        if (request != REQ_FILE || result != RESULT_OK || data == null || data.getData() == null || c == null) return;
        final Uri uri = data.getData();
        Object[] info = AndroidFiles.describe(this, uri);
        final String name = (String) info[0];
        final long size = (Long) info[1];
        Hub.toast(this, "Sending " + name + "...");
        Hub.background(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open file");
                c.sendFile(name, size, in, null);
                Hub.toast(this, "Sent " + name);
            } catch (IOException e) {
                Hub.toast(this, "Could not send " + name + ": " + e.getMessage());
            }
        });
    }

    @Override
    protected void onDestroy() {
        leaving = true;
        ViewerClient c = client;
        if (c != null) c.close();
        super.onDestroy();
    }

    // ---- ViewerClient.Listener (background thread) ----

    @Override
    public void onFrame(int width, int height, byte[] data, int off, int len) {
        final Bitmap bm = BitmapFactory.decodeByteArray(data, off, len);
        ViewerClient c = client;
        if (c != null) c.ackFrame();
        if (bm == null) return;
        runOnUiThread(() -> {
            if (!view.hasFrame()) overlay.setVisibility(View.GONE);
            view.setFrame(bm);
        });
    }

    @Override
    public void onClipboard(String text) {
        if (Hub.syncClipboard(this)) Hub.setClipboard(this, text);
    }

    @Override
    public FileReceiver.Sink fileSink() {
        return new AndroidFiles(this);
    }

    @Override
    public void onClosed(String reason) {
        if (leaving || reconnecting) return;
        reconnecting = true;
        runOnUiThread(() -> {
            if (!leaving) {
                overlay.setText("Connection lost - reconnecting...");
                overlay.setVisibility(View.VISIBLE);
            }
        });
        Hub.background(this::reconnectLoop);
    }

    /** Tries to get back to the host for a while after a drop (e.g. the PC's Wi-Fi blipped). */
    private void reconnectLoop() {
        long until = System.currentTimeMillis() + 150_000;
        int attempt = 0;
        while (!leaving && System.currentTimeMillis() < until) {
            try { Thread.sleep(attempt == 0 ? 500 : 1500); } catch (InterruptedException e) { return; }
            if (leaving) return;
            attempt++;
            try {
                ViewerClient c = ViewerClient.connect(host, port, pin == null ? "" : pin, Hub.deviceName(), Proto.ANDROID);
                if (leaving) {
                    c.close();
                    return;
                }
                client = c;
                reconnecting = false;
                runOnUiThread(() -> {
                    view.setClient(c);
                    overlay.setText("Reconnected");
                    c.start(this);
                });
                return;
            } catch (ViewerClient.AuthException e) {
                reconnecting = false;
                fail("Reconnect failed: " + e.getMessage());
                return;
            } catch (IOException e) {
                // Host still down; keep trying until the window runs out.
            }
        }
        reconnecting = false;
        if (!leaving) fail("Disconnected from " + host);
    }
}
