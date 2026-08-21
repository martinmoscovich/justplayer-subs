package com.brouken.player.subs.ui;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

/**
 * Builds the panel's icons. Every one is a {@code VectorDrawable} under {@code res/drawable} with a
 * {@code subtitle_ic_} prefix (see CLAUDE.md: {@code res/} is a global namespace shared with a live
 * upstream, so nothing here may collide with it) drawn in white and tinted at runtime — which is
 * what lets the same play glyph sit on a cyan row and on a white focused one.
 */
public final class SubsIcons {

    private SubsIcons() {}

    private static final long SPIN_MS = 1100L;

    public static ImageView icon(Context c, @DrawableRes int res, int tint, float sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        int px = SubsTheme.dp(c, sizeDp);
        iv.setLayoutParams(new android.view.ViewGroup.LayoutParams(px, px));
        return iv;
    }

    public static void tint(ImageView iv, int color) {
        iv.setColorFilter(color);
    }

    /**
     * Starts the continuous rotation of a spinner icon, or leaves an already-running one alone.
     * The animator is parked on the view itself so {@link #stopSpin} can find it: a recycled list
     * row that keeps spinning off-screen is an invisible wakeup every frame, forever.
     */
    public static void spin(View v) {
        Object existing = v.getTag();
        if (existing instanceof ObjectAnimator && ((ObjectAnimator) existing).isRunning()) {
            // Already spinning: leave it alone. Callers drive this from state-update methods that
            // run on every progress event — SubsCenteredBlock.show() from each auto-sync tick, the
            // selector's row bind on every list refresh — and restarting cancels the animator and
            // snaps rotation back to 0 (see stopSpin). At a few updates per second the glyph never
            // gets past the first frame, so the spinner reads as frozen at 0° rather than as a slow
            // or stuttering spin. Idempotent start is what every caller actually wants.
            return;
        }
        stopSpin(v);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f);
        a.setDuration(SPIN_MS);
        a.setInterpolator(new LinearInterpolator());
        a.setRepeatCount(ObjectAnimator.INFINITE);
        v.setTag(a);
        a.start();
    }

    public static void stopSpin(@Nullable View v) {
        if (v == null) return;
        Object tag = v.getTag();
        if (tag instanceof ObjectAnimator) {
            ((ObjectAnimator) tag).cancel();
            v.setTag(null);
        }
        v.setRotation(0f);
    }
}
