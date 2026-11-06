# 第三方组件与许可

应用代码采用根目录的 MIT 许可，下面的组件保留各自许可。CarLife 协议/客户端/视频/音频实现使用 CarPToCarL 的 MIT 开源实现，来源与许可见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)。

| 组件 | 版本 | 许可 |
| --- | --- | --- |
| Kotlin stdlib | 2.3.21 | Apache-2.0 |
| AndroidX core-ktx / AppCompat | 1.13.1 / 1.7.0 | Apache-2.0 |
| ViewPager2 / RecyclerView | 1.1.0 / 1.3.2 | Apache-2.0 |
| Bouncy Castle bcprov | 1.81.1 | MIT |
| Ant Design Icons outlined | 7f2516ac91226d2b41f93b35cb5197c8d94f7189 | MIT |
| JUnit | 4.13.2 | EPL-1.0，仅测试 |
| org.json / MockWebServer | 20240303 / 4.12.0 | JSON / Apache-2.0，仅测试 |
| AndroidX test runner | 1.6.2 | Apache-2.0，仅测试 |

AndroidX 传递模块、Kotlin coroutines 1.6.4、JetBrains annotations 13.0 和 Guava listenablefuture 1.0 使用 Apache-2.0。AGP、Gradle、Kotlin、Spotless 和 ktlint 的许可随各自分发包提供。APK 默认只打包 arm64-v8a / armeabi-v7a 两种 ABI（见 `app/build.gradle.kts`），未使用依赖 `conscrypt-android` 与 `bcpkix-jdk18on` 已移除以缩小安装包。

APK 内的声明见 [embedded-third-party-notices.txt](../app/src/main/assets/embedded-third-party-notices.txt)。图标来自 [Ant Design Icons](https://github.com/ant-design/ant-design-icons)，原始 SVG 和许可在 [third-party/ant-design-icons](third-party/ant-design-icons/)，资源映射见 [icon-sources.json](icon-sources.json)。

应用使用系统字体，壁纸为程序生成背景或用户自定义图片（不内置第三方图片）。第三方应用图标等内容来自用户安装的应用，相关权利归原作者所有。

## 对应源码

本分支不保留内置无线 ADB 激活，`libadb-android` 与 SPAKE2（spake2-android / spake2-java / spake2-c）依赖及其对应源码已按用户要求移除，不再随本分支分发；如需历史版本源码或替换步骤，见仓库历史提交。发布 APK 时请提供实际使用组件的对应源码和许可说明。无 ADB 模型的说明见 [no-adb-model.md](no-adb-model.md)。
