package com.brouken.player.subs;

import android.content.Context;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.List;

import subtitleengine.core.model.SubtitleFile;
import subtitleengine.sync.SyncState;

/**
 * Container that puts the two distinct subtitle concerns on one screen and manages focus between
 * them: the {@link SubtitleSelectorView} (choose the source) on top, and the {@link SyncView}
 * (manual sync) below. It owns no domain logic — it forwards selection/sync events to
 * {@link Callbacks} (the controller) and moves focus up/down between the two child views.
 */
public class SubtitlePanel extends FrameLayout implements SubtitleSelectorView.Listener, SyncView.Listener {

    /** Everything the controller supplies: sync ops, player ops, and subtitle selection. */
    public interface Callbacks {
        long currentPositionMs();
        boolean isPlaying();
        SyncState state();
        void onAnchor(int cueIndex, long cueStartMs, long videoPositionMs);
        void onNudge(long deltaMs);
        void onSeek(long deltaMs);
        void onSeekTo(long positionMs);
        void onTogglePlay();
        void onSelectOption(String optionId);
    }

    private final SubtitleSelectorView selector;
    private final SyncView syncView;
    private Callbacks callbacks;
    private boolean focusOnSelector = false;

    public SubtitlePanel(Context context) {
        super(context);
        setBackgroundColor(0xB3000000); // ~70% black, video visible behind
        setVisibility(GONE);
        setClickable(true);

        selector = new SubtitleSelectorView(context);
        selector.setListener(this);
        addView(selector, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        syncView = new SyncView(context);
        syncView.setListener(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.topMargin = dp(52); // below the selector row
        addView(syncView, slp);
    }

    public void setCallbacks(Callbacks cb) {
        this.callbacks = cb;
    }

    public void setOptions(List<SubtitleOption> options, String selectedId, boolean loadingMore) {
        selector.setOptions(options, selectedId, loadingMore);
    }

    public void bind(SubtitleFile file) {
        syncView.bind(file);
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    public void open() {
        focusOnSelector = false;
        setVisibility(VISIBLE);
        syncView.reset();
        syncView.setFocused(true);
        selector.setFocused(false);
    }

    public void close() {
        setVisibility(GONE);
    }

    public void onTick(long positionMs) {
        if (isOpen()) syncView.onTick(positionMs);
    }

    /** Routes a key to the focused child. Returns true if consumed. */
    public boolean handleKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return isNavKey(event.getKeyCode());
        }
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            close();
            return true;
        }
        return focusOnSelector ? selector.handleKey(keyCode) : syncView.handleKey(keyCode);
    }

    // --- SubtitleSelectorView.Listener ---

    @Override
    public void onSelect(String optionId) {
        if (callbacks != null) callbacks.onSelectOption(optionId);
    }

    @Override
    public void onFocusLeaveDown() {
        focusOnSelector = false;
        selector.setFocused(false);
        syncView.setFocused(true);
    }

    // --- SyncView.Listener ---

    @Override
    public void onFocusLeaveUp() {
        if (selector.isEmpty()) return;
        focusOnSelector = true;
        selector.setFocused(true);
        syncView.setFocused(false);
    }

    @Override
    public void onRequestClose() {
        close();
    }

    @Override public long currentPositionMs() { return callbacks != null ? callbacks.currentPositionMs() : 0L; }
    @Override public boolean isPlaying() { return callbacks != null && callbacks.isPlaying(); }
    @Override public SyncState state() { return callbacks != null ? callbacks.state() : SyncState.empty(); }
    @Override public void onAnchor(int i, long s, long v) { if (callbacks != null) callbacks.onAnchor(i, s, v); }
    @Override public void onNudge(long d) { if (callbacks != null) callbacks.onNudge(d); }
    @Override public void onSeek(long d) { if (callbacks != null) callbacks.onSeek(d); }
    @Override public void onSeekTo(long p) { if (callbacks != null) callbacks.onSeekTo(p); }
    @Override public void onTogglePlay() { if (callbacks != null) callbacks.onTogglePlay(); }

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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
