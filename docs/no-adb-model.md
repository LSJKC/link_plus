# 无内置 ADB 的权限模型

> 适用范围：本项目（基于 [schemawisp/link_plus](https://github.com/schemawisp/link_plus) 开发）。本文说明用户可见的连接与权限模型：本项目不保留 link_plus 原版的「内置无线 ADB 激活」方案。连接原理见 [carlife-mechanism.md](carlife-mechanism.md)。

## 1. 模型概述

本项目**不保留** link_plus 的内置无线 ADB 激活，相关代码、权限与依赖（`shizuku` 包、内置 shell 服务、`libadb-android`/SPAKE2 等）已移除。用户**不再需要**：

- 开发者选项；
- 无线调试 / USB 调试；
- ADB 配对码或应用内激活步骤；
- Shizuku / root。

安装 APK 后授予普通 Android 权限即可使用；内容源为手机 CarLife+（用户手动进入「无线连接」）。如后续需要额外内容来源，优先使用 Android 原生、无需 ADB 权限的方案（例如 `MediaProjection` 屏幕采集），不引入 ADB 权限。

## 2. 各链路怎么走

| 链路 | 通道 | 需要 shell/root/ADB 吗 |
| --- | --- | --- |
| CarLife 画面（H.264） | 本机回环 TCP：7240（握手/控制）、8240（视频裸流） | 否 |
| CarLife 音频（媒体 / TTS） | 本机回环 TCP：9240（媒体）、9241（TTS） | 否 |
| CarLife 触摸（车机回传） | 本机回环 TCP：9340（触控通道） | 否 |
| 无线 CarPlay 上行 | 蓝牙 iAP2 + Wi-Fi + AirPlay（H.264 视频 / RTP 音频） | 否 |

以上 CarLife 通道都是手机本机回环地址（`127.0.0.1`）上的 TCP：CarLife+ 引擎在用户手动进入「无线连接」后监听，本应用作为普通 socket 客户端扮演“车机端”连接，不需要任何高权限通道。车机触摸由应用按 CarLife 触控帧格式写入 9340 通道，不经过 shell。更多端口/时序说明见 [carlife-mechanism.md](carlife-mechanism.md)。

## 3. 已移除/降级的能力

link_plus 原版依赖内置 shell 的桌面类能力在本项目中不使用（或降级）：

- 虚拟屏桌面/应用抽屉（Dock、应用桌面、地图/音乐分屏等）——不提供；
- 通过 shell 启动虚拟屏应用——不提供；
- 车机触摸注入虚拟屏——不提供，改为回传 CarLife 9340 通道；
- 静默授予通知使用权等 shell 授权能力——不提供，改为用户在系统设置中手动授权；
- 虚拟屏/后台进程残留清理等 shell 维护操作——不提供。

如需补充内容来源或控制能力，优先使用 Android 原生、无需 ADB 权限的方案（如 `MediaProjection`、系统公开 API、用户手动授权），不引入 ADB/root/Shizuku。

## 4. 用户可见权限

应用保留无线 CarPlay 发送端与 CarLife 内容源所需的常规权限，按需申请、用户可随时撤销：

| 权限 | 用途 | 用户操作 |
| --- | --- | --- |
| 蓝牙（`BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`，附近设备） | 配对并选择目标车机 | 运行时授权 |
| Wi-Fi（`NEARBY_WIFI_DEVICES`；Android 12/12L 另需定位权限） | 加入车机网络、mDNS 发现服务 | 运行时授权 |
| 通知（`POST_NOTIFICATIONS`） | 前台服务状态与日志提示 | 运行时授权 |
| 通知使用权（通知监听服务，特殊访问） | 媒体卡读取当前播放信息（通知兜底导航） | 在系统设置中手动开启 |
| 录音（`RECORD_AUDIO`） | 音频采集链路（CarLife 音频走外部 PCM 时为可选回退） | 运行时授权 |
| 悬浮窗（`SYSTEM_ALERT_WINDOW`） | 暗幕/悬浮提示（按需） | 特殊权限，需在系统设置中手动开启 |
| 前台服务（`FOREGROUND_SERVICE` 等） | 连接期间保活，防止被系统回收 | 随功能启用，无需单独授权 |
| 网络（`INTERNET`/`ACCESS_NETWORK_STATE`） | 车机网络与协议通信 | 安装时授予 |

具体权限以 `AndroidManifest.xml` 与实际功能为准；随内置 ADB 代码一并移除的权限声明不会继续申请。

## 5. 用户操作步骤

1. 手机安装 CarLife+（已安装可跳过），确认可以进入「无线连接」；
2. 安装本应用 APK（`.buick` 测试包与正式包签名不同，不能直接互相覆盖）；
3. 启动应用，按提示授予蓝牙、附近 Wi-Fi、通知等运行时权限（录音、悬浮窗等仅在对应功能开启时申请）；
4. 手机与车机完成蓝牙配对并选择目标车机，连接无线 CarPlay；
5. 在 CarLife+ 中手动进入「无线连接」，应用连接本机 7240/8240/9240/9241/9340 TCP 通道后，车机显示 CarLife 画面；
6. 断开连接时应用释放解码器/编码器并结束后台服务，无需手工清理虚拟屏。

全程不需要开发者选项、无线调试、ADB 配对，也不安装 Shizuku、不使用 root。

## 6. 相关文档

- [carlife-mechanism.md](carlife-mechanism.md)：CarLife+ 无线连接与本应用“车机端”的连接原理
- [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md)：目标、实现与实车测试清单
- [PORT_MATRIX.md](PORT_MATRIX.md)：功能与界面说明
- [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)：CarLife 实现的上游 MIT 归属
