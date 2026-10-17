# Android port

## Project

This repository contains the Android adaptation only. The Kotlin app lives under `android/`; Gradle wrapper, app sources, unit tests, and BSD license asset are self-contained there. The application ID is `org.pkgj.android.catalog`.

The app has Library, Downloads, and Settings screens. Compose renders paged SQLite catalog results. A repository coordinates preferences, catalog refresh/import, downloads, and compatibility reports. WorkManager runs persistent foreground download jobs. Downloads use app-managed storage and can be exported with Android's Storage Access Framework. No all-files permission is requested.

The TSV parser follows the upstream PKGj column layouts, handles BOM and line endings, skips malformed rows, and stores catalog rows in SQLite. Vita game/demo entries can include an upstream zRIF. When present, the app converts it to a `.rif` alongside a completed PKG; it does not fabricate missing license data. New filenames use `[REGION-TITLEID] - Title vVersion` when those fields exist.

## Vita3K integration

The official [Vita3K Android manifest](https://github.com/Vita3K/Vita3K-Android/blob/android/android/src/main/AndroidManifest.xml) exports its launcher activity, but its `ACTION_VIEW` file filter is commented out and it declares no public PKG import intent. Vita3K's [Android activity](https://github.com/Vita3K/Vita3K-Android/blob/android/android/src/main/java/org/vita3k/emulator/Emulator.java) opens its own document picker. Its [Install .pkg menu](https://github.com/Vita3K/Vita3K-Android/blob/android/vita3k/gui/src/pkg_install_dialog.cpp) accepts PKG files and then asks the user to select a `.bin`/`.rif` license or enter zRIF. The app therefore exports files for the user to select inside Vita3K; it does not attempt an intent handoff, write to Vita3K's private storage, or report a game as installed.

Use Vita3K's **Install .pkg** action and select the exported PKG. If Vita3K asks for a license, select its matching exported RIF when available or provide license information you own. Vita3K displays the installation result. PSP, PS1, and other unsupported categories are not represented as playable.

The app reads `app_compat_db.xml` from the [official compatibility release](https://github.com/Vita3K/compatibility/releases/tag/compat_db) and status names from the repository's [public labels API](https://api.github.com/repos/Vita3K/compatibility/labels?per_page=100). On app startup it checks the release asset's ETag/Last-Modified values, downloads changed data, and caches title status, report issue, and update time in SQLite. Cached labels remain visible offline. Settings can trigger a manual check. The site warns that reports may not reflect recent emulator changes; the compatibility repository says reports are not intended for Android. Treat these as community reports, not device-specific guarantees.

## Limitations

- Vita3K installation remains a user action, and license material is not available for every catalog entry.
- The app cannot query a verified public Vita3K installed-games API.
- Android may defer background work under device-specific battery restrictions. WorkManager restores interrupted jobs as queued.
- Catalog URLs may be customized. TLS certificate checks are not bypassed; HTTPS redirects cannot downgrade to HTTP.

## Build and validation

From `android/`, run:

```sh
./gradlew test lint assembleDebug
```

The project uses Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, JDK 17, and Android API 35. Unit tests cover TSV parsing, filename handling, compatibility XML and validators, filtering, download state transitions, HTTP resume validation, and license decoding. The last local validation passed 12 tests, lint with 0 errors (12 warnings), and `assembleDebug`.

The debug APK was installed and launched on an AYN Odin 3 running Android 15. The app fetched and parsed the compatibility database; the screenshot in the repository shows colored status pills. Controller navigation, long background downloads, process-death recovery, export completion, and Vita3K installation still need the physical checks below.

## Physical-device checklist

1. On an AYN Odin or Android phone, open the library online and confirm catalogs and compatibility labels load. Check search, filters, D-pad/stick focus, A activation, B/back, and dialog focus in portrait and landscape.
2. Queue a small test download, background the app, turn the screen off, and check its notification, progress, pause/resume, cancellation, and retry.
3. Stop the app during a download, reopen it, and verify the job does not appear completed prematurely.
4. Select an export folder and confirm completed files and available RIFs are written there. Also test individual document-picker export.
5. In Vita3K, install a legally obtained compatible PKG, select its matching RIF if available, and verify the result in Vita3K.
