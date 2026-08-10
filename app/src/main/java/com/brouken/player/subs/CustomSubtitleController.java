package com.brouken.player.subs;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.util.List;

import subtitleengine.core.model.SubtitleFile;

/**
 * Coordinator that wires the two subtitle concerns to the player. It owns the
 * {@link SubtitleSelectionController} (which subtitle), the {@link SubtitleSyncController} (offset +
 * overlay), and the {@link SubtitlePanel} (UI), and forwards player operations. All new logic lives
 * in {@code com.brouken.player.subs}; {@code PlayerActivity} only creates this, calls
 * {@link #onMediaSet}, routes keys via {@link #dispatchKey}, opens the panel via {@link #openPanel()},
 * and calls {@link #release()} on teardown.
 */
public class CustomSubtitleController
        implements SubtitlePanel.Callbacks, SubtitleSelectionController.Listener {

    private static final long POLL_MS = 100;
    private static final int KEY_OPEN_PANEL = KeyEvent.KEYCODE_CAPTIONS;

    private final Context context;
    private final ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SubtitleSyncController sync;
    private final SubtitleSelectionController selection;
    private final SubtitlePanel panel;
    private final TranslationController translation;
    private final AutoSyncController autoSync;
    private boolean ticking;
    @Nullable private Uri mediaUri;

    public CustomSubtitleController(Context context, ViewGroup root, ExoPlayer player,
                                    DefaultTrackSelector trackSelector) {
        this.context = context;
        this.player = player;

        sync = new SubtitleSyncController(context);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        olp.bottomMargin = Math.round(48 * context.getResources().getDisplayMetrics().density);
        root.addView(sync.getOverlayView(), olp);

        panel = new SubtitlePanel(context);
        panel.setCallbacks(this);
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        translation = new TranslationController(context, sync, panel, handler, this::renderOverlay);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.TOP | Gravity.END;
        int indicatorMargin = Math.round(16 * context.getResources().getDisplayMetrics().density);
        ilp.topMargin = indicatorMargin;
        ilp.rightMargin = indicatorMargin;
        root.addView(translation.getIndicatorView(), ilp);

        autoSync = new AutoSyncController(context, panel, handler);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.TOP | Gravity.START;
        alp.topMargin = indicatorMargin;
        alp.leftMargin = indicatorMargin;
        root.addView(autoSync.getIndicatorView(), alp);

        selection = new SubtitleSelectionController(context, player, trackSelector, this, handler);
    }

    public void onMediaSet(@Nullable Uri mediaUri,
                           @Nullable List<MediaItem.SubtitleConfiguration> apiSubs,
                           @Nullable Uri prefsSubtitleUri) {
        this.mediaUri = mediaUri;
        selection.onMediaSet(mediaUri, apiSubs, prefsSubtitleUri);
        startTicking();
    }

    /** Routes a key: opens the panel on the CC key (when subtitles exist), or into the open panel. */
    public boolean dispatchKey(KeyEvent event) {
        if (panel.isOpen()) {
            return panel.handleKey(event);
        }
        if (selection.hasOptions() && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KEY_OPEN_PANEL) {
            panel.open();
            return true;
        }
        return false;
    }

    /** Opens the panel if any subtitle option exists. Wired to the subtitle button. */
    public void openPanel() {
        if (selection.hasOptions()) panel.open();
    }

    public void release() {
        ticking = false;
        handler.removeCallbacksAndMessages(null);
        translation.release();
        autoSync.release();
        selection.release();
        removeFromParent(sync.getOverlayView());
        removeFromParent(translation.getIndicatorView());
        removeFromParent(autoSync.getIndicatorView());
        removeFromParent(panel);
    }

    // --- SubtitlePanel.Callbacks (player + sync + selection) ---

    @Override public long currentPositionMs() { return player != null ? player.getCurrentPosition() : 0L; }

    @Override public boolean isPlaying() { return player != null && player.isPlaying(); }

    @Override public void onSyncChanged() { renderOverlay(); }

    @Override public void onSeek(long deltaMs) {
        onSeekTo((player != null ? player.getCurrentPosition() : 0L) + deltaMs);
    }

    @Override public void onSeekTo(long positionMs) {
        if (player == null) return;
        long target = positionMs;
        long duration = player.getDuration();
        if (target < 0) target = 0;
        if (duration > 0 && target > duration) target = duration;
        player.seekTo(target);
    }

    @Override public void onTogglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    @Override public void onSelectOption(String optionId) {
        selection.selectOption(optionId);
    }

    @Override public void onOpenSettings() {
        android.content.Intent i = new android.content.Intent(context, SubtitleSettingsActivity.class);
        if (!(context instanceof android.app.Activity)) {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(i);
    }

    @Override public void onStartTranslate() { translation.start(currentPositionMs()); }

    @Override public void onCancelTranslate() { translation.cancel(); }

    @Override public void onRestoreOriginal() { translation.restoreOriginal(); }

    @Override public void onStartAutoSync(boolean fromHere) {
        if (fromHere) autoSync.startFromHere(player != null ? player.getCurrentPosition() : 0L);
        else autoSync.startFromBeginning(player != null ? player.getDuration() : 0L);
    }

    @Override public void onCancelAutoSync() { autoSync.cancel(); }

    // --- SubtitleSelectionController.Listener ---

    @Override public void onSubtitleLoaded(SubtitleFile file) {
        sync.setSubtitle(file);
        panel.setSyncSession(sync.getSession());
        translation.setSource(file, selection.mediaTitle());
        autoSync.setSource(file, mediaUri);
        renderOverlay();
    }

    @Override public void onSubtitleCleared() {
        sync.clear();
        panel.setSyncSession(sync.getSession());
        translation.setSource(null, null);
        autoSync.setSource(null, mediaUri);
    }

    @Override public void onOptionsChanged(List<SubtitleOption> options, @Nullable String selectedId,
                                           boolean loadingMore) {
        panel.setOptions(options, selectedId, loadingMore);
    }

    // --- render loop ---

    private void startTicking() {
        if (ticking) return;
        ticking = true;
        handler.postDelayed(this::tick, POLL_MS);
    }

    private void tick() {
        if (!ticking) return;
        renderOverlay();
        translation.renderIndicator(panel.isOpen());
        autoSync.renderIndicator(panel.isOpen());
        panel.onTick(currentPositionMs());
        handler.postDelayed(this::tick, POLL_MS);
    }

    private void renderOverlay() {
        sync.render(currentPositionMs(), panel.isOpen());
    }

    private static void removeFromParent(android.view.View v) {
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }
}
