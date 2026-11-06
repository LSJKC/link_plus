# 对应源码与库替换

`third-party/libadb-android-3.1.1` 和 `third-party/spake2-java-2.2.1` 包含对应版本源码。SPAKE2 的原生子模块已放在 `android/src/main/cpp/spake2-c`，各目录保留上游许可和版权。

2026-11-06，附带的 SPAKE2 源码移除了 3 处注释中的联系邮箱，保留原署名和版权，代码逻辑未改。

应用的构建环境和命令见 [README](../README.md)。替换库后重新打包即可；使用自己的签名时，需要先卸载签名不同的旧包。

## 重建 SPAKE2

- spake2-java：tag `2.2.1`，提交 `7615ddd680b990e14513ebb66eac4cb0dbf82464`
- spake2-c：提交 `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31`
- 使用 LGPL-3.0；相关 GPL-3.0 原文也随 APK 提供。可按许可修改或替换库，也可为调试这些修改进行逆向工程

库的构建环境与应用不同：上游配置使用 JDK 21、AGP 8.9.2、SDK/Build Tools 35 和 Gradle 9.0.0，原生代码还需要对应的 NDK/CMake。具体版本以附带源码的构建文件为准。下面的库重建步骤尚未单独验证。

在 `third-party/spake2-java-2.2.1` 下执行：

```powershell
./gradlew.bat :android:assembleRelease
```

把 `android/build/outputs/aar` 生成的 AAR 放入应用的 `app/libs`。在 `app/build.gradle.kts` 中排除原 SPAKE2 依赖，再引用本地 AAR：

```kotlin
implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    exclude(group = "com.github.MuntashirAkon.spake2-java", module = "spake2-android")
}
implementation(files("libs/spake2-android-release.aar"))
```

文件名按实际产物调整，然后回到工程根目录执行 `./gradlew.bat :app:assembleBuick`。

保持公开接口和原生库加载名称不变，就能直接替换并重新打包，不需要原作者的私钥。若调整了接口，也要修改应用调用处。发布修改版时请一并提供修改后的库源码、许可和构建说明。
