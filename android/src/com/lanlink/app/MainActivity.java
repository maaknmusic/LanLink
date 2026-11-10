package com.lanlink.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import lanlink.core.Discovery;
import lanlink.core.Proto;

/** Home screen: share this phone, or connect to a PC or another phone. */
public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1, REQ_FILE = 2;

    private TextView shareStatus, pinText, addressText, controlStatus;
    private Button shareButton, newPinButton, controlButton, sendClipButton, sendFileButton, scanButton;
    private LinearLayout devices;
    private EditText addressInput;
    private final Runnable refresher = this::refresh;

    // Last state reported by HostService, which runs in the ":share" process.
    private boolean sharing;
    private String pin;
    private List<String> viewers = new ArrayList<>();
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            sharing = i.getBooleanExtra(HostService.EXTRA_RUNNING, false);
            pin = i.getStringExtra(HostService.EXTRA_PIN);
            ArrayList<String> names = i.getStringArrayListExtra(HostService.EXTRA_VIEWERS);
            viewers = names == null ? new ArrayList<String>() : names;
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Context c = this;
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 16);
        page.setPadding(pad, pad, pad, pad);

        // Share this phone
        LinearLayout share = Ui.card(c, "Share this phone");
        shareStatus = Ui.text(c, "", 15, Ui.MUTED);
        pinText = Ui.text(c, "", 34, Ui.TEXT);
        pinText.setTypeface(Typeface.DEFAULT_BOLD);
        pinText.setLetterSpacing(0.08f);
        addressText = Ui.text(c, "", 14, Ui.MUTED);
        shareButton = Ui.primary(c, "Start sharing", v -> toggleSharing());
        newPinButton = Ui.button(c, "New PIN", v -> command(HostService.ACTION_NEW_PIN));
        share.addView(shareStatus);
        share.addView(pinText);
        share.addView(addressText);
        share.addView(Ui.row(c, shareButton, newPinButton));

        controlStatus = Ui.text(c, "", 14, Ui.MUTED);
        controlButton = Ui.button(c, "Turn on remote control", v -> openAccessibilitySettings());
        share.addView(controlStatus);
        share.addView(controlButton);
        final CheckBox allowControl = new CheckBox(c);
        allowControl.setText("Let viewers tap and type on this phone");
        allowControl.setChecked(Hub.prefs(c).getBoolean("allowControl", true));
        allowControl.setOnCheckedChangeListener((b, on) -> {
            Hub.prefs(c).edit().putBoolean("allowControl", on).apply();
            if (sharing) command(HostService.ACTION_SETTINGS);
            refresh();
        });
        share.addView(allowControl);

        sendClipButton = Ui.button(c, "Send clipboard", v -> {
            String t = Hub.readClipboard(c);
            if (!sharing || t == null) {
                Hub.toast(c, "Nothing copied");
                return;
            }
            Hub.lastClip = t;
            sendClipboard(t);
            Hub.toast(c, "Clipboard sent");
        });
        sendFileButton = Ui.button(c, "Send file", v -> pickFile());
        share.addView(Ui.row(c, sendClipButton, sendFileButton));
        page.addView(share);

        // Connect to another device
        LinearLayout connect = Ui.card(c, "Connect to a device");
        connect.addView(Ui.text(c, "PCs and phones that are sharing on this Wi-Fi:", 14, Ui.MUTED));
        devices = new LinearLayout(c);
        devices.setOrientation(LinearLayout.VERTICAL);
        connect.addView(devices);
        scanButton = Ui.button(c, "Scan again", v -> scan());
        connect.addView(scanButton);
        connect.addView(Ui.text(c, "Or type the IP address shown on the other device:", 14, Ui.MUTED));
        addressInput = new EditText(c);
        addressInput.setHint("192.168.1.20");
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setSingleLine(true);
        addressInput.setText(Hub.prefs(c).getString("lastAddress", ""));
        Button go = Ui.primary(c, "Connect", v -> {
            String a = addressInput.getText().toString().trim();
            if (!a.isEmpty()) askPin(a, Proto.TCP_PORT, a);
        });
        connect.addView(Ui.row(c, addressInput, go));
        page.addView(connect);

        // Settings
        LinearLayout settings = Ui.card(c, "Settings");
        final CheckBox syncClip = new CheckBox(c);
        syncClip.setText("Sync copied text between devices");
        syncClip.setChecked(Hub.syncClipboard(c));
        syncClip.setOnCheckedChangeListener((b, on) -> {
            Hub.prefs(c).edit().putBoolean("syncClipboard", on).apply();
            if (sharing) command(HostService.ACTION_SETTINGS);
        });
        settings.addView(syncClip);

        final CheckBox dimScreen = new CheckBox(c);
        dimScreen.setText("Dim this phone's screen while a PC is viewing (saves battery)");
        dimScreen.setChecked(Hub.prefs(c).getBoolean("dimScreen", false));
        dimScreen.setOnCheckedChangeListener((b, on) -> {
            if (on && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) {
                // Needs "Display over other apps" to lower the brightness; let the user grant it.
                b.setChecked(false);
                new AlertDialog.Builder(c)
                        .setTitle("Allow screen dimming")
                        .setMessage("To dim the screen while sharing, allow LanLink to display over other apps.")
                        .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", getPackageName(), null))))
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            Hub.prefs(c).edit().putBoolean("dimScreen", on).apply();
            if (sharing) command(HostService.ACTION_SETTINGS);
            if (on) Hub.toast(c, "The screen will dim while a PC is connected. Tap it to brighten.");
        });
        settings.addView(dimScreen);
        settings.addView(Ui.text(c, "Received files are saved in " + AndroidFiles.folderLabel() + ".\n"
                + "Phone clipboard is sent when you open LanLink or tap Send clipboard (Android blocks background clipboard access).", 13, Ui.MUTED));
        page.addView(settings);

        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(0xFFF3F4F6);
        scroll.addView(page);
        setContentView(scroll);
        scan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Hub.listen(refresher);
        IntentFilter filter = new IntentFilter(HostService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
        // Ask the share service how things stand; it answers with an ACTION_STATE broadcast.
        command(HostService.ACTION_QUERY);
        refresh();
    }

    @Override
    protected void onPause() {
        Hub.unlisten(refresher);
        unregisterReceiver(stateReceiver);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Android only lets the foreground app read the clipboard, so sync it now.
        if (hasFocus && sharing && Hub.syncClipboard(this)) {
            String t = Hub.newClipboardText(this);
            if (t != null) sendClipboard(t);
        }
    }

    /** Sends a command to the share service, with the current settings attached. */
    private Intent commandIntent(String action) {
        return new Intent(this, HostService.class).setAction(action)
                .putExtra(HostService.EXTRA_ALLOW_CONTROL, Hub.prefs(this).getBoolean("allowControl", true))
                .putExtra(HostService.EXTRA_SYNC_CLIP, Hub.syncClipboard(this))
                .putExtra(HostService.EXTRA_DIM, Hub.prefs(this).getBoolean("dimScreen", false));
    }

    private void command(String action) {
        try {
            startService(commandIntent(action));
        } catch (RuntimeException e) {
            // The app is going to the background; the next onResume asks again.
        }
    }

    private void sendClipboard(String text) {
        startService(commandIntent(HostService.ACTION_CLIP).putExtra(HostService.EXTRA_TEXT, text));
    }

    private void refresh() {
        List<String> ips = Discovery.localAddresses();
        addressText.setText("This phone: " + Hub.deviceName() + "\nIP: " + (ips.isEmpty() ? "not on Wi-Fi" : android.text.TextUtils.join(", ", ips)));
        if (!sharing || pin == null) {
            shareStatus.setText("Not sharing. Tap Start sharing to get a PIN.");
            pinText.setText("--- ---");
            shareButton.setText("Start sharing");
        } else {
            int n = viewers.size();
            StringBuilder who = new StringBuilder();
            for (String name : viewers) who.append("\n- ").append(name);
            shareStatus.setText((n == 0 ? "Sharing. Enter this PIN on the other device:" : "Viewed by " + n + " device" + (n == 1 ? "" : "s") + ":" + who));
            pinText.setText(pin.substring(0, 3) + " " + pin.substring(3));
            shareButton.setText("Stop sharing");
        }
        newPinButton.setEnabled(sharing);
        sendClipButton.setEnabled(sharing && !viewers.isEmpty());
        sendFileButton.setEnabled(sharing && !viewers.isEmpty());
        boolean control = Hub.controlEnabled(this);
        controlStatus.setText(control ? "Remote control is on." : "Remote control is off: viewers can see but not touch this phone.");
        controlStatus.setTextColor(control ? Ui.OK : Ui.WARN);
        controlButton.setText(control ? "Accessibility settings" : "Turn on remote control");
    }

    // ---- Sharing ----

    private void toggleSharing() {
        if (sharing) {
            stopService(new Intent(this, HostService.class));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_CAPTURE) {
            if (result != RESULT_OK || data == null) {
                Hub.toast(this, "Screen sharing was cancelled");
                return;
            }
            Intent i = commandIntent(HostService.ACTION_START)
                    .putExtra(HostService.EXTRA_CODE, result).putExtra(HostService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } else if (request == REQ_FILE && result == RESULT_OK && data != null && data.getData() != null) {
            startService(commandIntent(HostService.ACTION_FILE).setData(data.getData())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i, REQ_FILE);
    }

    private void openAccessibilitySettings() {
        String msg = "In the list that opens, find LanLink (it may be under \"Installed apps\" or \"Downloaded apps\"), open it and switch it on.";
        if (Build.VERSION.SDK_INT >= 33) {
            msg += "\n\nIf the switch is greyed out (\"Restricted setting\"): go to Settings > Apps > LanLink, tap the ⋮ menu at the top right, "
                    + "choose \"Allow restricted settings\", then come back and try again. Android does this for apps not installed from the Play Store.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Turn on remote control")
                .setMessage(msg)
                .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNeutralButton("App info", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Connecting ----

    private void scan() {
        scanButton.setEnabled(false);
        scanButton.setText("Scanning...");
        Hub.background(() -> {
            List<Discovery.Device> found;
            try {
                found = Discovery.scan(1500);
            } catch (Exception e) {
                found = java.util.Collections.emptyList();
            }
            final List<Discovery.Device> result = found;
            final List<String> mine = Discovery.localAddresses();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                scanButton.setEnabled(true);
                scanButton.setText("Scan again");
                devices.removeAllViews();
                for (final Discovery.Device d : result) {
                    if (mine.contains(d.address)) continue;
                    String kind = Proto.ANDROID.equals(d.platform) ? "Phone" : "PC";
                    Button b = Ui.button(this, kind + ": " + d.name + "\n" + d.address, v -> askPin(d.address, d.port, d.name));
                    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    devices.addView(b);
                }
                if (devices.getChildCount() == 0) {
                    devices.addView(Ui.text(this, "None found. Turn sharing on in the other device's LanLink app, and check both are on the same Wi-Fi.", 14, Ui.WARN));
                }
            });
        });
    }

    private void askPin(final String address, final int port, final String label) {
        final EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("6-digit PIN");
        pin.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this)
                .setTitle("Connect to " + label)
                .setMessage("Enter the PIN shown in LanLink on " + label + ".")
                .setView(pin)
                .setPositiveButton("Connect", (d, w) -> {
                    Hub.prefs(this).edit().putString("lastAddress", address).apply();
                    startActivity(new Intent(this, ViewerActivity.class)
                            .putExtra(ViewerActivity.EXTRA_HOST, address)
                            .putExtra(ViewerActivity.EXTRA_PORT, port)
                            .putExtra(ViewerActivity.EXTRA_PIN, pin.getText().toString().replace(" ", "")));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
