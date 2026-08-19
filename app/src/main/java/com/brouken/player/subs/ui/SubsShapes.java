package com.brouken.player.subs.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * The one place a rounded container is built. Everything in the panel that has a fill, a hairline
 * and a corner radius — rows, chips, buttons, tabs, figure panels, modals — comes out of here, so
 * "panel-bg with a 10% white edge at 16dp" is written once instead of twenty times.
 *
 * <p>Every drawable is a fresh instance: a {@link GradientDrawable} is mutable and views recolour
 * theirs on focus, so sharing one would make two views change together.
 */
public final class SubsShapes {

    private SubsShapes() {}

    /** Fill + 1dp hairline + radius. Pass {@code 0} as the stroke colour for no border. */
    public static GradientDrawable rounded(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(SubsTheme.dpF(c.getResources(), radiusDp));
        if (stroke != 0) d.setStroke(SubsTheme.dp(c, SubsTheme.HAIRLINE_DP), stroke);
        return d;
    }

    /** Fill + radius, no border. */
    public static GradientDrawable rounded(Context c, int fill, float radiusDp) {
        return rounded(c, fill, 0, radiusDp);
    }

    /** A capsule: radius half the given height, so it stays round whatever the width. */
    public static GradientDrawable capsule(Context c, int fill, int stroke, float heightDp) {
        return rounded(c, fill, stroke, heightDp / 2f);
    }

    /**
     * Rounded on the left edge only — the 4dp accent bar that marks the playing row, which has to
     * follow the row's own 8dp corner instead of poking a square edge out of it. (Views are not
     * clipped to a parent's rounded background, so the shape has to carry the corner itself.)
     */
    public static GradientDrawable leftRounded(Context c, int fill, float radiusDp) {
        float r = SubsTheme.dpF(c.getResources(), radiusDp);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        return d;
    }

    /** The standard panel-bg surface: sidebar, readout capsules, figure panels, modals. */
    public static GradientDrawable panel(Context c, float radiusDp) {
        return rounded(c, SubsTheme.PANEL, SubsTheme.EDGE, radiusDp);
    }
}
