# 原版 link_plus 功能审计与恢复清单

> 审计对象：基线 `51fb394`（上游 link_plus 原版）→ 当前 `80030ca` + 工作区改动。
> 审计时间：2026-11-07 00:00（Asia/Shanghai）。工作区正被 activation-ui 等并行任务改动，**本文只是该时刻快照**。
> 本审计只读，未改动任何生产代码；结论用于下一步恢复决策。

## 0. 分类口径

- **A 恢复**：原版 link_plus 已有、后来被 CarLife 实现顶替或被删除的通用功能 → 以原版实现为主恢复，CarLife 只做扩展点。
- **B 保留**：原版没有、CarLife 独有或作为增量接入的功能 → 保留。
- **C 不恢复**：依赖已移除的内置无线 ADB/shell 服务，或用户此前明确要求删除的 CarLife 重复功能 → 保留删除，不重建。

## 1. 已恢复（工作区快照中已回到原版形态，来源为并行恢复改动）

| 原版英文/代码名 | 中文原版名 | 影响文件 | 状态/证据 |
|---|---|---|---|
| `DisplayPrefs.safeMargins` + 4×SeekBar (`ui_margin_side`/`ui_margin_value`) | 画面边距：左/上/右/下四滑块（0–120px，下次连接生效） | `launcher/settings/DisplayPrefs.kt`、`SettingsFragments.kt`、`res/values/interface_strings.xml` | 已恢复；`DisplayGeometry.create(...margins)` 消费；activation-ui 完成 |
| home sections (`home_prepare`/`home_session`/`home_shortcuts`/`home_travel`) | 首页：连接准备 / 本次连接 / 常用入口 / 出行概览 | `MainActivity.kt`、`res/layout/activity_main.xml`、`res/values/strings.xml` | 已恢复（原版布局，仅去掉内置服务卡） |
| audio status card (`audioTag`/`audioDesc`/`btnAudio`, `phone_home_audio_*`) | 首页声音状态卡（关闭/等待/发送中） | `MainActivity.kt`、`res/layout/activity_main.xml`、`res/values/phone_audio_strings.xml` | 已恢复 |
| `SettingsMenus.PERMISSIONS` + `openPermissionSettings`/`requestQuickPermissions`/`handlePermissionShortcut`/`promptOverlayPermission`/`refreshSleepPolicy`/`showBackgroundPermissionGuide` | 权限与后台：首次授权、通知/蓝牙/定位、省电与常亮指引 | `MainActivity.kt`、`ui/PhonePermissionsView.kt`、`res/values/phone_permissions_strings.xml` | 已恢复；内置服务项不再阻塞连接（`ConnectionPreflight`） |
| `AudioPreference` AUTO/QUALITY/COMPATIBILITY + `AudioSender` 原版采集/发送路径 + `AudioCaptureSupport` | 音乐与声音：自动 / 音质优先 / 兼容；兼容走原版 MediaProjection 播放采集 | `audio/AudioPreference.kt`、`audio/AudioSender.kt`、`audio/AudioCaptureSource.kt`、`audio/AudioCaptureSupport.kt`、`session/ConnectionPreflight.kt` | 已恢复；CarLife 外部 PCM 作为默认路径叠加，兼容模式才要求录音+投屏授权 |
| `WallpaperActivity`/`WallpaperStore`（与基线逐字节一致）+ `CarLifeWallpaperSource` 读取原版壁纸 | 壁纸：自选图片、校准、切片 | `ui/WallpaperActivity.kt`、`ui/WallpaperStore.kt`、`render/CarLifeWallpaperSource.kt` | 已恢复；生成壁纸已下线，CarLife 合成器复用原版壁纸 |
| `SettingsMenus` 原版六分类 + 设置搜索（`settings_search_hint`） | 设置：连接与设备 / 画面与外观 / 音乐与声音 / 帮助与诊断 + 搜索 | `launcher/settings/SettingsMenus.kt`、`SettingsCategoryFragment.kt`、`view_phone_settings.xml` | 已恢复；当前 `VISIBLE` 为 CarLife/连接/显示/音频/外观/关于 |
| `CarAppearancePanelFragment` + `CarAppearancePrefs` | 车机外观：背景透明度、模糊、面板轮廓 | `launcher/settings/CarAppearancePanelFragment.kt`、`ui/CarAppearancePrefs.kt` | 已恢复 UI；CarLife 内容源下仅部分生效（见 §5） |
| `LogExporter` 原版导出/复制/清空（`Scope`、`Result`、MediaStore 下载） + `LogBundle` | 帮助与诊断：导出日志、复制、清空 | `util/LogExporter.kt`、`util/LogBundle.kt`、`MainActivity.kt` | 原版入口与 API 保留；ZIP 整包为新增能力 |
| `DeviceIdentity` 自动读取 + `bt_mac_override` 手填入口 | 本机蓝牙地址（连接身份）：自动解析 + 手动覆盖 | `util/DeviceIdentity.kt`、`MainActivity.kt`、`SettingsCategoryFragment.kt` | 自动解析为原版；手填入口为新增恢复通道，首页未解析时软提示 |
| `MainActivity` 原版功能入口（`openSettings`/`requestAudioProjection`/`showSleepGuidance` 等） | 首页/设置的连接、声音、常亮、日志入口 | `MainActivity.kt` | 已恢复（除下方 C 类内置服务相关） |

## 2. 仍待恢复 / 需补齐

| 项 | 现状 | 建议 |
|---|---|---|
| CarLife 增量设置入口（TTS / 导航 / GPS / 壁纸开关 / 圆角半径） | `CarLifePrefs.setTtsEnabled/setNavEnabled/setWallpaperEnabled/setCornerRadius` 无任何 UI 调用点；`buildCarLifePage` 只有引擎状态/重连/诊断 | 在现有 **CarLife 页**追加设置区（additive，不占用原版分类），复用 `CarLifePrefs` 键 |
| 兼容模式授权提示 | `AudioPreference.COMPATIBILITY` 才要求 RECORD_AUDIO + 投屏，但首页/连接卡文案仍按“声音授权”笼统描述 | 连接 sheet 按策略分支显示；CarLife 默认路径不提示授权 |
| CarLife 引擎/端口状态在首页可见性 | 引擎检测在 CarLife 页（`buildCarLifePage`），首页只有通用状态 | 首页“连接准备”可选加一行引擎提示（低优先，避免堆 UI） |
| 设置搜索关键词覆盖 | `SettingsMenus.SEARCH` 仍引用 PANELS（含已下线面板）与 CarLife 页 | 清理已下线面板的搜索项；补 CarLife/TTS/导航/GPS/画面边距关键词 |

## 3. 不恢复（保留删除）

| 原版英文/代码名 | 中文原版名 | 影响文件 | 不恢复理由 |
|---|---|---|---|
| `EmbeddedAdb`/`ServiceActivationActivity`/`activity_service_activation.xml`/`activation_*` | 内置无线 ADB 激活与内置服务状态页 | `shizuku/*`（已删）、res 字符串/布局 | 用户明确要求移除内置 ADB/激活 |
| `SHELL_SERVICE`/`INPUT_SERVICE` 检查项与 `rowShizuku`/`shizukuChip`/`advCard` UI | 内置权限服务 / 触控输入服务 | `ConnectionPreflight`（枚举保留但永不检查）、`activity_main.xml`、`shizuku/*` | 依赖内置 shell，无 ADB 不可用 |
| `HiddenDisplaySource` 与虚拟屏 shell 准备（`parseCarPlayDisplays`/`prepareConnectionDisplays`/`releaseDisplays`） | 虚拟屏内容源/显示准备 | `capture/HiddenDisplaySource.kt`（已删）、`MainActivity` 相关函数 | CarLife 已取代内容源；且依赖 shell |
| `VirtualLauncher.startInputHealer`、`DeviceIdentity.resolveViaShell`、`LogExporter.collectInputDiagnostics` | 输入服务自愈 / shell 读身份 / 输入服务诊断 | 同名文件 | 依赖内置 shell；身份改手填覆盖，触摸改 CarLife 通道 |
| `NotificationAccess` 静默授权与 `rebindIfNotConnected`（`cmd notification allow_listener`/`dumpsys`） | 通知使用权静默授权/重绑 | `media/NotificationAccess.kt` | 依赖 shell；保留系统设置手动授权与监听器回调状态 |
| `AppOrientationCompat` 的 `am compat` 覆盖执行路径 | 应用横屏（系统属性覆盖） | `launcher/settings/AppOrientationCompat.kt`、`OrientationPanelFragment` | 恢复的 UI 仍依赖 shell；无 ADB 下只能查询/保存意愿，**建议隐藏入口或标注不可用** |
| PANELS：`DESKTOP`/`DOCK`/`SUPERSAMPLE`/`OFFICIAL_CAR_APPS`/`DASHBOARD_WIDGET`/`DEFAULT_MAP` | 桌面应用管理 / 侧栏大小 / 超采样 / 官方车载应用 / 仪表盘音乐组件 / 地图与交接 | `launcher/settings/SettingsMenus.kt`、`SettingsFragments.kt`、各 Panel 文件 | 用户此前明确要求去掉 CarLife 重复功能；且依赖虚拟屏/shell，无 ADB 下不生效 |

## 4. 非 UI 代码审计：原版是否被 CarLife 实现顶替

| 文件 | 差异结论 | 建议 |
|---|---|---|
| `input/TouchRouter.kt` | 原版 `route()`/手势/注入路径保留；CarLife `carLifeSink` 在入口前置、未接入时走原路径 | 无需恢复；现有“原版为主 + CarLife 扩展点”模式正确 |
| `session/SessionController.kt` | 基线函数集合全部保留，仅新增 `sourceDiagnostics`；原音频/视频/采集链路保留，CarLife 作为可选 ScreenSource | 无需恢复 |
| `audio/AudioSender.kt` + `AudioCaptureSource/AudioCaptureSupport` | 原版采集/发送/编码路径恢复；外部 PCM 为新增入口，兼容模式仍走原版 MediaProjection | 保持；实车验证兼容档 |
| `iap2/Iap2SenderSession.kt`、`airplay/VirtualIap2Link.kt` | 基线函数无删除，仅新增导航 0x5200–0x5203、CarLife NowPlaying 接口 | 保持（增量） |
| `capture/ScreenSource.kt` + `capture/CarLifeScreenSource.kt` | 原版接口未改，CarLife 是新增实现；原内容源被删除属 C 类 | 保持 |
| `media/NotificationAccess.kt` | shell 静默授权/重绑移除，保留手动授权与状态查询 | 不恢复 shell 部分 |
| `util/DeviceIdentity.kt` | 原自动解析保留，新增手填覆盖；`resolveViaShell` 移除 | 保持 |
| `util/LogExporter.kt` | 原 API/UI 保留，新增 ZIP 整包；`collectInputDiagnostics` 移除 | 保持 |
| `launcher/settings/AppOrientationCompat.kt` | 原实现保留，仅无 ADB 时降级 | UI 入口需处理（见 §3） |

**结论**：未发现“非 UI 原版功能被 CarLife 算法/类整体顶替且必须回滚”的代码块；真正丢失项都是 ADB/shell 依赖（C 类）或用户要求删除的重复页面。当前主要风险是 UI 入口与 CarLife 增量缺位。

## 5. 与用户要求/现状的冲突点（需裁决）

1. 用户要求“以 link_plus 原版为主 + CarLife 只补原版没有的”，但 `SettingsMenus` 仍保留 9 个原版 PANELS 并参与搜索（桌面/Dock/超采样/横屏/地图/官方车载/仪表盘等）——指导航已隐藏，搜索/深层入口仍需下线或标注。
2. “桌面/Dock/Grid/Map 不恢复”与工作区已恢复的面板代码并存：建议**保留代码、隐藏所有入口**，不要删除代码（避免与并行任务冲突）。
3. 应用横屏/超采样/桌面/地图/仪表盘依赖原虚拟屏与 shell；恢复 UI 但无 ADB 不可用，不能计入“已恢复可用功能”。
4. CarLife 增量开关（TTS/导航/GPS/壁纸/圆角）仍无 UI，需在 CarLife 页补，且不得占用原版分类。
5. 首页声音卡沿用原版“声音授权”文案；CarLife 默认外部 PCM 无需授权（仅兼容档需要），需按 `AudioPreference` 分支调整文案。
6. 部分恢复项来自 activation-ui 等并行任务（MainActivity/Settings/PhoneHomeDashboard/res），后续以他们的最终版本为准，避免重复/冲突修改。

## 6. 建议验证

```powershell
cd F:\carplay\_newproj\linkplus_carlife
$env:JAVA_HOME='F:\carplay\tools\jdk17\jdk-17.0.20.1'
.\gradlew.bat :app:compileBuickKotlin --console=plain
.\gradlew.bat :app:testBuickUnitTest --console=plain
```

实车清单：①连接/断开；②四边距滑块生效；③声音三策略（自动/音质/兼容）与授权提示；④壁纸选择/校准；⑤日志导出与分享；⑥权限与后台指引；⑦确认桌面/Dock/地图/超采样入口不可见。

## 7. 审计命令与证据

```text
git diff --stat 51fb394..HEAD
git diff --name-status -M 51fb394..HEAD -- app/src
git ls-tree -r --name-only 51fb394 -- app/src/main/java   # 基线类清单
git diff 51fb394 -- <path>                                 # 单文件对照
```

关键基线文件：`MainActivity.kt`(3037 行)、`launcher/settings/SettingsMenus.kt`、`audio/AudioPreference.kt`、`ui/WallpaperStore.kt`、`util/LogExporter.kt`、`input/TouchRouter.kt`、`session/SessionController.kt`。
