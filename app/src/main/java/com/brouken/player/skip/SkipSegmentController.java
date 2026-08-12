package com.brouken.player.skip;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a skip button while playback sits inside a segment supplied by the launching app, and
 * seeks past that segment when the button is used.
 *
 * <p>Owned by {@code PlayerActivity}, which only has to call {@link #setSegments}, and {@link
 * #release()} on teardown. With no segments the controller does nothing at all and never adds its
 * view to the hierarchy, so a launcher that does not send them is unaffected.
 */
public class SkipSegmentController {

    /** Position polling interval. Coarse on purpose: segment edges are seconds, not frames. */
    private static final long POLL_MS = 250;
    /** How long the button stays up within one segment before it gets out of the way. */
    private static final long AUTO_HIDE_MS = 10_000;

    private final Player player;
    private final ViewGroup root;
    private final SkipSegmentView button;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<SkipSegment> segments = new ArrayList<>();
    private boolean ticking;
    private boolean attached;
    /** The segment the button is currently offering, or null when it is hidden. */
    @Nullable private SkipSegment shownSegment;
    /** The segment the button was last dismissed or auto-hidden for; not offered again. */
    @Nullable private SkipSegment consumedSegment;
    private long shownAtMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) {
                return;
            }
            update();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public SkipSegmentController(Context context, ViewGroup root, Player player) {
        this.player = player;
        this.root = root;
        this.button = new SkipSegmentView(context);
        this.button.setVisibility(View.GONE);
        this.button.setOnClickListener(v -> skipCurrent());
    }

    /**
     * Replaces the segments being offered. Pass null or an empty list to turn the feature off for
     * the current media.
     */
    public void setSegments(@Nullable List<SkipSegment> segments) {
        this.segments = segments == null ? new ArrayList<>() : new ArrayList<>(segments);
        this.shownSegment = null;
        this.consumedSegment = null;
        hideButton();
        if (this.segments.isEmpty()) {
            stopTicking();
        } else {
            attachIfNeeded();
            startTicking();
        }
    }

    public void release() {
        stopTicking();
        hideButton();
        if (attached) {
            root.removeView(button);
            attached = false;
        }
    }

    private void attachIfNeeded() {
        if (attached) {
            return;
        }
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        final int margin = Math.round(32 * root.getResources().getDisplayMetrics().density);
        lp.bottomMargin = margin * 3; // clear of the transport controls
        lp.rightMargin = margin;
        root.addView(button, lp);
        attached = true;
    }

    private void startTicking() {
        if (ticking) {
            return;
        }
        ticking = true;
        handler.post(tick);
    }

    private void stopTicking() {
        ticking = false;
        handler.removeCallbacks(tick);
    }

    private void update() {
        final long position = player.getCurrentPosition();
        final SkipSegment active = segmentAt(position);

        if (active == null) {
            // Leaving a segment re-arms it, so seeking back into an intro offers the skip again.
            consumedSegment = null;
            hideButton();
            return;
        }
        if (active == consumedSegment) {
            hideButton();
            return;
        }
        if (active != shownSegment) {
            showButton(active);
            return;
        }
        if (System.currentTimeMillis() - shownAtMs >= AUTO_HIDE_MS) {
            consumedSegment = active;
            hideButton();
        }
    }

    @Nullable
    private SkipSegment segmentAt(long positionMs) {
        for (SkipSegment segment : segments) {
            if (segment.contains(positionMs)) {
                return segment;
            }
        }
        return null;
    }

    private void showButton(SkipSegment segment) {
        shownSegment = segment;
        shownAtMs = System.currentTimeMillis();
        button.setLabel(segment.getKind().getLabel());
        button.setVisibility(View.VISIBLE);
    }

    private void hideButton() {
        shownSegment = null;
        if (button.getVisibility() != View.GONE) {
            // Hiding a focused view strands the D-pad, so hand focus back to the player first.
            final boolean wasFocused = button.hasFocus();
            button.setVisibility(View.GONE);
            if (wasFocused) {
                root.requestFocus();
            }
        }
    }

    private void skipCurrent() {
        final SkipSegment segment = shownSegment;
        if (segment == null) {
            return;
        }
        consumedSegment = segment;
        hideButton();
        player.seekTo(segment.getEndMs());
    }
}
