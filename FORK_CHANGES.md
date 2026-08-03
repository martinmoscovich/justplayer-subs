# FORK_CHANGES

Changes made to the upstream Just Player (`moneytoo/Player`) in this fork. Keep upstream edits to a
minimum; all new logic lives in new packages. Each modified upstream file is listed here with the
reason, so rebases against `upstream` stay tractable.

## Modified upstream files

| File | Change | Reason |
|---|---|---|
| `settings.gradle` | Include `:subtitle-engine-java` by relative path (`../subtitle-engine-java`). | Depend on the pure-JVM subtitle engine, which lives as a sibling of the fork (`player/subtitle-engine-java`). |
| `app/build.gradle` | Add `implementation(project(':subtitle-engine-java'))` excluding the desktop `com.microsoft.onnxruntime:onnxruntime`; add `implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.20.0'`; add `implementation 'androidx.leanback:leanback:1.0.0'`. | Use the engine from the app (ONNX note above). Leanback provides `VerticalGridView` for the manual-sync "lyrics" panel (SPEC/PLAN §2.5). |
| `app/src/main/java/com/brouken/player/PlayerActivity.java` | +1 import; +1 field `customSubtitles`; instantiate `CustomSubtitleController` after `setMediaItem` + `onMediaSet(...)`; release at both player-teardown points; +1 line in `dispatchKeyEvent` to route keys to the sync panel. ~7 added lines, no existing logic changed. | Hook the custom subtitle overlay + sync panel with the smallest possible footprint. All behaviour lives in `com.brouken.player.subs.*`. |

## Added files (not upstream)

| File | Purpose |
|---|---|
| `gradle/libs.versions.toml` | Version catalog copied from the `player/` root so the included engine module resolves its `libs.*` accessors inside the fork build. |

<!-- Append new upstream modifications above as the integration proceeds (manifest intent-filter,
     PlayerActivity hooks for the subtitle overlay, etc.). -->
