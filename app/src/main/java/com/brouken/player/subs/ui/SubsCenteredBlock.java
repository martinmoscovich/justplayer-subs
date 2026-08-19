package com.brouken.player.subs.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

/**
 * One component for the five "the list is not the point right now" states across the three screens:
 * nothing to sync, reading the embedded track, auto-sync running, auto-sync/translation unavailable,
 * translation idle. They all used to be phrased differently in different places; they are the same
 * shape — an optional glyph, a headline, a subline, and sometimes a bar.
 *
 * <p>Lives centred in the <em>content</em> area, never over the sidebar: the sidebar keeps working
 * while one of these is up.
 */
public class SubsCenteredBlock extends LinearLayout {

    private final ImageView glyph;
    private final TextView headline;
    private final TextView subline;
    private final SubsProgressLine bar;

    public SubsCenteredBlock(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        int padH = SubsTheme.dp(c, 80);
        setPadding(padH, 0, padH, 0);

        glyph = SubsIcons.icon(c, 0, SubsTheme.INK_3, 44f);
        glyph.setVisibility(GONE);
        LayoutParams glp = new LayoutParams(SubsTheme.dp(c, 44), SubsTheme.dp(c, 44));
        glp.bottomMargin = SubsTheme.dp(c, 6);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        addView(glyph, glp);

        headline = SubsTheme.headlineMd(new TextView(c));
        headline.setTextColor(SubsTheme.ON_SURFACE);
        headline.setGravity(Gravity.CENTER);
        addView(headline, wrap(c, 0));

        subline = SubsTheme.bodyMd(new TextView(c));
        subline.setTextColor(SubsTheme.INK_2);
        subline.setGravity(Gravity.CENTER);
        subline.setLineSpacing(0f, 1.45f);
        addView(subline, wrap(c, 8));

        bar = new SubsProgressLine(c);
        bar.setVisibility(GONE);
        LayoutParams blp = new LayoutParams(SubsTheme.dp(c, 280), SubsTheme.dp(c, 6));
        blp.topMargin = SubsTheme.dp(c, 10);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        addView(bar, blp);
    }

    private static LayoutParams wrap(Context c, int topMarginDp) {
        LayoutParams p = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        p.topMargin = SubsTheme.dp(c, topMarginDp);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    /**
     * @param iconRes  0 for no glyph
     * @param spinning rotate the glyph — for the states that are actually waiting on something
     * @param sub      null or empty hides the subline entirely rather than leaving a blank row
     * @param fraction 0..1 to show the bar, negative to hide it
     */
    public void show(@DrawableRes int iconRes, boolean spinning, String title, @Nullable String sub,
                     float fraction) {
        if (iconRes != 0) {
            glyph.setImageResource(iconRes);
            glyph.setVisibility(VISIBLE);
            if (spinning) SubsIcons.spin(glyph); else SubsIcons.stopSpin(glyph);
        } else {
            SubsIcons.stopSpin(glyph);
            glyph.setVisibility(GONE);
        }
        headline.setText(title != null ? title : "");
        headline.setVisibility(title == null || title.isEmpty() ? GONE : VISIBLE);
        subline.setText(sub != null ? sub : "");
        subline.setVisibility(sub == null || sub.isEmpty() ? GONE : VISIBLE);
        if (fraction >= 0f) {
            bar.setVisibility(VISIBLE);
            bar.setFraction(fraction);
        } else {
            bar.setVisibility(GONE);
        }
        setVisibility(VISIBLE);
    }

    public void hide() {
        SubsIcons.stopSpin(glyph);
        setVisibility(GONE);
    }
}
