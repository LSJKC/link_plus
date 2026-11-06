# Buick Link +

把 Android 手机上的应用画面和声音传到无线 CarPlay 车机，再用车机触屏操作手机应用。手机无需 root，最低支持 Android 12。

手机端可以查看连接状态、调整设置；车机端提供应用桌面、地图分屏、音乐组件、歌词和天气。官方车载应用需要自行安装，项目不附带第三方 APK。

![手机界面](docs/images/phone-ui-overview.png)

## 开始使用

先在手机开发者选项中开启无线调试，按应用提示完成内置服务激活，再配对并选择车机。无需另装 Shizuku。录音、通知和定位权限会在使用对应功能时申请。

车型和系统版本会影响连接效果。目前 Android 17 息屏后保持连接仍有问题，欢迎反馈具体机型和日志。驾驶时请勿操作手机。

## 构建

使用 JDK 17、Android SDK 34、Build Tools 35.0.0 和 NDK 27.0.12077973。工程自带 Gradle 8.13，使用 AGP 8.13.2 和 Kotlin 2.3.21。通过 `ANDROID_HOME` 或 `local.properties` 配置本机 SDK 路径。

```powershell
./gradlew.bat spotlessCheck :app:testBuickUnitTest :app:lintDebug :app:lintBuick
./gradlew.bat :app:assembleBuick
```

APK 位于 `app/build/outputs/apk/buick/app-buick.apk`，测试包使用 `.buick` 后缀。正式签名通过 `keystore.properties` 配置，可参考同名示例文件；缺少配置时使用本机测试签名。不同签名的安装包不能直接覆盖安装。

- [项目结构](docs/architecture.md)
- [第三方组件与许可](docs/dependencies.md)
- [对应源码与库替换](docs/embedded-source-build.md)
- [English](README.en.md)

## 许可与声明

本项目基于 [shaonialife/cardeck](https://github.com/shaonialife/cardeck)，保留原始 [MIT 许可与版权声明](LICENSE)。第三方组件遵循各自许可，详见上面的许可说明。

这是非官方项目，与别克、通用汽车、Apple 无关联，也未获其授权或认证。相关商标归各自所有者，应用名称只用于说明适配用途。
