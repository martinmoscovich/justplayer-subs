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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.sync.SyncResolver;
import subtitleengine.sync.SyncState;
import subtitleengine.sync.SyncTransform;

/**
 * The manual-sync UI: a "lyrics" list of dialogue lines with a transport/control row. Pure sync
 * concern — subtitle selection lives in {@link SubtitleSelectorView}; the two are composed by
 * {@link SubtitlePanel}. Fully driven (no real view focus): the container routes keys here and ticks
 * it with the current position.
 *
 * <p>Zones: <b>CONTROLS</b> (transport row; up hands focus back to the selector via the listener)
 * and <b>LIST</b> (choose the spoken line; OK anchors, left/right nudge, Back cancels).
 */
public class SyncView extends FrameLayout {

    public interface Listener {
        long currentPositionMs();
        boolean isPlaying();
        SyncState state();
        void onAnchor(int cueIndex, long cueStartMs, long videoPositionMs);
        void onNudge(long deltaMs);
        void onSeek(long deltaMs);
        void onSeekTo(long positionMs);
        void onTogglePlay();
        /** ◄ past the leftmost control: switch to the other screen (selection). */
        void onToggleScreen();
        /** Back: open the sidebar menu. */
        void onOpenMenu();
        /** Done button: close the whole panel. */
        void onRequestClose();
    }

    public static final long SEGMENT_GAP_MS = 15_000;
    public static final long SEGMENT_RESTART_MS = 3_000;
    public static final long REACTION_TIME_MS = 200;
    private static final long NUDGE_MS = 50;
    private static final long SEEK_MS = 5_000;
    private static final long SEGMENT_LEAD_MS = 800;
    private static final long NO_TARGET = Long.MIN_VALUE;

    private static final int COLOR_DIM = 0x80FFFFFF;
    private static final int COLOR_ACTIVE = 0xFF4DD0E1;
    private static final int COLOR_FOCUS_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DISABLED = 0x40FFFFFF;
    private static final int FOCUS_BG = 0x33FFFFFF;
    private static final int SEGMENT_LABEL = 0xB0B0BEC5;

    private static final int PREV_SEG_INDEX = 1;
    private static final int PLAY_PAUSE_INDEX = 3;
    private static final int NEXT_SEG_INDEX = 5;

    private enum Zone { CONTROLS, LIST }

    private static final class Btn {
        final String label;
        final Runnable action;
        Btn(String label, Runnable action) { this.label = label; this.action = action; }
    }

    private final TextView readout;
    private final TextView hint;
    private final TextView emptyMessage;
    private final VerticalGridView list;
    private final LinearLayout buttonRow;
    private final CueAdapter adapter = new CueAdapter();
    private final Btn[] buttons;
    private final TextView[] buttonViews;
    private final boolean[] enabled;

    private Listener listener;
    private List<SubtitleEntry> cues = Collections.emptyList();
    private boolean autoscroll = true;
    private Zone zone = Zone.CONTROLS;
    private int buttonIndex = 0;
    private boolean lastPlaying = true;
    private boolean hasFocus = true; // false while the selector row above holds focus

    /** Whether this view (its control/list zones) currently holds focus, for highlight only. */
    public void setFocused(boolean f) {
        hasFocus = f;
        updateButtons();
    }

    public SyncView(Context context) {
        super(context);

        readout = label(15, Color.WHITE);
        readout.setPadding(dp(24), dp(2), dp(24), dp(8));
        addView(readout, lp(Gravity.TOP | Gravity.START, 0, 0));

        list = new VerticalGridView(context);
        list.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_NO_EDGE);
        list.setWindowAlignmentOffsetPercent(50f);
        list.setItemAlignmentOffsetPercent(50f);
        list.setFocusable(false);
        list.setAdapter(adapter);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        llp.topMargin = dp(28);
        llp.bottomMargin = dp(104);
        addView(list, llp);

        emptyMessage = label(17, 0xFFB0BEC5);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setPadding(dp(40), 0, dp(40), 0);
        emptyMessage.setText("This subtitle can't be synced\n(embedded — assumed in sync)");
        emptyMessage.setVisibility(GONE);
        FrameLayout.LayoutParams emp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emp.gravity = Gravity.CENTER;
        addView(emptyMessage, emp);

        hint = label(13, 0xFF90A4AE);
        hint.setPadding(dp(24), dp(6), dp(24), dp(4));
        addView(hint, lp(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(56)));

        buttons = new Btn[]{
                new Btn("Sync line", this::enterListMode),
                new Btn("|« Prev seg", this::seekPrevSegment),
                new Btn("« 5s", () -> seek(-SEEK_MS)),
                new Btn("⏸", this::togglePlay),
                new Btn("5s »", () -> seek(SEEK_MS)),
                new Btn("Next seg »|", this::seekNextSegment),
                new Btn("Done", this::requestClose),
        };
        enabled = new boolean[buttons.length];
        for (int i = 0; i < enabled.length; i++) enabled[i] = true;

        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(dp(12), dp(8), dp(12), dp(12));
        buttonViews = new TextView[buttons.length];
        for (int i = 0; i < buttons.length; i++) {
            TextView b = label(14, COLOR_FOCUS_TEXT);
            b.setText(buttons[i].label);
            b.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(4);
            blp.rightMargin = dp(4);
            buttonRow.addView(b, blp);
            buttonViews[i] = b;
        }
        addView(buttonRow, lp(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void bind(SubtitleFile file) {
        cues = (file != null && file.getEntries() != null) ? file.getEntries() : Collections.emptyList();
        adapter.notifyDataSetChanged();
        boolean empty = cues.isEmpty();
        emptyMessage.setVisibility(empty ? VISIBLE : GONE);
        list.setVisibility(empty ? GONE : VISIBLE);
        updateButtons();
    }

    /** Reset to the default zone; called when the panel opens. */
    public void reset() {
        autoscroll = true;
        zone = Zone.CONTROLS;
        buttonIndex = 0;
        adapter.setSelectedIndex(-1);
        updateReadout();
        updateButtons();
        updateHint();
        int idx = scrollTargetIndex(pos());
        if (idx >= 0 && idx < cues.size()) list.setSelectedPosition(idx);
    }

    public void onTick(long positionMs) {
        adapter.setActiveIndex(strictActiveIndex(positionMs));
        if (autoscroll) {
            int target = scrollTargetIndex(positionMs);
            if (target >= 0 && target != list.getSelectedPosition()) {
                list.setSelectedPositionSmooth(target);
            }
        }
        boolean playing = listener != null && listener.isPlaying();
        if (playing != lastPlaying) {
            lastPlaying = playing;
            buttonViews[PLAY_PAUSE_INDEX].setText(playing ? "⏸" : "▶");
        }
        updateButtons();
    }

    public boolean handleKey(int keyCode) {
        return zone == Zone.LIST ? handleListKey(keyCode) : handleControlsKey(keyCode);
    }

    // --- CONTROLS zone ---

    private boolean handleControlsKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                int left = nextEnabled(buttonIndex, -1);
                if (left == buttonIndex) {
                    if (listener != null) listener.onToggleScreen(); // already leftmost → other screen
                } else {
                    buttonIndex = left;
                    updateButtons();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                buttonIndex = nextEnabled(buttonIndex, 1);
                updateButtons();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true; // swallow
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (enabled[buttonIndex]) buttons[buttonIndex].action.run();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) listener.onOpenMenu();
                return true;
            default:
                return false;
        }
    }

    private int nextEnabled(int from, int dir) {
        int i = from;
        while (true) {
            int n = i + dir;
            if (n < 0 || n >= buttons.length) return from;
            i = n;
            if (enabled[i]) return i;
        }
    }

    // --- LIST zone ---

    private void enterListMode() {
        if (cues.isEmpty()) return;
        zone = Zone.LIST;
        int idx = scrollTargetIndex(pos());
        if (idx >= 0) list.setSelectedPosition(idx);
        adapter.setSelectedIndex(list.getSelectedPosition());
        updateButtons();
        updateHint();
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
            case KeyEvent.KEYCODE_DPAD_LEFT:
                nudge(-NUDGE_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                nudge(NUDGE_MS);
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
        if (anchored) autoscroll = true;
        updateButtons();
        updateHint();
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
        if (listener == null || p < 0 || p >= cues.size()) return;
        SubtitleEntry e = cues.get(p);
        long videoPos = Math.max(0, pos() - REACTION_TIME_MS);
        listener.onAnchor(e.getIndex(), e.getStartMs(), videoPos);
    }

    private void seek(long deltaMs) {
        if (listener != null) listener.onSeek(deltaMs);
    }

    private void togglePlay() {
        if (listener != null) listener.onTogglePlay();
    }

    private void requestClose() {
        if (listener != null) listener.onRequestClose();
    }

    private void seekNextSegment() {
        long t = nextSegmentTarget(pos());
        if (t != NO_TARGET && listener != null) listener.onSeekTo(Math.max(0, t));
    }

    private void seekPrevSegment() {
        long t = prevSegmentTarget(pos());
        if (t != NO_TARGET && listener != null) listener.onSeekTo(Math.max(0, t));
    }

    private void nudge(long deltaMs) {
        if (listener != null) {
            listener.onNudge(deltaMs);
            updateReadout();
        }
    }

    // --- segments ---

    private List<Integer> segmentStarts() {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < cues.size(); i++) {
            if (i == 0 || cues.get(i).getStartMs() - cues.get(i - 1).getEndMs() > SEGMENT_GAP_MS) {
                starts.add(i);
            }
        }
        return starts;
    }

    private long nextSegmentTarget(long positionMs) {
        SyncState st = state();
        for (Integer cueIdx : segmentStarts()) {
            long start = SyncResolver.adjust(st, cues.get(cueIdx).getStartMs());
            if (start > positionMs + 1_000) return start - SEGMENT_LEAD_MS;
        }
        return NO_TARGET;
    }

    private long prevSegmentTarget(long positionMs) {
        SyncState st = state();
        List<Integer> starts = segmentStarts();
        int cur = -1;
        for (int k = 0; k < starts.size(); k++) {
            if (SyncResolver.adjust(st, cues.get(starts.get(k)).getStartMs()) <= positionMs) cur = k;
            else break;
        }
        if (cur <= 0) return NO_TARGET;
        long curStart = SyncResolver.adjust(st, cues.get(starts.get(cur)).getStartMs());
        long target = (positionMs - curStart >= SEGMENT_RESTART_MS)
                ? curStart
                : SyncResolver.adjust(st, cues.get(starts.get(cur - 1)).getStartMs());
        return target - SEGMENT_LEAD_MS;
    }

    // --- highlight / scroll ---

    private int strictActiveIndex(long positionMs) {
        SyncState st = state();
        for (int i = 0; i < cues.size(); i++) {
            SubtitleEntry e = cues.get(i);
            long start = SyncResolver.adjust(st, e.getStartMs());
            if (start > positionMs) return -1;
            if (positionMs <= SyncResolver.adjust(st, e.getEndMs())) return i;
        }
        return -1;
    }

    private int scrollTargetIndex(long positionMs) {
        SyncState st = state();
        int last = -1;
        for (int i = 0; i < cues.size(); i++) {
            if (SyncResolver.adjust(st, cues.get(i).getStartMs()) <= positionMs) last = i;
            else break;
        }
        return last >= 0 ? last : (cues.isEmpty() ? -1 : 0);
    }

    private void updateButtons() {
        long p = pos();
        enabled[0] = !cues.isEmpty();
        enabled[PREV_SEG_INDEX] = prevSegmentTarget(p) != NO_TARGET;
        enabled[NEXT_SEG_INDEX] = nextSegmentTarget(p) != NO_TARGET;

        boolean controls = zone == Zone.CONTROLS;
        if (controls && !enabled[buttonIndex]) {
            int e = nextEnabled(buttonIndex, 1);
            if (e == buttonIndex) e = nextEnabled(buttonIndex, -1);
            buttonIndex = e;
        }
        for (int i = 0; i < buttonViews.length; i++) {
            TextView b = buttonViews[i];
            if (!enabled[i]) {
                b.setTextColor(COLOR_DISABLED);
                b.setBackgroundColor(Color.TRANSPARENT);
            } else if (controls && hasFocus && i == buttonIndex) {
                b.setTextColor(0xFF000000);
                b.setBackgroundColor(0xFFFFFFFF);
            } else {
                b.setTextColor(controls ? COLOR_FOCUS_TEXT : COLOR_DIM);
                b.setBackgroundColor(Color.TRANSPARENT);
            }
        }
    }

    private void updateHint() {
        hint.setText(zone == Zone.LIST
                ? "▲ ▼ choose line   ·   ◄ ► nudge ±50ms   ·   OK: anchor here   ·   Back: cancel"
                : "◄ ► move   ·   OK: select   ·   'Sync line' to anchor   ·   ◄ menu");
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
        return (listener != null && listener.state() != null) ? listener.state() : SyncState.empty();
    }

    private long pos() {
        return listener != null ? listener.currentPositionMs() : 0L;
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

    // --- list adapter ---

    private class CueAdapter extends RecyclerView.Adapter<CueAdapter.VH> {
        private int activeIndex = -1;
        private int selectedIndex = -1;

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
            root.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView divider = new TextView(ctx);
            divider.setGravity(Gravity.CENTER);
            divider.setTextColor(SEGMENT_LABEL);
            divider.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            divider.setPadding(0, dp(16), 0, dp(16));
            root.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
            h.text.setText(String.join("\n", e.getLines()));

            boolean active = position == activeIndex;
            boolean selected = position == selectedIndex;
            h.text.setTextColor(selected ? COLOR_FOCUS_TEXT : (active ? COLOR_ACTIVE : COLOR_DIM));
            h.text.setBackgroundColor(selected ? FOCUS_BG : Color.TRANSPARENT);
            h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, active || selected ? 20 : 18);

            long gap = position > 0 ? e.getStartMs() - cues.get(position - 1).getEndMs() : 0;
            if (gap > SEGMENT_GAP_MS) {
                h.divider.setText("— " + Math.round(gap / 1000.0) + "s —");
                h.divider.setVisibility(View.VISIBLE);
            } else {
                h.divider.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return cues.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView text;
            final TextView divider;
            VH(View root, TextView text, TextView divider) {
                super(root);
                this.text = text;
                this.divider = divider;
            }
        }
    }
}
