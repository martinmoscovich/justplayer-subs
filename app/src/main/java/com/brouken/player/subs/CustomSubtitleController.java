package com.brouken.player.subs;

import android.content.Context;
import android.content.SharedPreferences;
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

import subtitleengine.cache.CachedTranslation;
import subtitleengine.cache.SubtitleCache;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.sync.SyncState;

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
    private final SubtitleNoticeView notice;
    private final EmbeddedSubtitleController embedded;
    /**
     * Makes a settings change take effect on the running player instead of only on the next playback:
     * the sync session captured its {@link subtitleengine.sync.SyncSettings} at construction, and the
     * option rows show language-dependent grouping and chips.
     *
     * <p>A prefs listener rather than a hook on returning from {@link SubtitleSettingsActivity},
     * because that screen is not the only writer — the QR setup writes the same prefs from inside the
     * player. Kept in a field because {@code SharedPreferences} holds only a weak reference to its
     * listeners: an inline lambda would be collected and silently stop firing. Fires on whichever
     * thread wrote the pref, hence the hop to the main thread.
     *
     * <p>The translation client is deliberately not refreshed here — it re-reads at the start of each
     * run, so a run in flight can't change model or key halfway (see {@link SettingsTranslationClient}).
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (prefs, key) -> handler.post(this::onSettingsChanged);
    private boolean ticking;
    @Nullable private Uri mediaUri;
    /** The option currently selected, kept so the Translate/Sync screens know what they are acting on. */
    @Nullable private SubtitleOption selectedOption;

    /** Just a name for the option/file/cache-key trio that travels together once a subtitle actually
     *  has cues to show — see {@link #onSubtitleLoaded}. Distinct from {@link #selectedOption}: that
     *  one exists the instant the user picks something, even for an embedded track that has no
     *  {@link SubtitleFile} of its own yet (Media3 renders it natively until Translate/Auto-sync asks
     *  for real cues) — conflating the two was the root of this session's recurring bug. */
    private static final class ActiveSubtitle {
        final SubtitleOption option;
        final SubtitleFile file;
        @Nullable final String cacheKey;

        ActiveSubtitle(SubtitleOption option, SubtitleFile file, @Nullable String cacheKey) {
            this.option = option;
            this.file = file;
            this.cacheKey = cacheKey;
        }
    }

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
        // Follow the player's width so cues wrap instead of running edge to edge. A layout listener
        // rather than a one-shot read: at construction time root has not been measured yet, and the
        // width changes on resize (PiP, rotation, aspect-ratio switches).
        sync.setMaxWidthPx(root.getWidth());
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) sync.setMaxWidthPx(r - l);
        });

        panel = new SubtitlePanel(context);
        panel.setCallbacks(this);
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        embedded = new EmbeddedSubtitleController(context, handler, null);
        embedded.setListener(this::onExtractionStatus);

        translation = new TranslationController(context, sync, panel, embedded, handler, this::renderOverlay,
                this::promoteEmbeddedToOverlay);
        panel.setTranslateChunkBar(translation.getDetailedBarView());
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

        // Top-centre: between the two corner indicators, and far from the cues at the bottom.
        notice = new SubtitleNoticeView(context, handler);
        notice.setListener(this::openTranslatePanel);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        nlp.topMargin = indicatorMargin;
        root.addView(notice, nlp);

        selection = new SubtitleSelectionController(context, player, trackSelector, this, handler);
        selection.setCache(embedded.cache()); // one store for every kind of subtitle
        // After `selection` exists: the hash releases the provider search, which matches on it.
        embedded.setOnHashReady(sizeBytes -> selection.onMediaHash(embedded.videoHash(), sizeBytes));

        SubtitleSettings.prefs(context).registerOnSharedPreferenceChangeListener(settingsListener);
    }

    /** See {@link #settingsListener}. Always on the main thread. */
    private void onSettingsChanged() {
        sync.reloadSettings(context);
        selection.refresh();
    }

    public void onMediaSet(@Nullable Uri mediaUri,
                           @Nullable List<MediaItem.SubtitleConfiguration> apiSubs,
                           @Nullable Uri prefsSubtitleUri) {
        this.mediaUri = mediaUri;
        this.selectedOption = null;
        notice.hide(); // a notice about the previous media must not survive into this one
        embedded.setMedia(mediaUri);
        selection.onMediaSet(mediaUri, apiSubs, prefsSubtitleUri);
        startTicking();
    }

    /**
     * Routes a key: into the open panel, then to the auto-selection notice while it is up (it only
     * takes the navigation keys it needs), then the CC key opens the panel.
     */
    public boolean dispatchKey(KeyEvent event) {
        if (panel.isOpen()) {
            return panel.handleKey(event);
        }
        if (notice.handleKey(event)) {
            return true;
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
        SubtitleSettings.prefs(context).unregisterOnSharedPreferenceChangeListener(settingsListener);
        handler.removeCallbacksAndMessages(null);
        translation.release();
        autoSync.release();
        notice.release();
        embedded.release();
        selection.release();
        removeFromParent(sync.getOverlayView());
        removeFromParent(translation.getIndicatorView());
        removeFromParent(autoSync.getIndicatorView());
        removeFromParent(notice);
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

    /**
     * An embedded track with no cues extracted yet streams them out of the container while chunks
     * translate as they close — {@link TranslationController#start} resolves that itself (see its
     * class javadoc). Everything else already has its {@code SubtitleFile} and starts immediately.
     */
    @Override public void onStartTranslate() {
        translation.start(currentPositionMs(), player != null ? player.getDuration() : 0L);
    }

    @Override public void onCancelTranslate() { translation.cancel(); }

    @Override public void onPauseTranslate() { translation.pause(); }

    @Override public void onResumeTranslate() { translation.resume(); }

    // Discards the cached translation too — "Restore Original" reading as final, not as "hide it for
    // now": leaving a translated chip on screen after explicitly asking for the original back would
    // be misleading, and translateAgain()/re-selecting always pays to translate fresh either way.
    @Override public void onRestoreOriginal() {
        translation.forgetCachedTranslation();
        translation.restoreOriginal();
        selection.refresh(); // the "Translated" chip needs the option list re-evaluated to drop
    }

    @Override public void onTranslateAgain() {
        translation.translateAgain(currentPositionMs(), player != null ? player.getDuration() : 0L);
    }

    @Override public void onRetryMissing() {
        translation.retryMissing(currentPositionMs(), player != null ? player.getDuration() : 0L);
    }

    @Override public void onStartAutoSync(boolean fromHere) {
        if (needsExtraction()) {
            SubtitleOption requested = selectedOption;
            embedded.ensureExtracted(requested, file -> {
                // Same staleness guard as onStartTranslate() — see the comment there.
                if (selectedOption != requested) return;
                adoptExtractedAndAnnounce(file);
                startAutoSync(fromHere);
            });
            return;
        }
        startAutoSync(fromHere);
    }

    private void startAutoSync(boolean fromHere) {
        if (fromHere) autoSync.startFromHere(player != null ? player.getCurrentPosition() : 0L);
        else autoSync.startFromBeginning(player != null ? player.getDuration() : 0L);
    }

    /** True when what is selected is an embedded track we have not read the cues of yet. */
    private boolean needsExtraction() {
        return selectedOption != null
                && selectedOption.source == SubtitleOption.Source.EMBEDDED
                && !selectedOption.imageFormat
                && selectedOption.trackState == SubtitleOption.TrackState.NATIVE;
    }

    /**
     * Promotes a freshly-read embedded track to a first-class subtitle: from here on our overlay
     * draws it and Media3's text track goes off, which is what makes sync and translation apply to
     * it at all (and keeps a single subtitle on screen). Paired with {@link #announceCacheUse()} at
     * every one of its 3 call sites (the opportunistic on-select adopt, and the extract-then-translate/
     * extract-then-sync callbacks) — collapsed here so that pairing can't drift between them.
     */
    private void adoptExtractedAndAnnounce(SubtitleFile file) {
        selection.replaceActiveWithExtracted(file);
        announceCacheUse();
    }

    /**
     * Passed to {@link TranslationController} so it can hand itself the screen the moment a streaming
     * translate's first entries land, instead of requiring extraction to finish first the way the old
     * two-step extract-then-translate flow did. Composes the same two granular steps
     * {@link #onSubtitleLoaded} does for every other source — {@link SubtitleSelectionController#promoteSelectedToOverlay()}
     * (Media3's native track off) and {@link #activateOverlay} (our overlay on) — minus the
     * translation-source wiring {@code onSubtitleLoaded} also does: that wiring is
     * {@code translation.setSource()}, which starts with {@code abandon()} — routing through
     * {@code onSubtitleLoaded} here would cancel the very session whose first chunk just triggered
     * this call. Not wired to {@link #autoSync}: unlike {@code onSubtitleLoaded}'s {@code file}
     * (already complete), {@code current} here keeps growing after this call returns, and auto-sync
     * has no way to be told about that — giving it this one snapshot would just go stale.
     */
    private void promoteEmbeddedToOverlay(SubtitleFile current) {
        selection.promoteSelectedToOverlay();
        activateOverlay(current, embedded.keyFor(selectedOption));
    }

    private void onExtractionStatus(@Nullable String title, float fraction) {
        panel.setExtractionStatus(title, fraction);
    }

    /**
     * Says so when a subtitle came off the cache instead of being read again, and offers the way
     * out. Only when the cache actually changed what would have happened — announcing every hit
     * would fire on most playbacks and stop being read.
     */
    private void announceCacheUse() {
        if (panel.isOpen() || selectedOption == null) return;
        if (!embedded.lastWasFromCache()) return;
        notice.show("Subtitles loaded from cache", "Read again", () -> {
            embedded.forget(selectedOption);
            onStartTranslate();
        });
    }

    // Same reasoning as onCancelTranslate(): onStartAutoSync() can leave an extraction in flight.
    @Override public void onCancelAutoSync() { embedded.cancel(); autoSync.cancel(); }

    /**
     * The Sync screen stopped being shown (see {@link SubtitlePanel.Callbacks#onLeavingSync}) —
     * save or clear the manual-sync state for whatever is selected, per the delay-back-to-0 rule:
     * empty state (no anchors, nudge 0) means "clear it", anything else means "save it", and neither
     * happens if it already matches what's stored (no point rewriting the same bytes every time the
     * user glances at another screen and back).
     */
    @Override public void onLeavingSync() {
        if (selectedOption == null) return;
        String key = embedded.keyFor(selectedOption);
        if (key == null) return;
        SyncState current = sync.getSession().state();
        SubtitleCache cache = embedded.cache();
        if (current.equals(SyncState.empty())) {
            cache.removeSyncState(key); // no-op if nothing was stored
        } else if (!current.equals(cache.getSyncState(key))) {
            cache.putSyncState(key, current);
        }
        selection.refresh(); // "Synced" chip
    }

    // --- SubtitleSelectionController.Listener ---

    @Override public void onSubtitleLoaded(SubtitleFile file, SubtitleOption option) {
        ActiveSubtitle active = new ActiveSubtitle(option, file, embedded.keyFor(option));

        // Set explicitly here, off `active.cacheKey` — deliberately NOT left for onOptionsChanged()'s
        // own translation.setCache(embedded.cache(), embedded.keyFor(selectedOption)) call to handle:
        // for external/provider, onExternalLoaded() fires this callback BEFORE calling refresh() (which
        // is what triggers onOptionsChanged()), so at the point below where a cached translation would
        // be loaded, the session's cache key was still whatever the previously selected option had — a
        // real translation stayed unfound in its own (correct) cache entry because the session was
        // still looking under yesterday's key. Same ordering hazard as the SubtitleFile itself (see the
        // Listener javadoc); same fix, set it from what was just handed in.
        translation.setSource(active.file, selection.mediaTitle());
        translation.setCache(embedded.cache(), active.cacheKey);

        // A cache hit is effectively free — load it instead of the original, same reasoning as
        // adopting a cached extraction on select. Applies to every source (embedded/external/
        // provider) alike, since they all funnel through this same callback once their cues are
        // actually available.
        SubtitleFile toShow = active.file;
        boolean hasCachedTranslation = active.cacheKey != null && embedded.cache().getTranslation(
                active.cacheKey, translation.targetLanguage(), System.currentTimeMillis()) != null;
        if (hasCachedTranslation) {
            // Complete → apply synchronously so the very first frame shown is already the translation
            // (start() would still spin a worker thread just to do this same merge, visible as a flash
            // of the original first — confirmed live). Partial → nothing to show yet beyond the
            // original, so fall back to start(), which resumes translating exactly what's missing.
            SubtitleFile cachedTranslation = translation.loadCompleteFromCache(player != null ? player.getDuration() : 0L);
            if (cachedTranslation != null) {
                toShow = cachedTranslation;
            } else {
                translation.start(currentPositionMs(), player != null ? player.getDuration() : 0L);
            }
        }

        activateOverlay(toShow, active.cacheKey);
        autoSync.setSource(active.file, mediaUri);
    }

    /**
     * Makes {@code file} the visible subtitle: starts (or restarts) the sync session on it, restores
     * any saved manual-sync offset for {@code cacheKey}, and re-renders. Pure view-layer activation —
     * no opinion about where {@code file} came from, whether it is complete or still growing, or
     * whether a translation session is running. {@link #onSubtitleLoaded} composes this with the
     * translation/cache wiring a freshly-loaded external file needs; {@link #promoteEmbeddedToOverlay}
     * composes it with only the Media3 hand-off a streaming translate's first entries need — see that
     * method for why it must NOT also go through {@link #onSubtitleLoaded}.
     */
    private void activateOverlay(SubtitleFile file, @Nullable String cacheKey) {
        SyncState saved = cacheKey != null ? embedded.cache().getSyncState(cacheKey) : null;
        sync.setSubtitle(file);
        if (saved != null) sync.getSession().restoreState(saved);
        panel.setSyncSession(sync.getSession());
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
        SubtitleOption previous = selectedOption;
        selectedOption = null;
        if (selectedId != null) {
            for (SubtitleOption o : options) {
                if (o.id.equals(selectedId)) { selectedOption = o; break; }
            }
        }
        // A running extraction belongs to `previous`, not to whatever just got selected — the
        // onStartTranslate()/onStartAutoSync() staleness guards already stop it from being adopted,
        // but there is no reason to keep spending CPU/decode time on a result nobody will use.
        // translation/autoSync already self-reset on the next onSubtitleLoaded()/onSubtitleCleared()
        // (TranslationSession.setSource() -> abandon()); embedded extraction has no such hook, since
        // selecting a new option doesn't by itself load anything for embedded tracks.
        boolean selectionChanged = previous != selectedOption
                && !(previous != null && selectedOption != null && previous.id.equals(selectedOption.id));
        if (selectionChanged && embedded.isRunning()) embedded.cancel();
        // Must run before the adopt-from-cache block below: a cache hit adopts synchronously, which
        // calls onSubtitleLoaded() before this method returns — and onSubtitleLoaded() reads
        // selectedOption.translated to decide whether to load the translation. Computing it after
        // adopting left that read seeing whatever this SubtitleOption instance's flag happened to
        // still hold from an earlier pass (false on a freshly-rebuilt list) instead of the current
        // cache state — the "translated chip works but the load is inconsistent" bug.
        markCacheStatus(options);
        // A disk cache hit is effectively free — adopt it the moment the track is selected, so Sync
        // has cues to work with right away instead of sitting empty until the user happens to press
        // Translate/Auto-sync (the only thing that otherwise triggers ensureExtracted()).
        if (selectionChanged) {
            SubtitleFile cached = embedded.tryCached(selectedOption);
            if (cached != null) {
                adoptExtractedAndAnnounce(cached);
            }
        }
        translation.setSelectedOption(selectedOption, selection.mediaTitle());
        translation.setCache(embedded.cache(), embedded.keyFor(selectedOption));
        panel.setOptions(options, selectedId, loadingMore);
    }

    /**
     * "Extracted"/"Translated"/"Synced" chips (see {@link SubtitleSelectorView}) — a plain cache
     * lookup per option, not a session-only flag, so a track cached in an earlier session shows the
     * same chip the moment its row is built, before the user has touched anything this run.
     */
    private void markCacheStatus(List<SubtitleOption> options) {
        String targetLanguage = translation.targetLanguage();
        long now = System.currentTimeMillis();
        SubtitleCache cache = embedded.cache();
        for (SubtitleOption o : options) {
            o.extracted = o.source == SubtitleOption.Source.EMBEDDED && embedded.isCached(o);
            String key = embedded.keyFor(o);
            // isComplete(), not just present: a translation interrupted mid-run (app closed, source
            // changed) is also stored so the next run resumes cheaply, but showing "Translated" for
            // that would tell the user something finished that didn't — and selecting it silently
            // hits the API again for whatever chunk is still missing, which is surprising if the chip
            // just said it was already done.
            CachedTranslation translation = key != null ? cache.getTranslation(key, targetLanguage, now) : null;
            o.translated = translation != null && translation.isComplete();
            o.synced = key != null && cache.getSyncState(key) != null;
        }
    }

    /**
     * Announces an automatic pick. A subtitle in a target language is just good news; anything else
     * is a fallback the user may want translated, so it offers the shortcut — but only when
     * translation can actually run (embedded tracks are never parsed, and the LLM needs a key).
     */
    @Override public void onAutoSelected(SubtitleOption option, boolean preferredLanguage) {
        if (panel.isOpen()) return; // the selector already shows what is playing
        String language = LanguageFlags.displayNameFor(option.language);
        if (preferredLanguage) {
            notice.show(language != null ? language + " subtitle found!" : "Subtitle found!", false);
        } else {
            notice.show(language != null ? language + " subtitle selected" : "Subtitle selected",
                    translation.isAvailable());
        }
    }

    private void openTranslatePanel() {
        panel.openTranslate();
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
        translation.renderIndicator(panel.isOpen(), currentPositionMs());
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
