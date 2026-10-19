# PKGj Android Catalog

An Android port of [PKGj for PS Vita](https://github.com/blastrock/pkgj), rebuilt natively for Android.
This repository does not include or build the Vita application.

Browse PS Vita catalogs, search and filter titles, queue PKG downloads, and save packages and available RIF files to a folder you choose.

Vita3K compatibility reports status pills.

![PKGj Android Catalog in landscape](docs/screenshot.png)

## Build on Linux

Install Android Studio or the Android SDK, Android platform/build tools for API 35, and a JDK 17. The Gradle wrapper downloads the pinned Gradle version on first use.

```sh
./android/gradlew -p android test lint assembleDebug
```

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Its application ID is `org.pkgj.android.catalog`.

Install it on a connected Android device with USB debugging enabled:

```sh
adb devices
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

To launch it from ADB:

```sh
adb shell am start -n org.pkgj.android.catalog/org.pkgj.android.MainActivity
```

## Controls

D-pad or left stick to navigate, A to select, B to go back. L1/R1 switch tabs. L2/R2 or the right stick page through lists; hold a trigger to scroll faster.

## Game updates

**Download** queues the game and its available update in your chosen folder. Details show update info automatically; **Download update** saves just the patch, and the refresh icon checks again. Updates use the exact regional Title ID, have separate versioned filenames and do not generate another RIF.

Install the base game in Vita3K first, then select the update through **Install .pkg**. Use the matching game license if Vita3K prompts for one. The app cannot see your installed version.

## Notes

- Catalogs and compatibility reports are fetched from their public upstream sources and cached for offline use. Custom catalog URLs and local TSV imports are supported.
- Compatibility labels are community reports and may not match the current Vita3K Android build or device.
- New downloads go directly to the selected folder. Vita3K handles installation and license selection. Older downloads stored inside the app can be moved with **Save to folder**.
- No game files, credentials, or license material are bundled.

See [docs/android-port.md](docs/android-port.md) for architecture, catalog formats, Vita3K integration findings, limitations, and the device test checklist.

## License

BSD 2-Clause. See [LICENSE](LICENSE) for the original PKGj copyright notices and terms. The Android app also includes the license in its APK.
