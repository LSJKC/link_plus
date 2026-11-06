# 功能移植矩阵（hybrid63 → 本仓库）

> 分支：`carlife-port`  
> 快照：`7a1d81d` + v0.2.0 实车测试包（2026-11-06 深夜）  
> 旧插桩工程：`F:\carplay\_newproj\hybrid63`（只读参考）  
> 参考实现：[CarPToCarL](https://github.com/jjuugk/CarPToCarL) `7b729fb`（MIT，见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)）

本文是“把 hybrid63 插桩已验证功能全面移植为源码”的进度总表。目标与阶段口径见 [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md)；无 ADB 模型见 [no-adb-model.md](no-adb-model.md)；实车测试口径见 CARLIFE_PORT_PLAN §6/§10。

**状态口径**

| 状态 | 含义 |
| --- | --- |
| 已移植 | 代码已合入本仓库、当前可编译（不代表已过实车验证） |
| 移植中 | 底层能力已就绪（协议/客户端/解析），但未接到 link_plus 对应链路或未完成收尾 |
| 待移植 | 尚未开始 |
| 不移植 | 明确不做（改用原生实现或删除） |

**重要口径（2026-11-06 起）**：不再按阶段做“自测/逐阶段验收”。每个功能以“逻辑正确 + 可编译打包”为完成标准；全部功能就绪后一次性出实车测试包统一验证。

## 0.0 实车测试包 v0.2.0（0.2.0 / versionCode 154）

**已合入本包（已编译，待实车验证）**：视频直出 + P1B 合成（壁纸 cover + 安全区 contain + 圆角）、
音频媒体 9240（48k stereo 1408B / 44.1k 相位重采样）、TTS 9241（独立 ring + duck/mix）、
触摸 9340（安全区 contain 映射 + 单点/滑动/双指切换）、导航 iAP2（0x10030 → CarLifeNav →
`Iap2SenderSession` 0x5201/0x5202；AMap LinkSDK 可选源）、AirPlay 虚拟链路 0x5200 订阅注入、
内置壁纸 9 张 + 壁纸选择、设置页收敛为六类、无内置无线 ADB 激活、APK 瘦身（26.9MB → 15.1MB）。

**本包尚未包含（后续提交继续移植）**：媒体 NowPlaying 0x5001 发送（进行中）、封面 artwork
file-transfer（进行中，测试版）、HUD 广播入口、车机语音键 → CarLife VR、车机 GPS → 0x18010。

**快照说明**：本表状态按**已提交基线 `cb5ddc3`** 统计。仓库工作区存在其他并行工作流的未提交移植（见 §0.1），尚未计入状态列；合入后请更新对应行的“当前状态”与“目标源码文件”。

## 0.1 工作区并行进展（截至写表时，未提交，未计入 §1 状态）

以下文件/改动在工作区出现，属于并行移植（未提交、未构建验证），合入后应把 §1 对应功能更新为“移植中/已移植”：

| 功能 | 工作区已出现的实现 | 推断状态 |
| --- | --- | --- |
| 合成壁纸 / 安全区 | `render/CarLifeComposer.kt`、`render/CarLifeWallpaperSource.kt`、`capture/CarLifeScreenSource.kt` 改动、`ui/WallpaperStore.kt` 改动、`CarLifePrefs` 安全区/圆角/壁纸键 | 实现中 |
| 音频媒体 | `carlife/CarLifeAudioSink.kt`、`carlife/CarLifeAudioPipe.kt`、`audio/AudioSender.kt` 外部 PCM 改动、`capture/CarLifeScreenSource.kt` 的 `onAudioPcm` 接线 | 实现中 |
| TTS | `carlife/CarLifeAudioSink.kt` 的 TTS ring、`capture/CarLifeScreenSource.kt` 的 `onTtsPcm` 接线 | 实现中 |
| 触摸 | `input/CarLifeTouchSink.kt`、`input/TouchRouter.kt` 改动 | 实现中 |
| 导航 iAP2 | `carlife/CarLifeNav.kt`、`carlife/CarLifeNavAmap.kt`、`app/src/main/aidl/com/amap/linksdk/aidldefine/*`、`iap2/Iap2SenderSession.kt` 的 0x5200 订阅门控与 0x5201/0x5202 发送 | 实现中 |
| 内置壁纸 | `ui/BuiltinWallpapers.kt`、`app/src/main/assets/wallpapers/*.jpg`（9 张） | 实现中 |
| 设置页 / 瘦身 | `SettingsMenus.kt` 收敛为六类页面（连接与设备/显示与画面/音频/CarLife/外观与壁纸/日志与关于）、`SettingsCategoryFragment.kt`/`SettingsFragments.kt`/`MainActivity.kt`/`PhoneHomeDashboard.kt` 改动、`CarLifePrefs.kt` | 实现中 |
| HUD / 封面 artwork | 暂未见工作区实现文件（GPS/语音键的协议与发送 API 已在 `CarLifeHuClient` 就绪，见 §1） | 待移植 |

> 说明：这些文件由其他工作流并行写入，本表不保证其完整性；合入（提交 + 构建通过）后更新 §1。

---

## 1. 功能矩阵

| 功能 | hybrid63 源码参考路径 | 目标源码文件（本仓） | UI 入口 / 设置 key | 当前状态 | 说明与下一步 |
| --- | --- | --- | --- | --- | --- |
| 视频直出（CarLife H.264 → 编码器 Surface） | `inject/src/com/turtlelink/carlife/CarLifeHuClient.java`、`CarLifeVideoDecoder.java`、`CarLifeVideoPipeline.java`、`H264Sps.java`、`CarLifeProtocol.java`；`inject/src/tl/CarLifeBridge.java`（Surface 接线）。补充：CarPToCarL `carlife/*` | `carlife/CarLifeHuClient.kt`、`CarLifeProtocol.kt`、`H264Sps.kt`、`CarLifeVideoPipeline.kt`、`CarLifeVideoDecoder.kt`；`capture/CarLifeScreenSource.kt`；集成点 `session/SessionController.kt` | 无开关；编码 profile 受 `settings/display_scale_percent`、`display_fps`、`display_bitrate_mbps` 影响（`launcher/settings/DisplayPrefs.kt`）；车机分辨率来自 AirPlay SETUP | **已移植** | P1A 已完成（`25a350a`→`cb5ddc3`，含三轮审计修复）；实车画面待验证；尺寸不一致由 P1B 合成器解决 |
| 合成壁纸 / 安全区（contain + 圆角 + letterbox） | `inject/src/tl/CarLifeBridge.java`（把原版安全区内容层 Surface 直连解码器，复用原版合成器/壁纸）；`base-decoded/smali/` 原版混淆合成器（仅行为参考）；壁纸入口 `tl/BuiltinWallpapers.java`、`tl/CarLifeWallpaperActivity.java`。补充：CarPToCarL `render/DualDisplayComposer.kt` | 新建 `render/CarLifeComposer.kt`（EGL/OES + 壁纸 + contain + 圆角）、`render/CarLifeWallpaperSource.kt`；接入 `capture/CarLifeScreenSource.kt`；参考 `render/DualDisplayComposer.kt`、`render/DisplayGeometry.kt`、`ui/WallpaperStore.kt` | 设置→显示与画面/外观与壁纸；keys `carlife_wallpaper_enabled`、`carlife_safe_margin`、`carlife_corner_radius`（`CarLifePrefs`） | **待移植** | 当前 P1A 直出会拉伸；旧工程靠原版合成器 Surface 直连，无法照搬，需按 link_plus GL 重写；工作区已在实现（§0.1） |
| 音频媒体（9240 PCM → AudioSender） | `inject/src/tl/CarLifeAudio.java`、`tl/CarLifeAudioPipe.java`。补充：CarPToCarL `carlife/CarLifeAudioSink.kt` | 新建 `carlife/CarLifeAudioSink.kt`（媒体 ring/重采样/44.1k 相位）、`carlife/CarLifeAudioPipe.kt`（1408B 包）；`audio/AudioSender.kt` 增加外部 PCM 入口；`capture/CarLifeScreenSource.kt` 接 `onAudioPcm`/`onAudioFormat` | 设置→音频：key `carlife_audio_enabled`（`CarLifePrefs`）；保留 link_plus 原生音频策略 `audio_preferences/strategy` | **移植中** | 客户端 9240 收流/格式回调已就绪（工作区已实现 sink/pipe，§0.1）；缺 AudioSender 外部入口的构建验证 |
| TTS（9241 → 独立 ring/混音） | 同上（TTS 独立通路）+ `inject/src/tl/CarLifeBridge.java` 的 `onTtsPcm/onTtsFormat`。补充：CarPToCarL CarLifeAudioSink 的 TTS 段 | `carlife/CarLifeAudioSink.kt`（TTS ring）；`audio/AudioSender.kt`（混音/duck）；`capture/CarLifeScreenSource.kt` 接 `onTtsPcm`/`onTtsFormat` | 设置→音频：key `carlife_tts_enabled`（`CarLifePrefs`） | **移植中** | 9241 常为 16k 单声道；必须独立 ring，不能覆盖 48k 媒体；工作区已实现（§0.1） |
| 触摸（车机 → CarLife 9340） | `inject/src/tl/CarLifeTouch.java`（坐标映射 + 9340）、`tl/CarLifeBridge.java`（setGeometry）。补充：CarPToCarL `carlife/CarLifeHijack.kt` 触摸段 | 新建 `input/CarLifeTouchSink.kt`；`input/TouchRouter.kt` 新增 CarLife 目标；`carlife/CarLifeHuClient.kt` 已有 `touchDown/Move/Up/Action` | 设置→显示与画面：触摸节流 `settings/move_throttle_ms`（DisplayPrefs）；坐标映射依赖画面 pane（P1B） | **移植中** | 客户端 9340 发送 API 已就绪；缺 TouchRouter 目标与坐标映射（工作区已实现，§0.1），先单指 |
| 导航 iAP2（0x10030/通知/AMap → 0x5201/0x5202） | `inject/src/tl/CarLifeNav.java`（build5201/5202、HUD/通知汇入）、`tl/CarLifeNavAmap.java`（AMap LinkSDK） | 新建 `carlife/CarLifeNav.kt`（导航源汇总 + 0x5201/0x5202 组包）、`carlife/CarLifeNavAmap.kt`（AMap LinkSDK）、`app/src/main/aidl/com/amap/linksdk/aidldefine/*`；`iap2/Iap2SenderSession.kt` 增加 0x5200 订阅门控与 0x5201/0x5202 发送；`media/NavGuidance.kt` 通知文本兜底；`iap2/Iap2MessageIds.kt` 已有 0x5200 | 设置→CarLife/日志与关于：key `carlife_nav_enabled`（`CarLifePrefs`）；通知使用权系统设置手动开启；AMap LinkSDK 自动发现；调试“发送测试导航帧”入口待建 | **待移植** | 0x5200 后发；maneuverState 用 u64；空数据不发；CarLife 0x10030 仅对内置百度导航有效；工作区已实现（§0.1） |
| 媒体 NowPlaying（0x10035 → iAP2 0x5001） | `inject/src/tl/CarLifeMedia.java`（0x10035 → 原版 NowPlaying）、`tl/CarLifeBridge.java` 的 `onMediaInfo/onMediaProgress`。补充：CarPToCarL `iap2/Iap2NowPlaying.kt` + SessionController 桥 | `carlife/CarLifeProtocol.kt` 已有 `parseMediaInfoList`、`carlife/CarLifeHuClient.kt` 已有 `onMediaInfo`；待建 0x5001 组包/推送（`iap2/Iap2SenderSession.kt` 或 `airplay/VirtualIap2Link.kt` 由空态改真实数据，现有 TODO） | 无 UI；另可继续用 link_plus 原生媒体通知监听 `media/MediaSessionsState` 作数据源 | **移植中** | 当前仅 MIB3 空态应答；CarLife 媒体信息解析已就绪，缺桥接与真实 0x5001 组包 |
| 封面 artwork（0x5001 file-transfer） | `inject/src/tl/CarLifeMedia.java`（albumArt 只进本地媒体卡，未进 iAP2）；`base-decoded/smali/` 原版 file-transfer（未完整逆向）；CarPToCarL 未实现（仅会话声明） | 新建 `iap2/ArtworkTransfer.kt`（动态取 k1 会话 + SETUP/START/DATA/SUCCESS）；`airplay/VirtualIap2Link.kt` 0x5001 field 26；`carlife/CarLifeProtocol.kt` 媒体信息已含 albumArt | 无 UI | **待移植** | 真车 k1 会话号可能不是 12，必须动态取；失败回退不发 26；不阻塞实车测试包 |
| HUD（HUD 广播 → 导航） | `inject/src/tl/CarLifeHudReceiver.java`（`carlife_show_hud_info` / `mix_notify_start_navi`）、`tl/CarLifeNav.java onHud/onHudStop` | 新建 `media/CarLifeHudReceiver.kt`；汇入 `media/CarLifeNav.kt` | 无 UI（普通动态广播接收，不做宿主 Hook）；诊断日志 | **待移植** | 部分 ROM 收不到广播时不阻塞导航，走 AMap/通知兜底 |
| 语音键（车机 HID ↔ CarLife VR） | hybrid63 无对应实现；参考 CarPToCarL `carlife/VoiceKeyRouter.kt`（车机 HID/Consumer 键 → `HardKey.VR_START/STOP`） | 新建 `input/VoiceKeyRouter.kt`；接 `input/TouchRouter.kt` / iAP2 HID 事件；`carlife/CarLifeHuClient.kt` 已有 `sendHardKeyCode`，`CarLifeProtocol.HardKey.VR_START/STOP` 已就绪 | 无 UI（车机方向盘语音键） | **移植中** | 发送 API 已就绪；缺车机按键接收与派发 |
| GPS（车机 GPS → CarLife 0x18010） | hybrid63 无对应实现；参考 CarPToCarL `carlife/CarGpsOption.kt`、`iap2/NmeaLocation.kt`、SessionController GPS 段 | 已移植 `carlife/CarLifeProtocol.kt` 的 `carGps()` + `carlife/CarLifeHuClient.kt` 的 `sendCarGps()`；待建 `iap2/NmeaLocation.kt`、`carlife/CarGpsOption.kt` 并接线 | 待建：手机设置→连接与设备「车机 GPS 回填」开关（参考 CarPToCarL `CarGpsOption.enabled`） | **移植中** | 协议/发送 API 已就绪；缺数据源与开关（900ms 节流，NMEA 1Hz） |
| MAC 身份（蓝牙地址） | `inject/src/tl/MacIdentity.java`（`device_identity/bluetooth_address` 手填 + 缓存诊断） | `util/DeviceIdentity.kt`（自动解析 + 手填优先）；`launcher/settings/SettingsCategoryFragment.kt` 的 `showBluetoothMacDialog`；`MainActivity.kt` 未解析提示 | 手机设置→连接与设备→本机蓝牙地址（可选手填）；key `settings/bt_mac_override` | **已移植** | 用于 iAP2 传输标识与 ctrl 广播 TXT id；占位值时日志会提示 |
| 诊断日志导出 | `inject/src/tl/CarLifeDiagActivity.java`、`tl/CarLifeBridge.java`（diag）、`tl/CarLifeHijackActivity.java`（诊断入口） | `util/DebugFileLog.kt`、`util/LogExporter.kt`、`MainActivity.exportLogFile()`；CarLife 诊断快照 `session/SessionController.diagnosticSnapshot()`；现场工具 `scripts/p1a-field-check.ps1`、`scripts/verify-carlife-log.ps1` | 手机设置→帮助与诊断→导出日志文件 | **已移植** | 旧 `CarLifeDiagActivity` UI 不移植（改用原生导出 + 文件）；实车反馈附日志文件 |
| 内置壁纸 | `inject/src/tl/BuiltinWallpapers.java`（旧版 9 张 assets）、`tl/CarLifeWallpaperActivity.java`（选择页） | `ui/BuiltinWallpapers.kt`（迁移 hybrid63 9 张内置图）、`app/src/main/assets/wallpapers/*.jpg`；另有 `ui/WallpaperStore.kt`、`ui/WallpaperActivity.kt`、`ui/WallpaperCalibration.kt`；P1B `render/CarLifeComposer.kt` 使用 | 设置→外观与壁纸→壁纸；壁纸文件 `filesDir/wallpaper.jpg`；校准 key 组 `wallpaper_calibration`；key `carlife_wallpaper_enabled` | **待移植** | 壁纸系统已有；工作区已迁入内置图资产（§0.1），缺合成器接线与构建验证 |
| 设置页 | `inject/src/tl/CarLifeHijackActivity.java`（旧设置/诊断入口，Shizuku 移除后的适配逻辑）、`tl/CarLifeDiagActivity.java` | link_plus 原生设置页：`MainActivity.kt`（手机）、`launcher/settings/SettingsMenus.kt`/`SettingsFragments.kt`/`SettingsCategoryFragment.kt`（分类/面板）、`launcher/SettingsActivity.kt`（车机双栏）、`PhoneSettingsActivity.kt`；已收敛为六类页面（连接与设备/显示与画面/音频/CarLife/外观与壁纸/日志与关于），CarLife 开关 keys 见 `CarLifePrefs`（`carlife_*`） | 设置→六类页面（`SettingsMenus.VISIBLE`）；无内置 ADB 激活项 | **已移植（原生改编）** | 旧 Activation/诊断 Activity 不移植；删除 Shizuku/root 入口；GPS/HUD/音频等 CarLife 专属项随功能补齐（工作区已改，§0.1） |

### 状态汇总

| 状态 | 数量 | 功能 |
| --- | --- | --- |
| 已移植 | 4 | 视频直出、MAC 身份、诊断日志导出、设置页（原生改编） |
| 移植中 | 6 | 音频媒体、TTS、触摸、媒体 NowPlaying、语音键、GPS |
| 待移植 | 5 | 合成壁纸/安全区、导航 iAP2、封面 artwork、HUD、内置壁纸 |
| 不移植 | 0 | —（旧工程专属 UI 见备注，改用原生实现） |

> 注：“已移植”只表示代码已合入且可编译，实车是否通过以实车测试包结果为准（见 [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md) §10）。

---

## 2. CarLife 已有 → 去掉的重复功能

link_plus 自带的车机桌面/应用管理生态与“CarLife 唯一内容源”重复，也不再依赖已删除的内置 shell，属于 **APK 瘦身与降复杂度** 的删除目标。当前代码仍有残留（UI/设置入口），按下列清单逐步删除。

> 工作区进展（未提交）：`SettingsMenus.kt` 已收敛为六类页面，桌面应用/超采样/横屏/侧栏/地图分屏/官方车载应用/仪表盘组件不再出现在菜单；对应旧面板 ID 仅作遗留死代码引用。彻底删除代码/资源仍在进行。

| 功能 | link_plus 现有实现 | 处置 | 说明 |
| --- | --- | --- | --- |
| 桌面应用管理 | `launcher/DesktopApps.kt`、`launcher/VirtualLauncher.kt`、`launcher/settings/SettingsFragments.kt`（DesktopPanelFragment）、`launcher/settings/SettingsRows.kt` 相关 | 移除 | 不再有虚拟屏桌面/应用抽屉，画面只来自 CarLife |
| 官方车载应用 | `launcher/OfficialCarApps.kt`、`launcher/OfficialCarAppDiscovery.kt`、`launcher/PhoneCarPages.kt`、`OfficialCarAppsPanelFragment.kt` | 移除 | 不随包分发第三方 APK；CarLife 画面内自带应用入口 |
| 仪表盘/官方音乐组件 | `launcher/DashboardWidgets.kt`、`launcher/DashboardWidgetView.kt`、`launcher/DashboardWidgetBindActivity.kt`、`DashboardWidgetPanelFragment.kt` | 移除 | CarLife 已有自己的媒体界面 |
| 超采样 | `launcher/SupersamplePrefs.kt`、`SupersamplePanelFragment.kt`、`render/DualDisplayComposer.kt` 超采样路径 | 移除 | CarLife 码流尺寸由引擎决定，无虚拟屏超采样概念 |
| 应用横屏 | `launcher/OrientationReadback.kt`、`launcher/settings/AppOrientationCompat.kt`、`launcher/settings/OrientationCapability.kt`、`OrientationPanelFragment.kt` | 移除 | 不再启动手机应用；横屏/竖屏由 CarLife 自身决定 |
| 侧栏 Dock | `launcher/DockPrefs.kt`、`launcher/DockActivity.kt`、`launcher/DockDisplayState.kt`、`settings/DockPanelFragment.kt` | 移除 | CarLife 画面不拼接 link_plus 侧栏 |
| 地图分屏 / 手机地图交接 | `launcher/DefaultMapPrefs.kt`、`launcher/MapContentScale.kt`、`MapHandoffState.kt`、`MapLaunchResolver.kt`、`MapStatusActivity.kt`、`DashSplitHandle.kt`、`settings/DefaultMapPanelFragment.kt`、`render/Map*.kt` | 移除 | 导航画面由 CarLife 提供；iAP2 导航上行另见 §1 |
| 歌词 | `media/lyrics/*`（13 个类 + `PlayerLyricsNotification.kt`）、`render/MusicIsland.kt` 歌词段、`render/LeftWidgetColumn.kt` 歌词段 | 移除 | 官方 iAP2 0x5001 无歌词字段；车机歌词不在目标内 |
| 天气 | `media/WeatherRefreshState.kt`、`WeatherState.kt`、`CarAppearancePrefs.kt` 的 `weatherCity`、`render/LeftWidgetColumn.kt` 天气段 | 移除 | 与 CarLife 内容无关 |
| 手机信号 | `render/LeftWidgetColumn.kt` 信号段、`AndroidManifest.xml` 的 `READ_PHONE_STATE` 权限 | 移除 | 减权限、瘦身 |
| 车机外观定制（透明度/模糊/轮廓/浮层/灵动岛） | `ui/CarAppearancePrefs.kt`、`CarAppearancePanelFragment.kt`、`render/FloatingPanelLayout.kt`、`render/OverlayCard.kt`、`render/DualDisplayComposer.kt` 外观段 | 移除或降级 | 只保留 P1B 需要的壁纸/安全区/圆角；手机端 UI 外观（GlassActivity/AppearanceBindings）保留 |
| 应用图标缓存/桌面图标 | `launcher/AppIcons.kt`、`launcher/AppLaunchRecovery.kt`、`launcher/DesktopApps.kt` | 移除 | 随桌面应用管理一起删 |

### 已随“无内置 ADB”完成的瘦身

- 删除 `shizuku/` 包（13 文件）、`aidl/shizuku`（3 文件）、相关单测/仪器测试；
- 删除 `libadb-android` / SPAKE2 依赖与 `third-party/libadb-android-3.1.1`、`third-party/spake2-java-2.2.1` 源码；
- 删除 `capture/HiddenDisplaySource.kt`、激活界面的 layout/字符串/资源；
- 新增 `compat/` 占位包（`CarInputClient`/`Shell`/`ShellServiceGate`）：仅让遗留调用点编译通过、不执行任何 ADB/shell 操作；**待调用点清理后整体删除**。

---

## 3. 瘦身与清理清单（APK 体积/复杂度）

1. 按 §2 删除重复功能代码、布局、字符串、drawable、assets 与对应测试；
2. 清理 `compat/` 占位包与仅剩占位调用的代码路径；
3. 复核权限：删除 `READ_PHONE_STATE` 等不再使用项；音频切换到 CarLife PCM 后再评估 `RECORD_AUDIO`/`FOREGROUND_SERVICE_MEDIA_PROJECTION` 是否保留（MediaProjection 作为可选回退时保留）；
4. 清理未使用的 strings/drawable/layout（lint `UnusedResources` 辅助）；
5. 清理旧构建产物与 `field-logs/` 等本机文件（不入库，`.gitignore` 已覆盖）；
6. 每轮瘦身记录 APK 大小与版本号，对比 `app/build/outputs/apk/buick/app-buick.apk`。

---

## 4. 实车验证口径

- 不再逐阶段自测/逐阶段验收：功能完成标准 = **逻辑正确 + `:app:assembleBuick` 可编译打包**；
- 全部功能就绪后出**实车测试包**（buick APK + 版本号 + SHA-256），按 [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md) §10 清单一次性验证；
- 实车结果（通过/失败/日志）记录到 [P0-baseline.md](P0-baseline.md) 的待回填表，或随测试包反馈的日志文件/GitHub issue；历史 P1A 验证记录见 [P1A-carlife-video.md](P1A-carlife-video.md) 与 [audit/P1A_AUDIT_ROUND3.md](audit/P1A_AUDIT_ROUND3.md)（仅证据，不作为当前口径的阶段门槛）。

---

## 5. 数据来源（本矩阵的核对依据）

- 本仓库 `carlife-port` 快照 `cb5ddc3` 源码与 `git log`（`25a350a` 移植、`59fe8c6` 去 ADB、`cb5ddc3` 审计修复）；
- `docs/P1A-carlife-video.md`、`docs/audit/P1A_AUDIT_ROUND3.md`；
- hybrid63 `inject/src` 与 `base-decoded/` 只读源码；
- `reference/CarPToCarL` 快照 `7b729fb`（MIT）。
