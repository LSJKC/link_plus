# 第三方组件与许可

应用代码采用根目录的 MIT 许可，下面的组件保留各自许可。

| 组件 | 版本 | 许可 |
| --- | --- | --- |
| Kotlin stdlib | 2.3.21 | Apache-2.0 |
| AndroidX core-ktx / AppCompat | 1.13.1 / 1.7.0 | Apache-2.0 |
| ViewPager2 / RecyclerView | 1.1.0 / 1.3.2 | Apache-2.0 |
| Bouncy Castle bcprov / bcpkix | 1.81.1 | MIT |
| libadb-android | 3.1.1 | 本项目选用 Apache-2.0，附带代码的 BSD/MIT 声明一并保留 |
| spake2-android / spake2-java | 2.2.1 | LGPL-3.0 |
| Conscrypt Android | 2.8.0 | Apache-2.0，附 NOTICE 与 BoringSSL 许可 |
| Ant Design Icons outlined | 7f2516ac91226d2b41f93b35cb5197c8d94f7189 | MIT |
| JUnit | 4.13.2 | EPL-1.0，仅测试 |
| org.json / MockWebServer | 20240303 / 4.12.0 | JSON / Apache-2.0，仅测试 |
| AndroidX test runner | 1.6.2 | Apache-2.0，仅测试 |

AndroidX 传递模块、Kotlin coroutines 1.6.4、JetBrains annotations 13.0 和 Guava listenablefuture 1.0 使用 Apache-2.0。AGP、Gradle、Kotlin、Spotless 和 ktlint 的许可随各自分发包提供。

APK 内的声明见 [embedded-third-party-notices.txt](../app/src/main/assets/embedded-third-party-notices.txt)。图标来自 [Ant Design Icons](https://github.com/ant-design/ant-design-icons)，原始 SVG 和许可在 [third-party/ant-design-icons](third-party/ant-design-icons/)，资源映射见 [icon-sources.json](icon-sources.json)。

应用使用系统字体，壁纸由用户自行选择。第三方应用图标和官方组件内容来自用户安装的应用，相关权利归原作者所有。

## 对应源码

源码放在工程根目录的 `third-party` 下，保留上游的许可、版权和构建文件。

| 项目 | 对应提交 |
| --- | --- |
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) | c849886ebc6d48e7b46d967e78a6bb65c90c3b74 |
| [spake2-java](https://github.com/MuntashirAkon/spake2-java) | 7615ddd680b990e14513ebb66eac4cb0dbf82464 |
| [spake2-c](https://github.com/MuntashirAkon/spake2-c) | 0d15933e5ba3e662cb01245a7ac0dc9fca3eac31 |

修改和替换 SPAKE2 的步骤见 [对应源码与库替换](embedded-source-build.md)。发布 APK 时请同时提供对应源码和这些许可说明。
