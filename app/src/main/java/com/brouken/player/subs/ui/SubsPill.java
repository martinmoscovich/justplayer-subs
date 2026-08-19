package com.brouken.player.subs.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

/**
 * A floating capsule over the video, for the two things that keep running once the panel is closed:
 * an auto-sync and a translation. Panel-bg with a 10% white hairline — the same surface as the
 * sidebar, so a closed panel and its leftovers still look like one app.
 *
 * <p>Holds a glyph and a small column: a caption, a value, and optionally a {@link SubsMiniBar}. Any
 * of the three can be left out; the pill shrinks to what it was given.
 */
public class SubsPill extends LinearLayout {

    private final ImageView glyph;
    private final TextView caption;
    private final TextView value;
    private final SubsMiniBar bar;

    /** {@code iconTrailing} puts the glyph after the text — the translation pill sits at the right
     *  edge of the screen, where a leading icon would read as pointing off it. */
    public SubsPill(Context c, @DrawableRes int iconRes, int iconTint, boolean iconTrailing) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(SubsShapes.rounded(c, SubsTheme.PANEL, SubsTheme.EDGE, 22f));
        setPadding(SubsTheme.dp(c, 14), SubsTheme.dp(c, 8), SubsTheme.dp(c, 14), SubsTheme.dp(c, 8));
        setVisibility(GONE);

        glyph = SubsIcons.icon(c, iconRes, iconTint, 18f);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setGravity(iconTrailing ? Gravity.END : Gravity.START);

        caption = SubsTheme.labelSm(new TextView(c));
        caption.setTextColor(SubsTheme.INK_3);
        caption.setSingleLine(true);
        col.addView(caption, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        value = SubsTheme.labelLg(new TextView(c));
        value.setTextColor(SubsTheme.INK);
        value.setSingleLine(true);
        col.addView(value, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        bar = new SubsMiniBar(c);
        LayoutParams barLp = new LayoutParams(
                SubsTheme.dp(c, 120), SubsTheme.dp(c, 8));
        barLp.topMargin = SubsTheme.dp(c, 5);
        col.addView(bar, barLp);

        LayoutParams colLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        LayoutParams glyphLp = new LayoutParams(SubsTheme.dp(c, 18), SubsTheme.dp(c, 18));
        if (iconTrailing) {
            glyphLp.leftMargin = SubsTheme.dp(c, 10);
            addView(col, colLp);
            addView(glyph, glyphLp);
        } else {
            glyphLp.rightMargin = SubsTheme.dp(c, 10);
            addView(glyph, glyphLp);
            addView(col, colLp);
        }
    }

    /**
     * @param captionText small uppercase line, or null to drop it
     * @param valueText   the readable line, or null to drop it
     * @param cells       mini-bar colours, or null/empty to drop the bar
     */
    public void set(@Nullable String captionText, @Nullable String valueText, @Nullable int[] cells) {
        caption.setText(captionText != null ? captionText : "");
        caption.setVisibility(captionText == null || captionText.isEmpty() ? GONE : VISIBLE);
        value.setText(valueText != null ? valueText : "");
        value.setVisibility(valueText == null || valueText.isEmpty() ? GONE : VISIBLE);
        if (cells == null || cells.length == 0) {
            bar.setVisibility(GONE);
        } else {
            bar.setVisibility(VISIBLE);
            bar.setColors(cells);
        }
    }

    public void setIconTint(int color) {
        SubsIcons.tint(glyph, color);
    }

    public void setIcon(@DrawableRes int res) {
        glyph.setImageResource(res);
    }

    /** Layout params that keep the pill from stretching to whatever container it lands in. */
    public static ViewGroup.LayoutParams wrapParams() {
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
