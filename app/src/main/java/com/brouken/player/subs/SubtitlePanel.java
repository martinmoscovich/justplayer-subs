package com.brouken.player.subs;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.List;

import com.brouken.player.R;
import com.brouken.player.subs.ui.SubsBack;
import com.brouken.player.subs.ui.SubsSelectedBlock;
import com.brouken.player.subs.ui.SubsSidebarItem;
import com.brouken.player.subs.ui.SubsTheme;

import subtitleengine.sync.ManualSyncSession;

/**
 * Container for the three subtitle screens, switched via a left sidebar:
 * <ul>
 *   <li><b>Subtitles</b> — {@link SubtitleSelectorView} (choose the source).</li>
 *   <li><b>Sync</b> — {@link SyncView} (manual sync of the chosen subtitle).</li>
 *   <li><b>Translate</b> — {@link TranslateView} (AI-translate the active subtitle).</li>
 * </ul>
 * Focus is either on the sidebar (up/down switch screen, right/OK enter) or on the active screen
 * (left/back return to the sidebar). No domain logic — events go to {@link Callbacks}.
 */
public class SubtitlePanel extends FrameLayout
        implements SubtitleSelectorView.Listener, SyncView.Listener, TranslateView.Listener {

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
        void onStartTranslate();
        void onCancelTranslate();
        void onPauseTranslate();
        void onResumeTranslate();
        void onRestoreOriginal();
        void onTranslateAgain();
        /** Partial result: re-request only the chunks that failed, keeping what was already paid for. */
        void onRetryMissing();
        void onStartAutoSync(boolean fromHere);
        void onCancelAutoSync();
        /** The Sync screen just stopped being what's shown — either another screen took over, or the
         *  whole panel closed while Sync was up. Fires on the same "hover previews the screen" model
         *  as everything else here: moving the sidebar highlight off Sync counts, same as committing
         *  to it does, since that's already what changes what's rendered. */
        void onLeavingSync();
        /** The Translate screen just started being shown. If a translation is actively running,
         *  playback should pause and remember whether it was playing, so {@link #onLeavingTranslate()}
         *  can restore it. Same "hover previews the screen" firing model as {@link #onLeavingSync()}. */
        void onEnteringTranslate();
        /** The Translate screen just stopped being what's shown — mirrors {@link #onLeavingSync()}.
         *  Restores playback to whatever it was before {@link #onEnteringTranslate()}, if that paused it. */
        void onLeavingTranslate();
    }

    private enum Screen { SELECT, SYNC, TRANSLATE }
    private enum Focus { SIDEBAR, CONTENT }
    private static final Screen[] SCREENS = Screen.values(); // sidebar index 0..2 = content screens
    private static final int SIDEBAR_SETTINGS = 3;

    private final SubsSidebarItem[] sidebarItems;
    private final SubsSelectedBlock selectedBlock;
    private final SubtitleSelectorView selector;
    private final SyncView syncView;
    private final TranslateView translateView;
    private boolean translateAvailable = true;
    /** Mirrors what {@link #setOptions} last saw, so the SELECTED block can be rebuilt from either
     *  that or {@link #setExtractionStatus} without either caller having to know about the other. */
    @Nullable private SubtitleOption selectedOption;
    private boolean extracting;
    /** Non-null only while the panel is open — see {@link SubsBack}. */
    @Nullable private Object backCallback;

    private Callbacks callbacks;
    private Screen screen = Screen.SELECT;
    private Focus focus = Focus.SIDEBAR;
    private int sidebarIndex = 0; // 0=Subtitles, 1=Sync, 2=Translate, 3=Settings

    public SubtitlePanel(Context context) {
        super(context);
        setBackgroundColor(SubsTheme.VEIL);
        setVisibility(GONE);
        setClickable(true);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sidebar = new LinearLayout(context);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(SubsTheme.PANEL);
        sidebar.setPadding(dp(12), dp(28), dp(12), dp(20));
        row.addView(sidebar, new LinearLayout.LayoutParams(
                dp(Math.round(SubsTheme.SIDEBAR_W_DP)), ViewGroup.LayoutParams.MATCH_PARENT));

        sidebarItems = new SubsSidebarItem[]{
                new SubsSidebarItem(context, "Subtitles", R.drawable.subtitle_ic_subtitles),
                new SubsSidebarItem(context, "Sync", R.drawable.subtitle_ic_sync),
                new SubsSidebarItem(context, "Translate", R.drawable.subtitle_ic_translate),
                new SubsSidebarItem(context, "Settings", R.drawable.subtitle_ic_settings) };
        for (SubsSidebarItem it : sidebarItems) sidebar.addView(it, it.navParams());

        // Pushes the SELECTED block to the foot of the sidebar: it is an anchor, and one that floated
        // up with the nav would move every time the panel gained a screen.
        View spacer = new View(context);
        sidebar.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        selectedBlock = new SubsSelectedBlock(context);
        sidebar.addView(selectedBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The sidebar's right edge — a hairline, not a gap, so the two zones read as one surface.
        View edge = new View(context);
        edge.setBackgroundColor(SubsTheme.EDGE);
        row.addView(edge, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

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

        translateView = new TranslateView(context);
        translateView.setListener(this);
        content.addView(translateView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setCallbacks(Callbacks cb) {
        this.callbacks = cb;
    }

    public void setOptions(List<SubtitleOption> options, String selectedId, boolean loadingMore) {
        selector.setOptions(options, selectedId, loadingMore);
        selectedOption = null;
        if (options != null && selectedId != null) {
            for (SubtitleOption o : options) {
                if (selectedId.equals(o.id)) { selectedOption = o; break; }
            }
        }
        renderSelectedBlock();
    }

    /** The SELECTED block is derived, never pushed: everything it says is already in the option. */
    private void renderSelectedBlock() {
        SubtitleOption o = selectedOption;
        if (o == null) {
            selectedBlock.set(null, null, SubsSelectedBlock.Status.READY);
            return;
        }
        boolean extractingThis = extracting && o.source == SubtitleOption.Source.EMBEDDED;
        String flag = LanguageFlags.flagFor(o.language);
        // Same name the row shows: with a flag in front, the language code in the label is said twice.
        selectedBlock.set(flag, SubtitleSelectorView.titleFor(o, flag != null), SubsSelectedBlock.pick(
                o.state == SubtitleOption.State.ERROR,
                o.state == SubtitleOption.State.LOADING,
                extractingThis,
                o.translated,
                o.fromCache));
    }

    /** Binds the engine sync session (null = nothing syncable) into the sync screen. */
    public void setSyncSession(ManualSyncSession session) {
        syncView.setSession(session);
    }

    /** Pushes the current translation state into the Translate screen and sidebar. */
    public void setTranslateState(boolean available, TranslateUiState state) {
        translateAvailable = available;
        translateView.setState(state);
        styleSidebar();
    }

    /** Hands the detailed chunk-progress bar to the Translate screen — see {@link TranslateView#setChunkBar}. */
    public void setTranslateChunkBar(android.view.View bar) {
        translateView.setChunkBar(bar);
    }

    /**
     * Shows how far the read of an embedded track has got, on whichever screen asked for it. Passing
     * a {@code null} title clears it — the screens then go back to reporting their own state.
     */
    public void setExtractionStatus(@Nullable String title, float fraction) {
        translateView.setBusyStatus(title, fraction);
        syncView.setBusyStatus(title, fraction);
        extracting = title != null;
        renderSelectedBlock();
    }

    /** Pushes formatted auto-sync state (see {@link AutoSyncController}) into the Sync screen. */
    public void setAutoSyncState(AutoSyncUiState state) {
        syncView.setAutoSyncState(state);
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    public void open() {
        openOn(Screen.SELECT); // subtitle button always lands on the selection screen first
    }

    /** Opens straight on the Translate screen — the notice's Translate button skips the sidebar. */
    public void openTranslate() {
        openOn(Screen.TRANSLATE);
    }

    private void openOn(Screen target) {
        setVisibility(VISIBLE);
        claimBack();
        changeScreen(target);
        sidebarIndex = target.ordinal();
        syncView.reset();
        translateView.reset();
        showScreen();
        focusContent();
    }

    public void close() {
        if (callbacks != null) {
            if (screen == Screen.SYNC) callbacks.onLeavingSync();
            if (screen == Screen.TRANSLATE) callbacks.onLeavingTranslate();
        }
        // Otherwise this is the stale value the next open() sees, and changeScreen(SELECT) would fire
        // an unwarranted second onLeaving*() for whatever screen was up when the panel was last closed.
        screen = Screen.SELECT;
        releaseBack();
        setVisibility(GONE);
    }

    /**
     * On API 33+ Back never arrives as a key event (the manifest opts into
     * {@code enableOnBackInvokedCallback}), so every {@code KEYCODE_BACK} branch below is dead there
     * and Back would finish the Activity straight from an open panel. Claiming the dispatcher while
     * the panel is up routes it back to exactly the same handlers.
     */
    private void claimBack() {
        if (backCallback != null) return;
        backCallback = SubsBack.claim(this, this::onBackInvoked);
    }

    private void releaseBack() {
        SubsBack.release(this, backCallback);
        backCallback = null;
    }

    /** The same path a {@code KEYCODE_BACK} press takes, so the two routes can never disagree. */
    private void onBackInvoked() {
        handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
    }

    /** The dispatcher is only reachable once attached, so a panel opened before that claims here. */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isOpen()) claimBack();
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseBack();
        super.onDetachedFromWindow();
    }

    /** The one place {@link #screen} is allowed to change — so leaving Sync/Translate is never missed
     *  regardless of which of the three call sites (open, sidebar preview, close) caused it. */
    private void changeScreen(Screen next) {
        if (screen == Screen.SYNC && next != Screen.SYNC && callbacks != null) {
            callbacks.onLeavingSync();
        }
        if (screen != Screen.TRANSLATE && next == Screen.TRANSLATE && callbacks != null) {
            callbacks.onEnteringTranslate();
        }
        if (screen == Screen.TRANSLATE && next != Screen.TRANSLATE && callbacks != null) {
            callbacks.onLeavingTranslate();
        }
        screen = next;
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
        switch (screen) {
            case SELECT: return selector.handleKey(keyCode);
            case SYNC: return syncView.handleKey(keyCode);
            case TRANSLATE: return translateView.handleKey(keyCode);
            default: return false;
        }
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
        if (sidebarIndex < SCREENS.length) {
            changeScreen(SCREENS[sidebarIndex]);
            showScreen();
        }
        styleSidebar();
    }

    private void showScreen() {
        selector.setVisibility(screen == Screen.SELECT ? VISIBLE : GONE);
        syncView.setVisibility(screen == Screen.SYNC ? VISIBLE : GONE);
        translateView.setVisibility(screen == Screen.TRANSLATE ? VISIBLE : GONE);
    }

    private void focusSidebar() {
        focus = Focus.SIDEBAR;
        sidebarIndex = screen.ordinal();
        selector.setFocused(false);
        syncView.setFocused(false);
        translateView.setFocused(false);
        styleSidebar();
    }

    private void focusContent() {
        focus = Focus.CONTENT;
        if (screen == Screen.SELECT && selector.isEmpty()) {
            focusSidebar();
            return;
        }
        selector.setFocused(screen == Screen.SELECT);
        syncView.setFocused(screen == Screen.SYNC);
        translateView.setFocused(screen == Screen.TRANSLATE);
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

    // --- TranslateView.Listener ---

    @Override public void onStartTranslate() { if (callbacks != null) callbacks.onStartTranslate(); }
    @Override public void onCancelTranslate() { if (callbacks != null) callbacks.onCancelTranslate(); }
    @Override public void onPauseTranslate() { if (callbacks != null) callbacks.onPauseTranslate(); }
    @Override public void onResumeTranslate() { if (callbacks != null) callbacks.onResumeTranslate(); }
    @Override public void onRestoreOriginal() { if (callbacks != null) callbacks.onRestoreOriginal(); }
    @Override public void onTranslateAgain() { if (callbacks != null) callbacks.onTranslateAgain(); }
    @Override public void onRetryMissing() { if (callbacks != null) callbacks.onRetryMissing(); }

    // --- SyncView.Listener (auto-sync) ---

    @Override public void onStartAutoSync(boolean fromHere) { if (callbacks != null) callbacks.onStartAutoSync(fromHere); }
    @Override public void onCancelAutoSync() { if (callbacks != null) callbacks.onCancelAutoSync(); }

    // --- sidebar rendering ---

    private void styleSidebar() {
        int activeScreen = screen.ordinal();
        for (int i = 0; i < sidebarItems.length; i++) {
            boolean focused = focus == Focus.SIDEBAR && i == sidebarIndex;
            // Dimmed but still selectable — see setTranslateState(); a dead item that silently
            // ignores OK would be worse UX than just explaining why on the screen itself.
            boolean dimmedUnavailable = i == Screen.TRANSLATE.ordinal() && !translateAvailable;
            // Animate only while the panel is up: the first paint after open() is a new screen,
            // not a move, and 130ms of colour ramp there reads as the panel loading slowly.
            sidebarItems[i].setState(focused, i == activeScreen, dimmedUnavailable, isOpen());
        }
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
