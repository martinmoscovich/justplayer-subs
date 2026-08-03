package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.parser.SubtitleConverter;
import subtitleengine.sync.AnchorSource;
import subtitleengine.sync.SyncAnchor;
import subtitleengine.sync.SyncResolver;
import subtitleengine.sync.SyncState;

/**
 * Renders an <b>external</b> subtitle in our own overlay (not Media3's {@code SubtitleView}),
 * applying a runtime-adjustable {@link SyncState} offset. This is the single source of subtitle
 * rendering while active: it disables Media3's text track so the same cues are not drawn twice
 * (see SPEC §6.2).
 *
 * <p>All new logic lives here; {@code PlayerActivity} only creates this, calls
 * {@link #onMediaSet} once the media/subs are known, and {@link #release()} on teardown.
 *
 * <p>Slice 1 (Fase 3): parse + render + offset. The sync UI (anchors, nudge, D-pad) builds on top
 * of the {@link SyncState} exposed here.
 */
public class CustomSubtitleController implements SyncPanel.Callbacks {

    private static final String TAG = "CustomSubtitleController";
    private static final long POLL_MS = 100;
    /** Key that opens the manual-sync panel (CC/subtitles remote key). */
    private static final int KEY_OPEN_PANEL = KeyEvent.KEYCODE_CAPTIONS;

    private final Context context;
    private final ExoPlayer player;
    private final DefaultTrackSelector trackSelector;
    private final TextView overlay;
    private final SyncPanel syncPanel;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable private SubtitleFile subtitleFile;
    private SyncState syncState = SyncState.empty();
    private boolean active = false;

    public CustomSubtitleController(Context context, ViewGroup root, ExoPlayer player,
                                    DefaultTrackSelector trackSelector) {
        this.context = context;
        this.player = player;
        this.trackSelector = trackSelector;

        overlay = new TextView(context);
        overlay.setTextColor(Color.WHITE);
        overlay.setShadowLayer(6f, 0f, 0f, Color.BLACK);
        overlay.setGravity(Gravity.CENTER);
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        overlay.setVisibility(TextView.GONE);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(48);
        root.addView(overlay, lp);

        syncPanel = new SyncPanel(context);
        syncPanel.setCallbacks(this);
        root.addView(syncPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Routes a key event. Opens the sync panel on the CC key (when a subtitle is active), or hands
     * the key to the panel while it is open. Returns true if consumed. Called from
     * {@code PlayerActivity.dispatchKeyEvent}.
     */
    public boolean dispatchKey(KeyEvent event) {
        if (syncPanel.isOpen()) {
            return syncPanel.handleKey(event);
        }
        if (active && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KEY_OPEN_PANEL) {
            syncPanel.open();
            return true;
        }
        return false;
    }

    // --- SyncPanel.Callbacks ---

    @Override
    public long currentPositionMs() {
        return player != null ? player.getCurrentPosition() : 0L;
    }

    @Override
    public SyncState state() {
        return syncState;
    }

    @Override
    public void onAnchor(int cueIndex, long cueStartMs, long videoPositionMs) {
        syncState = syncState.withAnchorAdded(
                new SyncAnchor(cueIndex, cueStartMs, videoPositionMs, AnchorSource.MANUAL));
        render();
    }

    @Override
    public void onNudge(long deltaMs) {
        syncState = syncState.withNudgeShiftedBy(deltaMs);
        render();
    }

    @Override
    public void onSeek(long deltaMs) {
        if (player == null) return;
        long target = player.getCurrentPosition() + deltaMs;
        long duration = player.getDuration();
        if (target < 0) target = 0;
        if (duration > 0 && target > duration) target = duration;
        player.seekTo(target);
    }

    /** Opens the manual-sync panel if an external subtitle is active. Wired to the subtitle button. */
    public void openPanel() {
        if (active) syncPanel.open();
    }

    /**
     * Called once the media and its subtitles are known. Priority for the external subtitle we
     * take over:
     * <ol>
     *   <li>a subtitle passed via intent (the Nuvio path: {@code subs} / {@code subtitle_uri}),</li>
     *   <li>otherwise, for local media, a sidecar {@code <video>.srt} next to the file.</li>
     * </ol>
     * If none is found we stay inactive and Media3 renders embedded tracks as before.
     */
    public void onMediaSet(@Nullable Uri mediaUri,
                           @Nullable List<MediaItem.SubtitleConfiguration> apiSubs,
                           @Nullable Uri prefsSubtitleUri) {
        Uri external = firstExternalUri(apiSubs, prefsSubtitleUri);
        if (external == null) {
            external = sidecarCandidate(mediaUri);
        }
        if (external != null) {
            parseAsync(external); // parseAsync fails gracefully (stays inactive) if unreadable
        }
    }

    /** Replaces the current sync state and refreshes the shown cue immediately. */
    public void setSyncState(SyncState state) {
        this.syncState = state != null ? state : SyncState.empty();
        if (active) render();
    }

    public SyncState getSyncState() {
        return syncState;
    }

    public void release() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        if (overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        if (syncPanel.getParent() instanceof ViewGroup) {
            ((ViewGroup) syncPanel.getParent()).removeView(syncPanel);
        }
    }

    // --- internals ---

    @Nullable
    private static Uri firstExternalUri(@Nullable List<MediaItem.SubtitleConfiguration> apiSubs,
                                        @Nullable Uri prefsSubtitleUri) {
        if (apiSubs != null && !apiSubs.isEmpty()) {
            return apiSubs.get(0).uri;
        }
        return prefsSubtitleUri;
    }

    /**
     * Builds a {@code <video>.srt} sidecar URI next to {@code mediaUri} by swapping the extension.
     * Returns {@code null} when the URI has no extension. The candidate may not exist; the caller's
     * read attempt handles that. (Non-file URIs — e.g. MediaStore {@code content://} — produce a
     * bogus candidate that simply fails to open, which is fine.)
     *
     * <p><b>NOTE — testing hook, not the Nuvio flow.</b> In production the external subtitle always
     * arrives via the intent ({@code subs} / {@code subtitle_uri}). This sidecar fallback exists so
     * we can inject a subtitle on the emulator (adb cannot pass the {@code Uri[]} intent extra).
     * It doubles as a real convenience for local/HTTP media with a sidecar (like upstream's
     * {@code SubtitleFinder}). Revisit in Fase 2 once we validate the real intent contract.
     */
    @Nullable
    private static Uri sidecarCandidate(@Nullable Uri mediaUri) {
        if (mediaUri == null) return null;
        String s = mediaUri.toString();
        int dot = s.lastIndexOf('.');
        int slash = s.lastIndexOf('/');
        if (dot <= slash) return null; // no extension in the last path segment
        return Uri.parse(s.substring(0, dot) + ".srt");
    }

    private void parseAsync(Uri uri) {
        new Thread(() -> {
            try {
                String content = readText(context, uri);
                SubtitleFile file = SubtitleConverter.convert(content, "srt", null);
                handler.post(() -> onParsed(file, uri));
            } catch (Exception e) {
                android.util.Log.w(TAG, "no external subtitle loaded from " + uri + ": " + e.getMessage());
            }
        }, "custom-sub-parse").start();
    }

    private void onParsed(SubtitleFile file, Uri uri) {
        this.subtitleFile = file;
        this.active = true;
        syncPanel.bind(file);
        disableMedia3TextTrack();
        overlay.setVisibility(TextView.VISIBLE);
        scheduleTick();
        android.util.Log.i(TAG, "external subtitle active: " + file.getEntries().size() + " cues from " + uri);
    }

    private void disableMedia3TextTrack() {
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));
    }

    private void scheduleTick() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::tick, POLL_MS);
    }

    private void tick() {
        if (!active) return;
        render();
        if (syncPanel.isOpen()) syncPanel.onTick(currentPositionMs());
        scheduleTick();
    }

    private void render() {
        if (subtitleFile == null || player == null) {
            overlay.setText("");
            return;
        }
        if (syncPanel.isOpen()) {
            // The panel shows the lines itself; hide the bottom overlay to avoid duplication.
            if (overlay.getVisibility() != TextView.GONE) overlay.setVisibility(TextView.GONE);
            return;
        }
        if (overlay.getVisibility() != TextView.VISIBLE) overlay.setVisibility(TextView.VISIBLE);
        long pos = player.getCurrentPosition();
        String text = activeCueText(pos);
        if (!text.contentEquals(overlay.getText())) {
            overlay.setText(text);
        }
    }

    /** Returns the text of the cue active at {@code positionMs} after applying the sync transform. */
    private String activeCueText(long positionMs) {
        for (SubtitleEntry e : subtitleFile.getEntries()) {
            long start = SyncResolver.adjust(syncState, e.getStartMs());
            long end = SyncResolver.adjust(syncState, e.getEndMs());
            if (positionMs >= start && positionMs <= end) {
                return String.join("\n", e.getLines());
            }
            if (start > positionMs) break; // entries sorted by start; no later cue can match
        }
        return "";
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

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
