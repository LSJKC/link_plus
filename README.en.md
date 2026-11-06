# Buick Link +

An Android sender for compatible wireless CarPlay receivers: it streams phone CarLife+ video/audio and routes receiver touch input back to CarLife. Android 12 or later is required; root, developer options, wireless debugging and ADB are not required.

Current goal (since 2026-11-06): port all CarLife features already verified in the old smali-injection build into maintainable source (video, wallpaper/safe-area composition, audio/TTS, touch, navigation, media, HUD, voice key, GPS, MAC identity, diagnostics, wallpapers, settings), remove the built-in wireless ADB activation, slim the APK, and produce field-test packages. Feature progress: [port matrix](docs/PORT_MATRIX.md); goals and field-test checklist: [CarLife port plan](docs/CARLIFE_PORT_PLAN.md).

## Getting started

No developer options, wireless debugging or ADB: install the APK, grant the normal permissions (Bluetooth, nearby Wi-Fi, notifications, etc. on demand) and connect the receiver. The video source is CarLife+ on the phone, so open CarLife+ and enter its "wireless connection" first. See the [no-ADB model and permissions](docs/no-adb-model.md).

Compatibility depends on the phone and receiver. Keeping a connection while the screen is off remains a known issue on Android 17. Please do not operate the phone while driving.

## Field-test package and log feedback

- Test package: the `.buick` APK (`app/build/outputs/apk/buick/app-buick.apk`); version and SHA-256 are published with the release notes.
- Log feedback: use "Settings → Help & diagnostics → Export log file" in the app, and include phone/receiver model, system version, app version and reproduction steps.
- Developer debugging: `scripts/p1a-field-check.ps1` captures and validates CarLife log markers (`scripts/verify-carlife-log.ps1` validates an existing log).

## Build

Use JDK 17, Android SDK 34, Build Tools 35.0.0 and NDK 27.0.12077973. The project uses AGP 8.13.2, Gradle 8.13 and Kotlin 2.3.21. Set your SDK path with `ANDROID_HOME` or `local.properties`.

```powershell
./gradlew.bat spotlessCheck :app:testBuickUnitTest :app:lintDebug :app:lintBuick
./gradlew.bat :app:assembleBuick
```

The test APK uses the `.buick` suffix. Configure release signing with your own `keystore.properties`; builds without it use a local test key. APKs with different signatures cannot update each other.

## Documentation

- [Port matrix](docs/PORT_MATRIX.md)
- [CarLife port plan](docs/CARLIFE_PORT_PLAN.md)
- [No-ADB model and permissions](docs/no-adb-model.md)
- [GitHub downstream sync workflow](docs/GITHUB_WORKFLOW.md)
- [Architecture](docs/architecture.md)
- [Third-party licenses](docs/dependencies.md)
- [Chinese README](README.md)

## License

Based on [shaonialife/cardeck](https://github.com/shaonialife/cardeck), with the original [MIT license](LICENSE) and copyright retained. Third-party components keep their own licenses.

This is an unofficial project, unaffiliated with and not authorized or certified by Buick, General Motors or Apple. All trademarks belong to their respective owners. Please follow local traffic laws.
