# Android port

## Project

This repository contains the Android adaptation only. The Kotlin app lives under `android/`; Gradle wrapper, app sources, unit tests, and BSD license asset are self-contained there. The application ID is `org.pkgj.android.catalog`.

The app has Catalog, Downloads, and Settings tabs across the top. Settings includes an About section with the build version, original PKGj project, Vita3K compatibility source, and bundled BSD license. The library shows PS Vita content only and opens on Games. Category, region, compatibility and sort choices use compact menus. Region and compatibility menus accept multiple selections; selections within each filter are combined with OR, and filters are combined with AND. An empty selection means any region/status. PSP, PS1 and PS Mobile are excluded from browsing, settings and automatic catalog updates. Their legacy category identifiers remain readable so an upgrade does not break existing download records. Compatibility filters apply to Games; selecting another category clears the status filter. Compose renders paged SQLite catalog results. Paging queries run on the IO dispatcher and preserve coroutine cancellation, keeping filter changes off the UI thread. Landscape uses a shared search/filter toolbar, 48 dp catalog rows, compact download rows with actions beside the title, and two Settings columns with About links underneath. The header identifies the app as PKGj Android. A repository coordinates preferences, catalog refresh/import, downloads, and compatibility reports. WorkManager runs persistent foreground download jobs. New downloads write directly into a folder granted through Android's Storage Access Framework. Each job persists its folder and PKG/RIF document URIs. The shared HTTP transport writes to a seekable destination backed by a document file descriptor, checks destination space when available, and keeps partial files in that folder. It renames the PKG after transport completion and saves the matching RIF there. No private package or RIF copy is created for new jobs. Existing private downloads remain accessible and can be moved with Save to folder; successful moves remove the private copies. No all-files permission is requested.

The TSV parser follows the upstream PKGj column layouts, handles BOM and line endings, skips malformed rows, and stores catalog rows in SQLite. Vita game/demo entries can include an upstream zRIF. When present, the app converts it to a `.rif` alongside a completed PKG; it does not fabricate missing license data. New filenames use `[REGION-TITLEID] - Title vVersion` when those fields exist.

## Vita3K integration

The official [Vita3K Android manifest](https://github.com/Vita3K/Vita3K-Android/blob/android/android/src/main/AndroidManifest.xml) exports its launcher activity, but its `ACTION_VIEW` file filter is commented out and it declares no public PKG import intent. Vita3K's [Android activity](https://github.com/Vita3K/Vita3K-Android/blob/android/android/src/main/java/org/vita3k/emulator/Emulator.java) opens its own document picker. Its [Install .pkg menu](https://github.com/Vita3K/Vita3K-Android/blob/android/vita3k/gui/src/pkg_install_dialog.cpp) accepts PKG files and then asks the user to select a `.bin`/`.rif` license or enter zRIF. The app therefore exports files for the user to select inside Vita3K; it does not attempt an intent handoff, write to Vita3K's private storage, or report a game as installed.

Use Vita3K's **Install .pkg** action and select the exported PKG. If Vita3K asks for a license, select its matching exported RIF when available or provide license information you own. Vita3K displays the installation result. PSP, PS1, and other unsupported categories are not represented as playable.

The app reads `app_compat_db.xml` from the [official compatibility release](https://github.com/Vita3K/compatibility/releases/tag/compat_db) and status names from the repository's [public labels API](https://api.github.com/repos/Vita3K/compatibility/labels?per_page=100). On app startup it checks the release asset's ETag/Last-Modified values, downloads changed data, and caches title status, report issue, and update time in SQLite. Cached labels remain visible offline. Settings can trigger a manual check. The site warns that reports may not reflect recent emulator changes; the compatibility repository says reports are not intended for Android. Treat these as community reports, not device-specific guarantees.

## Download recovery and controls

Database version 6 adds destination URIs and a persisted transport-complete flag without deleting previous jobs. If saving or renaming fails after the HTTP transfer, retry completes that step without downloading the payload again. A job becomes Downloaded/Saved only after finalization. State transitions use conditional database updates so cancellation cannot be overwritten by a worker finishing concurrently. Cancellation removes the partial documents; Delete removes the saved files after confirmation. Changing the configured folder affects new jobs; existing jobs retain their original destination.

The app opens download documents in `rw` mode, which the [Android ContentResolver API](https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,java.lang.String)) specifies as seekable. Providers that cannot supply a writable seekable file fail with a folder error. URI permissions are retained through the document picker, without broad storage access.

L1/R1 switch the top tabs from any section, including search and dialogs. D-pad and left stick move focus, A activates, and B dismisses dialogs or leaves search. Search intercepts Up/Down: Up returns to the top menu; Down moves to the category filter and then the visible list. Controls have visible focus borders. L2/R2 page through the current list or scrollable view; holding a trigger accelerates catalog paging. The right stick also pages. Menus and dialogs register their own scroll target so paging does not move content behind them. Details keep Close and Download visible while their body scrolls; delete confirmation starts on Keep. Catalog position and filters survive tab changes.

Analog controls use Android's [documented controller axes](https://developer.android.com/games/sdk/game-controller/controller-input), including both trigger axis aliases. Input repeats stop on release, tab/window changes, or activity pause; simultaneous analog and digital trigger reports are suppressed.

## Limitations

- Vita3K installation remains a user action, and license material is not available for every catalog entry.
- The app cannot query a verified public Vita3K installed-games API.
- Android may defer background work under device-specific battery restrictions. WorkManager restores interrupted jobs as queued and preserves the saved transfer phase. Direct downloads require a writable seekable document provider; local shared-storage folders are the intended destination.
- Catalog URLs may be customized. TLS certificate checks are not bypassed; HTTPS redirects cannot downgrade to HTTP.

## Build and validation

From `android/`, run:

```sh
./gradlew test lint assembleDebug
```

The project uses Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, JDK 17, and Android API 35. The 18 JVM tests cover TSV parsing, filenames, compatibility XML and validators, multiple region/status filters, state transitions, seekable destinations, free-space failures, HTTP resume validation, and synthetic license decoding.

Validation on 18 October 2026 passed `test lint assembleDebug connectedDebugAndroidTest`: 18 JVM tests and 16 instrumented tests passed, lint reported 0 errors and 14 warnings, and the debug APK was built. Instrumented tests ran on an isolated x86_64 Android 17/API 37 emulator. They use local HTTP fixtures, synthetic package/license bytes, and a protected test-only document provider with persisted URI grants. They cover combined SQL filters, multi-select menus, D-pad escape from search, navigation beyond visible rows, A/B behavior in menus/details, shoulder navigation across tabs and modals, Settings/About/license navigation, download deletion controls, held digital and analog trigger paging and release, landscape density at 914 × 411 dp (five catalog rows, four paused downloads, and Settings through the license link without scrolling), a StrictMode check for database reads on the UI thread, direct PKG/RIF saving without private copies, pause/resume, cancellation, finalization retry without re-downloading, atomic completion/cancellation, and the version 5 to 6 database migration.

Run the instrumented tests on a test emulator with:

```sh
ANDROID_SERIAL=emulator-5556 ./android/gradlew -p android connectedDebugAndroidTest
```

The current catalog and details were visually checked in portrait and landscape on the emulator. See [catalog](screenshot.png), [details](screenshot-details.png), [Settings](screenshot-settings.png), and [Downloads](screenshot-downloads.png); these screenshots are from the emulator, not the Odin. The Downloads screenshot uses synthetic paused job records for layout review; no game files were transferred.

The earlier interface was installed and visually reviewed on an AYN Odin 3 running Android 15. No physical device was connected for this update. Physical buttons/sticks, long background downloads, actual OS process-death recovery, the system folder picker, removable-storage providers, and Vita3K installation remain on the hardware checklist. The emulator recovery checks exercise persisted phases and retry, without claiming a real process-kill test.

## Physical-device checklist

1. On an AYN Odin or Android phone, open the library online and confirm catalogs and compatibility labels load. Check search, multi-select filters, D-pad/left-stick focus, A activation, B/back, and dialog focus in portrait and landscape. Switch tabs with L1/R1 from search and dialogs. Page with L2/R2 and the right stick, checking hold acceleration and immediate release. Check that paging inside a dialog or menu leaves the catalog behind it in place.
2. Queue a small test download, background the app, turn the screen off, and check its notification, progress, pause/resume, cancellation, and retry.
3. Stop the app during a download, reopen it, and verify the job does not appear completed prematurely.
4. Select a download folder and confirm PKG/RIF files appear there with no duplicate in app storage. Check that partial files disappear on cancellation, saved files can be deleted, failed finalization retries without another HTTP transfer, and an existing private download can be moved with Save to folder. Revoke folder access and check the error and recovery after reselecting that folder.
5. In Vita3K, install a legally obtained compatible PKG, select its matching RIF if available, and verify the result in Vita3K.
