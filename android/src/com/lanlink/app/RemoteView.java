package com.lanlink.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import lanlink.core.Keys;
import lanlink.core.Proto;
import lanlink.core.ViewerClient;

/**
 * Draws the remote screen and turns touches into remote input. Two modes:
 *
 * Touch: tap = click there, drag = drag, long press = right-click (PC) or long press (phone).
 * Touchpad (PC only): finger moves the pointer, tap = click, two-finger tap = right-click,
 *   long press then move = drag (select text, move windows).
 *
 * In both: two-finger drag = scroll, pinch = zoom and pan.
 */
final class RemoteView extends View {
    private static final int IDLE = 0, PENDING = 1, DRAG = 2, HOLD = 3, TWO_PENDING = 4, ZOOM = 5, SCROLL = 6, DONE = 7, MOVING = 8;
    /** A two-finger touch shorter than this, without moving, is a right-click. */
    private static final long TWO_TAP_MS = 300;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private ViewerClient client;
    private Bitmap frame;
    private float zoom = 1f, panX, panY, scale = 1f;

    private boolean touchpad;
    /** Pointer position in touchpad mode, 0..1 across the remote screen. */
    private float curX = 0.5f, curY = 0.5f;

    private int mode = IDLE;
    private float startX, startY, lastX, lastY, lastSpan, span0, focusX0, focusY0, lastFocusX, lastFocusY, scrollAcc;
    private long holdAt, twoAt;
    private final float slop, step;

    private final Runnable longPress = () -> {
        if (mode != PENDING || client == null) return;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (touchpad) {
            // Press and hold the left button; moving now drags.
            mode = DRAG;
            client.pointer(Proto.DOWN, Proto.BTN_LEFT, curX, curY);
            invalidate();
        } else if (client.hostIsAndroid()) {
            mode = HOLD;
            holdAt = System.currentTimeMillis();
            send(Proto.DOWN, Proto.BTN_LEFT, startX, startY);
        } else {
            send(Proto.DOWN, Proto.BTN_RIGHT, startX, startY);
            send(Proto.UP, Proto.BTN_RIGHT, startX, startY);
            mode = DONE;
        }
    };

    RemoteView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0xFF111318);
        slop = Ui.dp(c, 10);
        step = Ui.dp(c, 36);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2));
    }

    void setClient(ViewerClient client) {
        this.client = client;
    }

    boolean isTouchpad() {
        return touchpad;
    }

    void setTouchpad(boolean on) {
        touchpad = on;
        if (on && client != null) client.pointer(Proto.MOVE, Proto.BTN_LEFT, curX, curY);
        invalidate();
    }

    void setFrame(Bitmap next) {
        boolean resized = frame == null || frame.getWidth() != next.getWidth() || frame.getHeight() != next.getHeight();
        frame = next;
        if (resized) zoom = 1f;
        layoutMatrix();
        invalidate();
    }

    boolean hasFrame() {
        return frame != null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutMatrix();
    }

    /** Fits the frame to the view, applies zoom, and keeps the image on screen. */
    private void layoutMatrix() {
        if (frame == null || getWidth() == 0) return;
        float vw = getWidth(), vh = getHeight(), bw = frame.getWidth(), bh = frame.getHeight();
        scale = Math.min(vw / bw, vh / bh) * zoom;
        float dw = bw * scale, dh = bh * scale;
        panX = dw <= vw ? (vw - dw) / 2 : Math.max(vw - dw, Math.min(0, panX));
        panY = dh <= vh ? (vh - dh) / 2 : Math.max(vh - dh, Math.min(0, panY));
        matrix.setScale(scale, scale);
        matrix.postTranslate(panX, panY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (frame == null) return;
        canvas.drawBitmap(frame, matrix, paint);
        if (touchpad) {
            // A ring where the pointer is, drawn at once so it never lags behind the stream.
            float x = panX + curX * frame.getWidth() * scale, y = panY + curY * frame.getHeight() * scale;
            float r = Ui.dp(getContext(), 14);
            ring.setColor(mode == DRAG ? 0xFFF59E0B : 0xCC3B82F6);
            canvas.drawCircle(x, y, r, ring);
        }
    }

    private float rx(float x) {
        return frame == null ? 0 : clamp((x - panX) / (frame.getWidth() * scale));
    }

    private float ry(float y) {
        return frame == null ? 0 : clamp((y - panY) / (frame.getHeight() * scale));
    }

    private void send(int action, int button, float x, float y) {
        client.pointer(action, button, rx(x), ry(y));
    }

    private void click(int button, float x, float y) {
        client.pointer(Proto.DOWN, button, x, y);
        client.pointer(Proto.UP, button, x, y);
    }

    // ---- Touch ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (client == null || frame == null) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = PENDING;
                startX = lastX = e.getX();
                startY = lastY = e.getY();
                postDelayed(longPress, 500);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(longPress);
                if (mode == DRAG || mode == HOLD) {
                    if (touchpad) client.pointer(Proto.UP, Proto.BTN_LEFT, curX, curY);
                    else send(Proto.UP, Proto.BTN_LEFT, e.getX(0), e.getY(0));
                }
                if (e.getPointerCount() == 2) {
                    mode = TWO_PENDING;
                    twoAt = System.currentTimeMillis();
                    span0 = lastSpan = span(e);
                    focusX0 = lastFocusX = focusX(e);
                    focusY0 = lastFocusY = focusY(e);
                    scrollAcc = 0;
                } else {
                    mode = DONE;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() == 1) {
                    oneFinger(e.getX(), e.getY());
                } else if (mode == TWO_PENDING || mode == ZOOM || mode == SCROLL) {
                    twoFingers(e);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // Lifting the second finger of a quick two-finger tap is a right-click.
                if (mode == TWO_PENDING && System.currentTimeMillis() - twoAt < TWO_TAP_MS && !client.hostIsAndroid()) {
                    if (touchpad) click(Proto.BTN_RIGHT, curX, curY);
                    else click(Proto.BTN_RIGHT, rx(focusX0), ry(focusY0));
                }
                mode = DONE;
                break;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (touchpad) touchpadUp();
                else touchUp(e.getX(), e.getY());
                mode = IDLE;
                invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (mode == DRAG || mode == HOLD) {
                    if (touchpad) client.pointer(Proto.UP, Proto.BTN_LEFT, curX, curY);
                    else send(Proto.UP, Proto.BTN_LEFT, e.getX(), e.getY());
                }
                mode = IDLE;
                invalidate();
                break;
            default:
                break;
        }
        return true;
    }

    private void oneFinger(float x, float y) {
        float dx = x - lastX, dy = y - lastY;
        lastX = x;
        lastY = y;
        boolean moved = Math.hypot(x - startX, y - startY) > slop;
        if (touchpad) {
            if (mode == PENDING && moved) {
                removeCallbacks(longPress);
                mode = MOVING;
            }
            if (mode == MOVING || mode == DRAG) movePointer(dx, dy);
            return;
        }
        if (mode == PENDING && moved) {
            removeCallbacks(longPress);
            mode = DRAG;
            send(Proto.DOWN, Proto.BTN_LEFT, startX, startY);
        }
        if (mode == DRAG || mode == HOLD) send(Proto.MOVE, Proto.BTN_LEFT, x, y);
    }

    private void touchUp(float x, float y) {
        if (mode == PENDING) {
            send(Proto.DOWN, Proto.BTN_LEFT, x, y);
            send(Proto.UP, Proto.BTN_LEFT, x, y);
        } else if (mode == DRAG) {
            send(Proto.UP, Proto.BTN_LEFT, x, y);
        } else if (mode == HOLD) {
            // Keep the press down long enough for the phone to see a long press.
            final float fx = rx(x), fy = ry(y);
            long wait = Math.max(0, 650 - (System.currentTimeMillis() - holdAt));
            postDelayed(() -> client.pointer(Proto.UP, Proto.BTN_LEFT, fx, fy), wait);
        }
    }

    private void touchpadUp() {
        if (mode == PENDING) click(Proto.BTN_LEFT, curX, curY);
        else if (mode == DRAG) client.pointer(Proto.UP, Proto.BTN_LEFT, curX, curY);
    }

    /** Moves the touchpad pointer by a finger movement, faster for quick swipes, like a laptop touchpad. */
    private void movePointer(float dx, float dy) {
        float dist = (float) Math.hypot(dx, dy);
        float accel = 1.2f + Math.min(2.5f, dist / Ui.dp(getContext(), 10));
        curX = clamp(curX + dx * accel / (frame.getWidth() * scale));
        curY = clamp(curY + dy * accel / (frame.getHeight() * scale));
        client.pointer(Proto.MOVE, Proto.BTN_LEFT, curX, curY);
        followPointer();
        invalidate();
    }

    /** When zoomed in, pans so the pointer stays on screen. */
    private void followPointer() {
        if (zoom <= 1f) return;
        float x = panX + curX * frame.getWidth() * scale, y = panY + curY * frame.getHeight() * scale;
        float margin = Ui.dp(getContext(), 48);
        if (x < margin) panX += margin - x;
        else if (x > getWidth() - margin) panX -= x - (getWidth() - margin);
        if (y < margin) panY += margin - y;
        else if (y > getHeight() - margin) panY -= y - (getHeight() - margin);
        layoutMatrix();
    }

    private void twoFingers(MotionEvent e) {
        float sp = span(e), fx = focusX(e), fy = focusY(e);
        if (mode == TWO_PENDING) {
            if (Math.abs(sp - span0) > Ui.dp(getContext(), 40)) mode = ZOOM;
            else if (Math.hypot(fx - focusX0, fy - focusY0) > Ui.dp(getContext(), 24)) mode = SCROLL;
        }
        if (mode == ZOOM && lastSpan > 0) {
            float oldScale = scale;
            zoom = Math.max(1f, Math.min(6f, zoom * sp / lastSpan));
            // Keep the point under the fingers fixed, then follow the fingers.
            float ix = (lastFocusX - panX) / oldScale, iy = (lastFocusY - panY) / oldScale;
            float newScale = Math.min((float) getWidth() / frame.getWidth(), (float) getHeight() / frame.getHeight()) * zoom;
            panX = fx - ix * newScale;
            panY = fy - iy * newScale;
            layoutMatrix();
            invalidate();
        } else if (mode == SCROLL) {
            scrollAcc += fy - lastFocusY;
            float x = touchpad ? curX : rx(fx), y = touchpad ? curY : ry(fy);
            while (scrollAcc >= step) { client.scroll(x, y, -1); scrollAcc -= step; }
            while (scrollAcc <= -step) { client.scroll(x, y, 1); scrollAcc += step; }
        }
        lastSpan = sp;
        lastFocusX = fx;
        lastFocusY = fy;
    }

    private static float span(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private static float focusX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) / 2;
    }

    private static float focusY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    // ---- Soft keyboard ----

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        // No suggestions, so most keyboards commit each character as it is typed.
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            private String composing = "";

            @Override
            public boolean commitText(CharSequence text, int pos) {
                replaceComposing(text.toString());
                composing = "";
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int pos) {
                replaceComposing(text.toString());
                composing = text.toString();
                return true;
            }

            @Override
            public boolean finishComposingText() {
                composing = "";
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                if (client == null) return true;
                for (int i = 0; i < before; i++) client.tapKey(Keys.BACK_SPACE);
                for (int i = 0; i < after; i++) client.tapKey(Keys.DELETE);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                KeyMap.send(client, event);
                return true;
            }

            @Override
            public boolean performEditorAction(int action) {
                if (client != null) client.tapKey(Keys.ENTER);
                return true;
            }

            /** Sends only what changed in the word being composed. */
            private void replaceComposing(String next) {
                if (client == null) return;
                int common = 0;
                while (common < composing.length() && common < next.length() && composing.charAt(common) == next.charAt(common)) common++;
                for (int i = composing.length(); i > common; i--) client.tapKey(Keys.BACK_SPACE);
                if (next.length() > common) client.text(next.substring(common));
            }
        };
    }
}
