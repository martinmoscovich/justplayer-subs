package com.brouken.player.subs;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import okhttp3.OkHttpClient;
import subtitleengine.core.model.ContentMetadata;
import subtitleengine.core.model.SearchLanguages;
import subtitleengine.core.model.SubtitleError;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.parser.SubtitleConverter;
import subtitleengine.provider.DownloadedSubtitle;
import subtitleengine.provider.OpenSubtitlesProvider;
import subtitleengine.provider.SubtitleProvider;
import subtitleengine.provider.SubtitleSearchResult;

/**
 * The selection concern: builds and owns the list of selectable subtitles (external via intent or a
 * test sidecar + embedded via Media3; provider results are future work), and switches between them.
 * Loading external content is async (URLs can be slow), so options carry a LOADING state.
 *
 * <ul>
 *   <li>External selected → fetch + parse, then {@link Listener#onSubtitleLoaded}; Media3 text off.</li>
 *   <li>Embedded selected → Media3 track override, then {@link Listener#onSubtitleCleared}.</li>
 * </ul>
 */
public class SubtitleSelectionController {

    private static final String TAG = "SubtitleSelection";

    public interface Listener {
        void onSubtitleLoaded(SubtitleFile file);
        void onSubtitleCleared();
        void onOptionsChanged(List<SubtitleOption> options, @Nullable String selectedId, boolean loadingMore);
    }

    private final Context context;
    private final ExoPlayer player;
    private final DefaultTrackSelector trackSelector;
    private final Listener listener;
    private final Handler mainHandler;

    private static final int MAX_PROVIDER_RESULTS = 12;

    private final List<SubtitleOption> externalOptions = new ArrayList<>();
    private final List<SubtitleOption> embeddedOptions = new ArrayList<>();
    private final List<SubtitleOption> providerOptions = new ArrayList<>();
    @Nullable private String selectedId;
    private boolean loadingMore = false; // true while a provider search is in flight
    private boolean providerSearched = false;
    @Nullable private Player.Listener tracksListener;

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
        buildExternalOptions(apiSubs, prefsSubtitleUri, mediaUri);

        if (tracksListener == null) {
            tracksListener = new Player.Listener() {
                @Override
                public void onTracksChanged(@NonNull Tracks tracks) {
                    rebuildEmbeddedOptions(tracks);
                    refresh();
                }
            };
            player.addListener(tracksListener);
        }
        rebuildEmbeddedOptions(player.getCurrentTracks());

        if (!externalOptions.isEmpty()) {
            selectOption(externalOptions.get(0).id);
        } else {
            refresh(); // embedded-only: Media3 renders its default; user can still pick in the panel
        }
        maybeSearchProvider();
    }

    public boolean hasOptions() {
        return !externalOptions.isEmpty() || !embeddedOptions.isEmpty()
                || !providerOptions.isEmpty() || loadingMore;
    }

    public void release() {
        if (tracksListener != null && player != null) {
            player.removeListener(tracksListener);
            tracksListener = null;
        }
    }

    public void selectOption(String id) {
        SubtitleOption opt = findOption(id);
        if (opt == null) return;
        selectedId = id;
        if (opt.source == SubtitleOption.Source.EMBEDDED) {
            selectEmbedded(opt);
        } else if (opt.source == SubtitleOption.Source.PROVIDER) {
            loadProvider(opt);
        } else {
            loadExternal(opt);
        }
    }

    // --- option list ---

    private List<SubtitleOption> allOptions() {
        List<SubtitleOption> all = new ArrayList<>();
        all.addAll(externalOptions);
        all.addAll(providerOptions);
        all.addAll(embeddedOptions);
        return all;
    }

    private void refresh() {
        listener.onOptionsChanged(allOptions(), selectedId, loadingMore);
    }

    @Nullable
    private SubtitleOption findOption(String id) {
        for (SubtitleOption o : externalOptions) if (o.id.equals(id)) return o;
        for (SubtitleOption o : providerOptions) if (o.id.equals(id)) return o;
        for (SubtitleOption o : embeddedOptions) if (o.id.equals(id)) return o;
        return null;
    }

    private void buildExternalOptions(@Nullable List<MediaItem.SubtitleConfiguration> apiSubs,
                                      @Nullable Uri prefsSubtitleUri, @Nullable Uri mediaUri) {
        externalOptions.clear();
        if (apiSubs != null && !apiSubs.isEmpty()) {
            for (int i = 0; i < apiSubs.size(); i++) {
                MediaItem.SubtitleConfiguration c = apiSubs.get(i);
                String lang = c.language;
                String labelText = c.label != null ? c.label : (lang != null ? lang : "External " + (i + 1));
                externalOptions.add(SubtitleOption.external("ext" + i, labelText, lang, c.uri));
            }
            return;
        }
        Uri single = prefsSubtitleUri != null ? prefsSubtitleUri : sidecarCandidate(mediaUri);
        if (single != null) {
            externalOptions.add(SubtitleOption.external("ext0", "External", null, single));
        }
    }

    private void rebuildEmbeddedOptions(@Nullable Tracks tracks) {
        List<SubtitleOption> found = new ArrayList<>();
        if (tracks != null) {
            int ti = 0;
            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_TEXT) continue;
                Format f = g.getTrackFormat(0);
                String lang = f.language;
                String labelText = f.label != null ? f.label : (lang != null ? lang : "Embedded " + (ti + 1));
                found.add(SubtitleOption.embedded("emb" + ti, labelText + " (embedded)", lang, ti));
                ti++;
            }
        }
        // Disabling the text track (when we take over for an external sub) fires onTracksChanged with
        // no text groups; keep the embedded options we already discovered rather than dropping them.
        if (found.isEmpty() && !embeddedOptions.isEmpty()) return;
        embeddedOptions.clear();
        embeddedOptions.addAll(found);
    }

    // --- external ---

    private void loadExternal(SubtitleOption opt) {
        if (opt.uri == null) return;
        opt.state = SubtitleOption.State.LOADING; // URLs can be slow — chip shows a spinner
        refresh();

        new Thread(() -> {
            try {
                String content = readText(context, opt.uri);
                SubtitleFile file = SubtitleConverter.convert(content, "srt", opt.language);
                mainHandler.post(() -> onExternalLoaded(opt, file));
            } catch (Exception e) {
                mainHandler.post(() -> onExternalError(opt, e));
            }
        }, "custom-sub-parse").start();
    }

    private void onExternalLoaded(SubtitleOption opt, SubtitleFile file) {
        opt.state = SubtitleOption.State.READY;
        if (!opt.id.equals(selectedId)) { // user switched away while it loaded
            refresh();
            return;
        }
        disableMedia3TextTrack();
        listener.onSubtitleLoaded(file);
        refresh();
        android.util.Log.i(TAG, "external subtitle active: " + file.getEntries().size() + " cues from " + opt.uri);
    }

    private void onExternalError(SubtitleOption opt, Exception e) {
        opt.state = SubtitleOption.State.ERROR;
        refresh();
        android.util.Log.w(TAG, "failed to load subtitle '" + opt.label + "': " + e.getMessage());
    }

    // --- provider (OpenSubtitles) ---

    private void maybeSearchProvider() {
        if (providerSearched) return;
        String apiKey = SubtitleSettings.getString(context, SubtitleSettings.KEY_OPENSUBTITLES, null);
        if (TextUtils.isEmpty(apiKey)) return; // no key configured → no provider search
        String title = mediaTitle();
        if (TextUtils.isEmpty(title)) return;
        providerSearched = true;

        SearchLanguages langs = searchLanguages();
        loadingMore = true;
        refresh();

        new Thread(() -> {
            SubtitleProvider provider = new OpenSubtitlesProvider(apiKey, new OkHttpClient());
            ContentMetadata meta = new ContentMetadata(title, null, null, null, null, null, null, title);
            try {
                List<SubtitleSearchResult> results = provider.searchByPriority(meta, langs, null);
                mainHandler.post(() -> onProviderResults(results));
            } catch (Exception e) {
                mainHandler.post(() -> onProviderError(e));
            }
        }, "opensubtitles-search").start();
    }

    private void onProviderResults(List<SubtitleSearchResult> results) {
        providerOptions.clear();
        int n = 0;
        for (SubtitleSearchResult r : results) {
            if (n++ >= MAX_PROVIDER_RESULTS) break;
            String lang = r.getLanguage() != null ? r.getLanguage().toUpperCase(Locale.ROOT) : "?";
            String label = lang + " · " + r.getProviderName();
            providerOptions.add(SubtitleOption.provider(
                    "prov" + r.getId(), label, r.getLanguage(), r.getId(), r.getRating(), r.getDownloadCount()));
        }
        loadingMore = false;
        refresh();
        android.util.Log.i(TAG, "OpenSubtitles: " + providerOptions.size() + " results");
    }

    private void onProviderError(Exception e) {
        loadingMore = false;
        refresh();
        android.util.Log.w(TAG, "OpenSubtitles search failed: " + e.getMessage());
    }

    private void loadProvider(SubtitleOption opt) {
        if (opt.providerRef == null) return;
        opt.state = SubtitleOption.State.LOADING;
        refresh();
        String apiKey = SubtitleSettings.getString(context, SubtitleSettings.KEY_OPENSUBTITLES, "");
        new Thread(() -> {
            SubtitleProvider provider = new OpenSubtitlesProvider(apiKey, new OkHttpClient());
            try {
                DownloadedSubtitle dl = provider.download(opt.providerRef);
                SubtitleFile file = SubtitleConverter.convert(dl.getContent(), "srt", opt.language);
                mainHandler.post(() -> onExternalLoaded(opt, file));
            } catch (Exception e) {
                mainHandler.post(() -> onExternalError(opt, e));
            }
        }, "opensubtitles-download").start();
    }

    @Nullable
    private String mediaTitle() {
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
        listener.onSubtitleCleared();
        Tracks tracks = player.getCurrentTracks();
        int ti = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            if (ti == opt.embeddedTextIndex) {
                trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0)));
                break;
            }
            ti++;
        }
        refresh();
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

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
