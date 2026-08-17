package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import subtitleengine.provider.MatchStrategy;
import subtitleengine.selection.SubtitleOptionSorter;

/**
 * Subtitle-source selector: a top tab bar — <b>Preferred</b> (the user's target languages) /
 * <b>Others</b>, each backed by the engine's {@link SubtitleOptionSorter} — over a vertical list
 * for the active tab, sectioned by source (<b>Embedded</b> / <b>External</b> / <b>Provider</b>,
 * empty sections hidden). A <b>Done</b> button sits at the bottom of the list.
 * Picking a subtitle moves focus to Done (a second OK closes the panel); ◄ switches to the sync
 * screen, Back opens the sidebar menu; ◄/► on the tab bar switches tabs, ▼ enters the list, ▲ from
 * the first row returns to the tab bar. The list is height-bound to the available space (a nested
 * {@link ScrollView}) and follows the focused row via real Android focus, so it never scrolls past
 * what's on screen.
 */
public class SubtitleSelectorView extends LinearLayout {

    public interface Listener {
        void onSelect(String optionId);
        /** ◄ / Back — move focus to the sidebar (the screen list). */
        void onOpenMenu();
        /** Done — close the panel. */
        void onRequestClose();
    }

    private enum TabKey { PREFERRED, OTHERS }
    private enum Focus { TABS, LIST }

    /** One option row: a container (for background), the left-aligned label, the right-aligned
     *  rating/downloads meta text, and the "PLAYING" badge — the last two are {@code null} when the
     *  row has no meta / is not the selected one. */
    private static final class Row {
        final View container;
        final TextView main;
        @Nullable final TextView meta;
        @Nullable final TextView badge;

        Row(View container, TextView main, @Nullable TextView meta, @Nullable TextView badge) {
            this.container = container;
            this.main = main;
            this.meta = meta;
            this.badge = badge;
        }
    }

    private static final int TEAL = 0xFF4DD0E1;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFF90A4AE;
    private static final int EMPTY_TAB = 0xFF4A5A63;
    private static final int ERROR = 0xFFEF9A9A;
    private static final int HEADER = 0xFF7A8A93;

    private final TextView statusLine;
    private final TextView preferredTabView;
    private final TextView othersTabView;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final TextView doneButton;
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
        setOrientation(VERTICAL);

        // Above the tabs, so the answer to "which one am I watching?" is on screen whatever the
        // list is scrolled to, and whichever tab the selected row lives in.
        statusLine = new TextView(c);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusLine.setSingleLine(true);
        statusLine.setEllipsize(TextUtils.TruncateAt.END);
        statusLine.setPadding(dp(16), dp(12), dp(16), dp(4));
        addView(statusLine, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout tabBar = new LinearLayout(c);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(16), dp(12), dp(16), dp(4));
        preferredTabView = tabItem("Preferred");
        othersTabView = tabItem("Others");
        tabBar.addView(preferredTabView, tabItemParams());
        tabBar.addView(othersTabView, tabItemParams());
        addView(tabBar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(4), dp(16), dp(12));
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        // weight=1 + height=0 bounds the list to whatever space is left under the tab bar and the
        // Done button below, instead of letting it grow past the screen.
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // Built once (not per-rebuild): pinned to the bottom, outside the scrollable list, so it's
        // always reachable regardless of how many subtitles there are.
        doneButton = new TextView(c);
        doneButton.setText("Done");
        doneButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        doneButton.setGravity(Gravity.CENTER);
        doneButton.setPadding(dp(14), dp(12), dp(14), dp(12));
        doneButton.setFocusable(true);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        dlp.setMargins(dp(16), dp(8), dp(16), dp(16));
        addView(doneButton, dlp);
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
            focusIndex = indexOfSelected();
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
                if (focusIndex == 0) {
                    focus = Focus.TABS;
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

    private void rebuild() {
        column.removeAllViews();
        rowViews.clear();

        SubtitleOptionSorter.LanguageGroup group = languageGroup(activeTab);
        for (SubtitleOptionSorter.SourceGroup g : group.sourceGroups) {
            column.addView(header(g.title));
            for (String id : g.optionIds) {
                SubtitleOption o = byId.get(id);
                if (o != null) addRow(o);
            }
        }
        if (loadingMore) column.addView(row("⟳ loading more…", DIM));
        if (ordered.isEmpty() && !loadingMore) {
            column.addView(row("No subtitles available", DIM));
        }

        styleAll();
    }

    private void addRow(SubtitleOption o) {
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(dp(14), dp(10), dp(14), dp(10));
        container.setFocusable(true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(2);
        container.setLayoutParams(cp);

        TextView main = new TextView(getContext());
        main.setText(mainText(o));
        main.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        main.setSingleLine(true);
        main.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        main.setLayoutParams(mainLp);
        container.addView(main);

        TextView meta = null;
        String metaText = metaText(o);
        if (!metaText.isEmpty()) {
            meta = new TextView(getContext());
            meta.setText(metaText);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            meta.setGravity(Gravity.END);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaLp.leftMargin = dp(12);
            meta.setLayoutParams(metaLp);
            container.addView(meta);
        }

        // The selected row also turns teal, but focus paints a row white — the badge is what keeps
        // the current choice visible while the user moves the highlight over it.
        TextView badge = null;
        if (o.id.equals(selectedId)) {
            badge = new TextView(getContext());
            badge.setText("PLAYING");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            badge.setTextColor(0xFF00251F);
            badge.setBackgroundColor(TEAL);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.leftMargin = dp(12);
            badgeLp.gravity = Gravity.CENTER_VERTICAL;
            badge.setLayoutParams(badgeLp);
            container.addView(badge);
        }

        column.addView(container);
        rowViews.add(new Row(container, main, meta, badge));
    }

    private void styleAll() {
        styleStatusLine();
        styleTabBar();
        styleRows();
    }

    private void styleStatusLine() {
        SubtitleOption sel = selectedId != null ? byId.get(selectedId) : null;
        if (sel == null) {
            statusLine.setText("No subtitle selected");
            statusLine.setTextColor(DIM);
            return;
        }
        StringBuilder sb = new StringBuilder();
        switch (sel.state) {
            case LOADING: sb.append("⟳ Loading: "); break;
            case ERROR:   sb.append("⚠ Failed: "); break;
            default:      sb.append("▶ Now showing: "); break;
        }
        String flag = LanguageFlags.flagFor(sel.language);
        if (flag != null) sb.append(flag).append(' ');
        sb.append(sel.label);
        if (sel.fromCache && sel.state == SubtitleOption.State.READY) sb.append("  ·  cached");
        statusLine.setText(sb.toString());
        statusLine.setTextColor(sel.state == SubtitleOption.State.ERROR ? ERROR : TEAL);
    }

    private void styleTabBar() {
        styleTab(preferredTabView, TabKey.PREFERRED);
        styleTab(othersTabView, TabKey.OTHERS);
    }

    private void styleTab(TextView tv, TabKey key) {
        boolean empty = languageGroup(key).isEmpty();
        boolean active = activeTab == key;
        boolean tabFocused = focused && focus == Focus.TABS && active;
        if (empty) {
            tv.setTextColor(EMPTY_TAB);
            tv.setBackgroundColor(Color.TRANSPARENT);
        } else if (tabFocused) {
            tv.setTextColor(0xFF000000);
            tv.setBackgroundColor(WHITE);
        } else if (active) {
            tv.setTextColor(TEAL);
            tv.setBackgroundColor(0x334DD0E1);
        } else {
            tv.setTextColor(DIM);
            tv.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private void styleRows() {
        for (int i = 0; i < rowViews.size() && i < ordered.size(); i++) {
            Row rv = rowViews.get(i);
            SubtitleOption o = ordered.get(i);
            boolean isSel = o.id.equals(selectedId);
            boolean isFocus = focused && focus == Focus.LIST && i == focusIndex;
            int mainColor;
            if (isFocus) {
                rv.container.setBackgroundColor(WHITE);
                mainColor = 0xFF000000;
            } else {
                rv.container.setBackgroundColor(isSel ? 0x334DD0E1 : Color.TRANSPARENT);
                mainColor = o.state == SubtitleOption.State.ERROR ? ERROR : (isSel ? TEAL : WHITE);
            }
            rv.main.setTextColor(mainColor);
            if (rv.meta != null) rv.meta.setTextColor(isFocus ? mainColor : DIM);
            // A real focus request makes the enclosing ScrollView auto-scroll the row into view,
            // so the list never leaves the currently focused item off-screen.
            if (isFocus) rv.container.requestFocus();
        }
        boolean doneFocus = focused && focus == Focus.LIST && focusIndex >= ordered.size();
        doneButton.setTextColor(doneFocus ? 0xFF000000 : WHITE);
        doneButton.setBackgroundColor(doneFocus ? WHITE : 0x33FFFFFF);
        if (doneFocus) doneButton.requestFocus();
    }

    private String mainText(SubtitleOption o) {
        String prefix = o.id.equals(selectedId) ? "● " : "○ ";
        String flag = LanguageFlags.flagFor(o.language);
        if (flag != null) prefix = prefix + flag + " ";
        if (o.state == SubtitleOption.State.LOADING) return prefix + o.label + "  ⟳";
        if (o.state == SubtitleOption.State.ERROR) return prefix + o.label + "  ⚠";
        return prefix + o.label;
    }

    private String metaText(SubtitleOption o) {
        StringBuilder sb = new StringBuilder();
        // Only ever set when a sibling option shares this one's label (see SubtitleOption.format) —
        // e.g. the same provider/language offered as both .srt and .vtt.
        if (o.format != null) sb.append(o.format);
        // Translated implies extracted (translating an embedded track needs its cues first) — show
        // only the higher one, not both stacked.
        if (o.translated) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("Translated");
        } else if (o.extracted) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("Extracted");
        }
        // Independent of the chip above — syncing and translating are unrelated actions on the same
        // subtitle, so both can show together.
        if (o.synced) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("Synced");
        }
        // TITLE is the least trustworthy match and not worth calling out — see MatchStrategy.
        if (o.matchStrategy == MatchStrategy.HASH) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("Hash");
        } else if (o.matchStrategy == MatchStrategy.MEDIA_ID) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("IMDB");
        }
        if (o.rating > 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("★").append(String.format(Locale.ROOT, "%.1f", o.rating));
        }
        if (o.downloadCount > 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("⬇").append(String.format(Locale.ROOT, "%,d", o.downloadCount));
        }
        return sb.toString();
    }

    private TextView tabItem(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        return tv;
    }

    private LinearLayout.LayoutParams tabItemParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = dp(4);
        return p;
    }

    private TextView header(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text.toUpperCase());
        tv.setTextColor(HEADER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(8), dp(14), dp(8), dp(6));
        return tv;
    }

    private TextView row(String text, int color) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(2);
        tv.setLayoutParams(p);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
