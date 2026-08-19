# FORK_CHANGES

Changes made to the upstream Just Player (`moneytoo/Player`) in this fork. Keep upstream edits to a
minimum; all new logic lives in new packages. Each modified upstream file is listed here with the
reason, so rebases against `upstream` stay tractable.

## Modified upstream files

| File | Change | Reason |
|---|---|---|
| `settings.gradle` | Include `:subtitle-engine-java` by relative path (`../subtitle-engine-java`). | Depend on the pure-JVM subtitle engine, which lives as a sibling of the fork (`player/subtitle-engine-java`). |
| `app/build.gradle` | Add `implementation(project(':subtitle-engine-java'))` excluding the desktop `com.microsoft.onnxruntime:onnxruntime`; add `implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.20.0'`; add `implementation 'androidx.leanback:leanback:1.0.0'`. | Use the engine from the app (ONNX note above). Leanback provides `VerticalGridView` for the manual-sync "lyrics" panel (SPEC/PLAN §2.5). |
| `app/src/main/java/com/brouken/player/PlayerActivity.java` | +1 import; +1 field `customSubtitles`; instantiate `CustomSubtitleController` after `setMediaItem` + `onMediaSet(...)`; release at both player-teardown points; +1 line in `dispatchKeyEvent` to route keys to the panel; +1 `setOnClickListener` on the subtitle button to open the panel. ~9 added lines, no existing logic changed (the subtitle button's default click — the Media3 track menu — is replaced; we manage subtitles). | Hook the custom subtitle system with the smallest possible footprint. All behaviour lives in `com.brouken.player.subs.*`. |
| `app/src/main/AndroidManifest.xml` | Register `com.brouken.player.subs.SubtitleSettingsActivity` (reuses the existing `Theme.AppCompat.DayNight.NoActionBar` + `R.layout.settings_activity`). | The subtitle settings screen, opened from the panel's sidebar. |

## `com.brouken.player.subs` package (all new)

Separated by concern (selection vs sync), each with its own UI + controller:
- `SubtitleOption` — one selectable subtitle (external / embedded / provider) with a READY/LOADING/ERROR state.
- `SubtitleSelectorView` — selection UI (chips + "loading more" indicator).
- `SubtitleSelectionController` — builds the option list (intent externals + embedded Media3 tracks; providers TBD), loads external content async, Media3 track override for embedded.
- `SyncView` — manual-sync UI (lyrics list + transport/control row).
- `SubtitleSyncController` — overlay rendering + `SyncState` (offset/anchors/nudge).
- `SubtitlePanel` — container that composes selector + sync view and manages focus between them.
- `CustomSubtitleController` — coordinator wiring the above to the player.

## Added files (not upstream)

| File | Purpose |
|---|---|
| `gradle/libs.versions.toml` | Version catalog copied from the `player/` root so the included engine module resolves its `libs.*` accessors inside the fork build. |
| `app/src/main/res/font/subtitle_inter_{regular,medium,semibold,bold}.ttf` | Inter (SIL OFL), the subtitle panel'''s typeface. Loaded only from `com.brouken.player.subs`; the app theme is untouched. |
| `app/src/main/res/drawable/subtitle_ic_*.xml` | The panel'''s 21 `VectorDrawable` icons, drawn in white and tinted at runtime. |

`res/` is a **global namespace shared with upstream**, so everything added there carries a
`subtitle_` prefix and nothing under `res/values` (or the theme) is edited. That keeps a rebase from
having to resolve a single resource conflict.

<!-- Append new upstream modifications above as the integration proceeds (manifest intent-filter,
     PlayerActivity hooks for the subtitle overlay, etc.). -->
