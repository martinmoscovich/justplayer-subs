package com.brouken.player.subs.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.brouken.player.R;

/**
 * The design tokens of the subtitle panel: the palette, the six type roles and the sizes, all
 * transcribed from {@code ux/UI-REDESIGN.html} exactly as written there. Nothing here reads a theme
 * attribute or a {@code res/values} resource — the panel deliberately owns its own look so that
 * nothing outside {@code com.brouken.player.subs} has to change (the player is a fork with a live
 * upstream, see CLAUDE.md).
 *
 * <p>Every measurement in the design is dp/sp over a 960×540 dp canvas — which is what a 1080p TV at
 * density 2.0 and a 4K TV at density 4.0 both report — so the numbers below are the ones from the
 * document, unscaled.
 */
public final class SubsTheme {

    private SubsTheme() {}

    // --- surfaces ---
    public static final int BG = 0xFF0E1416;
    public static final int SURFACE_1 = 0xFF161D1E;
    public static final int SURFACE_2 = 0xFF1A2122;
    public static final int SURFACE_3 = 0xFF242B2D;
    public static final int SURFACE_4 = 0xFF2F3638;
    /** {@code rgba(15,23,42,.75)} — sidebar, capsules, figure panels, modals. */
    public static final int PANEL = 0xBF0F172A;
    /** {@code rgba(255,255,255,.10)} — the hairline around every panel-bg surface. */
    public static final int EDGE = 0x1AFFFFFF;
    /** Black veil over the video behind the whole panel. */
    public static final int VEIL = 0xB3000000;
    /** Black veil under a modal — covers the content area only, never the sidebar. */
    public static final int SCRIM = 0x8C000000;

    // --- accents ---
    /** Ink cyan: text on dark. Selected row label, active dialogue, time figure, TRANSLATED. */
    public static final int PRIMARY = 0xFF8AEBFF;
    /** Fill cyan: the 4dp edge bar, the playhead, progress fills. */
    public static final int PRIMARY_CONTAINER = 0xFF22D3EE;
    /** Ink on any light fill — white focus and cyan badges alike. */
    public static final int ON_SECONDARY = 0xFF283044;
    /** {@code rgba(34,211,238,.20)} — the fill of anything chosen but not focused. */
    public static final int PRIMARY_20 = 0x3322D3EE;
    /** {@code rgba(34,211,238,.14)} — the softer fill a playing list row gets. */
    public static final int PRIMARY_14 = 0x2422D3EE;
    /** {@code rgba(34,211,238,.55)} — border of an active tab. */
    public static final int PRIMARY_55 = 0x8C22D3EE;
    /** {@code rgba(34,211,238,.28)} — border of a playing row. */
    public static final int PRIMARY_28 = 0x4722D3EE;
    /** {@code rgba(34,211,238,.30)} — border of a state chip. */
    public static final int PRIMARY_30 = 0x4D22D3EE;

    // --- inks ---
    public static final int INK = 0xFFFFFFFF;
    public static final int ON_SURFACE = 0xFFDDE4E5;
    public static final int INK_2 = 0xFFB4C7CE;
    public static final int INK_3 = 0xFF647C86;
    public static final int OUTLINE = 0xFF859397;
    /** Disabled: the foreground of something the focus skips entirely. */
    public static final int DISABLED = 0xFF3C494C;

    // --- semantics ---
    public static final int TERTIARY = 0xFFFFD6A3;
    public static final int TERTIARY_CONTAINER = 0xFFFFB13B;
    /** {@code rgba(255,214,163,.10)} — fill of match and busy chips. */
    public static final int TERTIARY_10 = 0x1AFFD6A3;
    public static final int ERROR = 0xFFFFB4AB;
    public static final int ERROR_CONTAINER = 0xFF93000A;
    public static final int ON_ERROR_CONTAINER = 0xFFFFDAD6;

    // --- chunk-bar semantics: never reused anywhere else ---
    public static final int STATUS_DONE = 0xFF4FBF7A;
    public static final int STATUS_TRANSLATING = 0xFFE8B93F;
    public static final int STATUS_FAILED = 0xFFD9584F;
    public static final int STATUS_PENDING = 0xFF3F465C;
    public static final int STATUS_EXTRACTING = 0xFF52626F;
    public static final int STATUS_CLOSED = 0xFFF4F7F9;

    // --- inks used on top of the white focus fill (chips invert rather than vanish) ---
    public static final int ON_LIGHT_MUTED = 0xFF4C5A60;
    public static final int ON_LIGHT_MATCH = 0xFF6E4600;
    public static final int ON_LIGHT_STATE = 0xFF005763;
    /** {@code rgba(40,48,68,.30)} / {@code .08} — chip border/fill over the white fill. */
    public static final int ON_LIGHT_BORDER = 0x4D283044;
    public static final int ON_LIGHT_FILL = 0x14283044;
    /** {@code rgba(110,70,0,.10)} / {@code rgba(0,87,99,.10)} / {@code rgba(0,87,99,.25)}. */
    public static final int ON_LIGHT_MATCH_FILL = 0x1A6E4600;
    public static final int ON_LIGHT_STATE_FILL = 0x1A005763;
    public static final int ON_LIGHT_STATE_BORDER = 0x40005763;

    // --- type roles (sp) ---
    /** Above the headline roles, and used by exactly one thing: the playback clock on Sync and
     *  Translate. Those two screens are read from the couch while the video keeps playing, so the
     *  clock has to be legible at ~3m — a step above the largest headline, not a jumbo readout (48sp
     *  was tried on the device and read as oversized). Not a general role: if a second element ever
     *  wants this size, the scale needs a real decision, not a second caller. */
    public static final float DISPLAY_SP = 40f;
    public static final float HEADLINE_LG_SP = 32f;
    public static final float HEADLINE_MD_SP = 24f;
    public static final float BODY_LG_SP = 20f;
    public static final float BODY_MD_SP = 18f;
    public static final float LABEL_LG_SP = 15f;
    public static final float LABEL_SM_SP = 12f;

    // --- type roles (tracking, em) ---
    public static final float DISPLAY_TRACK = .01f;
    public static final float HEADLINE_LG_TRACK = .02f;
    public static final float HEADLINE_MD_TRACK = .01f;
    public static final float BODY_TRACK = .01f;
    public static final float LABEL_LG_TRACK = .05f;
    public static final float LABEL_SM_TRACK = .1f;

    // --- motion ---
    /** Focus fill + text colour crossfade. Long enough not to blink, short enough not to lag. */
    public static final long FOCUS_MS = 130L;
    /** A chip never appears from nothing: alpha only. */
    public static final long CHIP_MS = 150L;
    /** Scale of a focused button / sidebar item. List rows never scale — see the design doc. */
    public static final float FOCUS_SCALE = 1.03f;

    // --- shared geometry ---
    /**
     * 152dp, not the 140dp of the design document. Measured against the shipped Inter SemiBold,
     * "Translate" at {@code label-lg} is 75.8dp wide; the 140dp rail leaves 66dp for it once the
     * sidebar padding, the item padding, the 20dp glyph and its gap are taken out, so the longest
     * item clipped mid-word on device. Widening the rail (plus 8dp item padding instead of 10dp)
     * buys 82dp and keeps every type and spacing token exactly as specified — the alternative was
     * shrinking the label, which is the one thing a 3-metre UI cannot afford.
     */
    public static final float SIDEBAR_W_DP = 152f;
    public static final float RADIUS_ROW_DP = 8f;
    public static final float RADIUS_CHIP_DP = 4f;
    public static final float RADIUS_PILL_DP = 10f;
    public static final float RADIUS_PANEL_DP = 16f;
    public static final float HAIRLINE_DP = 1f;

    // --- fonts ---

    private static Typeface regular, medium, semibold, bold;

    public static Typeface regular(Context c) {
        if (regular == null) regular = font(c, R.font.subtitle_inter_regular);
        return regular;
    }

    public static Typeface medium(Context c) {
        if (medium == null) medium = font(c, R.font.subtitle_inter_medium);
        return medium;
    }

    public static Typeface semibold(Context c) {
        if (semibold == null) semibold = font(c, R.font.subtitle_inter_semibold);
        return semibold;
    }

    public static Typeface bold(Context c) {
        if (bold == null) bold = font(c, R.font.subtitle_inter_bold);
        return bold;
    }

    /** Never throws: a missing/broken font file degrades to the system sans, it doesn't kill the panel. */
    private static Typeface font(Context c, int resId) {
        try {
            Typeface t = ResourcesCompat.getFont(c, resId);
            if (t != null) return t;
        } catch (Exception ignored) {
            // Resources.NotFoundException, or a font the platform refuses to parse.
        }
        return Typeface.SANS_SERIF;
    }

    // --- the six roles, applied ---

    public static <T extends TextView> T display(T tv) {
        style(tv, DISPLAY_SP, DISPLAY_TRACK, bold(tv.getContext()));
        // Tabular figures: without them the digits have different widths, so a running clock jitters
        // sideways every tick. A no-op on a font that lacks the feature.
        tv.setFontFeatureSettings("tnum");
        return tv;
    }

    public static <T extends TextView> T headlineLg(T tv) {
        return style(tv, HEADLINE_LG_SP, HEADLINE_LG_TRACK, bold(tv.getContext()));
    }

    public static <T extends TextView> T headlineMd(T tv) {
        return style(tv, HEADLINE_MD_SP, HEADLINE_MD_TRACK, semibold(tv.getContext()));
    }

    public static <T extends TextView> T bodyLg(T tv) {
        return style(tv, BODY_LG_SP, BODY_TRACK, medium(tv.getContext()));
    }

    public static <T extends TextView> T bodyMd(T tv) {
        return style(tv, BODY_MD_SP, BODY_TRACK, regular(tv.getContext()));
    }

    public static <T extends TextView> T labelLg(T tv) {
        return style(tv, LABEL_LG_SP, LABEL_LG_TRACK, semibold(tv.getContext()));
    }

    public static <T extends TextView> T labelSm(T tv) {
        return style(tv, LABEL_SM_SP, LABEL_SM_TRACK, bold(tv.getContext()));
    }

    private static <T extends TextView> T style(T tv, float sp, float tracking, @Nullable Typeface face) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setLetterSpacing(tracking);
        if (face != null) tv.setTypeface(face);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    // --- units ---

    public static int dp(Resources res, float v) {
        return Math.round(v * res.getDisplayMetrics().density);
    }

    public static int dp(Context c, float v) {
        return dp(c.getResources(), v);
    }

    public static float dpF(Resources res, float v) {
        return v * res.getDisplayMetrics().density;
    }

    public static float spToPx(Resources res, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, res.getDisplayMetrics());
    }
}
