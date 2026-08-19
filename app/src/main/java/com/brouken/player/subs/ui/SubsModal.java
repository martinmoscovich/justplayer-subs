package com.brouken.player.subs.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * A floating card over a 55% black veil that swallows navigation until there is a decision. Two
 * users today: the auto-sync "from where" choice and the review of its proposal.
 *
 * <p>Added inside the <em>content</em> area, so the veil stops at the sidebar — which is the point:
 * the modal belongs to the screen, not to the whole panel. Key handling stays with the owning
 * screen; this class only draws.
 */
public class SubsModal extends FrameLayout {

    private final TextView title;
    private final TextView big;
    private final LinearLayout buttonRow;

    public SubsModal(Context c) {
        super(c);
        setVisibility(GONE);

        View scrim = new View(c);
        scrim.setBackgroundColor(SubsTheme.SCRIM);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(SubsShapes.rounded(c, SubsTheme.SURFACE_1, SubsTheme.EDGE, SubsTheme.RADIUS_PANEL_DP));
        card.setPadding(SubsTheme.dp(c, 22), SubsTheme.dp(c, 18), SubsTheme.dp(c, 22), SubsTheme.dp(c, 18));
        LayoutParams clp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        addView(card, clp);

        title = SubsTheme.labelSm(new TextView(c));
        title.setTextColor(SubsTheme.INK_3);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        big = SubsTheme.headlineMd(new TextView(c));
        big.setTextColor(SubsTheme.ON_SURFACE);
        big.setGravity(Gravity.CENTER);
        big.setVisibility(GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = SubsTheme.dp(c, 14);
        card.addView(big, blp);

        buttonRow = new LinearLayout(c);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = SubsTheme.dp(c, 14);
        card.addView(buttonRow, rlp);
    }

    /** {@code bigText} null keeps the card compact — a menu has nothing to state, a proposal does. */
    public void show(String titleText, @Nullable String bigText, List<SubsButton> buttons) {
        title.setText(titleText != null ? titleText.toUpperCase() : "");
        big.setText(bigText != null ? bigText : "");
        big.setVisibility(bigText == null || bigText.isEmpty() ? GONE : VISIBLE);
        buttonRow.removeAllViews();
        for (SubsButton b : buttons) buttonRow.addView(b, b.rowParams());
        setVisibility(VISIBLE);
    }

    public void hide() {
        setVisibility(GONE);
        buttonRow.removeAllViews();
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }
}
