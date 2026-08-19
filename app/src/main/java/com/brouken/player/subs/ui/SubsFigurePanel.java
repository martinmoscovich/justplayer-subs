package com.brouken.player.subs.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * A caption, one big number, and an optional line under it. Translate uses these for the two things
 * the chunk bar physically cannot say — how far you can watch from where you are, and how much money
 * this has cost — plus the run's overall percentage, which nobody can count off twenty-three
 * segments by eye.
 */
public class SubsFigurePanel extends LinearLayout {

    private final TextView caption;
    private final TextView figure;
    private final TextView sub;

    public SubsFigurePanel(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(SubsShapes.panel(c, SubsTheme.RADIUS_PANEL_DP));
        setPadding(SubsTheme.dp(c, 20), SubsTheme.dp(c, 16), SubsTheme.dp(c, 20), SubsTheme.dp(c, 16));

        caption = SubsTheme.labelSm(new TextView(c));
        caption.setTextColor(SubsTheme.INK_3);
        caption.setSingleLine(true);
        addView(caption, gap(c, 0));

        figure = SubsTheme.headlineLg(new TextView(c));
        figure.setTextColor(SubsTheme.ON_SURFACE);
        figure.setSingleLine(true);
        addView(figure, gap(c, 4));

        sub = SubsTheme.labelLg(new TextView(c));
        sub.setTextColor(SubsTheme.INK_2);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(sub, gap(c, 4));
    }

    private static LayoutParams gap(Context c, int topDp) {
        LayoutParams p = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        p.topMargin = SubsTheme.dp(c, topDp);
        return p;
    }

    /** Layout params for a row of panels: equal widths, equal heights, 14dp apart. MATCH_PARENT
     *  height is what keeps a panel with no subline the same box as its neighbours instead of a
     *  shorter one — three cards of different heights read as three unrelated things. */
    public LayoutParams rowParams(boolean first) {
        LayoutParams p = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        if (!first) p.leftMargin = SubsTheme.dp(getContext(), 14);
        return p;
    }

    public void set(String label, String value, @Nullable String subline, boolean accent) {
        caption.setText(label != null ? label.toUpperCase() : "");
        figure.setText(value);
        figure.setTextColor(accent ? SubsTheme.PRIMARY : SubsTheme.ON_SURFACE);
        sub.setText(subline != null ? subline : "");
        sub.setVisibility(subline == null || subline.isEmpty() ? GONE : VISIBLE);
    }
}
