package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.sync.AnchorSource;
import subtitleengine.sync.SyncAnchor;
import subtitleengine.sync.SyncResolver;
import subtitleengine.sync.SyncState;

/**
 * The sync concern: holds the currently-synced subtitle + its {@link SyncState}, renders the active
 * cue in an overlay applying the offset, and applies anchors/nudges. Knows nothing about where the
 * subtitle came from (that is {@link SubtitleSelectionController}).
 */
public class SubtitleSyncController {

    private final TextView overlay;
    @Nullable private SubtitleFile file;
    private SyncState syncState = SyncState.empty();
    private boolean active;

    public SubtitleSyncController(Context context) {
        overlay = new TextView(context);
        overlay.setTextColor(Color.WHITE);
        overlay.setShadowLayer(6f, 0f, 0f, Color.BLACK);
        overlay.setGravity(Gravity.CENTER);
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        overlay.setVisibility(View.GONE);
    }

    /** The overlay view; the coordinator adds it to the player view hierarchy. */
    public View getOverlayView() {
        return overlay;
    }

    public boolean isActive() {
        return active;
    }

    public SyncState getState() {
        return syncState;
    }

    /** Starts syncing/rendering a subtitle (fresh {@link SyncState}). */
    public void setSubtitle(SubtitleFile f) {
        this.file = f;
        this.syncState = SyncState.empty();
        this.active = true;
    }

    /** Stops rendering (e.g. an embedded track was selected — Media3 draws it instead). */
    public void clear() {
        this.file = null;
        this.active = false;
        overlay.setText("");
        overlay.setVisibility(View.GONE);
    }

    public void anchor(int cueIndex, long cueStartMs, long videoPositionMs) {
        syncState = syncState.withAnchorAdded(
                new SyncAnchor(cueIndex, cueStartMs, videoPositionMs, AnchorSource.MANUAL));
    }

    public void nudge(long deltaMs) {
        syncState = syncState.withNudgeShiftedBy(deltaMs);
    }

    /** Updates the overlay for {@code positionMs}. Hidden while the panel is open (it shows lines). */
    public void render(long positionMs, boolean panelOpen) {
        if (!active || file == null || panelOpen) {
            if (overlay.getVisibility() != View.GONE) overlay.setVisibility(View.GONE);
            return;
        }
        if (overlay.getVisibility() != View.VISIBLE) overlay.setVisibility(View.VISIBLE);
        String text = activeCueText(positionMs);
        if (!text.contentEquals(overlay.getText())) overlay.setText(text);
    }

    private String activeCueText(long positionMs) {
        for (SubtitleEntry e : file.getEntries()) {
            long start = SyncResolver.adjust(syncState, e.getStartMs());
            long end = SyncResolver.adjust(syncState, e.getEndMs());
            if (positionMs >= start && positionMs <= end) return String.join("\n", e.getLines());
            if (start > positionMs) break;
        }
        return "";
    }
}
