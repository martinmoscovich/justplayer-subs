package com.brouken.player.subs.ui;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

/** Small text utilities the panel needs in more than one screen. */
public final class SubsText {

    private SubsText() {}

    /**
     * Builds a hint line where the parts that matter are brighter than the scaffolding around them.
     * Segments wrapped in {@code *asterisks*} get {@code ink-secondary}, everything else stays
     * {@code ink-muted} — so "◄ ► *MOVE* · OK: *SELECT*" reads as two verbs with punctuation around
     * them rather than as one grey sentence.
     */
    public static CharSequence hint(String marked) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean emph = false;
        for (String part : marked.split("\\*", -1)) {
            if (!part.isEmpty()) {
                int start = out.length();
                out.append(part);
                out.setSpan(new ForegroundColorSpan(emph ? SubsTheme.INK_2 : SubsTheme.INK_3),
                        start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            emph = !emph;
        }
        return out;
    }
}
