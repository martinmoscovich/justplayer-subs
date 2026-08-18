package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.text.Html;
import android.text.Layout;
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

    /** Share of the player width the overlay may use before wrapping; the rest is breathing room. */
    private static final float OVERLAY_WIDTH_FRACTION = 0.9f;

    private final TextView overlay;
    private final ManualSyncSession session;
    private boolean active;
    /** Raw cue text of the last render; compared against so styling runs only when the cue changes. */
    private String lastRenderedText = "";

    public SubtitleSyncController(Context context) {
        session = new ManualSyncSession(SubtitleSettings.syncSettings(context));
        overlay = new TextView(context);
        overlay.setTextColor(Color.WHITE);
        overlay.setShadowLayer(6f, 0f, 0f, Color.BLACK);
        overlay.setGravity(Gravity.CENTER);
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        overlay.setVisibility(View.GONE);
        // Wrap long cues onto balanced lines instead of one edge-to-edge run.
        overlay.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
    }

    /**
     * Caps the overlay width so a long cue wraps instead of stretching across the whole video. The
     * coordinator feeds the player view's width, since the cap has to follow the surface size
     * (fullscreen vs PiP, and every resize) rather than a fixed dp value.
     */
    public void setMaxWidthPx(int playerWidthPx) {
        if (playerWidthPx > 0) {
            overlay.setMaxWidth(Math.round(playerWidthPx * OVERLAY_WIDTH_FRACTION));
        }
    }

    /**
     * Renders inline subtitle markup (&lt;i&gt;, &lt;b&gt;, &lt;font color&gt;…) as actual styling
     * instead of showing the tags as literal text. Plain text passes through unchanged, so this is
     * safe to apply to every cue.
     *
     * <p>Line breaks are converted first: HTML collapses newlines, so a two-line cue would otherwise
     * render as one long line.
     */
    private static CharSequence styled(String text) {
        if (text.indexOf('<') < 0) return text; // nothing to parse — skip the cost and any surprises
        String html = text.replace("\n", "<br>");
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
    }

    /** The overlay view; the coordinator adds it to the player view hierarchy. */
    public View getOverlayView() {
        return overlay;
    }

    /** The engine session — the UI (SyncView) reads cues and issues anchors/nudges through it. */
    public ManualSyncSession getSession() {
        return session;
    }

    /** Starts syncing/rendering a subtitle (fresh sync state). */
    public void setSubtitle(@Nullable SubtitleFile f) {
        session.setSubtitle(f);
        active = f != null;
        if (!active) {
            overlay.setText("");
            lastRenderedText = "";
            overlay.setVisibility(View.GONE);
        }
    }

    /** Swaps the cue text in place (incremental translation) without touching the sync state. */
    public void updateSubtitle(@Nullable SubtitleFile f) {
        session.updateSubtitle(f);
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
        if (!text.contentEquals(lastRenderedText)) {
            lastRenderedText = text;
            overlay.setText(styled(text));
        }
    }
}
