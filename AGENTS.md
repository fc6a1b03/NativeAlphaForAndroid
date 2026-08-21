# AGENTS.md

Guidance for AI coding agents working on **WebNative** (fork of NativeAlphaForAndroid).

## Project Overview

WebNative is a single-module Android app that turns any website into a
borderless, fullscreen PWA-style app using the system Android WebView. Users
add custom URLs with display names, get home-screen shortcuts with
auto-fetched favicons (falling back to generated gradient letter icons), and
browse with multi-touch gestures. **Primary use case: Kimi Code Web** — the
WebView is tuned for high-frequency text-stream rendering (streaming AI
output, long documents, code blocks).

- Package / namespace / applicationId: `com.cylonid.nativealpha`
- Min SDK 31 (Android 12), target/compile SDK 37
- Current version: 2.1.14 (`versionCode 2114`)
- License: GPL-3.0
- Upstream: https://github.com/cylonid/NativeAlphaForAndroid (this repo is a fork)

## Tech Stack

- **Language**: Kotlin only (Java legacy fully migrated/removed). Java 17 bytecode.
- **UI**: Jetpack Compose + Material 3（全部页面：主界面/添加向导/全局设置/WebApp 设置/关于）。
  仅 WebViewActivity（渲染核心）保持 Java View 实现。DataBinding 已移除，ViewBinding 仅剩
  WebViewActivity 未用（保留为渲染层）。
- **Build**: Gradle 9.7.0 (wrapper) + AGP 9.3.1 + built-in Kotlin 2.3.20 +
  `org.jetbrains.kotlin.plugin.compose` (required for Compose).
- **Version Catalog**: all dependency versions in `gradle/libs.versions.toml`
  — never hardcode versions in build files.
- **Persistence**: Gson (JSON in SharedPreferences), `WebApp`/`GlobalSettings`
  are the schema (no legacy converters/deserializers).
- **Key libraries**: `androidx.webkit` (force-dark, offscreen pre-raster),
  Compose BOM + Material 3, Gson, JSoup (favicon/title), AboutLibraries,
  CircularProgressBar (shortcut dialog).
- **No**: GMS, adblock, sandbox/process-phoenix, biometric, work-runtime,
  easypermissions, drag-drop-recyclerview (removed in v2.0).

## Build & Test

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # signed release (needs key.properties, else debug fallback)
./gradlew testDebugUnitTest      # unit tests (Robolectric)
```

- `key.properties` (git-ignored) holds release signing; CI injects keystore
  from GitHub Secrets on tag builds.
- CI: `.github/workflows/ci.yml` — push/PR runs tests + debug build; tag `v*`
  builds signed APK+AAB and creates a GitHub Release.

## Architecture

- **Main screen**: `MainActivity` (Compose, `setContent`) hosts `MainScreen`
  (card list + FAB + empty state). Data from `DataManager.activeWebsites`,
  refreshed via `MainActivity.refreshTrigger` on resume.
- **Add flow**: `AddWebAppActivity` (Compose) — two-step wizard: URL →
  auto-detected title/icon → shortcut. `WebAppDataFetcher` (extracted from the
  old `ShortcutDialogFragment`) handles title/icon/start_url detection.
- **Settings**: `SettingsActivity` + `WebAppSettingsActivity` + `AboutActivity`
  are all Compose (`GlobalSettingsScreen` / `WebAppSettingsScreen` / `AboutScreen`).
- **Browser screen**: `WebViewActivity` (Java, kept as View) renders the site
  via `CustomBrowser`/`CustomWebChromeClient`. PWA text-stream optimizations
  live in `setupWebView()`: `RenderPriority.HIGH`, hardware acceleration,
  `textZoom(100)`, `OffscreenPreRaster`, `LOAD_DEFAULT` cache, overlay scrollbars.
- **Dynamic icons**: `IconGenerator` (gradient + first letter) used in list
  and shortcut fallback; `ShortcutDialogFragment` (legacy) still powers the
  "Re-create shortcut" flow from WebApp settings; the add-flow uses the new
  `WebAppDataFetcher`.
- **i18n**: `values/` (en) + `values-zh/` (zh), default Chinese via
  `AppCompatDelegate.setApplicationLocales` (language dropdown in SettingsActivity).
- **Backup**: versioned JSON (`{checksum, data:{version, websites, settings}}`)
  via SAF, SHA-256 verified. `BACKUP_FORMAT_VERSION = 2` in `DataManager`.

## Data & Persistence

- `DataManager` singleton owns everything: `websites` (JSON array in
  SharedPreferences `WEBSITEDATA`), global settings under `GLOBALSETTINGS`.
- **WebApp IDs double as array indices** — never remove list entries;
  deletion means `isActiveEntry = false` (`markInactive`).
- `WebApp.displayName` (nullable) is the list display name; falls back to
  `title` (derived from URL).
- **No backward data compatibility** (v2.0 decision): no legacy converters
  or deserializers; model fields are the schema. Renaming a property changes
  the persisted JSON.

## Gotchas & Caution Areas

1. **WebViewActivity.java is kept as Java** — do not migrate to Kotlin
   without explicit request; it is the render core and works.
2. **Compose requires the compose compiler plugin** (`kotlin-compose` in
   catalog) — removing it breaks `remember` with "couldn't inline method".
3. **Compose BOM is pinned to 2026.06.01** (stable) — newer BOMs may pull
   beta compose versions incompatible with Kotlin 2.3.20.
4. **Kotlin sources live in both `src/main/java` and `src/main/kotlin`** —
   both are compiled; keep `MainActivity.kt` in the kotlin source root.
5. **R8 is enabled for release** — keep Gson model classes in
   `proguard-rules.pro` (`com.cylonid.nativealpha.model.**`).
6. **Version Catalog is the single source of truth** for dependency versions.
7. **git identity is set repo-locally** (`kingon` / `kingon@local`).

## Release & Deployment

- Versioning: bump `versionCode`/`versionName` in `app/build.gradle`
  `defaultConfig`; update README and `doc/REFACTOR_PLAN.md` if changed.
- Distribution: GitHub Releases only (CI on tag), no Play/F-Droid.
- Privacy stance: minimal permissions (INTERNET, location, camera, audio);
  cleartext traffic enabled for user-added non-HTTPS sites; Safe Browsing
  disabled (per-app HTTP warning exists).
