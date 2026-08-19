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
 * One entry of the 140dp sidebar: 38dp tall, a 20dp glyph, {@code label-lg}.
 *
 * <p>Carries the panel's central rule, which the mockups had inverted: <b>focus is solid white,
 * the screen you are on is cyan</b>. At three metres the eye goes to the brightest thing, and that
 * has to be where the cursor is, not where you already were.
 *
 * <p><b>Dimmed</b> is a third, orthogonal thing: Translate greys out when it cannot run, but stays
 * selectable, because entering it is how the user finds out why.
 */
public class SubsSidebarItem extends LinearLayout {

    private static final float HEIGHT_DP = 38f;
    private static final float ICON_DP = 20f;

    private final ImageView glyph;
    private final TextView label;
    private final GradientDrawable bg;

    private boolean focused;
    private boolean active;
    private boolean dimmed;
    @Nullable private SubsFocus.Skin painted;

    public SubsSidebarItem(Context c, String text, @DrawableRes int iconRes) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // 8dp, not the 10dp drawn: see SubsTheme.SIDEBAR_W_DP for why the rail needed the room.
        setPadding(SubsTheme.dp(c, 8), 0, SubsTheme.dp(c, 8), 0);

        glyph = SubsIcons.icon(c, iconRes, SubsTheme.INK_2, ICON_DP);
        LayoutParams glp = new LayoutParams(SubsTheme.dp(c, ICON_DP), SubsTheme.dp(c, ICON_DP));
        glp.rightMargin = SubsTheme.dp(c, 10);
        addView(glyph, glp);

        label = SubsTheme.labelLg(new TextView(c));
        label.setText(text);
        label.setSingleLine(true);
        addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        bg = SubsShapes.rounded(c, 0x00000000, SubsTheme.RADIUS_ROW_DP);
        restyle(false);
    }

    public LayoutParams navParams() {
        LayoutParams p = new LayoutParams(LayoutParams.MATCH_PARENT, SubsTheme.dp(getContext(), HEIGHT_DP));
        p.bottomMargin = SubsTheme.dp(getContext(), 4);
        return p;
    }

    public void setState(boolean focused, boolean active, boolean dimmed, boolean animate) {
        if (this.focused == focused && this.active == active && this.dimmed == dimmed) return;
        this.focused = focused;
        this.active = active;
        this.dimmed = dimmed;
        restyle(animate);
    }

    private void restyle(boolean animate) {
        SubsFocus.Skin to;
        if (focused) {
            to = new SubsFocus.Skin(SubsTheme.INK, 0, SubsTheme.ON_SECONDARY);
        } else if (active) {
            to = new SubsFocus.Skin(SubsTheme.PRIMARY_20, 0, SubsTheme.PRIMARY);
        } else if (dimmed) {
            to = new SubsFocus.Skin(0x00000000, 0, SubsTheme.DISABLED);
        } else {
            to = new SubsFocus.Skin(0x00000000, 0, SubsTheme.INK_2);
        }
        SubsFocus.apply(this, bg, painted, to, Collections.singletonList(label),
                color -> SubsIcons.tint(glyph, color), animate,
                focused ? SubsTheme.FOCUS_SCALE : 1f);
        painted = to;
    }
}
