package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import subtitleengine.sync.ManualSyncSession;

/**
 * Container for the two subtitle screens, switched via a left sidebar:
 * <ul>
 *   <li><b>Subtitles</b> — {@link SubtitleSelectorView} (choose the source).</li>
 *   <li><b>Sync</b> — {@link SyncView} (manual sync of the chosen subtitle).</li>
 * </ul>
 * Focus is either on the sidebar (up/down switch screen, right/OK enter) or on the active screen
 * (left/back return to the sidebar). No domain logic — events go to {@link Callbacks}.
 */
public class SubtitlePanel extends FrameLayout implements SubtitleSelectorView.Listener, SyncView.Listener {

    public interface Callbacks {
        long currentPositionMs();
        boolean isPlaying();
        void onSeek(long deltaMs);
        void onSeekTo(long positionMs);
        void onTogglePlay();
        /** An anchor/nudge changed the sync — re-render the overlay immediately. */
        void onSyncChanged();
        void onSelectOption(String optionId);
        void onOpenSettings();
    }

    private enum Screen { SELECT, SYNC }
    private enum Focus { SIDEBAR, CONTENT }
    private static final int SIDEBAR_SETTINGS = 2;

    private final LinearLayout sidebar;
    private final TextView[] sidebarItems;
    private final SubtitleSelectorView selector;
    private final SyncView syncView;

    private Callbacks callbacks;
    private Screen screen = Screen.SELECT;
    private Focus focus = Focus.SIDEBAR;
    private int sidebarIndex = 0; // 0=Subtitles, 1=Sync, 2=Settings

    public SubtitlePanel(Context context) {
        super(context);
        setBackgroundColor(0xB3000000);
        setVisibility(GONE);
        setClickable(true);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        sidebar = new LinearLayout(context);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(0x22FFFFFF);
        sidebar.setPadding(dp(8), dp(24), dp(8), dp(24));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.MATCH_PARENT);
        row.addView(sidebar, sbLp);

        sidebarItems = new TextView[]{ sidebarItem("Subtitles"), sidebarItem("Sync"), sidebarItem("⚙ Settings") };
        for (TextView it : sidebarItems) sidebar.addView(it);

        FrameLayout content = new FrameLayout(context);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        row.addView(content, cLp);

        selector = new SubtitleSelectorView(context);
        selector.setListener(this);
        content.addView(selector, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        syncView = new SyncView(context);
        syncView.setListener(this);
        content.addView(syncView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setCallbacks(Callbacks cb) {
        this.callbacks = cb;
    }

    public void setOptions(List<SubtitleOption> options, String selectedId, boolean loadingMore) {
        selector.setOptions(options, selectedId, loadingMore);
    }

    /** Binds the engine sync session (null = nothing syncable) into the sync screen. */
    public void setSyncSession(ManualSyncSession session) {
        syncView.setSession(session);
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    public void open() {
        setVisibility(VISIBLE);
        screen = Screen.SELECT; // subtitle button always lands on the selection screen first
        syncView.reset();
        showScreen();
        focusContent();
    }

    public void close() {
        setVisibility(GONE);
    }

    public void onTick(long positionMs) {
        if (isOpen()) syncView.onTick(positionMs);
    }

    public boolean handleKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return isNavKey(event.getKeyCode());
        }
        int keyCode = event.getKeyCode();
        if (focus == Focus.SIDEBAR) return handleSidebarKey(keyCode);
        return screen == Screen.SELECT ? selector.handleKey(keyCode) : syncView.handleKey(keyCode);
    }

    private boolean handleSidebarKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                sidebarIndex = Math.max(0, sidebarIndex - 1);
                applySidebar();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                sidebarIndex = Math.min(sidebarItems.length - 1, sidebarIndex + 1);
                applySidebar();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (sidebarIndex == SIDEBAR_SETTINGS) {
                    if (callbacks != null) callbacks.onOpenSettings();
                } else {
                    focusContent();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                close();
                return true;
            default:
                return false;
        }
    }

    /** Reflects the highlighted sidebar item into the content screen (live preview). */
    private void applySidebar() {
        if (sidebarIndex == 0) {
            screen = Screen.SELECT;
            showScreen();
        } else if (sidebarIndex == 1) {
            screen = Screen.SYNC;
            showScreen();
        }
        styleSidebar();
    }

    private void showScreen() {
        selector.setVisibility(screen == Screen.SELECT ? VISIBLE : GONE);
        syncView.setVisibility(screen == Screen.SYNC ? VISIBLE : GONE);
    }

    private void focusSidebar() {
        focus = Focus.SIDEBAR;
        sidebarIndex = (screen == Screen.SELECT) ? 0 : 1;
        selector.setFocused(false);
        syncView.setFocused(false);
        styleSidebar();
    }

    private void focusContent() {
        focus = Focus.CONTENT;
        if (screen == Screen.SELECT) {
            if (selector.isEmpty()) { focusSidebar(); return; }
            selector.setFocused(true);
            syncView.setFocused(false);
        } else {
            syncView.setFocused(true);
            selector.setFocused(false);
        }
        styleSidebar();
    }

    // --- SubtitleSelectorView.Listener + SyncView.Listener (shared) ---

    @Override public void onSelect(String optionId) {
        if (callbacks != null) callbacks.onSelectOption(optionId);
    }

    @Override public void onOpenMenu() {
        focusSidebar();
    }

    @Override public void onRequestClose() {
        close();
    }

    @Override public long currentPositionMs() { return callbacks != null ? callbacks.currentPositionMs() : 0L; }
    @Override public boolean isPlaying() { return callbacks != null && callbacks.isPlaying(); }
    @Override public void onSyncChanged() { if (callbacks != null) callbacks.onSyncChanged(); }
    @Override public void onSeek(long d) { if (callbacks != null) callbacks.onSeek(d); }
    @Override public void onSeekTo(long p) { if (callbacks != null) callbacks.onSeekTo(p); }
    @Override public void onTogglePlay() { if (callbacks != null) callbacks.onTogglePlay(); }

    // --- sidebar rendering ---

    private void styleSidebar() {
        int activeScreen = screen == Screen.SELECT ? 0 : 1;
        for (int i = 0; i < sidebarItems.length; i++) {
            TextView it = sidebarItems[i];
            boolean focused = focus == Focus.SIDEBAR && i == sidebarIndex;
            if (focused) {
                it.setTextColor(0xFF000000);
                it.setBackgroundColor(0xFFFFFFFF);
            } else if (i == activeScreen) {
                it.setTextColor(0xFF4DD0E1);
                it.setBackgroundColor(0x334DD0E1);
            } else {
                it.setTextColor(0xFFB0BEC5);
                it.setBackgroundColor(Color.TRANSPARENT);
            }
        }
    }

    private TextView sidebarItem(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(0xFFB0BEC5);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(4);
        tv.setLayoutParams(p);
        return tv;
    }

    private static boolean isNavKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
