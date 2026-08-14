package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The transient bar shown when a subtitle is picked automatically: a message plus one or two
 * buttons, over the video and outside the panel.
 *
 * <p>It auto-hides after {@link #VISIBLE_MS}, and any key it handles restarts that countdown so the
 * bar does not vanish under the user's thumb while they are reaching for a button. Only the
 * navigation keys are consumed while it is up ({@link #handleKey} returns false for everything
 * else), so playback controls keep working.
 */
public class SubtitleNoticeView extends LinearLayout {

    public interface Listener {
        /** Translate pressed — take the user straight to the Translate screen. */
        void onTranslate();
    }

    private static final long VISIBLE_MS = 8000;

    private static final int TEAL = 0xFF4DD0E1;
    private static final int WHITE = 0xFFFFFFFF;

    private final Handler handler;
    private final TextView message;
    private final LinearLayout buttonRow;
    private final List<TextView> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private int focusIndex;
    @Nullable private Listener listener;

    private final Runnable autoHide = this::hide;

    public SubtitleNoticeView(Context context, Handler handler) {
        super(context);
        this.handler = handler;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(0xE6000000);
        setPadding(dp(16), dp(10), dp(16), dp(10));
        setVisibility(GONE);

        message = new TextView(context);
        message.setTextColor(WHITE);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        addView(message, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(HORIZONTAL);
        LayoutParams rowLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rowLp.leftMargin = dp(16);
        addView(buttonRow, rowLp);
    }

    public void setListener(@Nullable Listener l) {
        this.listener = l;
    }

    /**
     * Shows the bar with one custom action before Dismiss — used for "this came from the cache,
     * here is how to redo it".
     */
    public void show(String text, String actionLabel, Runnable action) {
        prepare(text);
        addButton(actionLabel, () -> {
            hide();
            action.run();
        });
        addButton("Dismiss", this::hide);
        finish();
    }

    /**
     * Shows the bar. {@code withTranslate} adds the Translate button before Dismiss and starts the
     * focus on it — it is the action worth taking; Dismiss is the way out.
     */
    public void show(String text, boolean withTranslate) {
        prepare(text);
        if (withTranslate) {
            addButton("Translate", () -> {
                hide();
                if (listener != null) listener.onTranslate();
            });
        }
        addButton("Dismiss", this::hide);
        finish();
    }

    private void prepare(String text) {
        message.setText(text);
        buttonRow.removeAllViews();
        buttons.clear();
        actions.clear();
    }

    private void finish() {
        focusIndex = 0;
        styleButtons();
        setVisibility(VISIBLE);
        restartTimer();
    }

    public void hide() {
        handler.removeCallbacks(autoHide);
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public void release() {
        handler.removeCallbacks(autoHide);
    }

    /** @return true if the bar consumed the key. Non-navigation keys always fall through. */
    public boolean handleKey(KeyEvent event) {
        if (!isShowing()) return false;
        int keyCode = event.getKeyCode();
        if (!isNavKey(keyCode)) return false;
        // Claim the whole up/down pair for the keys we act on, or the player would react to the
        // ACTION_UP of a press we already consumed.
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;

        restartTimer();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                focusIndex = Math.max(0, focusIndex - 1);
                styleButtons();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                focusIndex = Math.min(buttons.size() - 1, focusIndex + 1);
                styleButtons();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (focusIndex >= 0 && focusIndex < actions.size()) actions.get(focusIndex).run();
                return true;
            case KeyEvent.KEYCODE_BACK:
                hide();
                return true;
            default:
                return false;
        }
    }

    private static boolean isNavKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    private void restartTimer() {
        handler.removeCallbacks(autoHide);
        handler.postDelayed(autoHide, VISIBLE_MS);
    }

    private void addButton(String text, Runnable action) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(6), dp(14), dp(6));
        tv.setOnClickListener(v -> {
            restartTimer();
            action.run();
        });
        LayoutParams p = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(8);
        tv.setLayoutParams(p);
        buttonRow.addView(tv);
        buttons.add(tv);
        actions.add(action);
    }

    private void styleButtons() {
        for (int i = 0; i < buttons.size(); i++) {
            TextView tv = buttons.get(i);
            boolean isFocus = i == focusIndex;
            tv.setTextColor(isFocus ? Color.BLACK : TEAL);
            tv.setBackgroundColor(isFocus ? WHITE : 0x334DD0E1);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
