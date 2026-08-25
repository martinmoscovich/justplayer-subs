package com.brouken.player.subs;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.brouken.player.R;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.brouken.player.subs.debug.DebugLog;
import com.brouken.player.subs.debug.SubtitleDebugFormat;

import okhttp3.OkHttpClient;
import subtitleengine.cache.SubtitleCache;
import subtitleengine.core.SubtitlePriorityResolver;
import subtitleengine.core.model.ContentMetadata;
import subtitleengine.core.model.SearchLanguages;
import subtitleengine.core.model.SubtitleError;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.core.model.SubtitleFormat;
import subtitleengine.core.model.SubtitleSource;
import subtitleengine.core.model.SubtitleTrack;
import subtitleengine.provider.OpenSubtitlesProvider;
import subtitleengine.provider.SubtitleProvider;
import subtitleengine.provider.SubtitleSearchResult;
import subtitleengine.selection.AutoSelector;
import subtitleengine.selection.EpisodeTitleParser;
import subtitleengine.selection.SubtitleLabelParser;

/**
 * The selection concern: builds and owns the list of selectable subtitles (external via intent or a
 * test sidecar, embedded via Media3, and provider results), switches between them, and picks one
 * automatically. Loading external content is async (URLs can be slow), so options carry a LOADING
 * state.
 *
 * <ul>
 *   <li>External selected → fetch + parse, then {@link Listener#onSubtitleLoaded}; Media3 text off.</li>
 *   <li>Embedded selected → Media3 track override, then {@link Listener#onSubtitleCleared}.</li>
 * </ul>
 *
 * <p><b>Auto-selection.</b> The decision itself is the engine's ({@link AutoSelector} over
 * {@link SubtitlePriorityResolver}'s 7-level ladder); this class only maps options to engine tracks
 * and back, and decides <em>when</em> to ask. Sources arrive at different times — externals with the
 * intent, embedded on {@code onTracksChanged}, provider results after a network round trip — so it
 * asks again on every change, with the full candidate set. A manual pick locks auto-selection off for
 * the rest of the media, and options that failed to load are excluded from the candidates.
 */
public class SubtitleSelectionController {

    private static final String TAG = "SubtitleSelection";

    /**
     * {@code Format.id} marker the host must set (via {@code SubtitleConfiguration.buildUpon()
     * .setId(...)}) on every external subtitle it feeds into the player's {@code MediaItem}. Media3
     * reports sideloaded subtitles as regular text tracks in {@code player.getCurrentTracks()}
     * alongside genuinely container-embedded ones — indistinguishable by type — so
     * {@link #rebuildEmbeddedOptions} needs this marker to tell "real embedded track" apart from
     * "one of our own external subs echoed back," or every external ends up duplicated as embedded.
     *
     * <p>Media3 rewrites the id we set into {@code "<sourceIndex>:" + ourId} on the {@code Format} it
     * exposes in {@code Tracks} (e.g. {@code "1:ext:content://..."}), so matching must use
     * {@code contains}, not {@code startsWith}.
     */
    public static final String EXTERNAL_TRACK_ID_PREFIX = "ext:";

    public interface Listener {
        /**
         * @param option which option {@code file} belongs to — passed explicitly rather than left for
         *               the caller to infer from its own "currently selected" state, which this fires
         *               ahead of updating (see the three call sites: this interface has no ordering
         *               guarantee relative to {@link #onOptionsChanged}).
         */
        void onSubtitleLoaded(SubtitleFile file, SubtitleOption option);
        void onSubtitleCleared();
        void onOptionsChanged(List<SubtitleOption> options, @Nullable String selectedId, boolean loadingMore);
        /**
         * An automatically picked subtitle just became active (never fired for manual picks).
         *
         * @param preferredLanguage its language is one of the user's target languages — otherwise the
         *                          host should offer translating it.
         */
        void onAutoSelected(SubtitleOption option, boolean preferredLanguage);
    }

    private final Context context;
    private final ExoPlayer player;
    private final DefaultTrackSelector trackSelector;
    private final Listener listener;
    private final Handler mainHandler;

    /** Per language-group cap (target results and source results each get up to this many), not a
     *  cap on the combined list — see {@link SubtitleProvider#searchByPriority}. */
    private static final int MAX_PROVIDER_RESULTS_PER_GROUP = 12;

    /**
     * Remembers the last active option's id per media URI, for the lifetime of the process rather than
     * this controller — mirrors {@code EmbeddedSubtitleController.HASH_CACHE}'s reasoning:
     * {@code PlayerActivity.initializePlayer()} tears down and recreates this whole controller (so
     * selection resets to nothing) on every {@code onStart()}, including just returning from Settings.
     * Without this, coming back required re-picking the subtitle by hand — and worse, a translation in
     * progress on the embedded track that used to be selected had nothing driving it to get reselected
     * at all. See {@link #autoSelectOrRestoreLastActive()}.
     */
    private static final int LAST_ACTIVE_CACHE_CAPACITY = 4;
    private static final Map<String, String> LAST_ACTIVE_OPTION_CACHE =
            new LinkedHashMap<String, String>(LAST_ACTIVE_CACHE_CAPACITY, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > LAST_ACTIVE_CACHE_CAPACITY;
                }
            };

    private final List<SubtitleOption> externalOptions = new ArrayList<>();
    private final List<SubtitleOption> embeddedOptions = new ArrayList<>();
    private final List<SubtitleOption> providerOptions = new ArrayList<>();
    @Nullable private String selectedId;
    private boolean loadingMore = false; // true while a provider search is in flight
    private boolean providerSearched = false;
    @Nullable private Player.Listener tracksListener;
    /** A manual pick switches auto-selection off: the user's choice must not be overridden by a
     *  later provider result that the resolver happens to rank higher. */
    private boolean manuallySelected = false;
    /** Option id auto-selected but not yet active. The notice fires when it actually renders (an
     *  external/provider download can still fail), not when it is chosen. */
    @Nullable private String pendingAutoNoticeId;
    private boolean pendingAutoNoticePreferred;
    /** Only the first preferred-language pick gets a "found!" notice. Candidates keep arriving after
     *  the first one (embedded, then external subs, then provider results), and the resolver keeps
     *  upgrading to a better source in the same language — a real improvement, but not something
     *  worth a second near-identical toast; three "Spanish subtitle found!" popups in a row read as
     *  a bug, not as three separate good-news events. */
    private boolean notifiedPreferredFound = false;
    @Nullable private String mediaHash;
    @Nullable private String mediaBytes;
    @Nullable private SubtitleRetriever externalRetriever;
    @Nullable private SubtitleRetriever providerRetriever;
    @Nullable private Uri mediaUri;

    public SubtitleSelectionController(Context context, ExoPlayer player,
                                       DefaultTrackSelector trackSelector, Listener listener,
                                       Handler mainHandler) {
        this.context = context;
        this.player = player;
        this.trackSelector = trackSelector;
        this.listener = listener;
        this.mainHandler = mainHandler;
    }

    public void onMediaSet(@Nullable Uri mediaUri,
                           @Nullable List<MediaItem.SubtitleConfiguration> apiSubs,
                           @Nullable Uri prefsSubtitleUri) {
        this.mediaUri = mediaUri;
        // Earliest point at which we know a search is coming. The connection takes ~850ms to open
        // and the search cannot start until the media hash is ready (~1.4s later), so this fits
        // entirely inside a window that would otherwise be idle for this host.
        if (!TextUtils.isEmpty(SubtitleSettings.getApiKey(context, SubtitleSettings.KEY_OPENSUBTITLES))) {
            OpenSubtitlesHttp.warmUp();
        }
        manuallySelected = false;
        pendingAutoNoticeId = null;
        notifiedPreferredFound = false;
        selectedId = null;
        mediaHash = null;
        mediaBytes = null;
        buildExternalOptions(apiSubs, prefsSubtitleUri, mediaUri);

        if (tracksListener == null) {
            tracksListener = new Player.Listener() {
                @Override
                public void onTracksChanged(@NonNull Tracks tracks) {
                    // Embedded tracks show up after the media opens, so this is a re-evaluation
                    // trigger, not just a list refresh.
                    rebuildEmbeddedOptions(tracks);
                    refresh();
                    autoSelectOrRestoreLastActive();
                }
            };
            player.addListener(tracksListener);
        }
        rebuildEmbeddedOptions(player.getCurrentTracks());

        refresh();
        autoSelectOrRestoreLastActive();
        // The provider search waits for onMediaHash(): matching on the media's hash finds subtitles
        // for this exact release, where a title match cannot tell two of them apart. The hash costs
        // 128 KB and about a second, which is worth it — and onMediaHash() always arrives, with null
        // when the media has no stable identity, so the search is never lost.
    }

    /**
     * The media's OpenSubtitles hash and size, or nulls when it has none. Arrives once per media and
     * releases the provider search.
     */
    public void onMediaHash(@Nullable String hash, @Nullable String sizeBytes) {
        this.mediaHash = hash;
        this.mediaBytes = sizeBytes;
        maybeSearchProvider();
    }

    /**
     * Shares the host's cache so a subtitle downloaded once is not downloaded again, and builds the
     * two {@link SubtitleRetriever}s (external/provider) around it — see {@link DownloadSubtitleRetriever}
     * for why one class covers both; the only thing that differs is how each gets its raw text.
     */
    public void setCache(@Nullable SubtitleCache cache) {
        externalRetriever = new DownloadSubtitleRetriever(cache, mainHandler,
                opt -> readText(context, opt.uri));
        providerRetriever = new DownloadSubtitleRetriever(cache, mainHandler, this::downloadProviderContent);
    }

    private String downloadProviderContent(SubtitleOption opt) throws SubtitleError.ProviderError {
        String apiKey = SubtitleSettings.getApiKey(context, SubtitleSettings.KEY_OPENSUBTITLES);
        SubtitleProvider provider = new OpenSubtitlesProvider(apiKey, OpenSubtitlesHttp.client());
        return provider.download(opt.providerRef).getContent();
    }

    public boolean hasOptions() {
        // `providerSearched` on purpose: once a provider search has been attempted, the panel stays
        // reachable even if it found nothing or failed. Otherwise a media file with no sidecar and
        // no embedded track locks the user out of the very screen that searches for subtitles — and
        // out of the message explaining why there are none.
        return !externalOptions.isEmpty() || !embeddedOptions.isEmpty()
                || !providerOptions.isEmpty() || loadingMore || providerSearched;
    }

    public void release() {
        if (tracksListener != null && player != null) {
            player.removeListener(tracksListener);
            tracksListener = null;
        }
    }

    /** A user pick from the panel — final: it turns auto-selection off for this media. */
    public void selectOption(String id) {
        manuallySelected = true;
        pendingAutoNoticeId = null;
        SubtitleOption opt = findOption(id);
        // Logged like the automatic path: without this, the only decision that leaves no trace is
        // the one the user made, which is exactly the one you need when reproducing a report.
        android.util.Log.i(TAG, "manually selected " + (opt != null
                ? "'" + opt.label + "' (" + opt.source + ", lang=" + opt.language + ")" : id));
        DebugLog.log(DebugLog.CAT_SUBS, () -> "manual pick: "
                + (opt != null ? SubtitleDebugFormat.option(opt) : id));
        applySelection(id);
    }

    private void applySelection(String id) {
        SubtitleOption opt = findOption(id);
        if (opt == null) return;
        selectedId = id;
        if (mediaUri != null) LAST_ACTIVE_OPTION_CACHE.put(mediaUri.toString(), id);
        if (opt.source == SubtitleOption.Source.EMBEDDED) {
            selectEmbedded(opt);
        } else if (opt.source == SubtitleOption.Source.PROVIDER) {
            loadProvider(opt);
        } else {
            loadExternal(opt);
        }
    }

    /**
     * Hands rendering of the selected embedded track over to our overlay, now that its cues have
     * been read out of the container. The option stays exactly where it is in the list — from the
     * user's point of view nothing was selected, it just became syncable and translatable — but
     * Media3's text track goes off, so only one subtitle is ever on screen.
     */
    public void replaceActiveWithExtracted(SubtitleFile file) {
        if (selectedId == null) return;
        SubtitleOption opt = findOption(selectedId);
        if (opt == null) return;
        promoteSelectedToOverlay();
        listener.onSubtitleLoaded(file, opt);
        android.util.Log.i(TAG, "embedded track promoted to overlay: " + file.getEntries().size() + " cues");
        DebugLog.log(DebugLog.CAT_SUBS, () -> "embedded track promoted to our overlay: "
                + file.getEntries().size() + " cues · " + SubtitleDebugFormat.option(opt));
    }

    /**
     * Turns off Media3's native rendering for the currently-selected embedded track, without
     * re-announcing it through {@link Listener#onSubtitleLoaded} — for a translate run that is
     * already streaming content into the overlay itself (see {@code TranslationController}'s
     * promotion callback). Routing that case through {@link #replaceActiveWithExtracted} instead
     * would call back into {@code translation.setSource()}, which starts with {@code abandon()} —
     * cancelling the very session whose first chunk just triggered this call.
     * {@link #replaceActiveWithExtracted} composes this with the {@code onSubtitleLoaded}
     * announcement for the on-select/Sync path, where that announcement is exactly what's wanted.
     */
    public void promoteSelectedToOverlay() {
        if (selectedId == null) return;
        SubtitleOption opt = findOption(selectedId);
        if (opt == null) return;
        opt.trackState = SubtitleOption.TrackState.EXTRACTED;
        disableMedia3TextTrack();
        refresh();
        android.util.Log.i(TAG, "Media3 native text track disabled for '" + opt.label + "'");
    }

    // --- auto-selection (engine decides; this only maps to and from its model) ---

    /**
     * Tries {@link #LAST_ACTIVE_OPTION_CACHE} first, falling back to {@link #autoSelect()}'s normal
     * ranking when there's nothing remembered for this media or it isn't among the candidates yet
     * (embedded tracks, external subs, and provider results all arrive at different times — this runs
     * again at each of those points, same as {@link #autoSelect()} already did, so a remembered
     * provider-sourced pick still gets a chance once its search actually comes back). A successful
     * restore is treated exactly like a manual pick — {@link #manuallySelected} locks it in — since
     * from the user's point of view it already was their choice; this is just picking it back up after
     * the controller got torn down and recreated ({@code PlayerActivity.initializePlayer()}, e.g. on
     * returning from Settings), not proposing something new to rank.
     */
    private void autoSelectOrRestoreLastActive() {
        if (manuallySelected) return;
        String rememberedId = mediaUri != null ? LAST_ACTIVE_OPTION_CACHE.get(mediaUri.toString()) : null;
        if (rememberedId != null && !rememberedId.equals(selectedId)) {
            SubtitleOption remembered = findOption(rememberedId);
            if (remembered != null && isCandidate(remembered)) {
                manuallySelected = true;
                android.util.Log.i(TAG, "restored last-active selection '" + remembered.label + "' ("
                        + remembered.source + ", lang=" + remembered.language + ")");
                DebugLog.log(DebugLog.CAT_SUBS, () -> "restored last-active: "
                        + SubtitleDebugFormat.option(remembered));
                applySelection(rememberedId);
                return;
            }
        }
        autoSelect();
    }

    /**
     * Re-ranks every known option and switches if the winner is not what's playing. Called whenever
     * the candidate set changes: media set, embedded tracks discovered, provider results in, or an
     * option failed to load (which takes it out of the running).
     */
    private void autoSelect() {
        if (manuallySelected) return;
        if (!SubtitleSettings.autoSelectEnabled(context)) return;

        List<SubtitleTrack> embedded = new ArrayList<>();
        List<SubtitleTrack> external = new ArrayList<>();
        for (SubtitleOption o : embeddedOptions) if (isCandidate(o)) embedded.add(toTrack(o));
        for (SubtitleOption o : externalOptions) if (isCandidate(o)) external.add(toTrack(o));
        for (SubtitleOption o : providerOptions) if (isCandidate(o)) external.add(toTrack(o));

        AutoSelector.Decision decision = AutoSelector.decide(
                embedded, external, selectedId, targetLanguages(), goodOriginLanguages());
        if (decision == null) return; // nothing better than what is already playing

        SubtitleOption winner = findOption(decision.track.getId());
        if (winner == null) return;
        android.util.Log.i(TAG, "auto-selected '" + winner.label + "' (" + winner.source + ", lang="
                + winner.language + ", preferred=" + decision.preferredLanguage + ") over "
                + (selectedId != null ? selectedId : "nothing"));
        String previouslySelected = selectedId;
        DebugLog.log(DebugLog.CAT_SUBS, () -> "auto-selected (preferredLanguage="
                + decision.preferredLanguage + ") over " + (previouslySelected != null ? previouslySelected : "nothing")
                + ": " + SubtitleDebugFormat.option(winner));
        // Still auto-selects the better source — only the repeat notice is suppressed.
        if (!(decision.preferredLanguage && notifiedPreferredFound)) {
            pendingAutoNoticeId = winner.id;
            pendingAutoNoticePreferred = decision.preferredLanguage;
        }
        applySelection(winner.id);
    }

    /** A failed download must not keep winning the ladder on every re-evaluation. */
    private static boolean isCandidate(SubtitleOption o) {
        return o.state != SubtitleOption.State.ERROR;
    }

    private static SubtitleTrack toTrack(SubtitleOption o) {
        boolean embedded = o.source == SubtitleOption.Source.EMBEDDED;
        return new SubtitleTrack(
                o.id, o.language,
                o.imageFormat ? SubtitleFormat.IMAGE : SubtitleFormat.TEXT,
                embedded ? SubtitleSource.EMBEDDED : SubtitleSource.EXTERNAL,
                null,
                o.rating > 0 ? Float.valueOf(o.rating) : null,
                o.downloadCount > 0 ? Integer.valueOf(o.downloadCount) : null,
                null, null);
    }

    /** Languages the user wants to read, in priority order; device language when nothing is set. */
    private List<String> targetLanguages() {
        List<String> targets = SubtitleSettings.preferredLanguages(context).getTargets();
        if (!targets.isEmpty()) return targets;
        String dev = Locale.getDefault().getLanguage();
        return List.of(TextUtils.isEmpty(dev) ? "en" : dev);
    }

    /**
     * Languages worth showing (and translating from) when no target-language subtitle exists: the
     * user's configured source languages, or the engine's default list when unset.
     */
    private List<String> goodOriginLanguages() {
        List<String> sources = SubtitleSettings.preferredLanguages(context).getSources();
        return sources.isEmpty() ? SubtitlePriorityResolver.DEFAULT_GOOD_ORIGIN : sources;
    }

    /** Fires the auto-selection notice once the pick is actually on screen. */
    private void notifyAutoSelected(SubtitleOption opt) {
        if (pendingAutoNoticeId == null || !pendingAutoNoticeId.equals(opt.id)) return;
        pendingAutoNoticeId = null;
        if (pendingAutoNoticePreferred) notifiedPreferredFound = true;
        listener.onAutoSelected(opt, pendingAutoNoticePreferred);
    }

    // --- option list ---

    private List<SubtitleOption> allOptions() {
        List<SubtitleOption> all = new ArrayList<>();
        all.addAll(externalOptions);
        all.addAll(providerOptions);
        all.addAll(embeddedOptions);
        return all;
    }

    /** Re-pushes the option list as-is — e.g. after something outside this class (a translation
     *  cache entry being written or forgotten) changed what a chip should show. */
    public void refresh() {
        listener.onOptionsChanged(allOptions(), selectedId, loadingMore);
    }

    @Nullable
    private SubtitleOption findOption(String id) {
        for (SubtitleOption o : externalOptions) if (o.id.equals(id)) return o;
        for (SubtitleOption o : providerOptions) if (o.id.equals(id)) return o;
        for (SubtitleOption o : embeddedOptions) if (o.id.equals(id)) return o;
        return null;
    }

    /**
     * @param apiSubs what the launching app supplied: {@code null} when there was no launcher at all
     *                (opened from the file browser), an <em>empty list</em> when a launcher was there
     *                and supplied none. The difference decides whether {@link #sidecarCandidate} may
     *                guess — see the branch below.
     */
    private void buildExternalOptions(@Nullable List<MediaItem.SubtitleConfiguration> apiSubs,
                                      @Nullable Uri prefsSubtitleUri, @Nullable Uri mediaUri) {
        externalOptions.clear();
        if (apiSubs != null && apiSubs.isEmpty() && prefsSubtitleUri == null) {
            // A launcher that sent no subtitles has said something, and guessing over the top of it is
            // wrong. It is also expensive: the guess appends ".srt" to the media URL, and a debrid
            // proxy ignores the path and answers with the stream — so every playback started a
            // speculative download of the video itself, which is how a 22 GB file crashed the app one
            // second in (OutOfMemoryError, see readAll's ceiling). Harmless while Nuvio always sent a
            // list; the moment it sent none, this fired on every playback.
            return;
        }
        if (apiSubs != null && !apiSubs.isEmpty()) {
            String[] labels = new String[apiSubs.size()];
            String[] formats = new String[apiSubs.size()];
            Map<String, Integer> labelCounts = new HashMap<>();
            for (int i = 0; i < apiSubs.size(); i++) {
                MediaItem.SubtitleConfiguration c = apiSubs.get(i);
                labels[i] = externalDisplayLabel(c, i);
                formats[i] = subtitleFormatLabel(c.mimeType);
                labelCounts.merge(labels[i], 1, Integer::sum);
            }
            for (int i = 0; i < apiSubs.size(); i++) {
                MediaItem.SubtitleConfiguration c = apiSubs.get(i);
                // Only disambiguate when the label repeats (e.g. a provider offering the same
                // language as both .srt and .vtt) — a single result never needs its format called out.
                String format = labelCounts.get(labels[i]) > 1 ? formats[i] : null;
                externalOptions.add(SubtitleOption.external("ext" + i, labels[i], c.language, c.uri, format));
            }
            return;
        }
        Uri single = prefsSubtitleUri != null ? prefsSubtitleUri : sidecarCandidate(mediaUri);
        if (single != null) {
            externalOptions.add(SubtitleOption.external("ext0", "External", null, single, null));
        }
    }

    /** "ES - OpenSubtitles": same {@code <CODE> - <Provider>} convention the PROVIDER source already
     *  uses (see {@link #onProviderResults}), instead of Nuvio's raw free-text label ("Spanish -
     *  OpenSubtitles v3"). Re-parses the label with {@link SubtitleLabelParser} rather than reusing
     *  {@code ExternalSubtitleExtras.Resolved} — this is a display-formatting choice specific to this
     *  UI, not launcher-contract logic, so it stays out of the engine (unlike the parsing itself). Falls
     *  back to the raw label, then the language code alone, when either half can't be identified.
     */
    private static String externalDisplayLabel(MediaItem.SubtitleConfiguration c, int index) {
        SubtitleLabelParser.Parsed parsed = SubtitleLabelParser.parse(c.label);
        String lang = c.language != null ? c.language : parsed.languageCode;
        if (lang != null && parsed.provider != null) {
            return lang.toUpperCase(Locale.ROOT) + " - " + parsed.provider;
        }
        if (c.label != null) return c.label;
        if (lang != null) return lang;
        return "External " + (index + 1);
    }

    /** Short display tag for the format chip — derived from the same mimeType SubtitleUtils.buildSubtitle()
     *  already computes from the URI extension (reliable; unlike Nuvio's {@code filename} extra, which
     *  we've seen say ".srt" even when the URI itself ends in ".vtt"). */
    @Nullable
    private static String subtitleFormatLabel(@Nullable String mimeType) {
        if (mimeType == null) return null;
        if (mimeType.equals(MimeTypes.TEXT_VTT)) return "VTT";
        if (mimeType.equals(MimeTypes.TEXT_SSA)) return "SSA";
        if (mimeType.equals(MimeTypes.APPLICATION_TTML)) return "TTML";
        if (mimeType.equals(MimeTypes.APPLICATION_SUBRIP)) return "SRT";
        return null;
    }

    private void rebuildEmbeddedOptions(@Nullable Tracks tracks) {
        List<SubtitleOption> found = new ArrayList<>();
        if (tracks != null) {
            int ti = 0;
            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_TEXT) continue;
                Format f = g.getTrackFormat(0);
                // Not a real embedded track — one of our own external subs, echoed back by Media3.
                if (f.id != null && f.id.contains(EXTERNAL_TRACK_ID_PREFIX)) continue;
                String lang = f.language;
                String labelText = f.label != null ? f.label : (lang != null ? lang : "Embedded " + (ti + 1));
                found.add(SubtitleOption.embedded("emb" + ti, labelText + " (embedded)", lang, ti,
                        isImageSubtitle(f.sampleMimeType)));
                ti++;
            }
        }
        // Disabling the text track (when we take over for an external sub) fires onTracksChanged with
        // no text groups; keep the embedded options we already discovered rather than dropping them.
        if (found.isEmpty() && !embeddedOptions.isEmpty()) return;
        embeddedOptions.clear();
        embeddedOptions.addAll(found);
    }

    /** Bitmap subtitle formats — displayable by Media3, but never parseable into cues. */
    private static boolean isImageSubtitle(@Nullable String sampleMimeType) {
        return MimeTypes.APPLICATION_PGS.equals(sampleMimeType)
                || MimeTypes.APPLICATION_VOBSUB.equals(sampleMimeType)
                || MimeTypes.APPLICATION_DVBSUBS.equals(sampleMimeType);
    }

    // --- external ---

    private void loadExternal(SubtitleOption opt) {
        if (opt.uri == null || externalRetriever == null) return;
        acquire(opt, externalRetriever);
    }

    /**
     * Cache-first acquisition shared by external and provider (see {@link SubtitleRetriever}): a
     * cache hit delivers synchronously — there is no network involved, so a spinner would flash for
     * nothing — a miss marks the option LOADING and does the real fetch.
     */
    private void acquire(SubtitleOption opt, SubtitleRetriever retriever) {
        retriever.retrieve(opt,
                () -> { opt.state = SubtitleOption.State.LOADING; refresh(); },
                new SubtitleRetriever.Callback() {
                    @Override public void onLoaded(SubtitleFile file, boolean fromCache) {
                        opt.fromCache = fromCache;
                        if (fromCache) {
                            android.util.Log.i(TAG, "cache hit for '" + opt.label + "': "
                                    + file.getEntries().size() + " cues");
                            DebugLog.log(DebugLog.CAT_SUBS, () -> "cache hit " + SubtitleDebugFormat.origin(opt)
                                    + ": " + file.getEntries().size() + " cues");
                        }
                        onExternalLoaded(opt, file);
                    }
                    @Override public void onError(Exception e) {
                        onExternalError(opt, e);
                    }
                });
    }

    private void onExternalLoaded(SubtitleOption opt, SubtitleFile file) {
        opt.state = SubtitleOption.State.READY;
        if (!opt.id.equals(selectedId)) { // user switched away while it loaded
            refresh();
            return;
        }
        disableMedia3TextTrack();
        listener.onSubtitleLoaded(file, opt);
        refresh();
        // opt.uri is null for provider results — log the label instead of a bare "null".
        android.util.Log.i(TAG, "subtitle active: " + file.getEntries().size() + " cues from "
                + (opt.uri != null ? opt.uri : opt.label) + (opt.fromCache ? " (cached)" : ""));
        DebugLog.log(DebugLog.CAT_SUBS, () -> "active: " + file.getEntries().size() + " cues · "
                + SubtitleDebugFormat.option(opt));
        // After onSubtitleLoaded: the host decides whether to offer Translate, and that answer
        // depends on the source it was just given.
        notifyAutoSelected(opt);
    }

    private void onExternalError(SubtitleOption opt, Exception e) {
        opt.state = SubtitleOption.State.ERROR;
        if (opt.id.equals(pendingAutoNoticeId)) pendingAutoNoticeId = null;
        refresh();
        // Log the throwable: the message alone drops the cause, and the row only renders a "⚠".
        android.util.Log.w(TAG, "failed to load subtitle '" + opt.label + "'", e);
        DebugLog.log(DebugLog.CAT_SUBS, () -> "load FAILED " + SubtitleDebugFormat.option(opt) + " — " + e);
        // The failed option is out of the running now — let the runner-up take over. A manual pick
        // stays selected even after failing, so the panel keeps showing which row is the broken one.
        if (!manuallySelected && opt.id.equals(selectedId)) {
            selectedId = null;
            autoSelect();
        }
    }

    // --- provider (OpenSubtitles) ---

    private void maybeSearchProvider() {
        if (providerSearched) return;
        String apiKey = SubtitleSettings.getApiKey(context, SubtitleSettings.KEY_OPENSUBTITLES);
        if (TextUtils.isEmpty(apiKey)) return; // no key configured → no provider search
        String title = mediaTitle();
        if (TextUtils.isEmpty(title)) return;
        providerSearched = true;

        SearchLanguages langs = searchLanguages();
        loadingMore = true;
        refresh();
        DebugLog.log(DebugLog.CAT_SUBS, "OpenSubtitles search: title='" + title + "' langs=" + langs
                + " hash=" + mediaHash + " size=" + mediaBytes);

        new Thread(() -> {
            SubtitleProvider provider = new OpenSubtitlesProvider(apiKey, OpenSubtitlesHttp.client());
            // Nuvio's episode title shape is "<Show> - S01E04" with no separate season/episode
            // extra; pulling them out lets the query use OpenSubtitles' structured params instead of
            // relying on its free-text search tolerating the suffix.
            EpisodeTitleParser.Parsed parsed = EpisodeTitleParser.parse(title);
            ContentMetadata meta = new ContentMetadata(parsed.title, null, parsed.season, parsed.episode,
                    null, null, null, title, mediaHash, mediaBytes);
            try {
                List<SubtitleSearchResult> results =
                        provider.searchByPriority(meta, langs, null, MAX_PROVIDER_RESULTS_PER_GROUP);
                mainHandler.post(() -> onProviderResults(results));
            } catch (Exception e) {
                mainHandler.post(() -> onProviderError(e));
            }
        }, "opensubtitles-search").start();
    }

    private void onProviderResults(List<SubtitleSearchResult> results) {
        providerOptions.clear();
        // Already bounded per group (target/source) by searchByPriority — no further cap needed here.
        for (SubtitleSearchResult r : results) {
            String lang = r.getLanguage() != null ? r.getLanguage().toUpperCase(Locale.ROOT) : "?";
            String label = lang + " · " + r.getProviderName();
            providerOptions.add(SubtitleOption.provider(
                    "prov" + r.getId(), label, r.getLanguage(), r.getId(), r.getRating(), r.getDownloadCount(),
                    r.getMatchStrategy()));
        }
        loadingMore = false;
        refresh();
        android.util.Log.i(TAG, "OpenSubtitles: " + providerOptions.size() + " results");
        DebugLog.log(DebugLog.CAT_SUBS, "OpenSubtitles: " + providerOptions.size() + " results");
        // New candidates: a target-language result can outrank whatever is playing right now — or, if
        // nothing has been selected yet, be the remembered last-active option finally showing up.
        autoSelectOrRestoreLastActive();
    }

    private void onProviderError(Exception e) {
        loadingMore = false;
        refresh();
        // Throwable, not e.getMessage(): the message alone drops the cause and the stack trace, and
        // this failure is otherwise invisible — the list just renders as empty.
        android.util.Log.w(TAG, "OpenSubtitles search failed", e);
        DebugLog.log(DebugLog.CAT_SUBS, "OpenSubtitles search FAILED — " + e);
        // Called on the main thread (posted from the search worker), so the Toast is safe here.
        Toast.makeText(context,
                context.getString(R.string.subtitle_provider_search_failed),
                Toast.LENGTH_LONG).show();
    }

    private void loadProvider(SubtitleOption opt) {
        if (opt.providerRef == null || providerRetriever == null) return;
        acquire(opt, providerRetriever);
    }

    @Nullable
    String mediaTitle() {
        if (player == null) return null;
        MediaMetadata md = player.getMediaMetadata();
        CharSequence t = md != null ? (md.title != null ? md.title : md.displayTitle) : null;
        return t != null ? t.toString() : null;
    }

    private SearchLanguages searchLanguages() {
        // Search the languages the user wants, in priority order (target first, then source).
        SearchLanguages langs = SubtitleSettings.preferredLanguages(context).withoutSuffix();
        if (langs.isEmpty()) { // sensible default when nothing configured
            List<String> targets = new ArrayList<>();
            String dev = Locale.getDefault().getLanguage();
            targets.add(TextUtils.isEmpty(dev) ? "en" : dev);
            langs = new SearchLanguages(targets, List.of());
        }
        return langs;
    }

    // --- embedded (hand back to Media3) ---

    private void selectEmbedded(SubtitleOption opt) {
        // Re-selecting an already-promoted track hands it back to Media3's native renderer (see
        // onSubtitleCleared() below) — reset here so needsExtraction() sees it as NATIVE again, not
        // stuck EXTRACTED from a previous selection of the same option.
        opt.trackState = SubtitleOption.TrackState.NATIVE;
        listener.onSubtitleCleared();
        Tracks tracks = player.getCurrentTracks();
        int ti = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            // Must skip the same entries rebuildEmbeddedOptions() does, or embeddedTextIndex points
            // at the wrong track whenever an external sub sits before/between real embedded ones.
            if (g.getTrackFormat(0).id != null && g.getTrackFormat(0).id.contains(EXTERNAL_TRACK_ID_PREFIX)) continue;
            if (ti == opt.embeddedTextIndex) {
                trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0)));
                break;
            }
            ti++;
        }
        refresh();
        // Media3 renders it directly — it is on screen as soon as the override lands.
        notifyAutoSelected(opt);
    }

    private void disableMedia3TextTrack() {
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));
    }

    // --- helpers ---

    /**
     * {@code <video>.srt} sidecar next to {@code mediaUri}. <b>Testing hook, not the Nuvio flow</b>
     * (production subtitles arrive via the intent); lets us inject a subtitle on the emulator where
     * adb cannot pass the {@code Uri[]} extra. Revisit in Fase 2.
     */
    @Nullable
    private static Uri sidecarCandidate(@Nullable Uri mediaUri) {
        if (mediaUri == null) return null;
        String s = mediaUri.toString();
        int dot = s.lastIndexOf('.');
        int slash = s.lastIndexOf('/');
        if (dot <= slash) return null;
        return Uri.parse(s.substring(0, dot) + ".srt");
    }

    private static String readText(Context ctx, Uri uri) throws Exception {
        String scheme = uri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            } finally {
                conn.disconnect();
            }
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("cannot open " + uri);
            return readAll(in);
        }
    }

    /**
     * Hard ceiling on what will be pulled into memory for something claiming to be a subtitle. Real
     * ones are tiny — the largest seen in this project is 125 KB, and a dense two-hour film runs about
     * 60 KB — so 4 MB is roughly thirty times the worst realistic case and still nothing on a heap.
     *
     * <p>There was no limit at all until a launcher handed over a subtitle URL that served something
     * else entirely: the read grew to a quarter of a gigabyte and died with an
     * {@code OutOfMemoryError}, which — being an {@link Error}, not an {@link Exception} — went
     * straight past the download thread's catch and killed the process a second into playback. A URL
     * from outside the app is not a promise about what is behind it.
     */
    private static final int MAX_SUBTITLE_BYTES = 4 * 1024 * 1024;

    // Package-visible for its regression test: the cap is a crash fix, and an off-by-one in it would
    // bring back an OutOfMemoryError that kills the process rather than failing one subtitle.
    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > MAX_SUBTITLE_BYTES) {
                // Named for what it is, so the row's error and the log say "this wasn't a subtitle"
                // rather than something about memory.
                throw new IOException("Not a subtitle: the file is larger than "
                        + (MAX_SUBTITLE_BYTES / (1024 * 1024)) + " MB");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
