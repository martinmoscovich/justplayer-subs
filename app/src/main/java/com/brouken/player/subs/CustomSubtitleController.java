package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
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
public class CustomSubtitleController {

    private static final String TAG = "CustomSubtitleController";
    private static final long POLL_MS = 100;

    private final Context context;
    private final ExoPlayer player;
    private final DefaultTrackSelector trackSelector;
    private final TextView overlay;
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
    }

    /**
     * Called once the media and its subtitles are known. If an external subtitle is present, take
     * it over: parse it with the engine, render it here, and disable Media3's text track. If there
     * is no external subtitle, stay inactive and let Media3 handle embedded tracks as before.
     */
    public void onMediaSet(@Nullable List<MediaItem.SubtitleConfiguration> apiSubs,
                           @Nullable Uri prefsSubtitleUri) {
        Uri external = firstExternalUri(apiSubs, prefsSubtitleUri);
        if (external == null) {
            return; // embedded-only: Media3 renders, we stay out of the way
        }
        parseAsync(external);
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

    private void parseAsync(Uri uri) {
        new Thread(() -> {
            try {
                String content = readText(context, uri);
                SubtitleFile file = SubtitleConverter.convert(content, "srt", null);
                handler.post(() -> onParsed(file));
            } catch (Exception e) {
                android.util.Log.w(TAG, "failed to load external subtitle: " + e.getMessage());
            }
        }, "custom-sub-parse").start();
    }

    private void onParsed(SubtitleFile file) {
        this.subtitleFile = file;
        this.active = true;
        disableMedia3TextTrack();
        overlay.setVisibility(TextView.VISIBLE);
        scheduleTick();
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
        scheduleTick();
    }

    private void render() {
        if (subtitleFile == null || player == null) {
            overlay.setText("");
            return;
        }
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
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("cannot open " + uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
