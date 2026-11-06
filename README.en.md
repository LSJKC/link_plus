# Buick Link +

An Android sender for compatible wireless CarPlay receivers. It streams phone CarLife+ video and audio, and routes receiver touch input back to CarLife. Android 12 or later is required; root, developer options, wireless debugging and ADB are not required.

The phone UI shows connection status and settings; the content source is CarLife+ on the phone. The original shell-dependent launcher, app launching and touch injection have been removed; see the [no-ADB model](docs/no-adb-model.md). Third-party APKs are not bundled.

## Build

Use JDK 17, Android SDK 34, Build Tools 35.0.0 and NDK 27.0.12077973. The project uses AGP 8.13.2, Gradle 8.13 and Kotlin 2.3.21. Set your SDK path with `ANDROID_HOME` or `local.properties`.

```powershell
./gradlew.bat spotlessCheck :app:testBuickUnitTest :app:lintDebug :app:lintBuick
./gradlew.bat :app:assembleBuick
```

The test APK uses the `.buick` suffix. Configure release signing with your own `keystore.properties`; builds without it use a local test key. APKs with different signatures cannot update each other.

Keeping a connection while the screen is off remains a known issue on Android 17. Compatibility also depends on the phone and receiver.

See the [Chinese README](README.md), [architecture](docs/architecture.md), [no-ADB model](docs/no-adb-model.md) and [third-party licenses](docs/dependencies.md).

## License

Based on [shaonialife/cardeck](https://github.com/shaonialife/cardeck), with the original [MIT license](LICENSE) and copyright retained. Third-party components keep their own licenses.

This is an unofficial project, unaffiliated with and not authorized or certified by Buick, General Motors or Apple. All trademarks belong to their respective owners. Please follow local traffic laws.
