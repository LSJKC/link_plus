# 基于 link_plus 的 CarLife 适配：目标、实现与测试

> 工作副本：`F:\carplay\_newproj\linkplus_carlife`  
> 分支：`carlife-port`  
> 上游基线：[schemawisp/link_plus](https://github.com/schemawisp/link_plus) `51fb394`（Buick Link+，MIT，基于 cardeck）  
> 当前版本：`0.0.9-carlife.1`（versionCode 158）  
> 本文是目标、架构、实现要点、历史阶段与实车测试口径；功能与界面清单见 [PORT_MATRIX.md](PORT_MATRIX.md)，连接原理见 [carlife-mechanism.md](carlife-mechanism.md)。

---

## 0. 一句话目标

**本项目基于 [schemawisp/link_plus](https://github.com/schemawisp/link_plus)（Buick Link+，MIT）开发**：保留 link_plus 的蓝牙 iAP2 / AirPlay / VideoSender / AudioSender / 会话管理，在内容源位置增加 **CarLife**——手机 CarLife+ 以「无线连接」模式在本机监听 TCP，本应用作为车机端接入视频 H.264、音频与触摸，再经 link_plus 的 CarPlay 发送链路送到车机。已去掉内置无线 ADB 激活，瘦身 APK，提供实车测试包，并建立与上游的 GitHub 下游同步。

无需 root / ADB / Shizuku，细节见 [no-adb-model.md](no-adb-model.md)。

## 1. 为什么基于 link_plus 开发

- 完整可编译源码（Kotlin/Java、Gradle），蓝牙 iAP2、AirPlay、H.264 编码发送、RTP 音频、触摸分发、会话管理都是可改代码；
- 已有 `VideoSender` / `AudioSender` / `ScreenSource` / `SessionController` 等稳定接口，CarLife 作为新的内容源接入即可，不需要重新实现 CarPlay 发送端；
- 手机界面、设置、权限、日志导出、诊断与配对流程可复用，再按 CarLife 需求裁剪；
- MIT 许可，长期可维护，可与上游同步。

CarLife 协议/客户端/视频/音频部分使用了 [CarPToCarL](https://github.com/jjuugk/CarPToCarL) 的 MIT 开源实现（commit `7b729fb`，Copyright (c) 2026 lizhihua），并按 link_plus 的链路改造；归属与逐文件说明见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)。

## 2. 产品范围与边界

### 2.1 已实现功能（待实车验证）

视频（CarLife H.264 → CarPlay 画面）、画面合成（壁纸背景/安全区 contain/圆角）、媒体音频、TTS 导航播报、车机触摸回传、导航指引上行（0x5201/0x5202）、媒体 NowPlaying、封面（测试版）、HUD 广播、车机语音键、车机 GPS 回填、本机蓝牙地址、诊断日志与端口自检、壁纸（程序生成背景 + 用户自定义）、设置页。

详细实现位置、UI 入口与状态见 [PORT_MATRIX.md](PORT_MATRIX.md)。

### 2.2 不计划再做

- 内置无线 ADB 激活 / Shizuku / root 能力；
- 车机麦克风接管；
- 有线 CarPlay；
- iAP2 歌词字段（官方 0x5001 无歌词字段）；
- 内置第三方壁纸图片（不随包分发第三方图片）；
- 封面 file-transfer 与 field 0x001A（需要真车 k1 会话证据，暂不接入）。

### 2.3 适配范围

- **车型：仅适配别克车型**（无线 CarPlay 车机），不做其它品牌兼容保证；
- **手机端 CarLife：先保证荣耀机型**（荣耀车联 / CarLife+ 无线连接）；其它 Android 手机的 CarLife+ 兼容性按反馈逐步验证；
- CarLife+ 需由用户在手机上手动进入「无线连接」状态。

## 3. 架构

```text
手机 CarLife+（无线连接模式，本机监听 TCP：7240 控制 / 8240 视频 / 9240 媒体 / 9241 TTS / 9340 触摸）
        │ 本机 TCP
        ▼
CarLifeHuClient（协议/握手/视频帧/音频 PCM/触摸/媒体信息）
        ├── CarLifeVideoPipeline → CarLifeVideoDecoder → CarLifeComposer（壁纸/安全区/圆角）
        ├── CarLifeAudioSink / CarLifeAudioPipe（媒体 + TTS）
        ├── CarLifeTouchSink（车机触摸 → 9340）
        ├── CarLifeNav / CarLifeNavAmap（导航 → iAP2 0x5201/0x5202）
        ├── CarLifeNowPlaying（0x10035 → 0x5001，测试版封面元数据）
        └── CarLifeGpsBridge / VoiceKeyRouter（GPS 0x18010 / 语音键）
        ▼
link_plus：SessionController → VideoSender（编码/发送） + AudioSender（RTP 音频）
        → 蓝牙 iAP2 / Wi-Fi / AirPlay → 车机
```

关键实现位置：`carlife/`（CarLife 协议与客户端、音频、导航、NowPlaying、GPS）、`capture/CarLifeScreenSource.kt`（内容源生命周期）、`render/CarLifeComposer.kt`（画面合成）、`input/`（触摸/语音键）、`session/SessionController.kt`（会话与接线）。连接原理与端口时序见 [carlife-mechanism.md](carlife-mechanism.md)。

## 4. 实现要点

1. **视频**：CarLife 码流由 `CarLifeVideoDecoder` 解码到 `VideoSender` 的编码器 Surface；真实编码尺寸以 SPS 为准，车机档位来自 AirPlay SETUP；
2. **画面合成**：`CarLifeComposer` 用 EGL/OES 做壁纸 cover、安全区 contain、圆角（shader 内 SDF）；壁纸来源为程序生成背景或用户导入图片；
3. **音频**：媒体 9240 / TTS 9241 分别 ring，重采样到 48k stereo 16bit、1408B/352 帧，TTS 独立混音/duck；音频统一由 `AudioSender` 送出；
4. **触摸**：车机触摸按最近一次合成 pane 映射到 CarLife 视频坐标，经 9340 控制帧回传；
5. **导航**：CarLife 0x10030、AMap LinkSDK、通知文本与 HUD 广播归一成 HUD 快照，收到车机 0x5200 订阅后由 `Iap2SenderSession` 发送 0x5201/0x5202（maneuverState 用 u64，空数据不发）；
6. **媒体 NowPlaying**：CarLife 0x10035/进度按车机 0x5000 订阅属性推送 iAP2 0x5001（album=0x0006，无歌词）；封面为测试版元数据，不发 field 0x001A；
7. **其他**：HUD 广播接入、车机语音键 → CarLife VR、手机定位 → 0x18010、本机蓝牙地址手填、诊断日志导出、设置页六类页面。

## 5. 协议要点与实车测试经验（历史参考）

以下为历史实车测试日志与参考实现中已确认的行为要点，供实现与排障参考（仅行为经验，不涉及任何第三方闭源应用资源）：

1. **98311/98386 时序**：先发 98386，等 65570 确认（最多 2s）再发首条 98311；同尺寸 5s 去重；首帧后尺寸不一致时 2.5s 后二次初始化；HU_INFO 报 requested 尺寸。
2. **视频尺寸**：实际流常见 1280×720；内容层可能 1452×476，不要直接拉伸；CSD 优先从原始帧 SPS/PPS 提取；不要轻易 reset pipeline。
3. **音频**：200ms 队列 / 80ms prime；必须连续喂 1408B，否则 underrun；TTS 9241 独立 ring，不能覆盖媒体 48k/2ch；44.1k 跨包保留相位；没有 PCM 时才补零。
4. **触摸**：画面 contain 后必须映射到实际 pane；安全区/边距会影响内容区；letterbox 区不要误触。
5. **导航**：0x5201 需收到 0x5200 订阅后发；maneuverState 是 u64；组件 0 重复 [1,2]；空数据不发，避免 -1 帧；CarLife 0x10030 只对 CarLife 内置百度导航有效，高德走 AMap LinkSDK 或通知。
6. **通知使用权**：`MediaListenerService` 必须 `exported=false` + BIND 权限；覆盖安装/force-stop 后需重新授权；日志出现 `MEDIA: 通知监听已连接` 才继续。
7. **封面**：真车 file-transfer 会话号是 k1 项，可能不是 12，不能硬编码；0x5001 的 album 字段是 6，26/0x001A 是 artworkTransferID；真车不收 START 时不要发 DATA。
8. **NowPlaying / HUD / GPS**：0x5001 无歌词字段；HUD 广播不保证所有 ROM 都发，收不到时走 AMap/通知兜底；GPS 回填 900ms 节流（NMEA ~1Hz），无 fix 不发；语音键按住 1500ms 视为 PTT。
9. **熄屏**：不改系统 timeout、不加遮罩；锁屏/Doze 是否断投屏需实车确认。

## 6. 阶段历史

本仓库按阶段推进并保留记录；当前功能已整体实现，实车验证以测试包统一进行（不再要求逐阶段验收）：

| 阶段 | 内容 | 结果 |
| --- | --- | --- |
| P0 基线 | 确认 JDK 17 / Android SDK 34 / Build Tools 35 / NDK 27 与基线构建 | 完成（`33bed66` 文档基线；构建通过） |
| P1A 视频直出 | CarLife H.264 解码到 `VideoSender` 编码器 Surface | 完成 |
| v0.2.0 全量功能 | 画面合成、音频/TTS、触摸、导航、NowPlaying、HUD/语音/GPS、设置页、APK 瘦身 | 已出测试包 |
| v0.2.1 | NowPlaying/封面测试、HUD/语音/GPS、协议修复 | 已出测试包 |
| v0.2.2 → 0.0.9-carlife | 设置入口补全、版本命名跟随上游、移除内置第三方壁纸图片 | 当前测试包版本 |

## 7. 权限模型

本项目不保留内置无线 ADB 激活：用户不需要开发者选项、无线调试、配对码或 Shizuku/root，只申请普通 Android 权限（蓝牙、附近 Wi-Fi、通知、录音、悬浮窗、前台服务等，按需申请）。完整说明见 [no-adb-model.md](no-adb-model.md)。

## 8. 实车测试清单（一次性，交付前执行）

测试包 = `app/build/outputs/apk/buick/app-buick.apk` + 版本号（`appVersionName/appVersionCode`）+ SHA-256。测试前记录：手机型号/ROM、车机型号/固件、CarLife+ 版本、应用版本、日志文件。

| 项目 | 通过标准 | 记录 |
| --- | --- | --- |
| 连接 | 蓝牙 iAP2 + Wi-Fi + AirPlay 完成，无内置 ADB/开发者选项依赖 | 车机/手机/ROM/日志 |
| 画面 | CarLife 画面完整，比例正确（合成后不变形/留壁纸），无绿屏/黑屏 | `CARLIFE: carlife connected`、`decoder out`、`fit` |
| 音频媒体 | 音乐连续，无持续刺啦；切歌/暂停正常 | `audio`/`AUDIO` 日志 |
| TTS | 导航播报可听，不覆盖媒体格式 | `TTS` 日志 |
| 触摸 | 点击/滑动/长按准确；壁纸/letterbox 区不误触 | 触摸日志 |
| 导航 | 0x5200 后 0x5201/0x5202；无空 -1 帧；HUD 行出现 | `nav`/`5201` 日志 |
| 媒体 NowPlaying | 车机媒体卡歌名/歌手/进度正确 | `0x5001`/`NowPlaying` 日志 |
| 封面 | 元数据/日志正常；file-transfer 为测试版，不要求车机出现封面 | `artwork` 日志 |
| 语音键 | 车机语音键可唤起 CarLife 语音识别 | `VR`/`voice` 日志 |
| GPS | 车机 GPS 回填后 CarLife 导航定位改善（按开关） | `车机 GPS 已下发` 日志 |
| 蓝牙地址 | 占位地址时提示；手填后连接正常 | `MAC:` 日志 |
| 壁纸 | 自定义/默认背景在合成画面中显示正确 | 截图/日志 |
| 熄屏 | 锁屏/Doze 后画面/音频/触摸表现记录 | 现象记录 |
| 重连 | 断开后 10–30s 内可重连 | 日志 |
| 稳定 | 30–60 分钟无解码泄漏/断流 | 日志/性能记录 |
| 性能 | CPU/发热可接受，帧率目标 30/60 | 记录 |
| 日志导出 | 应用内导出日志，含 carlife/airplay/audio/touch/nav | 日志文件 |

**日志反馈方式**：应用内「设置 → 日志与关于 → 导出日志」导出完整日志，连同手机/车机型号、版本号、复现步骤提交（GitHub issue 或约定渠道）。

## 9. 风险与回滚

### 风险

1. 不同别克车型/车机固件的 AirPlay 与 CarLife 端口行为差异；
2. 画面合成与解码 Surface 的尺寸/格式兼容问题；
3. 音频外部 PCM 与 link_plus 原音频链路的冲突/欠载；
4. 触摸坐标映射依赖合成 pane，直出/合成切换时可能偏移；
5. iAP2 导航（0x5201/0x5202）与 NowPlaying（0x5001）需要真车验证字段/时序；
6. HUD/语音/GPS 数据源受 ROM/权限限制；
7. 车机兼容/发热/断流；
8. 第三方许可（CarPToCarL MIT、Apache 等组件）发布合规；
9. 瘦身误删仍被引用的资源/代码导致构建或运行回归。

### 回滚

- 回滚以**整包/提交**为单位；功能单独提交，实车失败时 revert 对应提交或回退测试包；
- `carlife-port` 保持可构建；上游同步在大冲突时用 `sync/upstream-YYYYMMDD` 分支验证后再合回；
- 发布测试包前保留上一可用版本（GitHub Releases），便于对比回退。

## 10. 环境与构建

- JDK 17：`F:\carplay\tools\jdk17\jdk-17.0.20.1`；
- Android SDK：`F:\carplay\tools\android-sdk`（platforms `android-34`、build-tools `35.0.0`、NDK `27.0.12077973`）；
- Gradle wrapper 8.13、AGP 8.13.2、Kotlin 2.3.21；
- `local.properties` 配置 `sdk.dir`。

```powershell
cd F:\carplay\_newproj\linkplus_carlife
$env:JAVA_HOME='F:\carplay\tools\jdk17\jdk-17.0.20.1'
.\gradlew.bat :app:assembleBuick
```

产物：`app/build/outputs/apk/buick/app-buick.apk`（`.buick` 后缀，使用本机测试签名；不同签名不能覆盖安装）。CI 见 GitHub Actions workflow `build-buick-apk`。

## 11. 发布与上游同步

- 发布流程、tag/Release 命名、日志反馈方式见 [GITHUB_WORKFLOW.md](GITHUB_WORKFLOW.md)；
- 版本命名跟随上游：`X.Y.Z-carlife`（当前 `0.0.9-carlife.1`，versionCode 158）；
- 上游同步：`upstream` 只 fetch，`main` 镜像上游，`carlife-port` 开发；推送前必须构建通过。

## 12. 来源与许可

- 上游基础：[schemawisp/link_plus](https://github.com/schemawisp/link_plus)（Buick Link+，MIT，基于 [shaonialife/cardeck](https://github.com/shaonialife/cardeck)）；本仓库保留上游 MIT 许可与版权声明；
- CarLife 实现参考：[CarPToCarL](https://github.com/jjuugk/CarPToCarL)（MIT，commit `7b729fb`，Copyright (c) 2026 lizhihua），见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)；
- 第三方组件：见 [dependencies.md](dependencies.md)；
- **本项目不提供、不分发旧闭源应用资源**（APK、图片、日志等）；不内置第三方壁纸图片。
