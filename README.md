# Buick Link +

把手机 CarLife+ 的画面、声音和触摸通过无线 CarPlay 投到车机：手机无需 root，也不需要开发者选项、无线调试或 ADB；最低支持 Android 12。

当前目标（2026-11-06 起）：把 hybrid63 插桩版已验证的 CarLife 功能**全面移植为可维护源码**（视频、合成壁纸/安全区、音频/TTS、触摸、导航、媒体、HUD、语音键、GPS、MAC 身份、诊断、壁纸、设置页），去掉内置无线 ADB 激活，瘦身 APK，并出实车测试包。功能进度见 [功能移植矩阵](docs/PORT_MATRIX.md)，目标与实车测试清单见 [CarLife 重构计划](docs/CARLIFE_PORT_PLAN.md)。

![手机界面](docs/images/phone-ui-overview.png)

## 开始使用

无需开发者选项、无线调试或 ADB：安装后授予普通权限（蓝牙、附近 Wi-Fi、通知等，按需申请）并连接车机；画面来自手机 CarLife+，需先在手机上打开 CarLife+ 并进入「无线连接」。无需另装 Shizuku。权限与链路说明见 [无 ADB 模型与权限](docs/no-adb-model.md)。

车型和系统版本会影响连接效果。目前 Android 17 息屏后保持连接仍有问题，欢迎反馈具体机型和日志。驾驶时请勿操作手机。

## 实车测试包与反馈

- 测试包：buick 变体 APK（`app/build/outputs/apk/buick/app-buick.apk`），版本号与 SHA-256 随发布说明提供；
- 反馈日志：应用内「设置 → 帮助与诊断 → 导出日志文件」导出完整日志，附上手机/车机型号、系统版本、应用版本、复现步骤；
- 开发调试：`scripts/p1a-field-check.ps1` 可抓取并校验 CarLife 链路日志（`scripts/verify-carlife-log.ps1` 单独校验已有日志）。

## 构建

使用 JDK 17、Android SDK 34、Build Tools 35.0.0 和 NDK 27.0.12077973。工程自带 Gradle 8.13，使用 AGP 8.13.2 和 Kotlin 2.3.21。通过 `ANDROID_HOME` 或 `local.properties` 配置本机 SDK 路径。

```powershell
./gradlew.bat spotlessCheck :app:testBuickUnitTest :app:lintDebug :app:lintBuick
./gradlew.bat :app:assembleBuick
```

APK 位于 `app/build/outputs/apk/buick/app-buick.apk`，测试包使用 `.buick` 后缀。正式签名通过 `keystore.properties` 配置，可参考同名示例文件；缺少配置时使用本机测试签名。不同签名的安装包不能直接覆盖安装。

## 文档

- [功能移植矩阵](docs/PORT_MATRIX.md)
- [CarLife 重构目标与计划](docs/CARLIFE_PORT_PLAN.md)
- [无 ADB 模型与权限](docs/no-adb-model.md)
- [GitHub 下游同步流程](docs/GITHUB_WORKFLOW.md)
- [项目结构](docs/architecture.md)
- [第三方组件与许可](docs/dependencies.md)
- [English](README.en.md)

## 许可与声明

本项目基于 [shaonialife/cardeck](https://github.com/shaonialife/cardeck)，保留原始 [MIT 许可与版权声明](LICENSE)。第三方组件遵循各自许可，详见上面的许可说明。

这是非官方项目，与别克、通用汽车、Apple 无关联，也未获其授权或认证。相关商标归各自所有者，应用名称只用于说明适配用途。
