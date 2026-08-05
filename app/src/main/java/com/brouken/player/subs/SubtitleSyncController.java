package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import subtitleengine.core.model.SubtitleFile;
import subtitleengine.sync.ManualSyncSession;

/**
 * The sync concern (app side): owns the engine's {@link ManualSyncSession} (all cue/sync logic) and
 * renders its active cue in an overlay. Knows nothing about where the subtitle came from (that is
 * {@link SubtitleSelectionController}) — only how to draw it and expose the session to the UI.
 */
public class SubtitleSyncController {

    private final TextView overlay;
    private final ManualSyncSession session;
    private boolean active;

    public SubtitleSyncController(Context context) {
        session = new ManualSyncSession(SubtitleSettings.syncSettings(context));
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

    /** The engine session — the UI (SyncView) reads cues and issues anchors/nudges through it. */
    public ManualSyncSession getSession() {
        return session;
    }

    public boolean isActive() {
        return active;
    }

    /** Starts syncing/rendering a subtitle (fresh sync state). */
    public void setSubtitle(@Nullable SubtitleFile f) {
        session.setSubtitle(f);
        active = f != null;
        if (!active) {
            overlay.setText("");
            overlay.setVisibility(View.GONE);
        }
    }

    /** Stops rendering (e.g. an embedded track was selected — Media3 draws it instead). */
    public void clear() {
        session.clear();
        active = false;
        overlay.setText("");
        overlay.setVisibility(View.GONE);
    }

    /** Updates the overlay for {@code positionMs}. Hidden while the panel is open (it shows lines). */
    public void render(long positionMs, boolean panelOpen) {
        if (!active || !session.hasCues() || panelOpen) {
            if (overlay.getVisibility() != View.GONE) overlay.setVisibility(View.GONE);
            return;
        }
        if (overlay.getVisibility() != View.VISIBLE) overlay.setVisibility(View.VISIBLE);
        String text = session.activeCueText(positionMs);
        if (!text.contentEquals(overlay.getText())) overlay.setText(text);
    }
}
