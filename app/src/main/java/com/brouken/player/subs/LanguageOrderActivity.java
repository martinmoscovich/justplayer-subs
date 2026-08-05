package com.brouken.player.subs;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.brouken.player.R;

/**
 * Ordered language editor (priority order) for a single settings key. D-pad friendly, "grab and
 * move" like reordering apps on Android TV: OK grabs a selected language, UP/DOWN reorders it, OK
 * drops it, ◄ removes it; OK on an available language adds it at the end. Persists the ordered CSV
 * back into {@link SubtitleSettings} on exit. No engine/player dependencies.
 */
public class LanguageOrderActivity extends AppCompatActivity {

    public static final String EXTRA_KEY = "lang_pref_key";
    public static final String EXTRA_TITLE = "lang_pref_title";

    private static final int TEAL = 0xFF4DD0E1;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFF90A4AE;
    private static final int HEADER = 0xFF7A8A93;
    private static final int MOVING_BG = 0x664DD0E1;
    private static final int FOCUS_BG = 0xFFFFFFFF;

    private final Map<String, String> nameByCode = new LinkedHashMap<>();
    private final List<String> selected = new ArrayList<>();
    private final List<String> available = new ArrayList<>();

    private String prefKey;
    private LinearLayout column;
    private final List<TextView> rowViews = new ArrayList<>();
    private TextView doneButton;
    private int focusIndex;   // 0..rows-1 = language rows; rows = Done
    private boolean moving;
    private int movingIndex;  // index into `selected` being moved

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefKey = getIntent().getStringExtra(EXTRA_KEY);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (prefKey == null) { finish(); return; }

        loadCatalog();
        loadState();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        scroll.setFillViewport(true);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(20), dp(24), dp(20));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText(title != null ? title : "Languages");
        heading.setTextColor(WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        heading.setPadding(dp(8), 0, dp(8), dp(4));
        column.addView(heading);

        TextView hint = new TextView(this);
        hint.setText("OK: grab / drop · ▲▼: reorder while grabbed · ◄: remove · OK on available: add");
        hint.setTextColor(DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(dp(8), 0, dp(8), dp(10));
        column.addView(hint);

        setContentView(scroll);
        rebuild();
    }

    private void loadCatalog() {
        String[] codes = getResources().getStringArray(R.array.subtitle_language_codes);
        String[] names = getResources().getStringArray(R.array.subtitle_language_names);
        for (int i = 0; i < codes.length && i < names.length; i++) nameByCode.put(codes[i], names[i]);
    }

    private void loadState() {
        selected.clear();
        available.clear();
        for (String c : SubtitleSettings.getLanguageList(this, prefKey)) {
            if (nameByCode.containsKey(c) && !selected.contains(c)) selected.add(c);
        }
        for (String c : nameByCode.keySet()) if (!selected.contains(c)) available.add(c);
    }

    private String name(String code) {
        String n = nameByCode.get(code);
        return n != null ? n : code;
    }

    private int rowCount() {
        return selected.size() + available.size();
    }

    private void rebuild() {
        column.removeViews(indexOfList(), column.getChildCount() - indexOfList());
        rowViews.clear();

        column.addView(header("Selected — priority order"));
        if (selected.isEmpty()) column.addView(note("None yet — add from below"));
        for (int i = 0; i < selected.size(); i++) {
            addRow((i + 1) + ".  " + name(selected.get(i)));
        }

        column.addView(header("Available"));
        if (available.isEmpty()) column.addView(note("All languages selected"));
        for (String c : available) addRow("＋  " + name(c));

        doneButton = new TextView(this);
        doneButton.setText("Done");
        doneButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        doneButton.setGravity(Gravity.CENTER);
        doneButton.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(24);
        doneButton.setLayoutParams(dlp);
        column.addView(doneButton);

        if (focusIndex > rowCount()) focusIndex = rowCount();
        styleRows();
    }

    /** First column child that belongs to the dynamic region (after heading + hint). */
    private int indexOfList() {
        return 2; // heading, hint
    }

    private void addRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(2);
        tv.setLayoutParams(p);
        column.addView(tv);
        rowViews.add(tv);
    }

    private void styleRows() {
        for (int i = 0; i < rowViews.size(); i++) {
            TextView tv = rowViews.get(i);
            boolean isFocus = i == focusIndex;
            boolean isMoving = moving && i == movingIndex;
            if (isMoving) {
                tv.setTextColor(WHITE);
                tv.setBackgroundColor(MOVING_BG);
            } else if (isFocus) {
                tv.setTextColor(0xFF000000);
                tv.setBackgroundColor(FOCUS_BG);
            } else {
                boolean isSelectedRow = i < selected.size();
                tv.setTextColor(isSelectedRow ? WHITE : DIM);
                tv.setBackgroundColor(Color.TRANSPARENT);
            }
        }
        if (doneButton != null) {
            boolean isFocus = focusIndex >= rowCount();
            doneButton.setTextColor(isFocus ? 0xFF000000 : WHITE);
            doneButton.setBackgroundColor(isFocus ? FOCUS_BG : 0x33FFFFFF);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (moving) { reorder(-1); return true; }
                focusIndex = Math.max(0, focusIndex - 1);
                styleRows();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (moving) { reorder(1); return true; }
                focusIndex = Math.min(rowCount(), focusIndex + 1);
                styleRows();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (moving) { removeMoving(); return true; }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                onOk();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (moving) { moving = false; styleRows(); return true; }
                finish(); // onPause persists
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void onOk() {
        if (moving) { moving = false; styleRows(); return; }
        if (focusIndex >= rowCount()) { finish(); return; } // Done
        if (focusIndex < selected.size()) {
            moving = true;
            movingIndex = focusIndex;
            styleRows();
        } else {
            int availIdx = focusIndex - selected.size();
            if (availIdx >= 0 && availIdx < available.size()) {
                String code = available.remove(availIdx);
                selected.add(code);
                focusIndex = selected.size() - 1; // follow it into the selected list
                rebuild();
            }
        }
    }

    private void reorder(int dir) {
        int target = movingIndex + dir;
        if (target < 0 || target >= selected.size()) return;
        String c = selected.remove(movingIndex);
        selected.add(target, c);
        movingIndex = target;
        focusIndex = target;
        rebuild();
    }

    private void removeMoving() {
        if (movingIndex < 0 || movingIndex >= selected.size()) return;
        String c = selected.remove(movingIndex);
        available.add(c);
        moving = false;
        focusIndex = Math.min(movingIndex, rowCount());
        rebuild();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (prefKey != null) SubtitleSettings.setLanguageList(this, prefKey, selected);
    }

    /** Launches the editor for a given key/title from a settings preference. */
    public static void start(Context c, String key, String title) {
        Intent i = new Intent(c, LanguageOrderActivity.class);
        i.putExtra(EXTRA_KEY, key);
        i.putExtra(EXTRA_TITLE, title);
        c.startActivity(i);
    }

    private TextView header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextColor(HEADER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(8), dp(16), dp(8), dp(6));
        return tv;
    }

    private TextView note(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(DIM);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(14), dp(6), dp(14), dp(6));
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
