# 原版 link_plus 功能审计与恢复清单（别克车机 + CarLife 内容源环境）

> 基线 `51fb394`（上游 link_plus）→ 当前 HEAD `906002b`；审计时间 2026-11-07 00:45。
> 使用环境：别克 E2LB 车机（1972×520，safeArea 1408×476@278,44）、CarLife+ 为唯一内容源、
> 无 root/ADB/Shizuku、不需要 link_plus 虚拟桌面/车机桌面/地图分屏。
> 本审计只读，未改生产代码。工作区仍有并行改动（UI/画面/会话），以下按 HEAD 结论、必要时注明工作区状态。

## 0. 判定口径

- **有用**：手机端通用功能（连接/显示/音频/壁纸/权限/日志/MAC/画面边距/常用入口/手机信号/手机天气）＋ CarLife 画面/音频/TTS/触摸/导航/HUD/GPS/语音键 ＋ CarPlay 发送链路。
- **无用（不恢复）**：依赖 link_plus 虚拟桌面、ADB/shell、车机桌面渲染的功能（桌面应用管理/Dock/超采样/横屏/官方车载应用/仪表盘组件/音乐岛/左列只读小组件/车机外观视觉项）。
- **删除（用户已确认）**：地图与交接（CarLife 自带导航）；仪表盘音乐组件（CarLife 模式无虚拟桌面音乐卡）。

## 1. 按环境重分类（原版被删/被隐藏功能）

| 原版英文/代码名 | 中文原版名 | 原版作用 | 本环境有用？ | 结论/现状 |
|---|---|---|---|---|
| Desktop app management (`DESKTOP`) | 管理桌面应用 | link_plus 桌面应用勾选 | 否（无桌面） | 已隐藏/不恢复；代码与面板残留 |
| Official car apps (`OFFICIAL_CAR_APPS`) | 车载应用与手机版 | 车机原生应用/RemoteViews 入口 | 否（无桌面） | 已隐藏/不恢复 |
| Dashboard widget (`DASHBOARD_WIDGET`) | 仪表盘音乐组件 | 车机右侧虚拟屏音乐卡 | 否（无桌面，用户确认） | **删除**；入口已移除，`DashboardWidget*` 类残留 |
| Supersample (`SUPERSAMPLE`) | 应用超采样 | 虚拟屏渲染清晰度 | 否（无虚拟屏） | 已隐藏/不恢复 |
| App orientation (`ORIENTATION`) | 应用横屏 | `am compat` 系统属性覆盖 | 否（无 ADB/无虚拟屏） | 已隐藏/不恢复 |
| Dock size (`DOCK`) | 侧栏大小 | link_plus Dock 缩放 | 否（无 Dock） | 已隐藏/不恢复 |
| Map & handoff (`DEFAULT_MAP`) | 地图与交接 | 地图分屏/手机交接 | 否（CarLife 自带导航，用户确认） | **删除**；入口已移除，`DefaultMapPanelFragment` 类残留 |
| Car appearance visuals (`CAR_APPEARANCE`) | 车机外观：透明磨砂/背景模糊/面板轮廓/音乐岛 | link_plus 桌面视觉 | 否（无桌面面板） | 视觉项已移除；页面仅剩“左列小组件+天气城市”（见 §3） |
| Left read-only widgets (`LeftWidgetColumn`) | 左侧只读小组件（时钟/日历/停车计时/快捷应用/天气） | link_plus 桌面左列 | 否（CarLife 全屏无左列渲染） | 不必恢复；配置入口应移除 |
| Weather (car left column) | 车机左列天气 | 桌面左列天气组件 | 否（无渲染路径） | 不必恢复；手机天气另议 |
| Phone signal/network (`view_phone_cellular`) | 手机信号/网络/移动数据 | 手机首页连接卡状态 | **是** | 已恢复（网络/移动数据状态 + “优先使用手机流量”开关） |
| Weather (phone home) | 手机首页天气 | 手机首页出行概览 | **是** | 显示已恢复，**刷新需适配**（§3） |
| Wallpaper (`WallpaperActivity`/`WallpaperStore`) | 壁纸：自选/内置/校准 | 手机端壁纸，CarLife 合成器背景 | **是** | 已恢复（10 张内置 + 自选/校准）；内置图来源需合规确认（§3） |
| Permissions & background (`PERMISSIONS`) | 权限与后台 | 蓝牙/通知/定位/省电/常亮 | **是** | 已恢复 |
| Audio strategy (`AudioPreference`) | 音乐与声音：自动/音质/兼容 | CarPlay 音频通道 | **是** | 已恢复（CarLife 外部 PCM 默认；兼容档走原版采集） |
| Display (`DISPLAY`) | 显示与画面：缩放/帧率/码率/画面边距 | 编码 profile 与安全区留边 | **是** | 已恢复 |
| Log export (`LogExporter`/`LogBundle`) | 日志导出/复制/清空 | 诊断 | **是** | 已恢复 + ZIP 分享 |
| Bluetooth MAC (`DeviceIdentity` + 手填) | 本机蓝牙地址 | 连接身份 | **是** | 已恢复（自动+手填入口） |
| Home quick entries (`home_shortcuts`) | 首页常用入口 | 手机首页快捷 | **是** | 已恢复（适配：APPS/MAP 换成 CarLife/连接/显示/关于） |
| Notification access (`NotificationAccess`) | 通知使用权 | 媒体会话枚举 | **是** | 已恢复（仅手动授权；silent grant 属 ADB 类不回） |
| Sleep policy / compat dim (`ScreenDimmer`) | 息屏与后台：常亮/兼容暗屏/立即暗屏 | 手机屏幕策略 | **是** | 已恢复 |
| Media mute/volume (`MediaMuteController`) | 媒体静音/音量恢复 | 车机媒体键 | **是** | 已恢复 |
| Target car/Wi-Fi auto join | 目标车机与网络 | 连接 | **是** | 原版逻辑保留（仅日志措辞变化） |
| Disclaimer/version/licenses | 免责/版本/许可 | — | **是** | 已恢复 |

## 2. 原版有价值但“漏恢复/需适配”逐项审计

| 项 | 结论 | 依据 | 建议 |
|---|---|---|---|
| 显示（分辨率/帧率/码率/画面边距） | **已恢复** | `DisplayPanelFragment`、`DisplayPrefs.safeMargins`；`SessionController` 用 `DisplayGeometry.create(..., margins)`，`CarLifeComposer` 按 content 窗格渲染 | 保持；CarLife 编码尺寸仍由车机 SETUP/引擎决定，缩放/帧率/码率作用于 CarPlay 编码 |
| 音频（策略/兼容采集/权限） | **已恢复** | `AudioPreference`、`AudioSender` 原版采集路径、`AudioCaptureSupport`、`ConnectionPreflight` 仅 COMPATIBILITY 要求录音+投屏 | 实车验证兼容档；默认 CarLife 外部 PCM 无需授权 |
| 壁纸（含内置） | **已恢复（有合规疑点）** | `WallpaperActivity/Store` 恢复；`MainActivity` 内置壁纸网格；`CarLifeWallpaperSource` 读原版壁纸 | 内置 10 张资产非基线资产，`BuiltinWallpapers` KDoc 称“link_plus 原版自带”不准确；与“不提供旧闭源资源”硬约束可能冲突，需确认来源/版权，必要时改代码生成或只留用户自选 |
| 权限与后台 | **已恢复** | `SettingsMenus.PERMISSIONS` + `PhonePermissionsView`（蓝牙/定位/通知/悬浮窗/省电/厂商后台） | 保持；内置服务项永不检查（`ConnectionPreflight` 注释） |
| 日志导出 | **已恢复** | `LogExporter` 原 API/入口 + `LogBundle` ZIP（保存/分享） | 保持 |
| MAC | **已恢复** | `DeviceIdentity` 自动 + `bt_mac_override` 手填（首页/设置→连接与设备入口） | 保持；首页未解析软提示 |
| 画面边距 | **已恢复** | 4×SeekBar 左/上/右/下（0–120px，下次连接），CarLife 合成器消费 | 保持；实车校准 |
| 常用入口 | **已恢复（适配后）** | 首页 6 快捷（连接/显示/音频/CarLife/外观/关于）+ 常用行（CarLife/MAC/目标/基础环境） | 保持 |
| 手机信号/网络/移动数据 | **已恢复** | `view_phone_cellular`、`phoneNetworkValue`/`phoneCellularValue`、`switchCellular` | 保持 |
| 天气（手机首页） | **应恢复（适配）** | 显示已恢复，但 `WeatherState.ensureFresh` 只在 `LeftWidgetColumn`（车机左列渲染）调用；CarLife 模式无左列渲染 → 天气不再自动刷新，长期“暂无数据/30 分钟刷新失效” | 在手机首页初始化/前台定时调用 `WeatherState.ensureFresh(context, CarAppearancePrefs.weatherCity(context), elapsed)`；涉及 `phone/PhoneHomeDashboard.kt`、`media/WeatherState.kt`；入口：首页“天气”行 |
| 左侧只读小组件 | **不必恢复** | 仅 `DualDisplayComposer` 渲染；CarLife 用 `CarLifeComposer` 不渲染左列；`CarAppearancePanelFragment` 的开关无效果 | 移除该页 widgetSwitches（保留“天气位置”供手机天气兜底）；`launcher/settings/CarAppearancePanelFragment.kt`、`SettingsMenus.CAR_APPEARANCE` |
| 车机天气/车机外观视觉项 | **不必恢复** | 同上，无渲染路径 | 不恢复 |
| 地图与交接、仪表盘组件 | **删除（已确认）** | CarLife 自带导航/无桌面音乐卡 | 保持删除；清理 `SettingsMenus` 死引用 |
| 设置死面板 | **需清理** | `SettingsMenus.createFragment/itemOf/directPhonePanel` 仍引用 `DASHBOARD_WIDGET`/`DEFAULT_MAP` 等；`PANELS` 已不含其中部分 → `itemOf()` 可能抛异常 | 删除常量/工厂分支或统一返回隐藏页：`launcher/settings/SettingsMenus.kt` |
| 自动接管/重试 | **未改坏**（详见 §3） | 会话层函数集合无删除；CarLife 接管为源层重试 | 保持；按 §3 建议验证 |

## 3. 自动接管/重试审计结论

- **原版会话/网络重试未被改坏**：`SessionController` 相对基线函数 54→55，唯一新增 `sourceDiagnostics`；Wi-Fi 自动加入/重试/取消分支代码保持一致，仅日志措辞随“无内置服务”调整；`ConnectionPreflight` 保留，仅内置服务项永不检查、音频权限仅 COMPATIBILITY 档检查（不阻塞默认路径）。
- **CarLife 接管是新增源层重试**（`capture/CarLifeScreenSource.kt`）：`manage()` 每 `RETRY_DELAY_MS=2000` 创建客户端；失败限流日志（10s 一次）；握手成功后 `STATUS_POLL_MS=500` 轮询；5s 无帧提示；持续收帧但无解码输出时重发 98311+重建解码器；断开后 `client.stop()`→触摸/语音解绑→`CarLifeNowPlaying.onCarLifeDisconnected`→解码器/管线清理→音频 reset→2s 重连；**不触发** `SessionController.retireSession`。
- **与参考实现语义等价**（只读历史实现 worker：约 1.5s 轮询 7240 → 握手 → 断开重试），未发现“改坏”。
- 残余风险/建议（carlife-core 正在修，供参考）：
  1. 半开端口：7240 可 accept 但不应答时 `client.start()` 最坏等 15s，接管延迟可达约 17s；建议首轮握手快速失败（3–5s）。
  2. 等待期车机只有壁纸/保活画面；建议首页状态行明确“等待 CarLife+ 无线连接”（CarLife 页已有）。
  3. 无限重试为设计（用户可随时开 CarLife+）；`detach`/会话结束能停（active/isCurrent 检查 + daemon 清理），无需改。
  4. 重连后状态重绑已覆盖（touch/voice sender 重绑、NowPlaying 断开、解码器/管线重置）；建议补“引擎晚于会话出现”“引擎重启”两条时序测试。
  5. 源层重试不触发 AirPlay 重连是期望行为（自动接管），不要改成 retireSession。

## 4. 需要补的恢复项（文件/入口级）

1. **手机天气自动刷新**（唯一确定的漏恢复）：`phone/PhoneHomeDashboard.kt` 在 init/前台定时调用 `WeatherState.ensureFresh(context, CarAppearancePrefs.weatherCity(context), SystemClock.elapsedRealtime())`；`media/WeatherState.kt` 无需改；入口：首页“天气”行。
2. **车机外观页精简**：`launcher/settings/CarAppearancePanelFragment.kt` 删除 `widgetSwitches`/左列小组件开关，仅保留“天气位置”；同步 `SettingsMenus.CAR_APPEARANCE` 文案（“天气位置”）。
3. **设置死面板清理**：`launcher/settings/SettingsMenus.kt` 删除未使用常量/工厂分支（DESKTOP/OFFICIAL_CAR_APPS/DASHBOARD_WIDGET/SUPERSAMPLE/ORIENTATION/DOCK/DEFAULT_MAP）或改为隐藏页，避免 `itemOf()` 抛异常。
4. （可选）首页等待 CarLife 状态提示：`phone/PhoneHomeDashboard.kt` 在未连引擎时显示“请打开 CarLife+ 无线连接”，避免用户误判黑屏。
5. **合规确认**：内置壁纸 10 张资产来源/版权与 `BuiltinWallpapers` KDoc 描述核对（非基线资产，可能违反“不提供旧闭源资源”约束）；如需恢复“原版观感”，优先代码生成或纯色/用户自选。

## 5. 明确不恢复清单

- 桌面应用管理、官方车载应用、仪表盘音乐组件、应用超采样、应用横屏、侧栏大小、地图与交接、车机外观视觉项（透明磨砂/背景模糊/面板轮廓/音乐岛）、左侧只读小组件、车机左列天气。
- 内置无线 ADB/内置服务/触控输入服务相关（激活页、shizuku 类、SERVICE 检查、隐藏虚拟屏、shell 输入自愈、shell 读身份、shell 通知静默授权/重绑、shell 输入诊断）。
- 理由：无 root/ADB/Shizuku；无 link_plus 虚拟桌面/车机桌面；地图与交接、仪表盘组件用户已确认删除。

## 6. 验证

```powershell
cd F:\carplay\_newproj\linkplus_carlife
$env:JAVA_HOME='F:\carplay\tools\jdk17\jdk-17.0.20.1'
.\gradlew.bat :app:compileBuickKotlin --console=plain
.\gradlew.bat :app:testBuickUnitTest --console=plain
```

实车清单：连接/断开与“引擎后开”自动接管；画面边距；声音三策略；壁纸（内置/自选/校准）；权限与后台；日志 ZIP；MAC；首页各卡（含天气刷新）；确认桌面/Dock/地图/超采样/仪表盘入口不可见。

## 7. 证据

```text
git diff --stat 51fb394..HEAD
git diff --name-status -M 51fb394..HEAD -- app/src
git ls-tree -r --name-only 51fb394 -- app/src/main/java
git diff 51fb394 -- <path>
```

关键文件：`MainActivity.kt`、`phone/PhoneHomeDashboard.kt`、`launcher/settings/SettingsMenus.kt`、`launcher/settings/SettingsFragments.kt`、`launcher/settings/CarAppearancePanelFragment.kt`、`audio/AudioPreference.kt`、`ui/WallpaperStore.kt`、`util/LogExporter.kt`、`input/TouchRouter.kt`、`session/SessionController.kt`、`capture/CarLifeScreenSource.kt`。
