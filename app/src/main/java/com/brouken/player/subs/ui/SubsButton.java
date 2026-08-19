package com.brouken.player.subs.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import java.util.Collections;

/**
 * A button in the panel's own idiom: 36dp tall, {@code label-lg}, an optional 18dp glyph, and four
 * states that are deliberately <em>categorical</em> rather than degrees of the same thing.
 *
 * <ul>
 *   <li><b>Normal</b> — {@code surface-container} fill, 10% white hairline.</li>
 *   <li><b>Focused</b> — solid white fill, {@code on-secondary} ink, scaled 1.03. Focus is white
 *       everywhere in this panel; cyan always means "chosen", never "here".</li>
 *   <li><b>Disabled</b> — <em>loses the container</em>: no fill, no border. It stops reading as an
 *       object, which is exactly what it is, because the focus walk skips it.</li>
 *   <li><b>Dimmed</b> — keeps its container and drops to 50%, applied to a whole row at once when
 *       the cursor has left that row. Still alive, still reachable by going back.</li>
 * </ul>
 *
 * <p>The 4dp horizontal margin {@link #rowParams} hands out is what makes the focus scale safe:
 * the 1.8dp it grows per side never reaches the parent's bounds, so no ancestor ever needs
 * {@code setClipChildren(false)}.
 */
public class SubsButton extends LinearLayout {

    private static final float HEIGHT_DP = 36f;
    private static final float PAD_DP = 16f;
    private static final float GAP_DP = 7f;
    private static final float ICON_DP = 18f;

    private final TextView label;
    @Nullable private ImageView glyph;
    private final GradientDrawable bg;
    private final boolean round;
    private final boolean iconTrailing;

    private boolean focused;
    private boolean enabledState = true;
    private boolean dimmed;
    @Nullable private SubsFocus.Skin painted;

    public SubsButton(Context c, @Nullable String text) {
        this(c, text, 0, false, false);
    }

    public SubsButton(Context c, @Nullable String text, @DrawableRes int iconRes) {
        this(c, text, iconRes, false, false);
    }

    /** The 36dp icon-only variant the sync transport row uses for prev/play/next. */
    public static SubsButton round(Context c, @DrawableRes int iconRes) {
        return new SubsButton(c, null, iconRes, true, false);
    }

    /** Text then glyph — "5s ⏩", where the arrow reads as the direction the number applies in. */
    public static SubsButton trailing(Context c, String text, @DrawableRes int iconRes) {
        return new SubsButton(c, text, iconRes, false, true);
    }

    public SubsButton(Context c, @Nullable String text, @DrawableRes int iconRes, boolean round,
                      boolean iconTrailing) {
        super(c);
        this.round = round;
        this.iconTrailing = iconTrailing;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        int padH = round ? 0 : SubsTheme.dp(c, PAD_DP);
        setPadding(padH, 0, padH, 0);

        label = SubsTheme.labelLg(new TextView(c));
        label.setSingleLine(true);
        label.setText(text != null ? text : "");
        label.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);

        boolean hasText = text != null && !text.isEmpty();
        if (iconRes != 0 && !iconTrailing) addGlyph(iconRes, hasText);
        addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        if (iconRes != 0 && iconTrailing) addGlyph(iconRes, hasText);

        bg = SubsShapes.rounded(c, SubsTheme.SURFACE_2, SubsTheme.EDGE,
                round ? HEIGHT_DP / 2f : SubsTheme.RADIUS_ROW_DP);
        restyle(false);
    }

    private void addGlyph(@DrawableRes int iconRes, boolean hasText) {
        Context c = getContext();
        glyph = SubsIcons.icon(c, iconRes, SubsTheme.INK, ICON_DP);
        LayoutParams glp = new LayoutParams(SubsTheme.dp(c, ICON_DP), SubsTheme.dp(c, ICON_DP));
        if (hasText) {
            if (iconTrailing) glp.leftMargin = SubsTheme.dp(c, GAP_DP);
            else glp.rightMargin = SubsTheme.dp(c, GAP_DP);
        }
        addView(glyph, glp);
    }

    /** Layout params with the height and the 4dp side margins every button in a row wants. */
    public LayoutParams rowParams() {
        LayoutParams p = new LayoutParams(
                round ? SubsTheme.dp(getContext(), HEIGHT_DP) : LayoutParams.WRAP_CONTENT,
                SubsTheme.dp(getContext(), HEIGHT_DP));
        p.leftMargin = SubsTheme.dp(getContext(), 4);
        p.rightMargin = SubsTheme.dp(getContext(), 4);
        return p;
    }

    public void setText(String text) {
        label.setText(text != null ? text : "");
        label.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);
    }

    public CharSequence getText() {
        return label.getText();
    }

    public void setIcon(@DrawableRes int iconRes) {
        if (glyph == null) addGlyph(iconRes, label.getVisibility() == VISIBLE);
        else glyph.setImageResource(iconRes);
        restyle(false);
    }

    public void setFocusedState(boolean f) {
        if (focused == f) return;
        focused = f;
        restyle(true);
    }

    /** Disabled: the focus walk must skip it, and it must stop looking like a button. */
    public void setEnabledState(boolean e) {
        if (enabledState == e) return;
        enabledState = e;
        restyle(true);
    }

    public boolean isEnabledState() {
        return enabledState;
    }

    /** Dimmed: the whole row is asleep because the cursor went elsewhere. */
    public void setDimmed(boolean d) {
        if (dimmed == d) return;
        dimmed = d;
        setAlpha(d ? .5f : 1f);
    }

    private void restyle(boolean animate) {
        SubsFocus.Skin to;
        if (!enabledState) {
            to = new SubsFocus.Skin(0x00000000, 0, SubsTheme.DISABLED);
        } else if (focused) {
            to = new SubsFocus.Skin(SubsTheme.INK, SubsTheme.INK, SubsTheme.ON_SECONDARY);
        } else {
            to = new SubsFocus.Skin(SubsTheme.SURFACE_2, SubsTheme.EDGE, SubsTheme.INK);
        }
        float scale = (enabledState && focused) ? SubsTheme.FOCUS_SCALE : 1f;
        SubsFocus.apply(this, bg, painted, to, Collections.singletonList(label),
                color -> { if (glyph != null) SubsIcons.tint(glyph, color); }, animate, scale);
        painted = to;
    }

    /** A 1dp × 22dp rule between groups of buttons — the sync row separates transport from the rest. */
    public static android.view.View separator(Context c) {
        android.view.View v = new android.view.View(c);
        v.setBackgroundColor(SubsTheme.EDGE);
        LayoutParams p = new LayoutParams(SubsTheme.dp(c, 1), SubsTheme.dp(c, 22));
        p.leftMargin = SubsTheme.dp(c, 4);
        p.rightMargin = SubsTheme.dp(c, 4);
        v.setLayoutParams(p);
        return v;
    }

}
