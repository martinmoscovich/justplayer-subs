package com.brouken.player.subs.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.WeakHashMap;

/**
 * The 130ms focus crossfade, in one place. Focus in this panel is drawn by hand (D-pad only, no
 * Android focus states), so the highlight would otherwise snap between two flat colours on every
 * key press — which reads as a flicker rather than as movement.
 *
 * <p>Interpolates the container's fill and border plus the text colours of whatever labels sit
 * inside it, all off one {@link ValueAnimator} so they can never drift apart. Scale is opt-in and
 * only for buttons and sidebar items: those already carry a 4dp margin, which is more than the
 * 1.8dp a 1.03 scale adds per side, so nothing ever needs {@code setClipChildren(false)} — a flag
 * that in the subtitle list's {@code ScrollView} would draw the whole scrollable content outside
 * its viewport.
 */
public final class SubsFocus {

    private SubsFocus() {}

    /** The three colours that change together when focus arrives or leaves. */
    public static final class Skin {
        public final int fill;
        public final int stroke;
        public final int ink;

        public Skin(int fill, int stroke, int ink) {
            this.fill = fill;
            this.stroke = stroke;
            this.ink = ink;
        }

        public boolean sameAs(@Nullable Skin other) {
            return other != null && other.fill == fill && other.stroke == stroke && other.ink == ink;
        }
    }

    /** Anything else that has to take the ink colour along with the labels — an icon's tint, say. */
    public interface InkSink {
        void onInk(int color);
    }

    private static final ArgbEvaluator ARGB = new ArgbEvaluator();
    /** One animator per view at most: a fast D-pad must never leave two of them fighting. */
    private static final WeakHashMap<View, ValueAnimator> RUNNING = new WeakHashMap<>();

    /**
     * Moves {@code view} from {@code from} to {@code to}. With {@code animate} false (first paint,
     * or a rebuild) it jumps straight there — an entry animation on a screen that just appeared is
     * noise, not feedback. {@code scaleTo} is {@link SubsTheme#FOCUS_SCALE} for a focused button or
     * sidebar item and {@code 1f} for everything else, list rows included.
     */
    public static void apply(View view, GradientDrawable bg, @Nullable Skin from, Skin to,
                             @Nullable List<TextView> inks, boolean animate, float scaleTo) {
        apply(view, bg, from, to, inks, null, animate, scaleTo);
    }

    public static void apply(View view, GradientDrawable bg, @Nullable Skin from, Skin to,
                             @Nullable List<TextView> inks, @Nullable InkSink extraInk,
                             boolean animate, float scaleTo) {
        cancel(view);
        if (!animate || from == null || from.sameAs(to)) {
            paint(view, bg, to, inks, extraInk);
            setScale(view, scaleTo);
            return;
        }
        final Skin start = from;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(SubsTheme.FOCUS_MS);
        a.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            paint(view, bg, new Skin(blend(start.fill, to.fill, t), blend(start.stroke, to.stroke, t),
                    blend(start.ink, to.ink, t)), inks, extraInk);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                RUNNING.remove(view);
                // Also fires on cancel, and that is the point: a crossfade interrupted halfway would
                // otherwise leave the view painted in a blend of two skins that means nothing. When
                // the cancel came from a new target, the call that cancelled paints over this
                // immediately, so settling here is never the last word in that case.
                paint(view, bg, to, inks, extraInk);
            }
        });
        RUNNING.put(view, a);
        if (view.getScaleX() != scaleTo) {
            view.animate().scaleX(scaleTo).scaleY(scaleTo).setDuration(SubsTheme.FOCUS_MS).start();
        }
        a.start();
    }

    /** Fades a view in or out over {@link SubsTheme#CHIP_MS} — chips never pop into existence. */
    public static void fade(View view, boolean visible) {
        float target = visible ? 1f : 0f;
        if (view.getVisibility() != View.VISIBLE && visible) {
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
        }
        view.animate().alpha(target).setDuration(SubsTheme.CHIP_MS)
                .withEndAction(() -> view.setVisibility(visible ? View.VISIBLE : View.GONE)).start();
    }

    private static void paint(View view, @Nullable GradientDrawable bg, Skin skin,
                              @Nullable List<TextView> inks, @Nullable InkSink extraInk) {
        if (bg != null) {
            bg.setColor(skin.fill);
            if (skin.stroke != 0) {
                bg.setStroke(SubsTheme.dp(view.getContext(), SubsTheme.HAIRLINE_DP), skin.stroke);
            } else {
                // A container that loses its border must lose it entirely, not fade to a
                // transparent-but-still-inset one: "disabled" is meant to stop looking like an object.
                bg.setStroke(0, 0);
            }
            view.setBackground(bg);
        }
        if (inks != null) {
            for (TextView tv : inks) tv.setTextColor(skin.ink);
        }
        if (extraInk != null) extraInk.onInk(skin.ink);
    }

    private static void setScale(View v, float s) {
        v.setScaleX(s);
        v.setScaleY(s);
    }

    private static void cancel(View view) {
        ValueAnimator a = RUNNING.remove(view);
        if (a != null) a.cancel();
        view.animate().cancel();
    }

    private static int blend(int from, int to, float t) {
        return (int) ARGB.evaluate(t, from, to);
    }
}
