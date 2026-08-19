package com.brouken.player.subs.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * "Which subtitle am I actually watching" — answered once, at the foot of the sidebar, instead of
 * three times in three different shapes at the top of the three screens (Sync used to say it
 * <em>twice</em>). Purely informative: the focus never reaches it.
 *
 * <p>Two rules that look like details and are not:
 * <ul>
 *   <li><b>Nothing selected → the block is not drawn at all.</b></li>
 *   <li><b>Something selected → all three rows, always.</b> It is anchored to the bottom, so a block
 *       whose height depended on its state would make that anchor jump on every subtitle change.</li>
 * </ul>
 */
public class SubsSelectedBlock extends LinearLayout {

    /**
     * Listed in display order; {@link #pick} resolves overlaps, since several can be true at once
     * (a cached subtitle that is also translated, a translated one whose download then failed).
     */
    public enum Status {
        READY("READY"),
        CACHED("CACHED"),
        TRANSLATED("TRANSLATED"),
        EXTRACTING("EXTRACTING"),
        LOADING("LOADING…"),
        FAILED("FAILED");

        public final String text;

        Status(String text) {
            this.text = text;
        }
    }

    private final TextView caption;
    private final TextView value;
    private final TextView status;

    public SubsSelectedBlock(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setPadding(SubsTheme.dp(c, 10), 0, SubsTheme.dp(c, 10), 0);
        setVisibility(GONE);

        // The block's own top edge: it separates "where you can go" from "what you are watching".
        View rule = new View(c);
        rule.setBackgroundColor(SubsTheme.EDGE);
        LayoutParams rlp = new LayoutParams(LayoutParams.MATCH_PARENT, SubsTheme.dp(c, 1));
        rlp.bottomMargin = SubsTheme.dp(c, 13);
        addView(rule, rlp);

        caption = SubsTheme.labelSm(new TextView(c));
        caption.setText("SELECTED");
        caption.setTextColor(SubsTheme.INK_3);
        addView(caption, row(c, 0));

        value = new TextView(c);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        value.setLetterSpacing(.01f);
        value.setTypeface(SubsTheme.semibold(c));
        value.setIncludeFontPadding(false);
        value.setTextColor(SubsTheme.ON_SURFACE);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        addView(value, row(c, 6));

        status = new TextView(c);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, SubsTheme.LABEL_SM_SP);
        status.setLetterSpacing(.08f);
        status.setTypeface(SubsTheme.bold(c));
        status.setIncludeFontPadding(false);
        status.setTextColor(SubsTheme.INK_3);
        status.setSingleLine(true);
        addView(status, row(c, 6));
    }

    private static LayoutParams row(Context c, int topMarginDp) {
        LayoutParams p = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        p.topMargin = SubsTheme.dp(c, topMarginDp);
        p.gravity = Gravity.START;
        return p;
    }

    /** {@code label == null} hides the block — that is the "nothing chosen" case, not an empty one. */
    public void set(@Nullable String flag, @Nullable String label, Status st) {
        if (label == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        value.setText(flag != null ? flag + "  " + label : label);
        value.setTextColor(st == Status.FAILED ? SubsTheme.ERROR : SubsTheme.ON_SURFACE);
        status.setText(st.text);
        status.setTextColor(colorFor(st));
    }

    private static int colorFor(Status st) {
        switch (st) {
            case TRANSLATED: return SubsTheme.PRIMARY;
            case FAILED: return SubsTheme.ERROR;
            default: return SubsTheme.INK_3;
        }
    }

    /**
     * Resolves the overlap the design fixes an order for: FAILED › LOADING › TRANSLATED › CACHED ›
     * READY, with EXTRACTING slotted just under LOADING since it is the same "still working on it"
     * answer for an embedded track.
     */
    public static Status pick(boolean failed, boolean loading, boolean extracting, boolean translated,
                              boolean cached) {
        if (failed) return Status.FAILED;
        if (loading) return Status.LOADING;
        if (extracting) return Status.EXTRACTING;
        if (translated) return Status.TRANSLATED;
        if (cached) return Status.CACHED;
        return Status.READY;
    }
}
