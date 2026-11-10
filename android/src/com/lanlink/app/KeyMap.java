package com.lanlink.app;

import android.view.KeyEvent;
import lanlink.core.Keys;
import lanlink.core.ViewerClient;

/** Turns Android key events into LanLink TEXT and KEY messages. */
final class KeyMap {
    private KeyMap() {}

    static int vk(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return Keys.BACK_SPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return Keys.DELETE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return Keys.ENTER;
            case KeyEvent.KEYCODE_TAB: return Keys.TAB;
            case KeyEvent.KEYCODE_ESCAPE: return Keys.ESCAPE;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Keys.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Keys.RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return Keys.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Keys.DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return Keys.HOME;
            case KeyEvent.KEYCODE_MOVE_END: return Keys.END;
            case KeyEvent.KEYCODE_PAGE_UP: return Keys.PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return Keys.PAGE_DOWN;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return Keys.CONTROL;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return Keys.ALT;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return Keys.SHIFT;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT: return Keys.WINDOWS;
            default: break;
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return Keys.A + (keyCode - KeyEvent.KEYCODE_A);
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return Keys.ZERO + (keyCode - KeyEvent.KEYCODE_0);
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) return Keys.F1 + (keyCode - KeyEvent.KEYCODE_F1);
        return 0;
    }

    private static boolean special(int vk) {
        return vk == Keys.BACK_SPACE || vk == Keys.DELETE || vk == Keys.ENTER || vk == Keys.TAB || vk == Keys.ESCAPE
                || (vk >= Keys.PAGE_UP && vk <= Keys.DOWN) || vk == Keys.CONTROL || vk == Keys.ALT || vk == Keys.SHIFT
                || vk == Keys.WINDOWS || (vk >= Keys.F1 && vk < Keys.F1 + 12);
    }

    /** Returns true if the event was sent to the remote device. */
    static boolean send(ViewerClient client, KeyEvent e) {
        if (client == null) return false;
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) {
            if (e.getCharacters() != null) client.text(e.getCharacters());
            return true;
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int vk = vk(e.getKeyCode());
        boolean shortcut = e.isCtrlPressed() || e.isAltPressed();
        if (vk != 0 && (special(vk) || shortcut)) {
            client.key(vk, down);
            return true;
        }
        int ch = e.getUnicodeChar();
        if (ch != 0) {
            if (down) client.text(new String(Character.toChars(ch)));
            return true;
        }
        return false;
    }
}
