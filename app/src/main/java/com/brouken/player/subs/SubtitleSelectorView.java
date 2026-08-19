package com.brouken.player.subs;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.brouken.player.R;
import com.brouken.player.subs.ui.SubsCenteredBlock;
import com.brouken.player.subs.ui.SubsChip;
import com.brouken.player.subs.ui.SubsFocus;
import com.brouken.player.subs.ui.SubsIcons;
import com.brouken.player.subs.ui.SubsShapes;
import com.brouken.player.subs.ui.SubsTab;
import com.brouken.player.subs.ui.SubsTheme;

import subtitleengine.provider.MatchStrategy;
import subtitleengine.selection.SubtitleOptionSorter;

/**
 * Subtitle-source selector: a tab row — <b>Preferred</b> (the user's target languages) /
 * <b>Others</b>, each backed by the engine's {@link SubtitleOptionSorter} — over a vertical list for
 * the active tab, sectioned by source (<b>Embedded</b> / <b>External</b> / <b>Provider</b>, empty
 * sections hidden), with a <b>Done</b> bar anchored below it, outside the scroll.
 *
 * <p>Each option is a card: a mark, a flag, the name, and chips. The chips replace the
 * double-space-concatenated meta text this screen used to carry, and they are split by <em>what kind
 * of fact</em> they are — properties of the file sit next to the title, states of the row are
 * right-aligned so that column can be scanned straight down.
 *
 * <p>The row that is <b>playing</b> and the row that has the <b>focus</b> are both on screen at once
 * and must not be confused: focus is a solid white card, playing is a cyan edge bar plus a play glyph
 * plus a cyan label. When they land on the same row the white wins and the play glyph survives it —
 * a difference of <em>shape</em>, which the colour inversion cannot take away.
 *
 * <p>Which subtitle is currently in use is no longer stated here at all: it lives at the foot of the
 * sidebar (see {@code SubsSelectedBlock}), in one place for all three screens.
 */
public class SubtitleSelectorView extends FrameLayout {

    public interface Listener {
        void onSelect(String optionId);
        /** ◄ / Back — move focus to the sidebar (the screen list). */
        void onOpenMenu();
        /** Done — close the panel. */
        void onRequestClose();
    }

    private enum TabKey { PREFERRED, OTHERS }
    private enum Focus { TABS, LIST }

    private static final float ROW_H_DP = 40f;
    private static final float EDGE_BAR_DP = 4f;

    /** One option row's views, kept so styling never has to walk the hierarchy again. */
    private static final class Row {
        final LinearLayout container;
        final GradientDrawable bg;
        final View edgeBar;
        final ImageView mark;
        final TextView label;
        final List<SubsChip> chips = new ArrayList<>();
        @Nullable SubsFocus.Skin painted;
        boolean spinning;

        Row(LinearLayout container, GradientDrawable bg, View edgeBar, ImageView mark, TextView label) {
            this.container = container;
            this.bg = bg;
            this.edgeBar = edgeBar;
            this.mark = mark;
            this.label = label;
        }
    }

    private final LinearLayout tabBar;
    private final SubsTab preferredTabView;
    private final SubsTab othersTabView;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final SubsCenteredBlock centeredBlock;
    private final TextView doneBar;
    private final GradientDrawable doneBg;
    @Nullable private SubsFocus.Skin donePainted;
    private Listener listener;

    private final Map<String, SubtitleOption> byId = new HashMap<>();
    private final List<SubtitleOption> ordered = new ArrayList<>();
    private final List<Row> rowViews = new ArrayList<>();
    private SubtitleOptionSorter.Result grouping = SubtitleOptionSorter.group(List.of(), List.of(), List.of());
    private TabKey activeTab = TabKey.PREFERRED;
    private String selectedId;
    private boolean loadingMore;
    private boolean focused;
    private Focus focus = Focus.LIST;
    private int focusIndex; // 0..ordered.size()-1 = option rows; ordered.size() = Done

    public SubtitleSelectorView(Context c) {
        super(c);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(16), dp(24), dp(14));
        addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tabBar = new LinearLayout(c);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        preferredTabView = new SubsTab(c, "Preferred");
        othersTabView = new SubsTab(c, "Others");
        tabBar.addView(preferredTabView, preferredTabView.rowParams());
        tabBar.addView(othersTabView, othersTabView.rowParams());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(14);
        col.addView(tabBar, tlp);

        // The list and the "nothing to show" block occupy the same box: only one of them is ever up.
        FrameLayout stage = new FrameLayout(c);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(16);
        col.addView(stage, slp);

        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        // No scrollbar: the panel has no pointer to drag one with, and the list already reports its
        // own position by moving the focused card into view.
        scroll.setVerticalScrollBarEnabled(false);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        stage.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        centeredBlock = new SubsCenteredBlock(c);
        centeredBlock.setVisibility(GONE);
        stage.addView(centeredBlock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Built once and pinned below the list, outside the scroll, so it stays reachable however
        // many subtitles there are. Wide rather than a pill: an unmissable target for a D-pad.
        doneBar = SubsTheme.labelLg(new TextView(c));
        doneBar.setText("Done");
        doneBar.setGravity(Gravity.CENTER);
        doneBar.setTextColor(SubsTheme.INK);
        doneBg = SubsShapes.rounded(c, SubsTheme.SURFACE_3, SubsTheme.RADIUS_ROW_DP);
        doneBar.setBackground(doneBg);
        doneBar.setFocusable(true);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        dlp.topMargin = dp(12);
        col.addView(doneBar, dlp);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setOptions(List<SubtitleOption> options, String selectedId, boolean loadingMore) {
        this.selectedId = selectedId;
        this.loadingMore = loadingMore;

        byId.clear();
        List<SubtitleOptionSorter.Ref> refs = new ArrayList<>();
        if (options != null) {
            for (SubtitleOption o : options) {
                byId.put(o.id, o);
                refs.add(new SubtitleOptionSorter.Ref(o.id, o.language, sorterSource(o.source),
                        o.matchStrategy == MatchStrategy.HASH));
            }
        }
        List<String> target = SubtitleSettings.getLanguageList(getContext(), SubtitleSettings.KEY_TARGET_LANGS);
        List<String> source = SubtitleSettings.getLanguageList(getContext(), SubtitleSettings.KEY_SOURCE_LANGS);
        grouping = SubtitleOptionSorter.group(refs, target, source);

        // Never leave the active tab pointed at an empty one when the other has content.
        if (languageGroup(activeTab).isEmpty() && !languageGroup(other(activeTab)).isEmpty()) {
            activeTab = other(activeTab);
        }

        rebuildOrderedForActiveTab();
        if (focusIndex > ordered.size()) focusIndex = ordered.size();
        rebuild();
    }

    private static SubtitleOptionSorter.Source sorterSource(SubtitleOption.Source s) {
        switch (s) {
            case EMBEDDED: return SubtitleOptionSorter.Source.EMBEDDED;
            case PROVIDER: return SubtitleOptionSorter.Source.PROVIDER;
            default:       return SubtitleOptionSorter.Source.EXTERNAL;
        }
    }

    public void setFocused(boolean f) {
        focused = f;
        if (f) {
            focus = Focus.LIST;
            TabKey target = tabContaining(selectedId);
            if (target != null) activeTab = target;
            rebuildOrderedForActiveTab();
            focusIndex = ordered.isEmpty() ? 0 : indexOfSelected();
            rebuild();
        } else {
            styleAll();
        }
    }

    public boolean isEmpty() {
        return !grouping.anyOptions;
    }

    public boolean handleKey(int keyCode) {
        return focus == Focus.TABS ? handleTabsKey(keyCode) : handleListKey(keyCode);
    }

    private boolean handleTabsKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (activeTab == TabKey.OTHERS && !languageGroup(TabKey.PREFERRED).isEmpty()) {
                    switchTab(TabKey.PREFERRED);
                } else if (listener != null) {
                    listener.onOpenMenu();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (activeTab == TabKey.PREFERRED && !languageGroup(TabKey.OTHERS).isEmpty()) {
                    switchTab(TabKey.OTHERS);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!ordered.isEmpty()) {
                    focus = Focus.LIST;
                    focusIndex = 0;
                    styleAll();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) listener.onOpenMenu();
                return true;
            default:
                return false;
        }
    }

    private boolean handleListKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // The tab row is only worth entering when there is a second tab to switch to.
                if (focusIndex == 0) {
                    if (tabsVisible()) focus = Focus.TABS;
                } else {
                    focusIndex--;
                }
                styleAll();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focusIndex = Math.min(ordered.size(), focusIndex + 1);
                styleAll();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) listener.onOpenMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (listener == null) return true;
                if (focusIndex >= ordered.size()) {
                    listener.onRequestClose();
                } else {
                    SubtitleOption o = ordered.get(focusIndex);
                    if (o.state != SubtitleOption.State.LOADING) {
                        listener.onSelect(o.id);
                        focusIndex = ordered.size(); // jump to Done for an easy confirm
                        styleAll();
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private void switchTab(TabKey t) {
        if (activeTab == t) return;
        activeTab = t;
        rebuildOrderedForActiveTab();
        focusIndex = 0;
        rebuild();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private SubtitleOptionSorter.LanguageGroup languageGroup(TabKey key) {
        return key == TabKey.PREFERRED ? grouping.preferred : grouping.others;
    }

    private static TabKey other(TabKey key) {
        return key == TabKey.PREFERRED ? TabKey.OTHERS : TabKey.PREFERRED;
    }

    private TabKey tabContaining(String id) {
        if (id == null) return null;
        if (containsId(grouping.preferred, id)) return TabKey.PREFERRED;
        if (containsId(grouping.others, id)) return TabKey.OTHERS;
        return null;
    }

    private static boolean containsId(SubtitleOptionSorter.LanguageGroup group, String id) {
        for (SubtitleOptionSorter.SourceGroup g : group.sourceGroups) {
            if (g.optionIds.contains(id)) return true;
        }
        return false;
    }

    private void rebuildOrderedForActiveTab() {
        ordered.clear();
        SubtitleOptionSorter.LanguageGroup group = languageGroup(activeTab);
        for (SubtitleOptionSorter.SourceGroup g : group.sourceGroups) {
            for (String id : g.optionIds) {
                SubtitleOption o = byId.get(id);
                if (o != null) ordered.add(o);
            }
        }
    }

    private int indexOfSelected() {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id.equals(selectedId)) return i;
        }
        return 0;
    }

    /** With nothing to choose between, a tab row is two labels explaining an empty screen. */
    private boolean tabsVisible() {
        return grouping.anyOptions;
    }

    private void rebuild() {
        for (Row r : rowViews) SubsIcons.stopSpin(r.mark);
        column.removeAllViews();
        rowViews.clear();

        boolean any = grouping.anyOptions;
        tabBar.setVisibility(tabsVisible() ? VISIBLE : GONE);

        if (!any && !loadingMore) {
            scroll.setVisibility(GONE);
            centeredBlock.show(R.drawable.subtitle_ic_subtitles, false, "No subtitles available",
                    "Nothing embedded, nothing found online", -1f);
            if (focus == Focus.TABS) focus = Focus.LIST;
            focusIndex = 0;
            styleAll();
            return;
        }
        centeredBlock.hide();
        scroll.setVisibility(VISIBLE);

        SubtitleOptionSorter.LanguageGroup group = languageGroup(activeTab);
        boolean first = true;
        for (SubtitleOptionSorter.SourceGroup g : group.sourceGroups) {
            column.addView(sectionHeader(g.title), sectionParams(first));
            first = false;
            for (String id : g.optionIds) {
                SubtitleOption o = byId.get(id);
                if (o != null) addRow(o);
            }
        }
        if (loadingMore) column.addView(quietRow("loading more…"));

        styleAll();
    }

    // --- row construction ---

    private void addRow(SubtitleOption o) {
        Context c = getContext();
        LinearLayout container = new LinearLayout(c);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(0, 0, dp(16), 0);
        container.setFocusable(true);
        GradientDrawable bg = SubsShapes.rounded(c, SubsTheme.SURFACE_2, SubsTheme.EDGE, SubsTheme.RADIUS_ROW_DP);
        container.setBackground(bg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp((int) ROW_H_DP));
        cp.leftMargin = dp(4);
        cp.rightMargin = dp(4);
        cp.bottomMargin = dp(6);
        container.setLayoutParams(cp);

        // The playing row's 4dp accent: a real child rather than a border, so it can keep its colour
        // when the white focus fill takes the rest of the row.
        View edgeBar = new View(c);
        edgeBar.setBackground(SubsShapes.leftRounded(c, SubsTheme.PRIMARY_CONTAINER, SubsTheme.RADIUS_ROW_DP));
        edgeBar.setVisibility(GONE);
        container.addView(edgeBar, new LinearLayout.LayoutParams(
                dp((int) EDGE_BAR_DP), ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView mark = SubsIcons.icon(c, R.drawable.subtitle_ic_circle, SubsTheme.INK_3, 18f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(18), dp(18));
        mlp.leftMargin = dp(16);
        mlp.rightMargin = dp(12);
        container.addView(mark, mlp);

        String flag = LanguageFlags.flagFor(o.language);
        if (flag != null) {
            TextView flagView = new TextView(c);
            flagView.setText(flag);
            flagView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
            flagView.setIncludeFontPadding(false);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.rightMargin = dp(12);
            container.addView(flagView, flp);
        }

        TextView label = SubsTheme.bodyLg(new TextView(c));
        label.setText(titleFor(o, flag != null));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        // Sized to its text, capped, rather than weighted: the chips have to stay welded to the end
        // of the name (that is what makes them read as facts about it), and a weighted label grows
        // into the spare room and drags them off to the right. The cap is what an unusually long
        // track name ellipsizes at, so a full chip row always fits beside it.
        label.setMaxWidth(dp(300));
        container.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Row row = new Row(container, bg, edgeBar, mark, label);

        // Facts about the file, pinned to the title.
        addChip(container, row, o.format != null ? new SubsChip(c, SubsChip.Kind.FORMAT, o.format) : null, 2);
        addChip(container, row, matchChip(c, o), 0);
        if (o.rating > 0) {
            addChip(container, row, new SubsChip(c, SubsChip.Kind.STAT,
                    "★ " + String.format(Locale.US, "%.1f", o.rating)), 0);
        }
        if (o.downloadCount > 0) {
            addChip(container, row, new SubsChip(c, SubsChip.Kind.STAT, "⬇ " + compactCount(o.downloadCount)), 0);
        }

        View spacer = new View(c);
        container.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // States of the row, right-aligned so the column reads straight down.
        if (o.state == SubtitleOption.State.LOADING) {
            addChip(container, row, new SubsChip(c, SubsChip.Kind.BUSY, "Downloading…"), 0);
        } else if (o.state == SubtitleOption.State.ERROR) {
            addChip(container, row, new SubsChip(c, SubsChip.Kind.FAIL, "Failed"), 0);
        }
        // Translated implies extracted (translating an embedded track needs its cues first) — only
        // the higher one is shown, never both stacked.
        if (o.translated) {
            addChip(container, row, new SubsChip(c, SubsChip.Kind.STATE, "Translated"), 0);
        } else if (o.extracted) {
            addChip(container, row, new SubsChip(c, SubsChip.Kind.STATE, "Extracted"), 0);
        }
        // Independent of the chip above — syncing and translating are unrelated actions on the same
        // subtitle, so both can show together.
        if (o.synced) {
            addChip(container, row, new SubsChip(c, SubsChip.Kind.STATE, "Synced"), 0);
        }

        column.addView(container);
        rowViews.add(row);
    }

    private void addChip(LinearLayout container, Row row, @Nullable SubsChip chip, int extraLeftDp) {
        if (chip == null) return;
        LinearLayout.LayoutParams p = chip.gapParams();
        p.leftMargin += dp(extraLeftDp);
        container.addView(chip, p);
        row.chips.add(chip);
    }

    @Nullable
    private static SubsChip matchChip(Context c, SubtitleOption o) {
        // TITLE is the least trustworthy match and not worth calling out — see MatchStrategy.
        if (o.matchStrategy == MatchStrategy.HASH) return new SubsChip(c, SubsChip.Kind.MATCH, "Hash");
        if (o.matchStrategy == MatchStrategy.MEDIA_ID) return new SubsChip(c, SubsChip.Kind.MATCH, "IMDB");
        return null;
    }

    /**
     * The name without the language code the label already carries — the flag says the language now,
     * and dropping the code is what leaves room for the chips. Only stripped when there <em>is</em> a
     * flag: with an unrecognised language the code is the only clue left, so it stays.
     */
    static String titleFor(SubtitleOption o, boolean hasFlag) {
        String label = o.label;
        if (!hasFlag || o.language == null) return label;
        String code = o.language.toUpperCase(Locale.ROOT);
        for (String sep : new String[]{" · ", " - "}) {
            String prefix = code + sep;
            if (label.length() > prefix.length() && label.toUpperCase(Locale.ROOT).startsWith(prefix)) {
                return label.substring(prefix.length());
            }
        }
        return label;
    }

    /** "4.8k", not "4,783": at three metres the order of magnitude is the whole message. */
    static String compactCount(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.US, "%.1fk", n / 1000.0);
        return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
    }

    private TextView sectionHeader(String text) {
        TextView tv = SubsTheme.labelSm(new TextView(getContext()));
        tv.setText(text.toUpperCase(Locale.ROOT));
        tv.setTextColor(SubsTheme.INK_3);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setHeight(dp(26));
        // The 2dp rule the design puts before the header, drawn as a compound drawable so the header
        // stays one view: a section header is a label, not a layout.
        android.graphics.drawable.GradientDrawable tick =
                SubsShapes.rounded(getContext(), SubsTheme.OUTLINE, 1f);
        tick.setSize(dp(2), dp(13));
        tv.setCompoundDrawablesWithIntrinsicBounds(tick, null, null, null);
        tv.setCompoundDrawablePadding(dp(9));
        tv.setPadding(dp(4), 0, 0, 0);
        return tv;
    }

    private LinearLayout.LayoutParams sectionParams(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = first ? 0 : dp(14);
        p.bottomMargin = dp(4);
        return p;
    }

    /** A row that says something is happening but is not a target: no card, no focus, centred. */
    private LinearLayout quietRow(String text) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        ImageView spinner = SubsIcons.icon(c, R.drawable.subtitle_ic_spinner, SubsTheme.INK_3, 18f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(18), dp(18));
        sp.rightMargin = dp(9);
        r.addView(spinner, sp);
        SubsIcons.spin(spinner);
        TextView tv = SubsTheme.bodyMd(new TextView(c));
        tv.setText(text);
        tv.setTextColor(SubsTheme.INK_3);
        r.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        return r;
    }

    // --- styling ---

    private void styleAll() {
        styleTabBar();
        styleRows();
        styleDone();
    }

    private void styleTabBar() {
        boolean preferredEmpty = languageGroup(TabKey.PREFERRED).isEmpty();
        boolean othersEmpty = languageGroup(TabKey.OTHERS).isEmpty();
        boolean tabsFocused = focused && focus == Focus.TABS;
        preferredTabView.setState(tabsFocused && activeTab == TabKey.PREFERRED,
                activeTab == TabKey.PREFERRED, preferredEmpty, true);
        othersTabView.setState(tabsFocused && activeTab == TabKey.OTHERS,
                activeTab == TabKey.OTHERS, othersEmpty, true);
    }

    private void styleRows() {
        for (int i = 0; i < rowViews.size() && i < ordered.size(); i++) {
            Row rv = rowViews.get(i);
            SubtitleOption o = ordered.get(i);
            boolean playing = o.id.equals(selectedId);
            boolean isFocus = focused && focus == Focus.LIST && i == focusIndex;
            boolean error = o.state == SubtitleOption.State.ERROR;

            int fill = isFocus ? SubsTheme.INK : (playing ? SubsTheme.PRIMARY_14 : SubsTheme.SURFACE_2);
            int stroke = isFocus ? SubsTheme.INK : (playing ? SubsTheme.PRIMARY_28 : SubsTheme.EDGE);
            int ink = isFocus ? SubsTheme.ON_SECONDARY
                    : (error ? SubsTheme.ERROR : (playing ? SubsTheme.PRIMARY : SubsTheme.INK));
            SubsFocus.Skin to = new SubsFocus.Skin(fill, stroke, ink);
            SubsFocus.apply(rv.container, rv.bg, rv.painted, to,
                    Collections.singletonList(rv.label), true, 1f);
            rv.painted = to;

            rv.edgeBar.setVisibility(playing ? VISIBLE : GONE);
            styleMark(rv, o, playing, isFocus, error);
            for (SubsChip chip : rv.chips) chip.setOnLight(isFocus);

            // A real focus request makes the enclosing ScrollView bring the row into view, so the
            // list never leaves the focused item off-screen.
            if (isFocus) rv.container.requestFocus();
        }
    }

    /**
     * The mark is the one signal that survives every recolouring, so it is set by <em>shape</em>
     * first: play for the row in use, a ring for the rest, a spinner while it loads, a warning when
     * it failed. Colour only ranks it afterwards.
     */
    private void styleMark(Row rv, SubtitleOption o, boolean playing, boolean isFocus, boolean error) {
        boolean loading = o.state == SubtitleOption.State.LOADING;
        int icon = loading ? R.drawable.subtitle_ic_spinner
                : error ? R.drawable.subtitle_ic_warning
                : playing ? R.drawable.subtitle_ic_play
                : R.drawable.subtitle_ic_circle;
        rv.mark.setImageResource(icon);
        if (loading != rv.spinning) {
            rv.spinning = loading;
            if (loading) SubsIcons.spin(rv.mark); else SubsIcons.stopSpin(rv.mark);
        }
        int tint = isFocus ? SubsTheme.ON_SECONDARY
                : (error ? SubsTheme.ERROR : (playing ? SubsTheme.PRIMARY_CONTAINER : SubsTheme.INK_3));
        SubsIcons.tint(rv.mark, tint);
    }

    private void styleDone() {
        boolean doneFocus = focused && focus == Focus.LIST && focusIndex >= ordered.size();
        SubsFocus.Skin to = doneFocus
                ? new SubsFocus.Skin(SubsTheme.INK, 0, SubsTheme.ON_SECONDARY)
                : new SubsFocus.Skin(SubsTheme.SURFACE_3, 0, SubsTheme.INK);
        SubsFocus.apply(doneBar, doneBg, donePainted, to, Collections.singletonList(doneBar), true, 1f);
        donePainted = to;
        if (doneFocus) doneBar.requestFocus();
    }

    private int dp(int v) {
        return SubsTheme.dp(getContext(), v);
    }
}
