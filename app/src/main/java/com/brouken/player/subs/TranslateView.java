package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Translate UI: an always-visible status line over a button row that changes shape with
 * {@link ButtonState}. All translation logic lives in the engine's {@code TranslationSession}
 * (via {@link TranslationController}); this view only renders state and turns key presses into
 * {@link Listener} calls. Mirrors {@link SyncView}'s contract (setListener / setFocused /
 * handleKey / reset).
 */
public class TranslateView extends FrameLayout {

    public interface Listener {
        void onStartTranslate();
        void onCancelTranslate();
        void onRestoreOriginal();
        /** Finished row: drop the cached translation and pay for a fresh one. */
        void onTranslateAgain();
        /** ◄ past the leftmost button, or Back: move focus to the sidebar. */
        void onOpenMenu();
        /** Done button: close the whole panel. */
        void onRequestClose();
    }

    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_WARNING = 0xFFFFB74D;
    private static final int COLOR_ERROR = 0xFFEF9A9A;
    private static final int COLOR_DIM = 0x80FFFFFF;
    private static final int COLOR_REASON = 0xFFB0BEC5;
    private static final int COLOR_FOCUS_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DISABLED = 0x40FFFFFF;

    private static final class Btn {
        final String label;
        final Runnable action;
        Btn(String label, Runnable action) { this.label = label; this.action = action; }
    }

    private final TextView statusView;
    private final TextView reasonView;
    private final LinearLayout buttonRow;

    private Listener listener;
    private final List<Btn> buttons = new ArrayList<>();
    private int buttonIndex = 0;
    private boolean hasFocus = true;
    private ButtonState buttonState = ButtonState.IDLE;
    /** What this screen would say on its own, and what a blocking step says over it (see setBusyStatus). */
    private String ownStatus = "";
    private ButtonState ownStatusState = ButtonState.IDLE;
    @Nullable private String busyStatus;

    public TranslateView(Context context) {
        super(context);

        statusView = label(16, COLOR_NORMAL);
        statusView.setPadding(dp(24), dp(24), dp(24), dp(8));
        addView(statusView, lp(Gravity.TOP | Gravity.START, 0, 0));

        reasonView = label(15, COLOR_REASON);
        reasonView.setGravity(Gravity.CENTER);
        reasonView.setPadding(dp(40), 0, dp(40), 0);
        reasonView.setVisibility(GONE);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.gravity = Gravity.CENTER;
        addView(reasonView, rp);

        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(dp(12), dp(8), dp(12), dp(12));
        addView(buttonRow, lp(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0));

        rebuildButtons(ButtonState.IDLE);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * A blocking step this screen is waiting on before it can do its own work — today, reading an
     * embedded track's cues out of the container. It takes over the status line because until it
     * finishes there is no translation state worth reporting; {@code null} gives the line back.
     */
    public void setBusyStatus(@Nullable String busy) {
        this.busyStatus = busy;
        renderStatus();
        // No ButtonState of its own drives the row while a blocking step (embedded extraction)
        // runs underneath this screen — without this, "Translate" stays showing (and re-presses as
        // "start a new run") with no way to cancel what is already in flight. Falls back to
        // ownStatusState when the blocking step ends — same pattern as setState() below.
        ButtonState effective = busy != null ? ButtonState.RUNNING : ownStatusState;
        if (rowKind(effective) != rowKind(buttonState)) {
            buttonState = effective;
            rebuildButtons(effective);
        } else {
            buttonState = effective;
        }
    }

    /** Pushes the current translation state. Buttons are only rebuilt (and focus reset) on a row change. */
    public void setState(boolean available, @Nullable String reason, String status, ButtonState newState) {
        this.ownStatus = status;
        this.ownStatusState = newState;
        renderStatus();

        boolean showReason = newState == ButtonState.UNAVAILABLE;
        reasonView.setVisibility(showReason ? VISIBLE : GONE);
        reasonView.setText(reason != null ? reason : "");

        if (rowKind(newState) != rowKind(buttonState)) {
            buttonState = newState;
            rebuildButtons(newState);
        } else {
            buttonState = newState;
        }
    }

    private void renderStatus() {
        if (busyStatus != null) {
            statusView.setText(busyStatus);
            statusView.setTextColor(COLOR_NORMAL);
            return;
        }
        statusView.setText(ownStatus);
        statusView.setTextColor(statusColor(ownStatusState));
    }

    public void setFocused(boolean f) {
        hasFocus = f;
        updateButtons();
    }

    public void reset() {
        buttonIndex = 0;
        updateButtons();
    }

    public boolean handleKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (buttonIndex == 0) {
                    if (listener != null) listener.onOpenMenu();
                } else {
                    buttonIndex--;
                    updateButtons();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (buttonIndex < buttons.size() - 1) {
                    buttonIndex++;
                    updateButtons();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (buttonIndex >= 0 && buttonIndex < buttons.size()) {
                    buttons.get(buttonIndex).action.run();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) listener.onOpenMenu();
                return true;
            default:
                return false;
        }
    }

    // --- button row construction ---

    /** Coarse row shape: all FINISHED_* variants share one row, so they don't reset focus on switch. */
    private static Object rowKind(ButtonState s) {
        switch (s) {
            case FINISHED_OK:
            case FINISHED_WARNING:
            case FINISHED_ERROR:
            case FINISHED_CANCELLED:
                return "FINISHED";
            default:
                return s;
        }
    }

    private void rebuildButtons(ButtonState state) {
        buttons.clear();
        switch (state) {
            case IDLE:
                buttons.add(new Btn("Translate", this::startTranslate));
                buttons.add(new Btn("Done", this::requestClose));
                break;
            case RUNNING:
                buttons.add(new Btn("Cancel", this::cancelTranslate));
                buttons.add(new Btn("Done", this::requestClose));
                break;
            case UNAVAILABLE:
                buttons.add(new Btn("Done", this::requestClose));
                break;
            case FINISHED_OK:
            case FINISHED_WARNING:
            case FINISHED_ERROR:
            case FINISHED_CANCELLED:
            default:
                // "Restore original" is secondary and must never hold default focus — it throws
                // away a translation the user paid for. Done comes first.
                buttons.add(new Btn("Done", this::requestClose));
                buttons.add(new Btn("Translate again", this::translateAgain));
                buttons.add(new Btn("Restore original", this::restoreOriginal));
                break;
        }
        buttonIndex = 0;

        buttonRow.removeAllViews();
        for (Btn b : buttons) {
            TextView tv = label(14, COLOR_FOCUS_TEXT);
            tv.setText(b.label);
            tv.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(4);
            blp.rightMargin = dp(4);
            buttonRow.addView(tv, blp);
        }
        updateButtons();
    }

    private void updateButtons() {
        for (int i = 0; i < buttonRow.getChildCount(); i++) {
            TextView b = (TextView) buttonRow.getChildAt(i);
            boolean focused = hasFocus && i == buttonIndex;
            if (focused) {
                b.setTextColor(0xFF000000);
                b.setBackgroundColor(0xFFFFFFFF);
            } else {
                b.setTextColor(COLOR_FOCUS_TEXT);
                b.setBackgroundColor(Color.TRANSPARENT);
            }
        }
    }

    private static int statusColor(ButtonState state) {
        switch (state) {
            case FINISHED_WARNING: return COLOR_WARNING;
            case FINISHED_ERROR: return COLOR_ERROR;
            case FINISHED_CANCELLED: return COLOR_DIM;
            default: return COLOR_NORMAL;
        }
    }

    // --- actions ---

    private void startTranslate() {
        if (listener != null) listener.onStartTranslate();
    }

    private void cancelTranslate() {
        if (listener != null) listener.onCancelTranslate();
    }

    private void translateAgain() {
        if (listener != null) listener.onTranslateAgain();
    }

    private void restoreOriginal() {
        if (listener != null) listener.onRestoreOriginal();
    }

    private void requestClose() {
        if (listener != null) listener.onRequestClose();
    }

    // --- helpers ---

    private TextView label(int sp, int color) {
        TextView tv = new TextView(getContext());
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return tv;
    }

    private FrameLayout.LayoutParams lp(int gravity, int topMargin, int bottomMargin) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.gravity = gravity;
        p.topMargin = topMargin;
        p.bottomMargin = bottomMargin;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
