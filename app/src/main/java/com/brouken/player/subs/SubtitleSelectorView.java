package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Subtitle-source selector: a vertical, sectioned list ("Embedded" and "External / Provider", the
 * latter also holding provider results and a "loading more…" row), plus a <b>Done</b> button at the
 * bottom. Picking a subtitle moves focus to Done (a second OK closes the panel); ◄ switches to the
 * sync screen, Back opens the sidebar menu.
 */
public class SubtitleSelectorView extends ScrollView {

    public interface Listener {
        void onSelect(String optionId);
        /** ◄ / Back — move focus to the sidebar (the screen list). */
        void onOpenMenu();
        /** Done — close the panel. */
        void onRequestClose();
    }

    private static final int TEAL = 0xFF4DD0E1;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFF90A4AE;
    private static final int ERROR = 0xFFEF9A9A;
    private static final int HEADER = 0xFF7A8A93;

    private final LinearLayout column;
    private TextView doneButton;
    private Listener listener;

    private final List<SubtitleOption> ordered = new ArrayList<>();
    private final List<TextView> rowViews = new ArrayList<>();
    private String selectedId;
    private boolean loadingMore;
    private boolean focused;
    private int focusIndex; // 0..ordered.size()-1 = option rows; ordered.size() = Done

    public SubtitleSelectorView(Context c) {
        super(c);
        setFillViewport(true);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(12));
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setOptions(List<SubtitleOption> options, String selectedId, boolean loadingMore) {
        this.selectedId = selectedId;
        this.loadingMore = loadingMore;
        ordered.clear();
        if (options != null) {
            for (SubtitleOption o : options) if (o.source == SubtitleOption.Source.EMBEDDED) ordered.add(o);
            for (SubtitleOption o : options) if (o.source != SubtitleOption.Source.EMBEDDED) ordered.add(o);
        }
        if (focusIndex > ordered.size()) focusIndex = ordered.size();
        rebuild();
    }

    public void setFocused(boolean f) {
        focused = f;
        if (f) focusIndex = indexOfSelected();
        styleRows();
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    public boolean handleKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                focusIndex = Math.max(0, focusIndex - 1);
                styleRows();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focusIndex = Math.min(ordered.size(), focusIndex + 1);
                styleRows();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) listener.onOpenMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (listener == null) return true;
                if (focusIndex >= ordered.size()) {
                    listener.onRequestClose();
                } else {
                    SubtitleOption o = ordered.get(focusIndex);
                    if (o.state != SubtitleOption.State.LOADING) {
                        listener.onSelect(o.id);
                        focusIndex = ordered.size(); // jump to Done for an easy confirm
                        styleRows();
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private int indexOfSelected() {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id.equals(selectedId)) return i;
        }
        return 0;
    }

    private void rebuild() {
        column.removeAllViews();
        rowViews.clear();

        boolean anyEmbedded = false, anyExternal = false;
        for (SubtitleOption o : ordered) {
            if (o.source == SubtitleOption.Source.EMBEDDED) anyEmbedded = true;
            else anyExternal = true;
        }

        if (anyEmbedded) {
            column.addView(header("Embedded"));
            for (SubtitleOption o : ordered) {
                if (o.source == SubtitleOption.Source.EMBEDDED) addRow(o);
            }
        }
        if (anyExternal || loadingMore) {
            column.addView(header("External / Provider"));
            for (SubtitleOption o : ordered) {
                if (o.source != SubtitleOption.Source.EMBEDDED) addRow(o);
            }
            if (loadingMore) column.addView(row("⟳ loading more…", DIM));
        }
        if (ordered.isEmpty() && !loadingMore) {
            column.addView(row("No subtitles available", DIM));
        }

        doneButton = new TextView(getContext());
        doneButton.setText("Done");
        doneButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        doneButton.setGravity(Gravity.CENTER);
        doneButton.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(24);
        doneButton.setLayoutParams(dlp);
        column.addView(doneButton);

        styleRows();
    }

    private void addRow(SubtitleOption o) {
        TextView tv = row(rowText(o), WHITE);
        column.addView(tv);
        rowViews.add(tv);
    }

    private void styleRows() {
        for (int i = 0; i < rowViews.size() && i < ordered.size(); i++) {
            TextView tv = rowViews.get(i);
            SubtitleOption o = ordered.get(i);
            boolean isSel = o.id.equals(selectedId);
            boolean isFocus = focused && i == focusIndex;
            if (isFocus) {
                tv.setTextColor(0xFF000000);
                tv.setBackgroundColor(WHITE);
            } else {
                tv.setBackgroundColor(isSel ? 0x334DD0E1 : Color.TRANSPARENT);
                tv.setTextColor(o.state == SubtitleOption.State.ERROR ? ERROR : (isSel ? TEAL : WHITE));
            }
        }
        if (doneButton != null) {
            boolean isFocus = focused && focusIndex >= ordered.size();
            doneButton.setTextColor(isFocus ? 0xFF000000 : WHITE);
            doneButton.setBackgroundColor(isFocus ? WHITE : 0x33FFFFFF);
        }
    }

    private String rowText(SubtitleOption o) {
        String prefix = o.id.equals(selectedId) ? "● " : "○ ";
        if (o.state == SubtitleOption.State.LOADING) return prefix + o.label + "  ⟳";
        if (o.state == SubtitleOption.State.ERROR) return prefix + o.label + "  ⚠";
        return prefix + o.label;
    }

    private TextView header(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text.toUpperCase());
        tv.setTextColor(HEADER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(8), dp(14), dp(8), dp(6));
        return tv;
    }

    private TextView row(String text, int color) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(2);
        tv.setLayoutParams(p);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
