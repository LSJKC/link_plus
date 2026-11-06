# Buick Link +

An Android sender for compatible wireless CarPlay receivers: it streams phone CarLife+ video/audio and routes receiver touch input back to CarLife. This project is **built on [schemawisp/link_plus](https://github.com/schemawisp/link_plus) (Buick Link+, MIT)** and adds a CarLife content source on top of its Bluetooth iAP2 / AirPlay / VideoSender / AudioSender stack. Root, developer options, wireless debugging and ADB are not required; Android 12 or later is required.

Compatibility: **Buick vehicles only** for now. On the phone side, Honor devices (Honor CarLink / CarLife+ wireless connection) are supported first; compatibility with CarLife+ on other Android phones will be verified from feedback. See the [CarLife connection mechanism](docs/carlife-mechanism.md).

## Getting started

No developer options, wireless debugging or ADB: install the APK, grant the normal permissions (Bluetooth, nearby Wi-Fi, notifications, etc. on demand) and connect the receiver. The video source is CarLife+ on the phone, so open CarLife+ and enter its "wireless connection" first. See the [no-ADB model and permissions](docs/no-adb-model.md).

Compatibility depends on the phone and receiver. Keeping a connection while the screen is off remains a known issue on Android 17. Please do not operate the phone while driving.

## Field-test package and log feedback

- Test package: the `.buick` APK (`app/build/outputs/apk/buick/app-buick.apk`); version and SHA-256 are published with the release notes.
- Log feedback: use "Settings → Logs & About → Export logs" in the app, and include phone/receiver model, system version, app version and reproduction steps.
- Developer debugging: `scripts/p1a-field-check.ps1` captures and validates CarLife log markers (`scripts/verify-carlife-log.ps1` validates an existing log).

## Build

Use JDK 17, Android SDK 34, Build Tools 35.0.0 and NDK 27.0.12077973. The project uses AGP 8.13.2, Gradle 8.13 and Kotlin 2.3.21. Set your SDK path with `ANDROID_HOME` or `local.properties`.

```powershell
./gradlew.bat spotlessCheck :app:testBuickUnitTest :app:lintDebug :app:lintBuick
./gradlew.bat :app:assembleBuick
```

The test APK uses the `.buick` suffix. Configure release signing with your own `keystore.properties`; builds without it use a local test key. APKs with different signatures cannot update each other.

## Documentation

- [Features and UI](docs/PORT_MATRIX.md)
- [Goals, implementation and testing](docs/CARLIFE_PORT_PLAN.md)
- [CarLife connection mechanism](docs/carlife-mechanism.md)
- [No-ADB model and permissions](docs/no-adb-model.md)
- [GitHub downstream sync workflow](docs/GITHUB_WORKFLOW.md)
- [Architecture](docs/architecture.md)
- [Third-party licenses](docs/dependencies.md)
- [Chinese README](README.md)

## License

Built on [schemawisp/link_plus](https://github.com/schemawisp/link_plus) (Buick Link+, MIT; itself based on [shaonialife/cardeck](https://github.com/shaonialife/cardeck)), with the original [MIT license](LICENSE) and copyright retained. Third-party components keep their own licenses.

**Code provenance and attribution**

- Upstream base: [schemawisp/link_plus](https://github.com/schemawisp/link_plus) (MIT); this repository is a downstream fork and keeps the upstream LICENSE.
- CarLife protocol/client/video/audio parts use the MIT-licensed implementation from [CarPToCarL](https://github.com/jjuugk/CarPToCarL) (commit `7b729fb`, Copyright (c) 2026 lizhihua) and are adapted to the link_plus pipeline (decode to the `VideoSender` encoder surface, audio through `AudioSender`, no-ADB rework, etc.). See [docs/third-party/CarPToCarL-NOTICE.md](docs/third-party/CarPToCarL-NOTICE.md) for the per-file notice; source files also keep the upstream attribution header.
- **This project does not provide or distribute resources from the old closed-source application** (APKs, images, logs, etc.), and bundles no third-party wallpaper images: wallpapers are either generated backgrounds or user-provided images.
- The MIT copyright and permission notices of the upstream and reference implementations are retained; keep them when redistributing.

This is an unofficial project, unaffiliated with and not authorized or certified by Buick, General Motors, Apple or Baidu. All trademarks belong to their respective owners. Please follow local traffic laws.
