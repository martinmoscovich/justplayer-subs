package com.brouken.player.subs;

import android.content.Context;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import com.brouken.player.R;
import com.brouken.player.subs.ui.SubsButton;
import com.brouken.player.subs.ui.SubsIcons;
import com.brouken.player.subs.ui.SubsShapes;
import com.brouken.player.subs.ui.SubsTheme;

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

    private final Handler handler;
    private final TextView message;
    private final LinearLayout buttonRow;
    private final List<SubsButton> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private int focusIndex;
    @Nullable private Listener listener;

    private final Runnable autoHide = this::hide;

    public SubtitleNoticeView(Context context, Handler handler) {
        super(context);
        this.handler = handler;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // Nearly opaque rather than the panel's 75%: this one floats over moving video with no veil
        // behind it, and a message you have eight seconds to read cannot afford the contrast.
        setBackground(SubsShapes.rounded(context, 0xEB0E1416, SubsTheme.EDGE, SubsTheme.RADIUS_PANEL_DP));
        setPadding(dp(14), dp(10), dp(14), dp(10));
        setVisibility(GONE);

        ImageView glyph = SubsIcons.icon(context, R.drawable.subtitle_ic_subtitles, SubsTheme.PRIMARY, 20f);
        LayoutParams glyphLp = new LayoutParams(dp(20), dp(20));
        glyphLp.rightMargin = dp(9);
        addView(glyph, glyphLp);

        message = SubsTheme.bodyLg(new TextView(context));
        message.setTextColor(SubsTheme.INK);
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
     * Shows the bar with one custom action — used for "this came from the cache, here is how to
     * redo it". No separate Dismiss: this is the one thing worth doing, and closing it without doing
     * it is what the auto-hide timer (or Back) is for.
     */
    public void show(String text, String actionLabel, Runnable action) {
        prepare(text);
        addButton(actionLabel, () -> {
            hide();
            action.run();
        });
        finish();
    }

    /**
     * Shows the bar. {@code withTranslate} adds the Translate button, focused by default — it is the
     * action worth taking. Without it, there is nothing to take action on and so nothing to focus:
     * the bar is a passive message, closed only by the auto-hide timer (see {@link #handleKey}, which
     * stops claiming navigation keys the moment there is no button for them to move between — a bar
     * with no action has no business taking the D-pad away from playback).
     */
    public void show(String text, boolean withTranslate) {
        prepare(text);
        if (withTranslate) {
            addButton("Translate", () -> {
                hide();
                if (listener != null) listener.onTranslate();
            });
        }
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

    /** @return true if the bar consumed the key. Non-navigation keys always fall through, and so does
     *  everything else once there is no button on screen — a passive, no-action bar (see the 1-arg
     *  {@link #show(String, boolean)}) has nothing to move focus between and no business taking the
     *  D-pad away from playback while it counts down on its own. */
    public boolean handleKey(KeyEvent event) {
        if (!isShowing() || buttons.isEmpty()) return false;
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
        SubsButton b = new SubsButton(getContext(), text);
        b.setOnClickListener(v -> {
            restartTimer();
            action.run();
        });
        buttonRow.addView(b, b.rowParams());
        buttons.add(b);
        actions.add(action);
    }

    private void styleButtons() {
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setFocusedState(i == focusIndex);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
