# 功能与界面说明（Buick Link+ CarLife 版）

> 仓库：`F:\carplay\_newproj\linkplus_carlife`（分支 `carlife-port`）  
> 代码快照：`80030ca` + UI 恢复改动；应用版本：`0.0.9-carlife.2`（versionCode 159）  
> 定位：**本项目基于 [schemawisp/link_plus](https://github.com/schemawisp/link_plus)（Buick Link+，MIT）开发**，在它的蓝牙 iAP2 / AirPlay / VideoSender / AudioSender 链路上增加 CarLife 内容源；CarLife 手机端（CarLife+ 无线连接模式）的画面、声音与触摸经本机 TCP 端口接入，再由 link_plus 的 CarPlay 发送链路送到车机。连接原理见 [CarLife 连接机制](carlife-mechanism.md)。  
> CarLife 协议/客户端/视频/音频部分使用了 [CarPToCarL](https://github.com/jjuugk/CarPToCarL) 的 MIT 开源实现（commit `7b729fb`，Copyright (c) 2026 lizhihua），并按 link_plus 的链路改造；归属与逐文件说明见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)。  
> 无需 root / ADB / Shizuku，权限模型见 [no-adb-model.md](no-adb-model.md)。

> **UI 原则（2026-11-07 用户要求）**：以 link_plus 原版为主，CarLife 只补原版没有的功能；原版好用的功能恢复原版形态（首页设计、常用入口、本机蓝牙 MAC、权限与后台、画面边距四滑块、原版壁纸、原版音频策略、日志分享/保存）。原版功能审计与恢复记录见 [AUDIT_RESTORE.md](AUDIT_RESTORE.md)（恢复审计）。

## 适配范围

- **车型：仅适配别克车型**（无线 CarPlay 车机；实车测试以别克车型为准，其它品牌不做保证）。
- **手机端 CarLife：先保证荣耀机型**（荣耀车联 / CarLife+ 无线连接）；其它 Android 手机的 CarLife+ 兼容性按用户反馈逐步验证，不在当前保证范围。
- CarLife+ 需要在手机上手动进入「无线连接」状态，应用才能连上本机端口。

## 状态口径

| 状态 | 含义 |
| --- | --- |
| 已实现待实车验证 | 代码已完成、可编译打包；功能逻辑以实车测试包验证结果为准 |
| 测试版 | 已有实现但不完整或依赖真车证据，可能在测试包中默认关闭/仅部分生效 |
| 不计划再做 | 明确不做，或已从本应用移除 |

## 0. 界面总览

### 0.1 首页（原版 link_plus 设计）

首页以 link_plus 原版布局为主：hero 连接状态（启动/断开）、目标车机、连接准备、常用入口、画面与声音（音频状态卡）、音乐/导航/天气等原版卡片。在此基础上新增两行：

- **CarLife** 行（显眼入口）：提示「先在手机打开 CarLife+ → 无线连接，再回首页点启动连接」，点按进入 CarLife 页；
- **本机蓝牙 MAC** 行：显示自动解析/已手填/未解析占位状态，点按可手填本机蓝牙地址（Honor/MagicOS 等脱敏机型自动解析失败时使用）。

首页**不再有运行日志卡**；兼容暗屏/立即假息屏卡已移除；日志统一在「帮助与诊断」页。

### 0.2 设置顶层（6 项）

**CarLife / 连接与设备 / 显示与画面 / 音频 / 外观与壁纸 / 帮助与诊断**（权限与后台入口保留在「连接与设备」内）。

原版重复入口（桌面应用管理 / 车载应用 / 仪表盘组件 / 超采样 / 横屏 / 侧栏 / 地图分屏）**隐藏不可达**；对应原版面板类保留编译，不做删除式改动。

### 0.3 重点页面

- **CarLife 页**：引擎检测、端口自检、重连、日志诊断，以及五个增量开关——TTS 导航播报、导航指引上行、壁纸背景、画面圆角、车机 GPS 转发。
- **显示与画面**：恢复原版「画面边距」左/上/右/下**四个滑块**（仅向内收缩，下次连接生效）；分辨率/帧率/码率等原版项保留。
- **音频**：恢复原版音频策略——自动 / 音质优先 / 兼容模式。音质优先走 CarLife 音源，**无需投屏授权**；兼容模式需要录音 + 投屏授权（走原版 MediaProjection 采集路径）。TTS 与导航指引开关在 CarLife 页。
- **外观与壁纸**：恢复原版 `WallpaperActivity` / `WallpaperStore`（自选图片、校准、切片）；**无生成壁纸、无内置第三方图片**。CarLife 画面是否使用壁纸背景由 CarLife 页开关控制。
- **帮助与诊断**：日志「分享日志（ZIP）」只在应用缓存中打包并通过系统分享，**不落下载目录**；只有「保存日志（ZIP）」才写入「下载/Buick Link＋」；另有诊断、版本、许可与免责声明入口。
- **连接与设备**：目标车机、网络兼容连接、本机蓝牙地址、权限与后台。

## 1. 功能与实现

| 功能 | 实现位置 | UI 入口或设置 key | 状态 |
| --- | --- | --- | --- |
| CarLife 视频（H.264 接入 → CarPlay 车机画面） | `carlife/CarLifeHuClient.kt`、`CarLifeProtocol.kt`、`H264Sps.kt`、`CarLifeVideoPipeline.kt`、`CarLifeVideoDecoder.kt`；`capture/CarLifeScreenSource.kt`；`session/SessionController.kt` | 无单独开关；编码参数受「显示与画面」的 `settings/display_scale_percent`、`display_fps`、`display_bitrate_mbps` 影响；车机档位来自 AirPlay SETUP | 已实现待实车验证 |
| 画面合成（壁纸背景 / 安全区 contain / 圆角） | `render/CarLifeComposer.kt`、`render/CarLifeWallpaperSource.kt`、`render/DisplayGeometry.kt`、`ui/WallpaperStore.kt` | 「显示与画面 → 画面边距」`settings/safe_margin_left|top|right|bottom`（四滑块，仅向内收缩）；CarLife 页「壁纸背景」`carlife_wallpaper_enabled`、「画面圆角」`carlife_corner_radius` | 已实现待实车验证 |
| 媒体音频（CarLife 音源 / 原版音频策略） | `carlife/CarLifeAudioSink.kt`、`carlife/CarLifeAudioPipe.kt`、`capture/CarLifeScreenSource.kt`、`audio/AudioSender.kt`、`audio/AudioPreference.kt` | 首页「画面与声音」（`carlife_audio_enabled`）；「音频」页策略 `audio_preferences/strategy`（自动/音质优先/兼容） | 已实现待实车验证 |
| TTS 导航播报（独立通道，混入车机音频） | `carlife/CarLifeAudioSink.kt`（TTS ring）、`audio/AudioSender.kt`（混音/duck）、`capture/CarLifeScreenSource.kt` | CarLife 页「TTS 导航播报」`carlife_tts_enabled` | 已实现待实车验证 |
| 车机触摸回传 CarLife（单点/滑动/双指手势切换） | `input/CarLifeTouchSink.kt`、`input/TouchRouter.kt`、`carlife/CarLifeHuClient.kt` | 无独立开关；「显示与画面」触摸节流 `settings/move_throttle_ms` | 已实现待实车验证 |
| 导航指引上行（CarLife 导航 → 车机仪表/导航卡） | `carlife/CarLifeNav.kt`、`carlife/CarLifeNavAmap.kt`、`aidl/com/amap/linksdk/aidldefine/*`、`media/NavGuidance.kt`、`iap2/Iap2SenderSession.kt` | CarLife 页「导航指引上行」`carlife_nav_enabled`；通知使用权需在系统设置手动开启 | 已实现待实车验证 |
| 媒体 NowPlaying（车机媒体卡歌名/歌手/进度） | `carlife/CarLifeNowPlaying.kt`、`airplay/VirtualIap2Link.kt`、`iap2/Iap2SenderSession.kt` | 无 UI；车机订阅 0x5000 后按订阅属性推送 0x5001 | 已实现待实车验证 |
| 封面（artwork 元数据与本地压缩/缓存） | `carlife/CarLifeNowPlaying.kt`（768×768 JPEG 压缩/缓存/日志） | 无 UI | 测试版（**不发 field 0x001A / field26**；file-transfer 会话需真车 k1 证据后再接） |
| HUD 广播（车机/手机侧 HUD 信息 → 导航） | `ui/CarLifeHudReceiver.kt`、`carlife/CarLifeNav.kt` | 无独立 UI；系统广播 `carlife_show_hud_info` / `mix_notify_start_navi` | 已实现待实车验证 |
| 车机语音键（方向盘语音键 → CarLife 语音识别） | `input/CarLifeVoiceKeyRouter.kt`、`input/TouchRouter.kt`、`iap2/Iap2SenderSession.kt`（HID） | 无 UI（车机按键触发；requestSiri + HID Consumer 0xCF 兜底） | 已实现待实车验证 |
| 车机 GPS 回填（手机定位 → CarLife 0x18010） | `carlife/CarLifeGpsBridge.kt`、`carlife/CarLifeHuClient.kt`（`sendCarGps`） | CarLife 页「车机 GPS 转发」`settings/carlife_use_car_gps` | 已实现待实车验证 |
| 本机蓝牙地址（iAP2 传输标识 / ctrl 广播） | `util/DeviceIdentity.kt`、`MainActivity.kt`、`launcher/settings/SettingsCategoryFragment.kt` | 首页「本机蓝牙 MAC」行（点按手填）；「连接与设备」页手填入口；key `settings/bt_mac_override` | 已实现待实车验证 |
| 诊断日志（ZIP 分享/保存）与端口自检 | `util/DebugFileLog.kt`、`util/LogBundle.kt`、`util/LogExporter.kt`、`MainActivity.kt` | 「帮助与诊断」：分享日志（ZIP，不落盘）/保存日志（ZIP，写下载/Buick Link＋）；CarLife 页：刷新自检/重连/日志诊断 | 已实现待实车验证 |
| 壁纸（原版） | `ui/WallpaperActivity.kt`、`ui/WallpaperStore.kt`、`render/CarLifeWallpaperSource.kt` | 「外观与壁纸」：图库选图、校准、切片；**无生成壁纸、无内置第三方图片** | 已实现待实车验证 |
| 设置页结构（原版为主 + CarLife 增量） | `MainActivity.kt`、`launcher/settings/SettingsMenus.kt` / `SettingsFragments.kt` / `SettingsCategoryFragment.kt`、`launcher/settings/CarLifePrefs.kt` | 顶层 6 项：CarLife / 连接与设备 / 显示与画面 / 音频 / 外观与壁纸 / 帮助与诊断；原版重复入口隐藏不可达 | 已实现待实车验证 |
| CarLife 连接页（引擎/端口/重连/诊断 + 五个开关） | `MainActivity.kt`（`buildCarLifePage`）、`carlife/CarLifeEngineDetector.kt`、`carlife/CarLifeGpsBridge.kt`、`launcher/settings/CarLifePrefs.kt` | CarLife 页：刷新自检 / 重连 / 日志诊断；TTS、导航指引、壁纸背景、画面圆角、车机 GPS 五个开关 | 已实现待实车验证 |
| 内置无线 ADB 激活 / Shizuku / root 能力 | 无（相关代码、权限、依赖已移除） | 无入口 | 不计划再做 |
| 车机麦克风接管 | 无 | 无 | 不计划再做 |
| 有线 CarPlay | 无 | 无 | 不计划再做 |
| iAP2 歌词字段 | 无（官方 0x5001 无歌词字段） | 无 | 不计划再做 |
| 内置第三方壁纸图片 / 生成壁纸 | 无（不随包分发第三方图片；生成壁纸已下线） | 无 | 不计划再做 |

### 状态汇总

| 状态 | 数量 | 功能 |
| --- | --- | --- |
| 已实现待实车验证 | 15 | 视频、画面合成、媒体音频、TTS、触摸、导航上行、NowPlaying、HUD、语音键、GPS、MAC 身份、日志、壁纸、设置页、CarLife 页 |
| 测试版 | 1 | 封面 artwork（不发 field26 / file-transfer 未接） |
| 不计划再做 | 5 | 内置无线 ADB/Shizuku/root、车机麦克风接管、有线 CarPlay、iAP2 歌词字段、内置第三方壁纸图片/生成壁纸 |

## 2. 已隐藏/下线的 link_plus 功能

以 link_plus 原版为主后，以下重复入口从设置菜单**隐藏不可达**，原版面板类保留编译（避免误删上游代码）：

| 已隐藏功能 | 说明 |
| --- | --- |
| 桌面应用管理 / 应用抽屉 | 显示入口隐藏；面板类保留编译 |
| 官方车载应用入口 | 显示入口隐藏；不随包分发第三方 APK |
| 仪表盘音乐组件 / 官方 RemoteViews 组件 | 显示入口隐藏 |
| 应用超采样 | 显示入口隐藏 |
| 应用横屏 | 显示入口隐藏 |
| 侧栏 Dock | 显示入口隐藏 |
| 地图分屏 / 手机地图交接 | 显示入口隐藏 |
| 内置第三方壁纸图片 / 生成壁纸 | 不提供；壁纸使用原版自选图片方案 |

## 3. 已知限制与验证状态

- 所有「已实现待实车验证」项以 GitHub Releases 的测试包在别克车型上实测为准；测试记录反馈方式见 [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md) 的实车测试清单。
- 音频「兼容模式」需要录音 + 投屏（MediaProjection）授权；默认「音质优先」走 CarLife 音源，无需投屏授权。
- 封面 artwork 为测试版：只做元数据与本地压缩/缓存/日志，**不发送 file-transfer 与 field 0x001A**；需要真车 k1 会话证据后再接。
- HUD 依赖系统广播，部分 ROM 可能收不到；收不到时不阻塞导航（AMap/通知兜底）。
- CarLife 手机端先保证荣耀机型；其它 Android 手机 CarLife+ 的端口行为/权限提示可能不同。
- 仅适配别克车型，不做其它品牌车机的兼容保证。
- 本项目**不提供、不分发旧闭源应用资源**（APK、图片、日志等）。

## 4. 连接原理

手机 CarLife+ 以「无线连接」模式在本机监听 TCP 端口，本应用扮演“车机端”连接这些端口，收 H.264/音频/触摸，再经 link_plus 的 AirPlay 发送链路输出到车机。完整说明（端口、握手、时序）见 [CarLife 连接机制](carlife-mechanism.md)。

## 5. 相关文档与维护说明

- 原版功能审计/恢复记录：[AUDIT_RESTORE.md](AUDIT_RESTORE.md)
- 开发目标、实现与实车测试：[CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md)
- 实现位置列路径均相对 `app/src/main/java/io/github/shaonialife/buicklinkplus/`（`aidl/` 相对 `app/src/main/`）。
- 修改用户可见行为时同步更新本文与 CARLIFE_PORT_PLAN；新增设置项请同时登记 key。
- 归属与许可见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md) 与 [dependencies.md](dependencies.md)。
