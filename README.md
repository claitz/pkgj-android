# PKGj Android Catalog

An Android port of [PKGj for PS Vita](https://github.com/blastrock/pkgj), rebuilt natively for Android. This repository contains the Android implementation; it does not include or build the Vita application.

Browse the upstream PKGj catalogs, search and filter titles, queue PKG downloads, and export completed files and available license files through Android's document picker. Vita3K compatibility reports appear as colored status pills. Downloads are not installed automatically: open Vita3K and use its own install flow.

![PKGj Android Catalog on an AYN Odin](docs/screenshot.png)

## Build on Arch Linux

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

## GitHub releases

Push a version tag such as `v0.1.0` to run the checks and publish a GitHub Release with the APK attached:

```sh
git tag v0.1.0
git push origin v0.1.0
```

The release APK is built from the Gradle debug variant and uses Android's default debug signing key. It can be sideloaded for testing; it is not signed for Play Store distribution.

## Notes

- Catalogs and compatibility reports are fetched from their public upstream sources and cached for offline use. Custom catalog URLs and local TSV imports are supported.
- Compatibility labels are community reports and may not match the current Vita3K Android build or device.
- Downloaded files remain in app-managed storage until exported or deleted. Vita3K handles installation and license selection.
- No game files, credentials, or license material are bundled.

See [docs/android-port.md](docs/android-port.md) for architecture, catalog formats, Vita3K integration findings, limitations, and the device test checklist.

## License

BSD 2-Clause. See [LICENSE](LICENSE) for the original PKGj copyright notices and terms. The Android app also includes the license in its APK.
