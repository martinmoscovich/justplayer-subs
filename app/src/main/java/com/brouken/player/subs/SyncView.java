package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.brouken.player.R;
import com.brouken.player.subs.ui.SubsButton;
import com.brouken.player.subs.ui.SubsCenteredBlock;
import com.brouken.player.subs.ui.SubsClock;
import com.brouken.player.subs.ui.SubsModal;
import com.brouken.player.subs.ui.SubsShapes;
import com.brouken.player.subs.ui.SubsText;
import com.brouken.player.subs.ui.SubsTheme;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.sync.ManualSyncSession;
import subtitleengine.sync.SyncState;
import subtitleengine.sync.SyncTransform;

/**
 * The manual-sync UI: a "lyrics" list of dialogue lines with a transport/control row. All sync
 * computation lives in the engine's {@link ManualSyncSession}; this view only renders it and turns
 * key presses into session calls / player operations (via {@link Listener}).
 *
 * <p>Three things share the screen and must never be confused with each other: the line that is
 * <b>playing</b> (cyan), the line the user has <b>pointed at</b> (white card), and the <b>focus</b>
 * itself, which is in the button row until "Sync line" moves it into the list. That is the same
 * white-is-focus / cyan-is-chosen rule the whole panel follows.
 *
 * <p>Whenever something is running or impossible, the dialogue list stops being useful and gives way
 * to a {@link SubsCenteredBlock}; the two modal moments (choosing where auto-sync starts, reviewing
 * what it found) go through {@link SubsModal}. Both replace text that used to be crammed into the
 * hint line.
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

    // Dialogue inks. The two non-active ones are alpha-modulated on-surface: a dialogue you are not
    // at is still readable, just clearly not the one being talked about.
    private static final int CUE_FAR = 0x6BDDE4E5;
    private static final int CUE_NEAR = 0x9EDDE4E5;
    private static final int TIME_FAR = 0xBF647C86;
    private static final int TIME_ACTIVE = 0xB38AEBFF;
    private static final int TIME_SELECTED = 0x8C283044;

    private static final int AUTO_SYNC_INDEX = 1;
    private static final int RESET_INDEX = 2;
    private static final int PREV_SEG_INDEX = 3;
    private static final int SEEK_BACK_INDEX = 4;
    private static final int PLAY_PAUSE_INDEX = 5;
    private static final int SEEK_FWD_INDEX = 6;
    private static final int NEXT_SEG_INDEX = 7;

    private enum Zone { CONTROLS, LIST, REVIEW, AUTO_SYNC_MENU }

    /**
     * Which row of buttons is on screen. The full transport only makes sense when there is something
     * to transport through: while a read or an auto-sync runs, the row collapses to the two things
     * that still apply — and one of them is always a way to stop it.
     */
    private enum RowShape { FULL, RUNNING, DEAD }

    private static final String EMPTY_TITLE = "Nothing to sync yet";
    private static final String EMPTY_SUB = "Press Auto-sync to read this track from the video";

    private static final class Btn {
        final String label;
        final Runnable action;
        Btn(String label, Runnable action) { this.label = label; this.action = action; }
    }

    private final TextView readout;
    private final TextView hint;
    private final SubsCenteredBlock centeredBlock;
    private final SubsModal modal;
    private final VerticalGridView list;
    private final LinearLayout buttonRow;
    private final CueAdapter adapter = new CueAdapter();

    private final Btn[] buttons;
    private final SubsButton[] buttonViews;
    private final boolean[] enabled;
    private final SubsButton runCancelButton;
    private final SubsButton runDoneButton;
    private final SubsButton deadDoneButton;
    private RowShape rowShape = RowShape.FULL;
    private int runIndex; // focus within the RUNNING row: 0 = Cancel, 1 = Done

    /** Modal buttons, rebuilt per showing so their focus state starts clean. */
    private final List<SubsButton> modalButtons = new ArrayList<>();
    private int modalIndex;

    private Listener listener;
    private ManualSyncSession session; // engine — the source of truth for cues + sync logic
    @Nullable private AutoSyncUiState autoSyncState;
    @Nullable private String busyTitle;
    private float busyFraction = -1f;
    private boolean autoscroll = true;
    private Zone zone = Zone.CONTROLS;
    private int buttonIndex = 0;
    private boolean lastPlaying = true;
    private boolean hasFocus = true;
    private final SubsClock clock;

    public SyncView(Context context) {
        super(context);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(24), dp(56), dp(24), dp(14));
        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Overlaid on the root FrameLayout rather than added to `column`, so it floats over the
        // list in the corner instead of stealing a row of vertical space from it.
        clock = new SubsClock(context);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.gravity = Gravity.TOP | Gravity.END;
        clockLp.topMargin = dp(12);
        clockLp.rightMargin = dp(24);
        addView(clock, clockLp);

        FrameLayout stage = new FrameLayout(context);
        column.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list = new VerticalGridView(context);
        list.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_NO_EDGE);
        list.setWindowAlignmentOffsetPercent(50f);
        list.setItemAlignmentOffsetPercent(50f);
        list.setFocusable(false);
        list.setAdapter(adapter);
        stage.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        centeredBlock = new SubsCenteredBlock(context);
        centeredBlock.setVisibility(GONE);
        FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        stage.addView(centeredBlock, cbp);

        hint = SubsTheme.labelSm(new TextView(context));
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(SubsTheme.INK_3);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(10);
        column.addView(hint, hlp);

        buttons = new Btn[]{
                new Btn("Sync line", this::enterListMode),
                new Btn("Auto-sync", this::toggleAutoSync),
                new Btn("Reset", this::resetSync),
                new Btn("Prev seg", this::seekPrevSegment),
                new Btn("5s", () -> seek(-1)),
                new Btn("", this::togglePlay),
                new Btn("5s", () -> seek(1)),
                new Btn("Next seg", this::seekNextSegment),
                new Btn("Done", this::requestClose),
        };
        enabled = new boolean[buttons.length];
        Arrays.fill(enabled, true);

        buttonViews = new SubsButton[]{
                new SubsButton(context, "Sync line", R.drawable.subtitle_ic_anchor),
                new SubsButton(context, "Auto-sync", R.drawable.subtitle_ic_sparkles),
                // Round and icon-only, like the segment jumps: the row already carried eight controls
                // and a ninth labelled one pushed the transport group into the Done button at 1920px.
                SubsButton.round(context, R.drawable.subtitle_ic_retry),
                SubsButton.round(context, R.drawable.subtitle_ic_prev_seg),
                new SubsButton(context, "5s", R.drawable.subtitle_ic_rewind),
                SubsButton.round(context, R.drawable.subtitle_ic_pause),
                SubsButton.trailing(context, "5s", R.drawable.subtitle_ic_forward),
                SubsButton.round(context, R.drawable.subtitle_ic_next_seg),
                new SubsButton(context, "Done"),
        };

        runCancelButton = new SubsButton(context, "Cancel");
        runDoneButton = new SubsButton(context, "Done");
        deadDoneButton = new SubsButton(context, "Done");

        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        column.addView(buttonRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildRow(RowShape.FULL);

        // The offset/nudge/anchor readout: a capsule pinned to the content area's top-left corner,
        // out of the way of everything, because it is a reference and never a target.
        readout = SubsTheme.labelSm(new TextView(context));
        readout.setTextColor(SubsTheme.INK_2);
        readout.setGravity(Gravity.CENTER_VERTICAL);
        readout.setSingleLine(true);
        readout.setBackground(SubsShapes.panel(context, SubsTheme.RADIUS_ROW_DP));
        readout.setPadding(dp(12), 0, dp(12), 0);
        readout.setHeight(dp(28));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        rlp.gravity = Gravity.TOP | Gravity.START;
        rlp.leftMargin = dp(24);
        rlp.topMargin = dp(16);
        addView(readout, rlp);

        modal = new SubsModal(context);
        addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateReadout();
        updateHint();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Binds the engine session (the cue/sync source of truth). Null = nothing to sync. */
    public void setSession(ManualSyncSession session) {
        this.session = session;
        long seekS = session != null ? session.settings().getSeekMs() / 1000 : 5;
        // By constant, never by literal: these two were written as 3 and 5 and silently became the
        // prev-segment and play/pause buttons the moment Reset was inserted ahead of them, labelling
        // two icon-only round buttons "5s" (setText also un-hides a label that was GONE).
        buttonViews[SEEK_BACK_INDEX].setText(seekS + "s");
        buttonViews[SEEK_FWD_INDEX].setText(seekS + "s");
        adapter.notifyDataSetChanged();
        updateReadout();
        updateButtons();
        updateStage();
    }

    /**
     * A blocking step this screen is waiting on — reading an embedded track's cues out of the
     * container. It takes over the screen, which is exactly the state the screen is in while it
     * runs; {@code null} gives it back. {@code fraction} is negative when unknown.
     */
    public void setBusyStatus(@Nullable String title, float fraction) {
        this.busyTitle = title;
        this.busyFraction = fraction;
        updateButtons();
        updateStage();
    }

    /** Pushes formatted auto-sync state (see {@link AutoSyncController}). A confident result opens Zone.REVIEW. */
    public void setAutoSyncState(AutoSyncUiState state) {
        this.autoSyncState = state;
        if (state.showResultModal && zone != Zone.REVIEW) {
            zone = Zone.REVIEW;
            adapter.setSelectedIndex(-1);
            showReviewModal();
        }
        updateButtons();
        updateHint();
        updateReadout();
        updateStage();
    }

    public void setFocused(boolean f) {
        hasFocus = f;
        if (f && zone == Zone.CONTROLS) {
            // Arriving on the screen starts at "Sync line". Without this the cursor keeps wherever
            // it was pushed while the session was still null and every control was disabled — which
            // is how entering Sync used to land on "Next seg".
            buttonIndex = enabled[0] ? 0 : nextEnabled(0, 1);
        }
        updateButtons();
    }

    public void reset() {
        autoscroll = true;
        zone = Zone.CONTROLS;
        buttonIndex = 0;
        runIndex = 0;
        modal.hide();
        adapter.setSelectedIndex(-1);
        updateReadout();
        updateButtons();
        updateHint();
        updateStage();
        int idx = session != null ? session.scrollTargetIndex(pos()) : -1;
        if (idx >= 0 && idx < cues().size()) list.setSelectedPosition(idx);
    }

    public void onTick(long positionMs) {
        clock.setPositionMs(positionMs); // before the session guard: the clock is not session state
        if (session == null) return;
        adapter.setActiveIndex(session.activeCueIndex(positionMs));
        if (autoscroll) {
            int target = session.scrollTargetIndex(positionMs);
            if (target >= 0 && target != list.getSelectedPosition()) {
                list.setSelectedPositionSmooth(target);
            }
        }
        boolean playing = listener != null && listener.isPlaying();
        clock.setPlaying(playing);
        if (playing != lastPlaying) {
            lastPlaying = playing;
            buttonViews[PLAY_PAUSE_INDEX].setIcon(
                    playing ? R.drawable.subtitle_ic_pause : R.drawable.subtitle_ic_play);
        }
        // Only with a scale does the readout depend on where we are — without one it would reformat
        // the same string every tick.
        if (Math.abs(session.transform().getScale() - 1.0) > 1e-6) updateReadout();
        updateButtons();
    }

    public boolean handleKey(int keyCode) {
        switch (zone) {
            case LIST: return handleListKey(keyCode);
            case REVIEW: return handleModalKey(keyCode, true);
            case AUTO_SYNC_MENU: return handleModalKey(keyCode, false);
            default: return handleControlsKey(keyCode);
        }
    }

    // --- CONTROLS zone ---

    private boolean handleControlsKey(int keyCode) {
        if (rowShape != RowShape.FULL) return handleReducedRowKey(keyCode);
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

    /** RUNNING ([Cancel, Done]) and DEAD ([Done]) rows: a two-stop walk, nothing to disable. */
    private boolean handleReducedRowKey(int keyCode) {
        int count = rowShape == RowShape.RUNNING ? 2 : 1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (runIndex == 0) {
                    if (listener != null) listener.onOpenMenu();
                } else {
                    runIndex--;
                    updateButtons();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (runIndex < count - 1) {
                    runIndex++;
                    updateButtons();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (rowShape == RowShape.RUNNING && runIndex == 0) {
                    if (listener != null) listener.onCancelAutoSync();
                } else {
                    requestClose();
                }
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
                exitListMode();
                return true;
            case KeyEvent.KEYCODE_BACK:
                exitListMode();
                return true;
            default:
                return false;
        }
    }

    private void exitListMode() {
        zone = Zone.CONTROLS;
        adapter.setSelectedIndex(-1);
        // Always resume following playback. Leaving the list means it stops being browsable — the
        // selection is cleared and focus returns to the button row — so a list frozen wherever the
        // user stopped scrolling only drifts further from the video, with no way back short of
        // reopening the panel. This used to resume only on the anchoring exit, so backing out of
        // "Sync line" left it stuck.
        autoscroll = true;
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
        syncChanged();
    }

    /**
     * Every path that mutates the sync state funnels through here. Besides re-rendering the overlay,
     * the row times have to rebind: they show the <em>adjusted</em> start, so a nudge or an accepted
     * auto-sync moves all of them. {@code notifyItemRangeChanged} is a non-structural change, so the
     * D-pad selection and the scroll position survive it (unlike {@code notifyDataSetChanged}).
     */
    private void syncChanged() {
        if (listener != null) listener.onSyncChanged();
        int n = adapter.getItemCount();
        if (n > 0) adapter.notifyItemRangeChanged(0, n);
    }

    private void nudge(int dir) {
        if (session == null) return;
        if (dir < 0) session.nudgeLeft();
        else session.nudgeRight();
        syncChanged();
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

    /**
     * Puts the subtitle back where the file says it goes: no anchors, no nudge, no accepted auto-sync
     * offset. The only way out of a sync that went wrong was to nudge back by hand in 50ms steps and
     * hope to land on zero, or to leave the screen and hope nothing was saved — neither of which is a
     * way to say "start over".
     *
     * <p>Clears the whole {@link SyncState}, not just the nudge: from the user's side there is one
     * displacement on screen, however it got there, and a Reset that left an anchor behind would not
     * look like a reset. Disabled when there is nothing to undo, so it never reads as a live control
     * that does nothing.
     */
    private void resetSync() {
        if (session == null) return;
        session.restoreState(SyncState.empty());
        syncChanged();
        updateReadout();
        updateButtons();
    }

    private void seekPrevSegment() {
        if (session == null || listener == null) return;
        long t = session.prevSegmentTarget(pos());
        if (t != ManualSyncSession.NO_TARGET) listener.onSeekTo(t);
    }

    // --- the two modal moments ---

    /** The auto-sync proposal. It <em>proposes</em>: nothing is applied until Accept. */
    private void showReviewModal() {
        if (autoSyncState == null) return;
        if (autoSyncState.hasConfidentResult) {
            // A stretch has no single number of seconds to offer: the correction grows through the
            // file (a 1.0427 speed-up is 2.4s per minute), so "Shift subtitles +10.20s" would state
            // a figure that is true only at the head of the file and visibly wrong by the end. Say
            // what it does instead, and give the displacement at the position the user is watching,
            // which is the part they can check against the screen.
            String big = autoSyncState.hasScale()
                    ? String.format(Locale.US, "Stretch subtitles %.4f×  ·  %+.2fs here",
                            autoSyncState.scale, displacementHereSeconds(autoSyncState))
                    : String.format(Locale.US, "Shift subtitles %+.2fs", autoSyncState.offsetSeconds);
            showModal("Auto-sync found a match", big,
                    new SubsButton(getContext(), "Accept"),
                    new SubsButton(getContext(), "Reject"));
            return;
        }
        // Nothing to propose, but the run still ended: the user waited through it and gets told here,
        // in the same place a proposal would have appeared, rather than in a hint they may not look at.
        showModal(autoSyncState.hint != null && !autoSyncState.hint.isEmpty()
                        ? autoSyncState.hint : "No confident match",
                "The subtitles were left as they are",
                new SubsButton(getContext(), "OK"));
    }

    /**
     * What the proposal would move the subtitle by at the current playback position — the same
     * quantity the readout's {@code HERE} shows, computed against the proposed transform instead of
     * the applied one. Inverts {@code videoTime = cue·scale + offset} to find the cue currently on
     * screen, which is what the user is looking at when they judge the proposal.
     */
    private double displacementHereSeconds(AutoSyncUiState state) {
        double posSeconds = pos() / 1000.0;
        return posSeconds * (1 - 1 / state.scale) + state.offsetSeconds / state.scale;
    }

    private void showAutoSyncMenu() {
        showModal("Start auto-sync from", null,
                new SubsButton(getContext(), "From Start", R.drawable.subtitle_ic_play),
                new SubsButton(getContext(), "From Here", R.drawable.subtitle_ic_location));
    }

    private void showModal(String title, @Nullable String big, SubsButton... buttons) {
        modalButtons.clear();
        Collections.addAll(modalButtons, buttons);
        modalIndex = 0;
        modal.show(title, big, modalButtons);
        styleModalButtons();
    }

    private void styleModalButtons() {
        for (int i = 0; i < modalButtons.size(); i++) {
            modalButtons.get(i).setFocusedState(i == modalIndex);
        }
    }

    /**
     * Both modals share this: two buttons, ◄ ► to choose, OK to take it, Back to decline. The
     * {@code confirmIsFirst} flag only decides what Back means — rejecting a proposal is a real
     * decision, dismissing a menu is not.
     */
    private boolean handleModalKey(int keyCode, boolean review) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (modalIndex > 0) { modalIndex--; styleModalButtons(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (modalIndex < modalButtons.size() - 1) { modalIndex++; styleModalButtons(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (review) {
                    // A no-match modal has one button and nothing to accept — any press dismisses it.
                    boolean hasProposal = autoSyncState != null && autoSyncState.hasConfidentResult;
                    if (hasProposal && modalIndex == 0) acceptAutoSync(); else rejectAutoSync();
                } else {
                    boolean fromHere = modalIndex == 1;
                    exitModal();
                    if (listener != null) listener.onStartAutoSync(fromHere);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (review) rejectAutoSync(); else exitModal();
                return true;
            default:
                return true; // modal: swallow navigation until there is a decision
        }
    }

    private void acceptAutoSync() {
        if (session != null && autoSyncState != null && autoSyncState.hasConfidentResult) {
            session.applyVadTransform(autoSyncState.scale, autoSyncState.offsetSeconds);
            syncChanged();
        }
        exitModal();
    }

    private void rejectAutoSync() {
        exitModal(); // discards the proposal — SyncState is untouched
    }

    private void exitModal() {
        modal.hide();
        modalButtons.clear();
        zone = Zone.CONTROLS;
        updateButtons();
        updateHint();
        updateReadout();
        updateStage();
    }

    private void toggleAutoSync() {
        if (listener == null) return;
        if (busyTitle != null || (autoSyncState != null && autoSyncState.running)) {
            listener.onCancelAutoSync();
        } else {
            zone = Zone.AUTO_SYNC_MENU;
            showAutoSyncMenu();
            updateButtons();
            updateHint();
        }
    }

    // --- rendering ---

    private boolean running() {
        return busyTitle != null || (autoSyncState != null && autoSyncState.running);
    }

    /** True only when the screen has literally nothing to offer: no cues <em>and</em> no way to get any. */
    private boolean deadEnd() {
        return !hasCues() && autoSyncState != null && !autoSyncState.available;
    }

    private boolean hasCues() {
        return session != null && session.hasCues();
    }

    /**
     * Decides between the three things that can occupy the middle of the screen — the dialogue list,
     * a running step, or an explanation of why there is nothing to do — and keeps the button row in
     * step with it.
     */
    private void updateStage() {
        RowShape shape;
        if (running()) {
            if (busyTitle != null) {
                // Reading the embedded track: one job, one thing to say.
                String pct = busyFraction >= 0f ? Math.round(busyFraction * 100) + "%" : null;
                centeredBlock.show(R.drawable.subtitle_ic_spinner, true, busyTitle, pct, busyFraction);
            } else {
                // Auto-sync: several windows at once, each with its own column — see
                // AutoSyncUiState.probes for why one shared title and bar could not say this.
                centeredBlock.showProbes(R.drawable.subtitle_ic_spinner, "Listening to the video",
                        probeColumns());
            }
            list.setVisibility(GONE);
            shape = RowShape.RUNNING;
        } else if (deadEnd()) {
            centeredBlock.show(R.drawable.subtitle_ic_unavailable, false, "Auto-sync unavailable",
                    autoSyncState.unavailableReason, -1f);
            list.setVisibility(GONE);
            shape = RowShape.DEAD;
        } else if (!hasCues()) {
            // Still the full row: the message says "press Auto-sync", so Auto-sync has to be there.
            centeredBlock.show(R.drawable.subtitle_ic_subtitles, false, EMPTY_TITLE, EMPTY_SUB, -1f);
            list.setVisibility(GONE);
            shape = RowShape.FULL;
        } else {
            centeredBlock.hide();
            list.setVisibility(VISIBLE);
            shape = RowShape.FULL;
        }
        if (shape != rowShape) {
            runIndex = 0;
            buildRow(shape);
            updateHint();
        }
        updateButtons();
    }

    /** Maps the formatted state into what the block draws. Never empty while auto-sync runs, but a
     *  run reports its first event a beat after starting, so this covers the gap with one column. */
    private java.util.List<SubsCenteredBlock.Probe> probeColumns() {
        java.util.List<SubsCenteredBlock.Probe> columns = new java.util.ArrayList<>();
        if (autoSyncState != null) {
            for (AutoSyncUiState.ProbeRow row : autoSyncState.probes) {
                columns.add(new SubsCenteredBlock.Probe(row.label, row.title, row.fraction, row.active));
            }
        }
        if (columns.isEmpty()) {
            columns.add(new SubsCenteredBlock.Probe("", "Starting", -1f, true));
        }
        return columns;
    }

    private void buildRow(RowShape shape) {
        rowShape = shape;
        buttonRow.removeAllViews();
        Context c = getContext();
        switch (shape) {
            case RUNNING:
                buttonRow.addView(runCancelButton, runCancelButton.rowParams());
                buttonRow.addView(runDoneButton, runDoneButton.rowParams());
                break;
            case DEAD:
                buttonRow.addView(deadDoneButton, deadDoneButton.rowParams());
                break;
            case FULL:
            default:
                for (int i = 0; i < buttonViews.length; i++) {
                    // The transport group is fenced off from the two sync actions on its left and
                    // from Done on its right: three groups, three jobs.
                    if (i == PREV_SEG_INDEX || i == buttonViews.length - 1) {
                        buttonRow.addView(SubsButton.separator(c));
                    }
                    buttonRow.addView(buttonViews[i], buttonViews[i].rowParams());
                }
                break;
        }
    }

    private void updateButtons() {
        if (rowShape != RowShape.FULL) {
            boolean dim = !hasFocus;
            if (rowShape == RowShape.RUNNING) {
                runCancelButton.setFocusedState(hasFocus && runIndex == 0);
                runDoneButton.setFocusedState(hasFocus && runIndex == 1);
                runCancelButton.setDimmed(dim);
                runDoneButton.setDimmed(dim);
            } else {
                deadDoneButton.setFocusedState(hasFocus);
                deadDoneButton.setDimmed(dim);
            }
            return;
        }

        long p = pos();
        enabled[0] = hasCues();
        enabled[AUTO_SYNC_INDEX] = autoSyncState == null || autoSyncState.available;
        enabled[PREV_SEG_INDEX] = session != null && session.prevSegmentTarget(p) != ManualSyncSession.NO_TARGET;
        enabled[NEXT_SEG_INDEX] = session != null && session.nextSegmentTarget(p) != ManualSyncSession.NO_TARGET;
        enabled[RESET_INDEX] = session != null && !session.state().equals(SyncState.empty());

        boolean controls = zone == Zone.CONTROLS;
        if (controls && !enabled[buttonIndex]) {
            int e = nextEnabled(buttonIndex, 1);
            if (e == buttonIndex) e = nextEnabled(buttonIndex, -1);
            buttonIndex = e;
        }
        // The whole row dims as one when the cursor has gone somewhere else (into the dialogue list,
        // or over to the sidebar): a sleeping group, not eight broken buttons.
        boolean dimRow = !controls || !hasFocus;
        for (int i = 0; i < buttonViews.length; i++) {
            buttonViews[i].setEnabledState(enabled[i]);
            buttonViews[i].setFocusedState(controls && hasFocus && i == buttonIndex);
            buttonViews[i].setDimmed(dimRow);
        }
    }

    private void updateHint() {
        if (zone == Zone.LIST) {
            hint.setText(SubsText.hint("▲ ▼ *CHOOSE LINE* · ◄ ► *NUDGE* · OK: *ANCHOR HERE* · BACK: CANCEL"));
            hint.setVisibility(VISIBLE);
            return;
        }
        if (zone == Zone.REVIEW || zone == Zone.AUTO_SYNC_MENU || rowShape != RowShape.FULL) {
            // Nothing to instruct: a modal states its own case, and a run in progress has taken the
            // dialogue list away, so the keys the hint describes do not apply to anything.
            hint.setVisibility(INVISIBLE);
            return;
        }
        hint.setVisibility(VISIBLE);
        if (autoSyncState != null && !autoSyncState.available && hasCues()) {
            // Manual sync still works, so the screen stays; the reason for the dead Auto-sync button
            // goes here rather than taking over the whole screen. Never a silent failure.
            hint.setText(SubsText.hint(upper(autoSyncState.unavailableReason)));
        } else if (autoSyncState != null && autoSyncState.hint != null && !autoSyncState.hint.isEmpty()
                && !autoSyncState.running) {
            // Terminal messages that aren't a proposal: "No confident match", "Cancelled",
            // "Auto-sync failed: …". Those must reach the user too, not just errors.
            hint.setText(SubsText.hint(upper(autoSyncState.hint)));
        } else {
            hint.setText(SubsText.hint("◄ ► *MOVE* · OK: *SELECT* · 'SYNC LINE' TO ANCHOR · ◄ *MENU*"));
        }
    }

    private static String upper(@Nullable String s) {
        return s == null ? "" : s.toUpperCase(Locale.US);
    }

    /**
     * How far the subtitle has been moved, stated where the user can act on it.
     *
     * <p>The old readout showed the transform's raw {@code offset}, which is the displacement at cue
     * time <em>zero</em> — with two or more anchors that is an extrapolation back to the head of the
     * file, not what is on screen. A real case: three anchors resolved to scale 1.0406, so the actual
     * displacement grew 2.4s per minute of video and by minute 30 was 73s, while the readout still
     * said 10.20. Worse, taking a second anchor <em>moves</em> that number (the first anchor forces
     * scale 1, so its offset is a local measurement; the second lets the regression re-attribute part
     * of it to drift) — which reads as the sync having changed when nothing on screen did.
     *
     * <p>So: {@code HERE} first, the total displacement at the current playback position, and
     * {@code AT 0:00} beside it for the absolute figure the anchors resolved to. Both include the
     * nudge — they are the same quantity at two points in the file. When there is no scale the two
     * are equal by definition and it collapses to one {@code DELAY}. {@code SCALE} and {@code NUDGE}
     * stay as the components that produced them, each shown only when it is doing something.
     */
    private void updateReadout() {
        SyncState st = session != null ? session.state() : SyncState.empty();
        SyncTransform t = session != null ? session.transform() : SyncTransform.IDENTITY;
        double scale = t.getScale();
        long nudge = st.getNudgeMs();
        boolean scaled = Math.abs(scale - 1.0) > 1e-6;

        double atStartMs = t.getOffsetMs() + nudge;
        StringBuilder sb = new StringBuilder();
        if (scaled) {
            // The cue currently on screen, found by inverting the transform — not adjust(pos)-pos,
            // which would answer for a cue *written* at the current timestamp and is off by another
            // factor of the drift (3s of the 73 in the case above).
            double rawNowMs = (pos() - t.getOffsetMs() - nudge) / scale;
            sb.append(String.format(Locale.US, "HERE %+.2fS · AT 0:00 %+.2fS · SCALE %.4f",
                    (pos() - rawNowMs) / 1000.0, atStartMs / 1000.0, scale));
        } else {
            sb.append(String.format(Locale.US, "DELAY %+.2fS", atStartMs / 1000.0));
        }
        if (nudge != 0) sb.append(String.format(Locale.US, " · NUDGE %+dMS", nudge));
        int anchors = st.getAnchors() != null ? st.getAnchors().size() : 0;
        sb.append(" · ").append(anchors).append(anchors == 1 ? " ANCHOR" : " ANCHORS");
        readout.setText(sb.toString());
    }

    private List<SubtitleEntry> cues() {
        return session != null ? session.cues() : Collections.emptyList();
    }

    private long pos() {
        return listener != null ? listener.currentPositionMs() : 0L;
    }

    private int dp(int v) {
        return SubsTheme.dp(getContext(), v);
    }

    // --- list adapter ---

    private class CueAdapter extends RecyclerView.Adapter<CueAdapter.VH> {
        private static final int MAX_WIDTH_DP = 680;
        private static final int GUTTER_DP = 60;

        private int activeIndex = -1;
        private int selectedIndex = -1;

        void setActiveIndex(int idx) {
            if (idx == activeIndex) return;
            int old = activeIndex;
            activeIndex = idx;
            // The two neighbours change with it: "near" is defined relative to the active line.
            notifyAround(old);
            notifyAround(idx);
        }

        private void notifyAround(int idx) {
            if (idx < 0) return;
            for (int i = Math.max(0, idx - 1); i <= idx + 1 && i < getItemCount(); i++) {
                notifyItemChanged(i);
            }
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
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout divider = new LinearLayout(ctx);
            divider.setOrientation(LinearLayout.HORIZONTAL);
            divider.setGravity(Gravity.CENTER);
            divider.setPadding(0, dp(9), 0, dp(9));
            View ruleL = rule(ctx);
            TextView gap = SubsTheme.labelSm(new TextView(ctx));
            gap.setTextColor(SubsTheme.INK_3);
            View ruleR = rule(ctx);
            divider.addView(ruleL, ruleParams(ctx, false));
            divider.addView(gap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            divider.addView(ruleR, ruleParams(ctx, true));
            root.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // A symmetric 60 · text · 60 grid: the start time lives in the left gutter and the
            // dialogue still sits dead centre of the screen, which is where the eye rests.
            LinearLayout lyric = new LinearLayout(ctx);
            lyric.setOrientation(LinearLayout.HORIZONTAL);
            lyric.setGravity(Gravity.CENTER_VERTICAL);
            lyric.setPadding(dp(12), dp(7), dp(12), dp(7));

            TextView time = SubsTheme.labelSm(new TextView(ctx));
            time.setLetterSpacing(.06f);
            time.setGravity(Gravity.END);
            time.setPadding(0, 0, dp(14), 0);
            time.setSingleLine(true);
            lyric.addView(time, new LinearLayout.LayoutParams(
                    dp(GUTTER_DP), ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView text = SubsTheme.bodyMd(new TextView(ctx));
            text.setGravity(Gravity.CENTER);
            text.setLineSpacing(0f, 1.15f);
            lyric.addView(text, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View spacer = new View(ctx);
            lyric.addView(spacer, new LinearLayout.LayoutParams(dp(GUTTER_DP), 1));

            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    dp(MAX_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(lyric, llp);

            return new VH(root, lyric, text, time, divider, gap);
        }

        private View rule(Context ctx) {
            View v = new View(ctx);
            v.setBackgroundColor(SubsTheme.SURFACE_4);
            return v;
        }

        private LinearLayout.LayoutParams ruleParams(Context ctx, boolean right) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(46), dp(1));
            if (right) p.leftMargin = dp(12); else p.rightMargin = dp(12);
            p.gravity = Gravity.CENTER_VERTICAL;
            return p;
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            List<SubtitleEntry> cues = cues();
            SubtitleEntry e = cues.get(position);
            h.text.setText(String.join("\n", e.getLines()));
            // The ADJUSTED start, not the file's raw one. This screen exists to line subtitles up
            // with the video, so the useful number is when the cue will actually appear — which is
            // also what picks the active row (activeCueIndex works on adjusted times) and what the
            // playback clock in the corner counts. Showing the raw value made a cue reading 15:20
            // light up while the clock said 14:38.
            h.time.setText(clock(session != null ? session.adjust(e.getStartMs()) : e.getStartMs()));

            boolean active = position == activeIndex;
            boolean selected = position == selectedIndex;
            boolean near = !active && !selected && activeIndex >= 0 && Math.abs(position - activeIndex) == 1;

            if (selected) {
                h.lyric.setBackground(SubsShapes.rounded(getContext(), SubsTheme.INK, SubsTheme.RADIUS_ROW_DP));
                h.text.setTextColor(SubsTheme.ON_SECONDARY);
                h.text.setTypeface(SubsTheme.semibold(getContext()));
                h.text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, SubsTheme.BODY_LG_SP);
                h.time.setTextColor(TIME_SELECTED);
            } else {
                h.lyric.setBackground(null);
                h.text.setTextColor(active ? SubsTheme.PRIMARY : (near ? CUE_NEAR : CUE_FAR));
                h.text.setTypeface(active ? SubsTheme.medium(getContext()) : SubsTheme.regular(getContext()));
                h.text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                        active ? SubsTheme.BODY_LG_SP : SubsTheme.BODY_MD_SP);
                h.time.setTextColor(active ? TIME_ACTIVE : TIME_FAR);
            }

            if (session != null && position > 0 && session.isSegmentStart(position)) {
                h.gap.setText(Math.round(session.silenceBefore(position) / 1000.0) + "S");
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
            final LinearLayout lyric;
            final TextView text;
            final TextView time;
            final LinearLayout divider;
            final TextView gap;

            VH(View root, LinearLayout lyric, TextView text, TextView time, LinearLayout divider, TextView gap) {
                super(root);
                this.lyric = lyric;
                this.text = text;
                this.time = time;
                this.divider = divider;
                this.gap = gap;
            }
        }
    }

    /** {@code h:mm:ss} — a dialogue's start time, which past an hour is how the user reads it. */
    private static String clock(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%d:%02d", m, s);
    }
}
