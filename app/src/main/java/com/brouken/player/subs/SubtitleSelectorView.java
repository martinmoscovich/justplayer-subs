package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal subtitle-source selector: a row of chips (external / embedded / provider results) plus
 * an optional "loading more…" indicator for async sources still being fetched. Pure UI — it reports
 * selection and focus-leave via {@link Listener}; the controller owns the option list and loading.
 */
public class SubtitleSelectorView extends LinearLayout {

    public interface Listener {
        void onSelect(String optionId);
        /** User pressed down: hand focus to the view below (the sync controls). */
        void onFocusLeaveDown();
    }

    private static final int TEAL = 0xFF4DD0E1;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFF90A4AE;
    private static final int ERROR = 0xFFEF9A9A;

    private Listener listener;
    private final List<SubtitleOption> options = new ArrayList<>();
    private String selectedId;
    private boolean loadingMore;
    private boolean focused;
    private int focusIndex;

    public SubtitleSelectorView(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(20), dp(12), dp(20), dp(6));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * Replaces the shown options. {@code loadingMore} appends a spinner chip meaning "more results
     * are still coming" (e.g. a provider search in progress) while keeping the current items usable.
     */
    public void setOptions(List<SubtitleOption> newOptions, String selectedId, boolean loadingMore) {
        options.clear();
        if (newOptions != null) options.addAll(newOptions);
        this.selectedId = selectedId;
        this.loadingMore = loadingMore;
        if (focusIndex >= options.size()) focusIndex = Math.max(0, options.size() - 1);
        rebuild();
    }

    public void setFocused(boolean f) {
        focused = f;
        if (f) focusIndex = indexOfSelected();
        styleChips();
    }

    public boolean isEmpty() {
        return options.isEmpty();
    }

    /** Handles a key while this zone has focus. Returns true if consumed. */
    public boolean handleKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                focusIndex = Math.max(0, focusIndex - 1);
                styleChips();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                focusIndex = Math.min(options.size() - 1, focusIndex + 1);
                styleChips();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (listener != null) listener.onFocusLeaveDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (listener != null && focusIndex >= 0 && focusIndex < options.size()) {
                    SubtitleOption o = options.get(focusIndex);
                    if (o.state != SubtitleOption.State.LOADING) listener.onSelect(o.id);
                }
                return true;
            default:
                return false;
        }
    }

    private int indexOfSelected() {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id.equals(selectedId)) return i;
        }
        return 0;
    }

    private void rebuild() {
        removeAllViews();
        addView(chip("Subtitle:", DIM, dp(4), dp(10)));
        if (options.isEmpty() && !loadingMore) {
            addView(chip("—", DIM, dp(12), dp(12)));
            return;
        }
        for (SubtitleOption o : options) addView(chip(chipText(o), WHITE, dp(12), dp(12)));
        if (loadingMore) addView(chip("⟳ loading…", DIM, dp(12), dp(12)));
        styleChips();
    }

    private void styleChips() {
        for (int i = 0; i < options.size(); i++) {
            TextView chip = (TextView) getChildAt(i + 1); // child 0 is the "Subtitle:" label
            if (chip == null) continue;
            SubtitleOption o = options.get(i);
            boolean isSel = o.id.equals(selectedId);
            boolean isFocus = focused && i == focusIndex;
            if (isFocus) {
                chip.setTextColor(0xFF000000);
                chip.setBackgroundColor(WHITE);
            } else {
                chip.setBackgroundColor(isSel ? 0x334DD0E1 : Color.TRANSPARENT);
                chip.setTextColor(o.state == SubtitleOption.State.ERROR ? ERROR : (isSel ? TEAL : WHITE));
            }
        }
    }

    private String chipText(SubtitleOption o) {
        if (o.state == SubtitleOption.State.LOADING) return o.label + " ⟳";
        if (o.state == SubtitleOption.State.ERROR) return o.label + " ⚠";
        return o.label;
    }

    private TextView chip(String text, int color, int padStart, int padEnd) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setPadding(padStart, dp(6), padEnd, dp(6));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(4);
        p.rightMargin = dp(4);
        tv.setLayoutParams(p);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
