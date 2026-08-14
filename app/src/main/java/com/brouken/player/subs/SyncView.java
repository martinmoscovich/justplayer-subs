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
import androidx.annotation.Nullable;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.sync.ManualSyncSession;
import subtitleengine.sync.SyncState;
import subtitleengine.sync.SyncTransform;

/**
 * The manual-sync UI: a "lyrics" list of dialogue lines with a transport/control row. All sync
 * computation lives in the engine's {@link ManualSyncSession}; this view only renders it and turns
 * key presses into session calls / player operations (via {@link Listener}).
 */
public class SyncView extends FrameLayout {

    public interface Listener {
        long currentPositionMs();
        boolean isPlaying();
        void onSeek(long deltaMs);
        void onSeekTo(long positionMs);
        void onTogglePlay();
        /** An anchor/nudge changed the sync — re-render the overlay. */
        void onSyncChanged();
        /** ◄ past the leftmost control, or Back: move focus to the sidebar. */
        void onOpenMenu();
        /** Done button: close the whole panel. */
        void onRequestClose();
        void onStartAutoSync(boolean fromHere);
        void onCancelAutoSync();
    }

    private static final int COLOR_DIM = 0x80FFFFFF;
    private static final int COLOR_ACTIVE = 0xFF4DD0E1;
    private static final int COLOR_FOCUS_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DISABLED = 0x40FFFFFF;
    private static final int FOCUS_BG = 0x33FFFFFF;
    private static final int SEGMENT_LABEL = 0xB0B0BEC5;

    private static final int AUTO_SYNC_INDEX = 1;
    private static final int PREV_SEG_INDEX = 2;
    private static final int SEEK_BACK_INDEX = 3;
    private static final int PLAY_PAUSE_INDEX = 4;
    private static final int SEEK_FWD_INDEX = 5;
    private static final int NEXT_SEG_INDEX = 6;

    private enum Zone { CONTROLS, LIST, REVIEW, AUTO_SYNC_MENU }

    private static final String[] AUTO_SYNC_MENU_OPTIONS = { "From Start", "From Here" };

    private static final class Btn {
        final String label;
        final Runnable action;
        Btn(String label, Runnable action) { this.label = label; this.action = action; }
    }

    private final TextView readout;
    private final TextView hint;
    /** Shown when there is nothing to sync. Embedded tracks reach this screen with no cues until
     *  their container has been read, and pressing Auto-sync is what starts that read. */
    private static final String EMPTY_MESSAGE = "Nothing to sync yet\nPress Auto-sync to read this track from the video";

    private final TextView emptyMessage;
    @Nullable private String busyStatus;
    private final VerticalGridView list;
    private final LinearLayout buttonRow;
    private final CueAdapter adapter = new CueAdapter();
    private final Btn[] buttons;
    private final TextView[] buttonViews;
    private final boolean[] enabled;

    private Listener listener;
    private ManualSyncSession session; // engine — the source of truth for cues + sync logic
    @Nullable private AutoSyncUiState autoSyncState;
    private boolean autoscroll = true;
    private Zone zone = Zone.CONTROLS;
    private int buttonIndex = 0;
    private int autoSyncMenuIndex = 0; // 0 = "From Start", 1 = "From Here"
    private boolean lastPlaying = true;
    private boolean hasFocus = true;

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
        emptyMessage.setText(EMPTY_MESSAGE);
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
                new Btn("Auto-sync", this::toggleAutoSync),
                new Btn("|« Prev seg", this::seekPrevSegment),
                new Btn("« 5s", () -> seek(-1)),
                new Btn("⏸", this::togglePlay),
                new Btn("5s »", () -> seek(1)),
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

    /** Binds the engine session (the cue/sync source of truth). Null = nothing to sync. */
    public void setSession(ManualSyncSession session) {
        this.session = session;
        long seekS = session != null ? session.settings().getSeekMs() / 1000 : 5;
        buttonViews[SEEK_BACK_INDEX].setText("« " + seekS + "s");
        buttonViews[SEEK_FWD_INDEX].setText(seekS + "s »");
        adapter.notifyDataSetChanged();
        boolean empty = cues().isEmpty();
        emptyMessage.setVisibility(empty ? VISIBLE : GONE);
        list.setVisibility(empty ? GONE : VISIBLE);
        updateReadout();
        updateButtons();
    }

    /**
     * A blocking step this screen is waiting on — reading an embedded track's cues out of the
     * container. It replaces the empty-state message, which is exactly the state the screen is in
     * while it runs; {@code null} restores it.
     */
    public void setBusyStatus(@Nullable String busy) {
        this.busyStatus = busy;
        emptyMessage.setText(busy != null ? busy : EMPTY_MESSAGE);
    }

    /** Pushes formatted auto-sync state (see {@link AutoSyncController}). A confident result opens Zone.REVIEW. */
    public void setAutoSyncState(AutoSyncUiState state) {
        this.autoSyncState = state;
        buttonViews[AUTO_SYNC_INDEX].setText(state.running ? "Cancel" : "Auto-sync");
        if (state.hasConfidentResult) {
            zone = Zone.REVIEW;
            adapter.setSelectedIndex(-1);
        }
        updateButtons();
        updateHint();
        updateReadout();
    }

    public void setFocused(boolean f) {
        hasFocus = f;
        updateButtons();
    }

    public void reset() {
        autoscroll = true;
        zone = Zone.CONTROLS;
        buttonIndex = 0;
        adapter.setSelectedIndex(-1);
        updateReadout();
        updateButtons();
        updateHint();
        int idx = session != null ? session.scrollTargetIndex(pos()) : -1;
        if (idx >= 0 && idx < cues().size()) list.setSelectedPosition(idx);
    }

    public void onTick(long positionMs) {
        if (session == null) return;
        adapter.setActiveIndex(session.activeCueIndex(positionMs));
        if (autoscroll) {
            int target = session.scrollTargetIndex(positionMs);
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
        switch (zone) {
            case LIST: return handleListKey(keyCode);
            case REVIEW: return handleReviewKey(keyCode);
            case AUTO_SYNC_MENU: return handleAutoSyncMenuKey(keyCode);
            default: return handleControlsKey(keyCode);
        }
    }

    // --- CONTROLS zone ---

    private boolean handleControlsKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                int left = nextEnabled(buttonIndex, -1);
                if (left == buttonIndex) {
                    if (listener != null) listener.onOpenMenu();
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
                return true;
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
        if (session == null || !session.hasCues()) return;
        zone = Zone.LIST;
        int idx = session.scrollTargetIndex(pos());
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
                nudge(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                nudge(1);
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
        List<SubtitleEntry> cues = cues();
        if (cues.isEmpty()) return;
        int p = Math.max(0, Math.min(cues.size() - 1, list.getSelectedPosition() + delta));
        list.setSelectedPositionSmooth(p);
        adapter.setSelectedIndex(p);
    }

    private void anchorSelected() {
        int p = list.getSelectedPosition();
        List<SubtitleEntry> cues = cues();
        if (session == null || p < 0 || p >= cues.size()) return;
        SubtitleEntry e = cues.get(p);
        session.anchor(e.getIndex(), e.getStartMs(), pos());
        if (listener != null) listener.onSyncChanged();
    }

    private void nudge(int dir) {
        if (session == null) return;
        if (dir < 0) session.nudgeLeft();
        else session.nudgeRight();
        if (listener != null) listener.onSyncChanged();
        updateReadout();
    }

    private void seek(int dir) {
        if (listener == null || session == null) return;
        listener.onSeek(dir * session.settings().getSeekMs());
    }

    private void togglePlay() {
        if (listener != null) listener.onTogglePlay();
    }

    private void requestClose() {
        if (listener != null) listener.onRequestClose();
    }

    private void seekNextSegment() {
        if (session == null || listener == null) return;
        long t = session.nextSegmentTarget(pos());
        if (t != ManualSyncSession.NO_TARGET) listener.onSeekTo(t);
    }

    private void seekPrevSegment() {
        if (session == null || listener == null) return;
        long t = session.prevSegmentTarget(pos());
        if (t != ManualSyncSession.NO_TARGET) listener.onSeekTo(t);
    }

    // --- REVIEW zone (auto-sync proposal: accept/reject, never applied silently) ---

    private boolean handleReviewKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                acceptAutoSync();
                return true;
            case KeyEvent.KEYCODE_BACK:
                rejectAutoSync();
                return true;
            default:
                return true; // modal: swallow navigation until accept/reject
        }
    }

    private void acceptAutoSync() {
        if (session != null && autoSyncState != null && autoSyncState.hasConfidentResult) {
            session.applyVadOffset(autoSyncState.offsetSeconds);
            if (listener != null) listener.onSyncChanged();
        }
        exitReviewMode();
    }

    private void rejectAutoSync() {
        exitReviewMode(); // discards the proposal — SyncState is untouched
    }

    private void exitReviewMode() {
        zone = Zone.CONTROLS;
        updateButtons();
        updateHint();
        updateReadout();
    }

    private void toggleAutoSync() {
        if (listener == null) return;
        if (autoSyncState != null && autoSyncState.running) {
            listener.onCancelAutoSync();
        } else {
            autoSyncMenuIndex = 0;
            zone = Zone.AUTO_SYNC_MENU;
            updateButtons();
            updateHint();
        }
    }

    // --- AUTO_SYNC_MENU zone: "From Start" / "From Here" choice, modal like REVIEW ---

    private boolean handleAutoSyncMenuKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                autoSyncMenuIndex = 1 - autoSyncMenuIndex;
                updateHint();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                exitAutoSyncMenu();
                if (listener != null) listener.onStartAutoSync(autoSyncMenuIndex == 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                exitAutoSyncMenu();
                return true;
            default:
                return true; // modal: swallow navigation until a choice or Back
        }
    }

    private void exitAutoSyncMenu() {
        zone = Zone.CONTROLS;
        updateButtons();
        updateHint();
    }

    // --- rendering ---

    private void updateButtons() {
        long p = pos();
        boolean hasCues = session != null && session.hasCues();
        enabled[0] = hasCues;
        enabled[AUTO_SYNC_INDEX] = autoSyncState == null || autoSyncState.available;
        enabled[PREV_SEG_INDEX] = session != null && session.prevSegmentTarget(p) != ManualSyncSession.NO_TARGET;
        enabled[NEXT_SEG_INDEX] = session != null && session.nextSegmentTarget(p) != ManualSyncSession.NO_TARGET;

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
        if (zone == Zone.AUTO_SYNC_MENU) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < AUTO_SYNC_MENU_OPTIONS.length; i++) {
                if (i > 0) sb.append("    ");
                sb.append(i == autoSyncMenuIndex ? "▸ " : "  ").append(AUTO_SYNC_MENU_OPTIONS[i]);
            }
            sb.append("   ·   OK: start   ·   Back: cancel");
            hint.setText(sb.toString());
            return;
        }
        if (zone == Zone.REVIEW) {
            String proposal = autoSyncState != null ? autoSyncState.hint : "";
            hint.setText(proposal + "   ·   OK: accept   ·   Back: reject");
            return;
        }
        if (zone == Zone.LIST) {
            hint.setText("▲ ▼ choose line   ·   ◄ ► nudge   ·   OK: anchor here   ·   Back: cancel");
            return;
        }
        if (autoSyncState != null && !autoSyncState.available) {
            // Unavailable reason ("Load an external subtitle first", "No media source", HLS, …) —
            // never fail silently, per PLAN.md §8.
            hint.setText(autoSyncState.unavailableReason != null ? autoSyncState.unavailableReason : "");
        } else if (autoSyncState != null && autoSyncState.hint != null && !autoSyncState.hint.isEmpty()) {
            // Covers both RUNNING progress ("Extracting audio… 34%") and terminal messages that
            // aren't a confident-result proposal ("No confident match", "Cancelled",
            // "Auto-sync failed: …") — those must reach the user too, not just errors.
            hint.setText(autoSyncState.hint);
        } else {
            hint.setText("◄ ► move   ·   OK: select   ·   'Sync line' to anchor   ·   ◄ menu");
        }
    }

    private void updateReadout() {
        SyncState st = session != null ? session.state() : SyncState.empty();
        SyncTransform t = session != null ? session.transform() : SyncTransform.IDENTITY;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "offset %+.2fs · nudge %+dms", t.getOffsetMs() / 1000.0, st.getNudgeMs()));
        if (Math.abs(t.getScale() - 1.0) > 1e-6) {
            sb.append(String.format(Locale.ROOT, " · scale %.4f", t.getScale()));
        }
        int anchors = st.getAnchors() != null ? st.getAnchors().size() : 0;
        sb.append("   (").append(anchors).append(anchors == 1 ? " anchor)" : " anchors)");
        readout.setText(sb.toString());
    }

    private List<SubtitleEntry> cues() {
        return session != null ? session.cues() : Collections.emptyList();
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
            List<SubtitleEntry> cues = cues();
            SubtitleEntry e = cues.get(position);
            h.text.setText(String.join("\n", e.getLines()));

            boolean active = position == activeIndex;
            boolean selected = position == selectedIndex;
            h.text.setTextColor(selected ? COLOR_FOCUS_TEXT : (active ? COLOR_ACTIVE : COLOR_DIM));
            h.text.setBackgroundColor(selected ? FOCUS_BG : Color.TRANSPARENT);
            h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, active || selected ? 20 : 18);

            if (session != null && position > 0 && session.isSegmentStart(position)) {
                h.divider.setText("— " + Math.round(session.silenceBefore(position) / 1000.0) + "s —");
                h.divider.setVisibility(View.VISIBLE);
            } else {
                h.divider.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return cues().size();
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
