package com.lanlink.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** App-wide state shared by the activities and services. */
final class Hub {
    private Hub() {}

    static final Handler main = new Handler(Looper.getMainLooper());
    /** The running share service, or null. */
    static volatile HostService host;
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();

    static void listen(Runnable r) { listeners.add(r); }
    static void unlisten(Runnable r) { listeners.remove(r); }

    /** Tells visible screens that sharing or control state changed. */
    static void changed() {
        main.post(() -> {
            for (Runnable r : listeners) r.run();
        });
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lanlink", Context.MODE_PRIVATE);
    }

    static boolean syncClipboard(Context c) {
        return prefs(c).getBoolean("syncClipboard", true);
    }

    /** Whether LanLink's accessibility service is on. Works from any process, unlike ControlService.enabled(). */
    static boolean controlEnabled(Context c) {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (android.accessibilityservice.AccessibilityServiceInfo info
                : am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null && c.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) return true;
        }
        return false;
    }

    static String deviceName() {
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (!maker.isEmpty() && !model.toLowerCase().startsWith(maker.toLowerCase())) {
            model = Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
        }
        return model;
    }

    static void toast(final Context c, final String msg) {
        main.post(() -> Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    /** Last text we put on or read from the clipboard, so we never echo it back. */
    static volatile String lastClip;

    static void setClipboard(final Context c, final String text) {
        main.post(() -> {
            lastClip = text;
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("LanLink", text));
        });
    }

    /** Clipboard text, readable only while one of our screens is in the foreground. */
    static String readClipboard(Context c) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) return null;
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(c);
        return t == null ? null : t.toString();
    }

    /** Returns clipboard text if it changed since we last saw it. */
    static String newClipboardText(Context c) {
        String t = readClipboard(c);
        if (t == null || t.equals(lastClip)) return null;
        lastClip = t;
        return t;
    }

    static void background(Runnable r) {
        Thread t = new Thread(r, "lanlink-bg");
        t.setDaemon(true);
        t.start();
    }
}
