package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
 * video. Fully driven by {@link CustomSubtitleController} — it routes key events here and ticks us
 * with the current position; we never grab focus, so there is no autoscroll-vs-focus fight.
 *
 * <ul>
 *   <li>D-pad up/down — navigate lines; this <b>freezes autoscroll</b>.</li>
 *   <li>OK — anchor the selected line to the current video position (adjusts sync) and
 *       <b>re-enables autoscroll</b> until the user moves again.</li>
 *   <li>D-pad left/right — nudge the global offset ±{@value #NUDGE_MS} ms.</li>
 *   <li>Back — close.</li>
 * </ul>
 *
 * <p>The top {@link #header} is a placeholder for the future subtitle-selection UI (embedded +
 * external), which will live in this same panel.
 */
public class SyncPanel extends FrameLayout {

    /** Bridge to the player/engine state, implemented by {@link CustomSubtitleController}. */
    public interface Callbacks {
        long currentPositionMs();
        SyncState state();
        void onAnchor(int cueIndex, long cueStartMs, long videoPositionMs);
        void onNudge(long deltaMs);
    }

    private static final long NUDGE_MS = 50;

    private final TextView header;   // reserved for subtitle selection (Fase later)
    private final TextView readout;  // effective offset / nudge
    private final TextView hint;     // controls help
    private final VerticalGridView list;
    private final CueAdapter adapter = new CueAdapter();

    private Callbacks callbacks;
    private List<SubtitleEntry> cues = Collections.emptyList();
    private boolean autoscroll = true;

    public SyncPanel(Context context) {
        super(context);
        setBackgroundColor(0xB3000000); // ~70% black, video still visible behind
        setVisibility(GONE);
        setClickable(true);

        header = label(16, 0xFFB0BEC5);
        header.setText("Sync");
        header.setPadding(dp(24), dp(14), dp(24), dp(2));
        addView(header, lp(Gravity.TOP | Gravity.START));

        readout = label(15, Color.WHITE);
        readout.setPadding(dp(24), dp(2), dp(24), dp(10));
        addView(readout, lp(Gravity.TOP | Gravity.START, dp(28), 0));

        list = new VerticalGridView(context);
        list.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_NO_EDGE);
        list.setWindowAlignmentOffsetPercent(50f);
        list.setItemAlignmentOffsetPercent(50f);
        list.setFocusable(false);
        list.setAdapter(adapter);
        FrameLayout.LayoutParams llp =
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        llp.topMargin = dp(70);
        llp.bottomMargin = dp(48);
        addView(list, llp);

        hint = label(13, 0xFF90A4AE);
        hint.setText("OK: anchor  ·  ◄ ► nudge  ·  Back: close");
        hint.setPadding(dp(24), dp(8), dp(24), dp(14));
        addView(hint, lp(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
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
        setVisibility(VISIBLE);
        updateReadout();
        int idx = activeIndex(pos());
        if (idx >= 0) list.setSelectedPosition(idx);
    }

    public void close() {
        setVisibility(GONE);
    }

    /** Called by the controller's poll loop while open, to drive autoscroll and the "now" marker. */
    public void onTick(long positionMs) {
        if (!isOpen()) return;
        int active = activeIndex(positionMs);
        adapter.setActiveIndex(active);
        if (autoscroll && active >= 0 && active != list.getSelectedPosition()) {
            list.setSelectedPositionSmooth(active);
        }
    }

    /** Handles a key while the panel is open. Returns true if consumed. */
    public boolean handleKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return isNavKey(event.getKeyCode()); // swallow the matching UP so it doesn't leak
        }
        switch (event.getKeyCode()) {
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
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                nudge(-NUDGE_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                nudge(NUDGE_MS);
                return true;
            case KeyEvent.KEYCODE_BACK:
                close();
                return true;
            default:
                return false;
        }
    }

    // --- internals ---

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

    private void moveSelection(int delta) {
        if (cues.isEmpty()) return;
        int p = list.getSelectedPosition() + delta;
        p = Math.max(0, Math.min(cues.size() - 1, p));
        list.setSelectedPositionSmooth(p);
    }

    private void anchorSelected() {
        int p = list.getSelectedPosition();
        if (callbacks == null || p < 0 || p >= cues.size()) return;
        SubtitleEntry e = cues.get(p);
        callbacks.onAnchor(e.getIndex(), e.getStartMs(), pos());
        autoscroll = true; // re-enable autoscroll after anchoring (until the user navigates again)
        updateReadout();
    }

    private void nudge(long deltaMs) {
        if (callbacks == null) return;
        callbacks.onNudge(deltaMs);
        updateReadout();
    }

    private int activeIndex(long positionMs) {
        SyncState st = state();
        int last = -1;
        for (int i = 0; i < cues.size(); i++) {
            SubtitleEntry e = cues.get(i);
            long start = SyncResolver.adjust(st, e.getStartMs());
            long end = SyncResolver.adjust(st, e.getEndMs());
            if (positionMs >= start && positionMs <= end) return i;
            if (start > positionMs) return last; // nearest previous cue
            last = i;
        }
        return last;
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

    private FrameLayout.LayoutParams lp(int gravity) {
        return lp(gravity, 0, 0);
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

    // --- list adapter: one dialogue line per row, "now" line highlighted ---

    private class CueAdapter extends RecyclerView.Adapter<CueAdapter.VH> {
        private int activeIndex = -1;

        void setActiveIndex(int idx) {
            if (idx == activeIndex) return;
            int old = activeIndex;
            activeIndex = idx;
            if (old >= 0) notifyItemChanged(old);
            if (idx >= 0) notifyItemChanged(idx);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tv.setPadding(dp(24), dp(10), dp(24), dp(10));
            // Use ViewGroup.LayoutParams (not RecyclerView.LayoutParams): leanback's GridLayoutManager
            // converts these to its own params type; a RecyclerView.LayoutParams would be kept as-is
            // and then fail its internal cast (ClassCastException during layout).
            tv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            SubtitleEntry e = cues.get(position);
            ((TextView) holder.itemView).setText(String.join(" ", e.getLines()));
            boolean active = position == activeIndex;
            TextView tv = (TextView) holder.itemView;
            tv.setTextColor(active ? Color.WHITE : 0x99FFFFFF);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, active ? 20 : 18);
        }

        @Override
        public int getItemCount() {
            return cues.size();
        }

        class VH extends RecyclerView.ViewHolder {
            VH(View v) {
                super(v);
            }
        }
    }
}
