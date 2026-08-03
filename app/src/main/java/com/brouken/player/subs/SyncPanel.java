package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.sync.SyncResolver;
import subtitleengine.sync.SyncState;
import subtitleengine.sync.SyncTransform;

/**
 * Manual-sync overlay ("lyrics" style): a scrolling list of dialogue lines over the still-playing
 * video, plus a bottom control row. Fully driven by {@link CustomSubtitleController} (it routes key
 * events here and ticks us with the current position); we never use real view focus, so there is no
 * autoscroll-vs-focus fight.
 *
 * <p>Two focus zones:
 * <ul>
 *   <li><b>CONTROLS</b> (default) — the bottom button row: {@code Sync line}, seek ±, nudge ±,
 *       {@code Done}. Left/right moves, OK activates. Reachable without scrolling the (possibly very
 *       long) cue list.</li>
 *   <li><b>LIST</b> (entered via {@code Sync line}) — pick the line being spoken now: up/down
 *       navigates, <b>OK anchors</b> the selected line to the current position and returns to
 *       CONTROLS, <b>Back</b> cancels (no change) and returns.</li>
 * </ul>
 *
 * <p>The top {@link #header} is a placeholder for the future subtitle-selection UI.
 */
public class SyncPanel extends FrameLayout {

    /** Bridge to the player/engine state, implemented by {@link CustomSubtitleController}. */
    public interface Callbacks {
        long currentPositionMs();
        SyncState state();
        void onAnchor(int cueIndex, long cueStartMs, long videoPositionMs);
        void onNudge(long deltaMs);
        void onSeek(long deltaMs);
    }

    // --- tunables ---
    /** A silence longer than this between consecutive cues starts a new visual segment. */
    public static final long SEGMENT_GAP_MS = 15_000;
    private static final long NUDGE_MS = 50;
    private static final long SEEK_SHORT_MS = 5_000;
    private static final long SEEK_LONG_MS = 10_000;

    // --- colors ---
    private static final int COLOR_DIM = 0x80FFFFFF;      // idle line
    private static final int COLOR_ACTIVE = 0xFF4DD0E1;   // line playing now (teal, not white)
    private static final int COLOR_FOCUS_TEXT = 0xFFFFFFFF;
    private static final int FOCUS_BG = 0x33FFFFFF;       // selected line background (LIST mode)
    private static final int SEGMENT_DIVIDER = 0x33FFFFFF;

    private enum Zone { CONTROLS, LIST }

    private static final class Btn {
        final String label;
        final Runnable action;
        Btn(String label, Runnable action) { this.label = label; this.action = action; }
    }

    private final TextView header;
    private final TextView readout;
    private final VerticalGridView list;
    private final LinearLayout buttonRow;
    private final CueAdapter adapter = new CueAdapter();
    private final Btn[] buttons;
    private final TextView[] buttonViews;

    private Callbacks callbacks;
    private List<SubtitleEntry> cues = Collections.emptyList();
    private boolean autoscroll = true;
    private Zone zone = Zone.CONTROLS;
    private int buttonIndex = 0;

    public SyncPanel(Context context) {
        super(context);
        setBackgroundColor(0xB3000000); // ~70% black, video still visible behind
        setVisibility(GONE);
        setClickable(true);

        header = label(16, 0xFFB0BEC5);
        header.setText("Sync");
        header.setPadding(dp(24), dp(14), dp(24), dp(2));
        addView(header, lp(Gravity.TOP | Gravity.START, 0, 0));

        readout = label(15, Color.WHITE);
        readout.setPadding(dp(24), dp(2), dp(24), dp(10));
        addView(readout, lp(Gravity.TOP | Gravity.START, dp(28), 0));

        list = new VerticalGridView(context);
        list.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_NO_EDGE);
        list.setWindowAlignmentOffsetPercent(50f);
        list.setItemAlignmentOffsetPercent(50f);
        list.setFocusable(false);
        list.setAdapter(adapter);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        llp.topMargin = dp(70);
        llp.bottomMargin = dp(64);
        addView(list, llp);

        buttons = new Btn[]{
                new Btn("Sync line", this::enterListMode),
                new Btn("-10s", () -> seek(-SEEK_LONG_MS)),
                new Btn("-5s", () -> seek(-SEEK_SHORT_MS)),
                new Btn("+5s", () -> seek(SEEK_SHORT_MS)),
                new Btn("+10s", () -> seek(SEEK_LONG_MS)),
                new Btn("-50ms", () -> nudge(-NUDGE_MS)),
                new Btn("+50ms", () -> nudge(NUDGE_MS)),
                new Btn("Done", this::close),
        };
        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(dp(12), dp(8), dp(12), dp(12));
        buttonViews = new TextView[buttons.length];
        for (int i = 0; i < buttons.length; i++) {
            TextView b = label(14, COLOR_FOCUS_TEXT);
            b.setText(buttons[i].label);
            b.setPadding(dp(16), dp(8), dp(16), dp(8));
            LinearLayout.LayoutParams blp =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(4);
            blp.rightMargin = dp(4);
            buttonRow.addView(b, blp);
            buttonViews[i] = b;
        }
        addView(buttonRow, lp(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0));
    }

    public void setCallbacks(Callbacks cb) {
        this.callbacks = cb;
    }

    public void bind(SubtitleFile file) {
        cues = (file != null && file.getEntries() != null) ? file.getEntries() : Collections.emptyList();
        adapter.notifyDataSetChanged();
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    public void open() {
        if (cues.isEmpty()) return;
        autoscroll = true;
        zone = Zone.CONTROLS;
        buttonIndex = 0;
        adapter.setSelectedIndex(-1);
        setVisibility(VISIBLE);
        updateReadout();
        updateButtons();
        int idx = scrollTargetIndex(pos());
        if (idx >= 0) list.setSelectedPosition(idx);
    }

    public void close() {
        setVisibility(GONE);
    }

    /** Called by the controller's poll loop while open, to drive autoscroll and the "now" marker. */
    public void onTick(long positionMs) {
        if (!isOpen()) return;
        adapter.setActiveIndex(strictActiveIndex(positionMs)); // teal highlight, or none in gaps
        if (autoscroll) {
            int target = scrollTargetIndex(positionMs);        // active, or nearest previous
            if (target >= 0 && target != list.getSelectedPosition()) {
                list.setSelectedPositionSmooth(target);
            }
        }
    }

    /** Handles a key while the panel is open. Returns true if consumed. */
    public boolean handleKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return isNavKey(event.getKeyCode()); // swallow the matching UP
        }
        return zone == Zone.LIST ? handleListKey(event.getKeyCode()) : handleControlsKey(event.getKeyCode());
    }

    // --- CONTROLS zone ---

    private boolean handleControlsKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                buttonIndex = Math.max(0, buttonIndex - 1);
                updateButtons();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                buttonIndex = Math.min(buttons.length - 1, buttonIndex + 1);
                updateButtons();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                buttons[buttonIndex].action.run();
                return true;
            case KeyEvent.KEYCODE_BACK:
                close();
                return true;
            default:
                return false;
        }
    }

    // --- LIST zone (choosing the line to anchor) ---

    private void enterListMode() {
        zone = Zone.LIST;
        int idx = scrollTargetIndex(pos());
        if (idx >= 0) list.setSelectedPosition(idx);
        adapter.setSelectedIndex(list.getSelectedPosition());
        updateButtons();
    }

    private boolean handleListKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                autoscroll = false;
                moveSelection(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                autoscroll = false;
                moveSelection(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                anchorSelected();
                exitListMode(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                exitListMode(false);
                return true;
            default:
                return false;
        }
    }

    private void exitListMode(boolean anchored) {
        zone = Zone.CONTROLS;
        adapter.setSelectedIndex(-1);
        if (anchored) autoscroll = true; // resume following playback after a successful anchor
        updateButtons();
        updateReadout();
    }

    private void moveSelection(int delta) {
        if (cues.isEmpty()) return;
        int p = Math.max(0, Math.min(cues.size() - 1, list.getSelectedPosition() + delta));
        list.setSelectedPositionSmooth(p);
        adapter.setSelectedIndex(p);
    }

    private void anchorSelected() {
        int p = list.getSelectedPosition();
        if (callbacks == null || p < 0 || p >= cues.size()) return;
        SubtitleEntry e = cues.get(p);
        callbacks.onAnchor(e.getIndex(), e.getStartMs(), pos());
    }

    private void seek(long deltaMs) {
        if (callbacks != null) callbacks.onSeek(deltaMs);
    }

    private void nudge(long deltaMs) {
        if (callbacks != null) {
            callbacks.onNudge(deltaMs);
            updateReadout();
        }
    }

    // --- highlight / scroll helpers ---

    /** Index of the cue whose adjusted window contains {@code pos}; -1 in a gap. */
    private int strictActiveIndex(long positionMs) {
        SyncState st = state();
        for (int i = 0; i < cues.size(); i++) {
            SubtitleEntry e = cues.get(i);
            long start = SyncResolver.adjust(st, e.getStartMs());
            if (start > positionMs) return -1;              // before this cue: in a gap
            if (positionMs <= SyncResolver.adjust(st, e.getEndMs())) return i;
        }
        return -1;
    }

    /** Where the list should center: the active cue, or the nearest previous one. */
    private int scrollTargetIndex(long positionMs) {
        SyncState st = state();
        int last = -1;
        for (int i = 0; i < cues.size(); i++) {
            if (SyncResolver.adjust(st, cues.get(i).getStartMs()) <= positionMs) last = i;
            else break;
        }
        return last >= 0 ? last : (cues.isEmpty() ? -1 : 0);
    }

    private static boolean isNavKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
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

    private void updateButtons() {
        boolean controls = zone == Zone.CONTROLS;
        for (int i = 0; i < buttonViews.length; i++) {
            boolean focused = controls && i == buttonIndex;
            TextView b = buttonViews[i];
            b.setTextColor(focused ? 0xFF000000 : (controls ? COLOR_FOCUS_TEXT : COLOR_DIM));
            b.setBackgroundColor(focused ? 0xFFFFFFFF : Color.TRANSPARENT);
        }
    }

    private void updateReadout() {
        SyncState st = state();
        SyncTransform t = SyncResolver.resolve(st.getAnchors());
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "offset %+.2fs · nudge %+dms", t.getOffsetMs() / 1000.0, st.getNudgeMs()));
        if (Math.abs(t.getScale() - 1.0) > 1e-6) {
            sb.append(String.format(Locale.ROOT, " · scale %.4f", t.getScale()));
        }
        int anchors = st.getAnchors() != null ? st.getAnchors().size() : 0;
        sb.append("   (").append(anchors).append(anchors == 1 ? " anchor)" : " anchors)");
        readout.setText(sb.toString());
    }

    private SyncState state() {
        return (callbacks != null && callbacks.state() != null) ? callbacks.state() : SyncState.empty();
    }

    private long pos() {
        return callbacks != null ? callbacks.currentPositionMs() : 0L;
    }

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

    // --- list adapter: one dialogue cue per row, with segment dividers ---

    private class CueAdapter extends RecyclerView.Adapter<CueAdapter.VH> {
        private int activeIndex = -1;   // playing now (teal)
        private int selectedIndex = -1; // D-pad cursor in LIST mode (white + bg)

        void setActiveIndex(int idx) {
            if (idx == activeIndex) return;
            int old = activeIndex;
            activeIndex = idx;
            if (old >= 0) notifyItemChanged(old);
            if (idx >= 0) notifyItemChanged(idx);
        }

        void setSelectedIndex(int idx) {
            if (idx == selectedIndex) return;
            int old = selectedIndex;
            selectedIndex = idx;
            if (old >= 0) notifyItemChanged(old);
            if (idx >= 0) notifyItemChanged(idx);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            // ViewGroup.LayoutParams (not RecyclerView's): leanback converts these to its own type;
            // a RecyclerView.LayoutParams would fail its internal cast during layout.
            root.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View divider = new View(ctx);
            divider.setBackgroundColor(SEGMENT_DIVIDER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(140), dp(1));
            dlp.gravity = Gravity.CENTER_HORIZONTAL;
            dlp.topMargin = dp(20);
            dlp.bottomMargin = dp(20);
            root.addView(divider, dlp);

            TextView tv = new TextView(ctx);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tv.setLineSpacing(0f, 1.05f);
            tv.setPadding(dp(40), dp(9), dp(40), dp(9));
            root.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            return new VH(root, tv, divider);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            SubtitleEntry e = cues.get(position);
            // Preserve separate dialogue lines (\n); long single lines wrap naturally.
            h.text.setText(String.join("\n", e.getLines()));

            boolean active = position == activeIndex;
            boolean selected = position == selectedIndex;
            h.text.setTextColor(selected ? COLOR_FOCUS_TEXT : (active ? COLOR_ACTIVE : COLOR_DIM));
            h.text.setBackgroundColor(selected ? FOCUS_BG : Color.TRANSPARENT);
            h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, active || selected ? 20 : 18);

            // Segment divider: shown when this cue follows a long silence.
            boolean segmentBreak = position > 0
                    && (e.getStartMs() - cues.get(position - 1).getEndMs()) > SEGMENT_GAP_MS;
            h.divider.setVisibility(segmentBreak ? View.VISIBLE : View.GONE);
        }

        @Override
        public int getItemCount() {
            return cues.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView text;
            final View divider;
            VH(View root, TextView text, View divider) {
                super(root);
                this.text = text;
                this.divider = divider;
            }
        }
    }
}
