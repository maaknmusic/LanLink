package com.lanlink.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import lanlink.core.Keys;
import lanlink.core.Proto;

/**
 * Replays a viewer's input on this phone. Android lets apps inject touches only
 * through an accessibility service, which the user turns on in Settings.
 *
 * Keyboard shortcuts from a PC viewer:
 *   Esc, Alt+Left = Back    F1, Win = Home    F2 = Recent apps    F3 = Notifications
 *   F4 = Quick settings     PrtSc = Screenshot
 *   In a text box: Ctrl+A/C/X/V, Shift+arrows/Home/End select, Ctrl+Backspace deletes a word,
 *   Tab / Shift+Tab move between fields, Enter submits.
 *   Elsewhere: Tab / Shift+Tab = Recent apps, Left/Right = previous/next card, Enter = open it,
 *   Up/Down scroll a little, Page Up/Page Down scroll a page.
 */
public final class ControlService extends AccessibilityService {
    static volatile ControlService instance;

    private final Object lock = new Object();
    private final List<float[]> stroke = new ArrayList<>();
    private boolean down;
    private long downAt;
    private volatile boolean ctrl, shift, alt;

    static boolean enabled() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Hub.changed();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        Hub.changed();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        Hub.changed();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    private int[] screen() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[] {m.widthPixels, m.heightPixels};
    }

    // ---- Touch ----

    /** Collects a press-move-release and plays it back as one gesture when released. */
    void pointer(int action, int button, float x, float y) {
        if (button == Proto.BTN_RIGHT) {
            if (action == Proto.UP) global(GLOBAL_ACTION_BACK);
            return;
        }
        if (button == Proto.BTN_MIDDLE) {
            if (action == Proto.UP) global(GLOBAL_ACTION_HOME);
            return;
        }
        int[] s = screen();
        float px = clamp(x) * (s[0] - 1), py = clamp(y) * (s[1] - 1);
        synchronized (lock) {
            if (action == Proto.DOWN) {
                stroke.clear();
                stroke.add(new float[] {px, py});
                downAt = SystemClock.uptimeMillis();
                down = true;
            } else if (action == Proto.MOVE) {
                if (down && stroke.size() < 4000) stroke.add(new float[] {px, py});
            } else if (action == Proto.UP && down) {
                down = false;
                stroke.add(new float[] {px, py});
                play(new ArrayList<>(stroke), SystemClock.uptimeMillis() - downAt);
            }
        }
    }

    /** Forgets half-finished input, e.g. when the viewer disconnects. */
    void reset() {
        synchronized (lock) {
            down = false;
            stroke.clear();
        }
        ctrl = shift = alt = false;
    }

    private void play(List<float[]> pts, long held) {
        float[] start = pts.get(0);
        Path path = new Path();
        path.moveTo(start[0], start[1]);
        float far = 0;
        for (float[] p : pts) {
            path.lineTo(p[0], p[1]);
            far = Math.max(far, (float) Math.hypot(p[0] - start[0], p[1] - start[1]));
        }
        long duration;
        if (far < 12) {
            // A tap, or a long press if it was held.
            path = new Path();
            path.moveTo(start[0], start[1]);
            duration = held >= 450 ? Math.min(held, 3000) : 50;
        } else {
            duration = Math.max(80, Math.min(held, 3000));
        }
        gesture(path, duration);
    }

    void scroll(float x, float y, int notches) {
        int[] s = screen();
        float px = clamp(x) * (s[0] - 1), py = clamp(y) * (s[1] - 1);
        float dist = notches * s[1] * 0.12f;
        // Wheel down means content moves up, so the finger swipes upward.
        float endY = Math.max(1, Math.min(s[1] - 2, py - dist));
        float startY = Math.max(1, Math.min(s[1] - 2, py));
        if (Math.abs(endY - startY) < 10) return;
        Path path = new Path();
        path.moveTo(px, startY);
        path.lineTo(px, endY);
        gesture(path, 250);
    }

    /** Taps the middle of the screen: opens the centred card in Recent apps. */
    private void tapCenter() {
        int[] s = screen();
        Path path = new Path();
        path.moveTo(s[0] / 2f, s[1] / 2f);
        gesture(path, 50);
    }

    /**
     * Swipes across the middle of the screen to move one card in a sideways list such as Recent apps.
     * {@code previous} brings the card on the left to the centre (the finger moves right).
     */
    private void swipeHorizontal(boolean previous) {
        int[] s = screen();
        float y = s[1] / 2f, from = s[0] * (previous ? 0.3f : 0.7f), to = s[0] * (previous ? 0.7f : 0.3f);
        Path path = new Path();
        path.moveTo(from, y);
        path.lineTo(to, y);
        gesture(path, 200);
    }

    private void gesture(final Path path, final long duration) {
        Hub.main.post(() -> {
            try {
                GestureDescription g = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
                dispatchGesture(g, null, null);
            } catch (RuntimeException ignored) {
                // Off-screen or invalid stroke; drop it.
            }
        });
    }

    void global(final int action) {
        Hub.main.post(() -> performGlobalAction(action));
    }

    void action(int a) {
        switch (a) {
            case Proto.ACT_BACK: global(GLOBAL_ACTION_BACK); break;
            case Proto.ACT_HOME: global(GLOBAL_ACTION_HOME); break;
            case Proto.ACT_RECENTS: global(GLOBAL_ACTION_RECENTS); break;
            case Proto.ACT_NOTIFICATIONS: global(GLOBAL_ACTION_NOTIFICATIONS); break;
            default: break;
        }
    }

    // ---- Keyboard ----

    void key(final int vk, boolean isDown) {
        switch (vk) {
            case Keys.CONTROL: ctrl = isDown; return;
            case Keys.SHIFT: shift = isDown; return;
            case Keys.ALT: alt = isDown; return;
            default: break;
        }
        if (!isDown) return;
        final boolean c = ctrl, s = shift, a = alt;
        Hub.main.post(() -> handleKey(vk, c, s, a));
    }

    void text(final String t) {
        Hub.main.post(() -> {
            Field f = Field.focused(this);
            if (f != null) f.replace(t);
        });
    }

    /** Runs on the main thread. */
    private void handleKey(int vk, boolean c, boolean s, boolean a) {
        // System navigation works anywhere.
        if (vk == Keys.ESCAPE || (a && vk == Keys.LEFT)) { performGlobalAction(GLOBAL_ACTION_BACK); return; }
        if (vk == Keys.F1 || vk == Keys.WINDOWS) { performGlobalAction(GLOBAL_ACTION_HOME); return; }
        if (vk == Keys.F1 + 1) { performGlobalAction(GLOBAL_ACTION_RECENTS); return; }
        if (vk == Keys.F1 + 2) { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); return; }
        if (vk == Keys.F1 + 3) { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); return; }
        if (vk == Keys.PRINT_SCREEN && Build.VERSION.SDK_INT >= 28) { performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT); return; }

        Field f = Field.focused(this);
        if (f == null) {
            // No text box focused, so the keys drive the phone itself, e.g. the Recent apps switcher:
            // Tab / Shift+Tab open it, Left/Right move between app cards, Enter opens the centred one.
            switch (vk) {
                case Keys.TAB: performGlobalAction(GLOBAL_ACTION_RECENTS); break;
                case Keys.ENTER: tapCenter(); break;
                case Keys.LEFT: swipeHorizontal(true); break;
                case Keys.RIGHT: swipeHorizontal(false); break;
                case Keys.UP: scroll(0.5f, 0.5f, -1); break;
                case Keys.DOWN: scroll(0.5f, 0.5f, 1); break;
                case Keys.PAGE_UP: scroll(0.5f, 0.5f, -5); break;
                case Keys.PAGE_DOWN: scroll(0.5f, 0.5f, 5); break;
                default: break;
            }
            return;
        }
        if (c) {
            switch (vk) {
                case Keys.A + ('A' - 'A'): f.select(0, f.text.length()); break;
                case Keys.A + ('C' - 'A'): copy(f, false); break;
                case Keys.A + ('X' - 'A'): copy(f, true); break;
                case Keys.A + ('V' - 'A'): {
                    // The PC viewer sends its clipboard just before Ctrl+V, so this is the PC's text.
                    String clip = Hub.lastClip;
                    if (clip != null) f.replace(clip); else f.node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    break;
                }
                case Keys.BACK_SPACE: f.deleteWordBack(); break;
                case Keys.LEFT: f.moveCaret(f.wordStart(f.a), s); break;
                case Keys.RIGHT: f.moveCaret(f.wordEnd(f.b), s); break;
                case Keys.HOME: f.moveCaret(0, s); break;
                case Keys.END: f.moveCaret(f.text.length(), s); break;
                default: break;
            }
            return;
        }
        switch (vk) {
            case Keys.BACK_SPACE: f.deleteBack(); break;
            case Keys.DELETE: f.deleteForward(); break;
            case Keys.ENTER:
                if (Build.VERSION.SDK_INT >= 30 && !s) {
                    f.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                } else {
                    f.replace("\n");
                }
                break;
            case Keys.TAB: {
                AccessibilityNodeInfo next = f.node.focusSearch(s ? View.FOCUS_BACKWARD : View.FOCUS_FORWARD);
                if (next != null) next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                break;
            }
            case Keys.LEFT: f.moveCaret(s ? f.a - 1 : (f.a == f.b ? f.a - 1 : f.a), s); break;
            case Keys.RIGHT: f.moveCaret(s ? f.b + 1 : (f.a == f.b ? f.b + 1 : f.b), s); break;
            case Keys.HOME: f.moveCaret(0, s); break;
            case Keys.END: f.moveCaret(f.text.length(), s); break;
            default: break;
        }
    }

    /** Copies the selection and sends it straight to the viewers (Android hides the clipboard from background apps). */
    private void copy(Field f, boolean cut) {
        if (f.a == f.b || f.node.isPassword()) return;
        String sel = f.text.substring(f.a, f.b);
        f.node.performAction(cut ? AccessibilityNodeInfo.ACTION_CUT : AccessibilityNodeInfo.ACTION_COPY);
        Hub.lastClip = sel;
        HostService h = Hub.host;
        if (h != null) h.sendClipboard(sel);
    }

    /** The focused editable text box, with its text and selection. */
    private static final class Field {
        final AccessibilityNodeInfo node;
        final String text;
        /** Selection, a <= b. */
        final int a, b;

        private Field(AccessibilityNodeInfo node, String text, int a, int b) {
            this.node = node;
            this.text = text;
            this.a = a;
            this.b = b;
        }

        static Field focused(AccessibilityService s) {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return null;
            AccessibilityNodeInfo n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (n == null || !n.isEditable()) return null;
            CharSequence cur = n.getText();
            String t = cur == null ? "" : cur.toString();
            if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) t = "";
            int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
            if (a < 0 || b < 0 || a > t.length() || b > t.length()) a = b = t.length();
            return new Field(n, t, Math.min(a, b), Math.max(a, b));
        }

        void replace(String add) {
            set(text.substring(0, a) + add + text.substring(b), a + add.length());
        }

        void deleteBack() {
            int from = a == b && a > 0 ? text.offsetByCodePoints(a, -1) : a;
            if (from != b) set(text.substring(0, from) + text.substring(b), from);
        }

        void deleteForward() {
            int to = a == b && b < text.length() ? text.offsetByCodePoints(b, 1) : b;
            if (a != to) set(text.substring(0, a) + text.substring(to), a);
        }

        void deleteWordBack() {
            int from = a == b ? wordStart(a) : a;
            if (from != b) set(text.substring(0, from) + text.substring(b), from);
        }

        int wordStart(int i) {
            while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
            while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
            return i;
        }

        int wordEnd(int i) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            return i;
        }

        /** Moves the caret to {@code to}; with {@code extend}, grows the selection towards it instead. */
        void moveCaret(int to, boolean extend) {
            to = Math.max(0, Math.min(text.length(), to));
            if (!extend) select(to, to);
            else if (to < a) select(to, b);
            else select(a, to);
        }

        private void set(String next, int caret) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            select(caret, caret);
        }

        void select(int from, int to) {
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, from);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, to);
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        }
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
