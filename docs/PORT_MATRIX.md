# 功能与界面说明（Buick Link+ CarLife 版）

> 仓库：`F:\carplay\_newproj\linkplus_carlife`（分支 `carlife-port`）  
> 代码快照：`9186242`；应用版本：`0.0.9-carlife.1`（versionCode 158）  
> 定位：**本项目基于 [schemawisp/link_plus](https://github.com/schemawisp/link_plus)（Buick Link+，MIT）开发**，在它的蓝牙 iAP2 / AirPlay / VideoSender / AudioSender 链路上增加 CarLife 内容源；CarLife 手机端（CarLife+ 无线连接模式）的画面、声音与触摸经本机 TCP 端口接入，再由 link_plus 的 CarPlay 发送链路送到车机。连接原理见 [CarLife 连接机制](carlife-mechanism.md)。  
> CarLife 协议/客户端/视频/音频部分使用了 [CarPToCarL](https://github.com/jjuugk/CarPToCarL) 的 MIT 开源实现（commit `7b729fb`，Copyright (c) 2026 lizhihua），并按 link_plus 的链路改造；归属与逐文件说明见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)。  
> 无需 root / ADB / Shizuku，权限模型见 [no-adb-model.md](no-adb-model.md)。

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

## 1. 功能与界面

| 功能 | 实现位置 | UI 入口或设置 key | 状态 |
| --- | --- | --- | --- |
| CarLife 视频（H.264 接入 → CarPlay 车机画面） | `carlife/CarLifeHuClient.kt`、`CarLifeProtocol.kt`、`H264Sps.kt`、`CarLifeVideoPipeline.kt`、`CarLifeVideoDecoder.kt`；`capture/CarLifeScreenSource.kt`；`session/SessionController.kt` | 无单独开关；编码参数受「设置 → 显示与画面」的 `settings/display_scale_percent`、`display_fps`、`display_bitrate_mbps` 影响；车机档位来自 AirPlay SETUP | 已实现待实车验证 |
| 画面合成（壁纸背景 / 安全区 contain / 圆角） | `render/CarLifeComposer.kt`、`render/CarLifeWallpaperSource.kt`、`render/DisplayGeometry.kt`、`ui/WallpaperStore.kt` | 「设置 → 显示与画面」：安全区 `carlife_safe_margin`、圆角 `carlife_corner_radius`；「设置 → 外观与壁纸」：壁纸背景 `carlife_wallpaper_enabled` | 已实现待实车验证 |
| 媒体音频（CarLife 媒体声音进 CarPlay 音频） | `carlife/CarLifeAudioSink.kt`、`carlife/CarLifeAudioPipe.kt`、`capture/CarLifeScreenSource.kt`、`audio/AudioSender.kt` | 「设置 → 音频 → CarLife 音源」`carlife_audio_enabled` | 已实现待实车验证 |
| TTS 导航播报（独立通道，混入车机音频） | `carlife/CarLifeAudioSink.kt`（TTS ring）、`audio/AudioSender.kt`（混音/duck）、`capture/CarLifeScreenSource.kt` | 「设置 → 音频 → TTS 导航播报」`carlife_tts_enabled` | 已实现待实车验证 |
| 车机触摸回传 CarLife（单点/滑动/双指手势切换） | `input/CarLifeTouchSink.kt`、`input/TouchRouter.kt`、`carlife/CarLifeHuClient.kt` | 无独立开关；「设置 → 显示与画面」触摸节流 `settings/move_throttle_ms` | 已实现待实车验证 |
| 导航指引上行（CarLife 导航 → 车机仪表/导航卡） | `carlife/CarLifeNav.kt`、`carlife/CarLifeNavAmap.kt`、`aidl/com/amap/linksdk/aidldefine/*`、`media/NavGuidance.kt`、`iap2/Iap2SenderSession.kt` | 「设置 → 音频 → 导航指引上行」`carlife_nav_enabled`；通知使用权需在系统设置手动开启 | 已实现待实车验证 |
| 媒体 NowPlaying（车机媒体卡歌名/歌手/进度） | `carlife/CarLifeNowPlaying.kt`、`airplay/VirtualIap2Link.kt`、`iap2/Iap2SenderSession.kt` | 无 UI；车机订阅 0x5000 后按订阅属性推送 0x5001 | 已实现待实车验证 |
| 封面（artwork 元数据与本地压缩/缓存） | `carlife/CarLifeNowPlaying.kt`（768×768 JPEG 压缩/缓存/日志） | 无 UI | 测试版（**不发 field 0x001A / field26**；file-transfer 会话需真车 k1 证据后再接） |
| HUD 广播（车机/手机侧 HUD 信息 → 导航） | `ui/CarLifeHudReceiver.kt`、`carlife/CarLifeNav.kt` | 「设置 → CarLife」：发送/停止 HUD 测试按钮；系统广播 `carlife_show_hud_info` / `mix_notify_start_navi` | 已实现待实车验证 |
| 车机语音键（方向盘语音键 → CarLife 语音识别） | `input/CarLifeVoiceKeyRouter.kt`、`input/TouchRouter.kt`、`iap2/Iap2SenderSession.kt`（HID） | 无 UI（车机按键触发；requestSiri + HID Consumer 0xCF 兜底） | 已实现待实车验证 |
| 车机 GPS 回填（手机定位 → CarLife 0x18010） | `carlife/CarLifeGpsBridge.kt`、`carlife/CarLifeHuClient.kt`（`sendCarGps`） | 「设置 → CarLife → 车机 GPS 转发」`settings/carlife_use_car_gps` | 已实现待实车验证 |
| 本机蓝牙地址（iAP2 传输标识 / ctrl 广播） | `util/DeviceIdentity.kt`、`launcher/settings/SettingsCategoryFragment.kt` | 「设置 → 连接与设备 → 本机蓝牙地址」`settings/bt_mac_override` | 已实现待实车验证 |
| 诊断日志与端口自检 | `util/DebugFileLog.kt`、`util/LogExporter.kt`、`MainActivity.kt`、`launcher/settings/SettingsCategoryFragment.kt` | 「设置 → 日志与关于」导出本次/全部日志；「设置 → CarLife」刷新状态/端口自检/重连 | 已实现待实车验证 |
| 壁纸（背景） | `ui/WallpaperActivity.kt`、`ui/WallpaperStore.kt`、`render/CarLifeWallpaperSource.kt` | 「设置 → 外观与壁纸」：壁纸编辑器（导入自定义图片）、恢复默认 | 已实现待实车验证（**不内置第三方图片**：默认程序生成主题底色背景，支持用户自定义图片） |
| 设置页 | `MainActivity.kt`、`launcher/settings/SettingsMenus.kt` / `SettingsFragments.kt` / `SettingsCategoryFragment.kt`、`launcher/SettingsActivity.kt`、`PhoneSettingsActivity.kt`、`launcher/settings/CarLifePrefs.kt` | 手机设置六类页面：连接与设备 / 显示与画面 / 音频 / CarLife / 外观与壁纸 / 日志与关于 | 已实现待实车验证 |
| 内置无线 ADB 激活 / Shizuku / root 能力 | 无（相关代码、权限、依赖已移除） | 无入口 | 不计划再做 |
| 车机麦克风接管 | 无 | 无 | 不计划再做 |
| 有线 CarPlay | 无 | 无 | 不计划再做 |
| iAP2 歌词字段 | 无（官方 0x5001 无歌词字段） | 无 | 不计划再做 |
| 内置第三方壁纸图片 | 无（不随包分发第三方图片） | 无 | 不计划再做 |

### 状态汇总

| 状态 | 数量 | 功能 |
| --- | --- | --- |
| 已实现待实车验证 | 14 | 视频、画面合成、媒体音频、TTS、触摸、导航上行、NowPlaying、HUD、语音键、GPS、MAC 身份、诊断日志、壁纸、设置页 |
| 测试版 | 1 | 封面 artwork（不发 field26 / file-transfer 未接） |
| 不计划再做 | 5 | 内置无线 ADB/Shizuku/root、车机麦克风接管、有线 CarPlay、iAP2 歌词字段、内置第三方壁纸图片 |

## 2. 已下线功能（link_plus 桌面类，不再提供）

本项目只保留 CarLife 内容源与 CarPlay 发送所需功能；link_plus 原桌面/应用管理类能力已从菜单下线，代码与资源在后续版本清理：

| 已下线功能 | 说明 |
| --- | --- |
| 桌面应用管理 / 应用抽屉 | 画面只来自 CarLife，不再有虚拟屏桌面 |
| 官方车载应用入口 | 不随包分发第三方 APK；CarLife 画面内自带应用入口 |
| 仪表盘音乐组件 / 官方 RemoteViews 组件 | 媒体信息统一走 CarLife NowPlaying 与车机媒体卡 |
| 应用超采样 | CarLife 码流尺寸由手机引擎决定，无虚拟屏超采样概念 |
| 应用横屏 | 不再启动手机应用，横竖屏由 CarLife 自身决定 |
| 侧栏 Dock | 车机画面不拼接 link_plus 侧栏 |
| 地图分屏 / 手机地图交接 | 导航画面由 CarLife 提供；仪表导航走 iAP2 上行 |
| 歌词 | 官方 iAP2 0x5001 无歌词字段，不做 |
| 天气 / 手机信号 | 与 CarLife 内容无关，移除 |
| 车机外观定制（透明度/模糊/轮廓/浮层） | 只保留画面合成需要的壁纸/安全区/圆角；手机端 UI 外观保留 |
| 应用图标缓存 / 桌面图标 | 随桌面应用管理一起移除 |
| 内置第三方壁纸图片 | 不内置第三方图片；使用程序生成背景 + 用户自定义图片 |

## 3. 已知限制与验证状态

- 所有「已实现待实车验证」项以 GitHub Releases 的测试包在别克车型上实测为准；测试记录与日志反馈方式见 [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md) 的实车测试清单。
- 封面 artwork 为测试版：只做元数据与本地压缩/缓存/日志，**不发送 file-transfer 与 field 0x001A**；需要真车 k1 会话证据后再接。
- HUD 依赖系统广播，部分 ROM 可能收不到；收不到时不阻塞导航（AMap/通知兜底）。
- CarLife 手机端先保证荣耀机型；其它 Android 手机 CarLife+ 的端口行为/权限提示可能不同。
- 仅适配别克车型，不做其它品牌车机的兼容保证。
- 本项目**不提供、不分发旧闭源应用资源**（APK、图片、日志等）。

## 4. 连接原理

手机 CarLife+ 以「无线连接」模式在本机监听 TCP 端口，本应用扮演“车机端”连接这些端口，收 H.264/音频/触摸，再经 link_plus 的 AirPlay 发送链路输出到车机。完整说明（端口、握手、时序）见 [CarLife 连接机制](carlife-mechanism.md)（由 carlife-core 维护）。

## 5. 维护说明

- 实现位置列中的路径均相对 `app/src/main/java/io/github/shaonialife/buicklinkplus/`（`aidl/` 相对 `app/src/main/`）。
- 修改用户可见行为时同步更新本文与 [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md)；新增设置项请同时登记 key。
- 归属与许可见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md) 与 [dependencies.md](dependencies.md)。
