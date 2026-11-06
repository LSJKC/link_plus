# link_plus × CarLife 重构目标与实施文档

> 工作副本：`F:\carplay\_newproj\linkplus_carlife`  
> 分支：`carlife-port`  
> 基线：[schemawisp/link_plus](https://github.com/schemawisp/link_plus) `51fb394`（MIT，基于 cardeck）  
> 旧插桩版：`F:\carplay\_newproj\hybrid63`（只作为行为/协议参考，不再往这边加 smali）  
> 本文是给“新窗口”的目标、边界、迁移映射、全面移植任务与实车验证口径（2026-11-06 口径变更见 §0.1）。

---

## 0. 一句话目标

**把 hybrid63 插桩已验证的 CarLife 功能全面移植为 link_plus 可编译源码（CarLife 唯一内容源 → 无线 CarPlay），去掉内置无线 ADB 激活，瘦身 APK，出实车测试包，并建立与上游 schemawisp/link_plus 的 GitHub 下游同步。**

首版以“功能完整、可维护、可实测”为主；不追求一比一复刻旧龟壳的壁纸/安全区外观（功能等价即可）。功能级进度见 [PORT_MATRIX.md](PORT_MATRIX.md)。

## 0.1 口径变更（2026-11-06，用户明确要求）

- **目标改为全面移植**：把 hybrid63 已验证功能（视频/合成壁纸安全区/音频/TTS/触摸/导航/媒体/HUD/语音键/GPS/MAC/诊断/壁纸/设置页）一次性移植为源码，不再按阶段交付；
- **不再逐阶段自测/逐阶段验收**：功能完成标准是“逻辑正确 + 可编译打包”；全部功能就绪后统一出实车测试包做实车验证；
- **APK 瘦身**：删除 CarLife 用不到的 link_plus 桌面/地图/歌词/超采样等功能（清单见 [PORT_MATRIX.md](PORT_MATRIX.md) §2）；
- **实车验证优先**：实车测试包 = buick APK + 版本号 + SHA-256；实车清单见 §10；结果反馈到验收表/日志；
- **GitHub 下游同步**：`main` 镜像上游、`carlife-port` 开发，流程见 [GITHUB_WORKFLOW.md](GITHUB_WORKFLOW.md)。

---

## 1. 新窗口启动提示词

把下面这段直接作为新窗口的目标：

```text
在 F:\carplay\_newproj\linkplus_carlife 工作，分支 carlife-port。
先完整阅读 docs/CARLIFE_PORT_PLAN.md、docs/PORT_MATRIX.md、docs/GITHUB_WORKFLOW.md、README.md、docs/architecture.md，
以及旧工程 F:\carplay\_newproj\hybrid63 的 inject/src 和 base-decoded 相关实现。

目标：把 hybrid63 插桩已验证功能全面移植为 link_plus 源码（CarLife 唯一内容源 → 无线 CarPlay）：
- CarLife 作为唯一内容源（视频/音频/TTS/触摸/导航/媒体/HUD/语音/GPS），复用 link_plus 的 iAP2/AirPlay/会话/编码/发送；
- 不做 smali 插桩，不修改闭源 APK，不需要 root/Shizuku；
- 不保留内置无线 ADB 激活；不引入 root/Shizuku/ADB；只使用 Android 原生、无需 ADB 权限的能力；
- 全面移植、瘦身 APK、出实车测试包，并建立与上游的 GitHub 下游同步；功能状态以 docs/PORT_MATRIX.md 为准；
- 不再逐阶段自测/验收：逻辑正确 + 可编译打包即可，统一做实车验证；
- 不向旧 hybrid63 写代码。
```

建议同时设置 goal：

```text
完成 linkplus_carlife 分支：把 hybrid63 已验证功能全面移植为源码 + 去掉内置无线 ADB 激活 + 瘦身 APK + 出实车测试包 + 建立 GitHub 下游同步；旧插桩版只读参考，不修改。
```

---

## 2. 为什么走 link_plus

### 2.1 旧插桩版的问题

`hybrid63` 当前是：

```text
闭源 TurtleLink 0.3.63 反编译
→ smali 接缝（Aq/d9/eh/vm 等）
→ 注入 Java
→ d8 + baksmali 合并
→ apktool 回编
```

问题：

- 依赖闭源 APK 版本；官方更新就要重打所有补丁；
- smali 可读性差，d9/Aq/音频队列的改动容易触发 VerifyError；
- 协议字段/发送逻辑被混淆类绑死，封面 file-transfer、导航上行都很难做；
- 闭源二次分发/激活/授权有长期风险。

### 2.2 link_plus 的优点

- 完整源码，Kotlin/Java，Gradle 构建；
- iAP2、AirPlay、H.264、RTP 音频、触摸、会话管理都是可改代码；
- 有单测和 androidTest；
- MIT 许可，长期可维护；
- 已有自己的车机桌面/地图分屏/音乐/歌词/天气（本分支瘦身会删除大部分与 CarLife 重复的能力，见 [PORT_MATRIX.md](PORT_MATRIX.md) §2）；
- 社区反馈清晰度/连接体验不差。

### 2.3 link_plus 的代价

- **没有 CarLife**：全仓 `CarLife` 0 命中，必须移植；
- 有自己的桌面/虚拟屏，不是原版龟壳的安全区/壁纸布局；
- 无 root/Shizuku，但原版使用**内置无线 ADB**（开发者选项 → 无线调试 → 配对激活）实现桌面/触摸注入等能力；本分支不保留该激活路径，相关能力按无 ADB 模型移除或降级；
- Android 17 息屏保持连接问题，README 也承认；
- 无结构化 AMap LinkSDK，导航目前是通知文本解析；
- 封面 file-transfer 只有会话声明，没有完整 artwork 发送端。

---

## 3. 产品目标与边界

### 3.1 全面移植目标（全部为交付内容）

1. 手机连接 CarLife 引擎（TCP，非 root）；CarLife H.264 解码并显示到 CarPlay 车机；
2. 合成壁纸/安全区/contain/圆角（旧龟壳外观功能等价，不要求像素级复刻）；
3. CarLife 媒体音频 + TTS 导航播报进入 CarPlay 音频流；
4. 车机触摸回传给 CarLife；
5. 导航：CarLife 0x10030 / AMap LinkSDK / 通知文本 → iAP2 0x5201/0x5202；HUD 广播接入；
6. 媒体 NowPlaying（0x10035 → 0x5001）、封面 artwork（file-transfer，按真车证据推进）；
7. 语音键（车机 HID → CarLife VR）、GPS 回填（车机 GPS → 0x18010）；
8. MAC 身份（自动解析 + 手填）、诊断日志导出、内置壁纸、设置页（原生改编）；
9. 连接/断开/重连稳定；
10. 去内置无线 ADB、APK 瘦身、实车测试包、GitHub 下游同步。

逐功能状态与目标文件见 [PORT_MATRIX.md](PORT_MATRIX.md)。

### 3.2 不阻塞实车测试包的项

- 旧龟壳壁纸/安全区/圆角的一比一复刻（功能等价即可）；
- 封面 file-transfer 完整发送（需要真车 k1 订阅证据；不阻塞测试包）；
- 车机麦克风接管；
- 有线 CarPlay；
- iAP2 歌词字段（已确认官方没有，不做）。

### 3.3 明确不做

- 不往闭源 APK 里加 smali；
- 不依赖 root/Shizuku/内置无线 ADB；
- 不修改 link_plus 的第三方许可文件；
- 不在未验证情况下硬编码 file-transfer session=12；
- 不把 CarLife 的 TTS 混进媒体格式导致 48000/2ch 被改写。

---

## 4. 目标架构

```text
CarLife 手机引擎
   │ TCP 视频 / 音频 / 触摸
   ▼
CarLifeClient（移植旧 CarLifeHuClient）
   ├── CarLifeProtocol
   ├── H264Sps / CarLifeVideoDecoder
   ├── CarLifeAudioSource（媒体 9240 + TTS 9241）
   └── CarLifeTouchSink
   │
   ▼
link_plus SessionController
   ├── VideoSender（已有 H.264 编码/发送）
   ├── AudioSender（需增加外部 PCM 入口）
   ├── TouchRouter（需增加 CarLife 目标）
   └── Iap2SenderSession（导航 0x5201/0x5202）
```

### 4.1 视频

link_plus 链路：

```text
SessionController → VideoSender.start { surface, profile -> ... }
                 → ScreenSource.attach(surface, width, height, density, geometry, targetFps)
```

当前 `ScreenSource` 实现是 `capture/CarLifeScreenSource.kt`（`HiddenDisplaySource` 已随无 ADB 删除）：

```text
CarLifeScreenSource : ScreenSource  （已实现）
```

实现分两步（**实现顺序，不是验收门槛**）：

**① 直出（已移植）**

- `attach()` 里用 CarLife 的 H.264 参数创建 MediaCodec 解码器；
- decoder output 直接配置到 `VideoSender` 提供的编码器 Surface；
- 不做壁纸/圆角/letterbox；尺寸可能被拉伸（P1A 已合入，审计记录见 `docs/audit/P1A_AUDIT_ROUND3.md`）。

**② 合成（待移植，旧龟壳外观功能等价）**

- `CarLifeComposer`：EGL + OES 纹理 + 壁纸 + contain + 圆角；
- 参考旧工程原版合成器行为（hybrid63 `base-decoded/smali/`）与 CarPToCarL `render/DualDisplayComposer.kt`，用 link_plus 的 GL 代码重写；
- 不再碰 smali；
- 输入 SurfaceTexture 由 CarLife 解码器填充，输出到编码器 Surface。

### 4.2 音频

link_plus `AudioSender` 当前从 MediaProjection 采集（原版另有内置 ADB/shell 辅助的采集路径，本分支不保留），**没有公开 feedPcm**。需要新增：

```text
AudioSender.feedExternalPcm(pcm, rate, channels, isTts)
```

或抽出 `PcmPacketizer`：

- 统一 48k stereo 16bit；
- 352 frames / 1408B；
- 媒体/TTS 独立 ring；
- TTS 混音/duck；
- 44.1k 相位连续；
- 不再启动 MediaProjection 采集（CarLife 模式下用外部 PCM）；
- 保留原 MediaProjection 路径作为可选回退。

实现位置：`carlife/CarLifeAudioSink.kt`（媒体/TTS ring、重采样、44.1k 相位）+ `carlife/CarLifeAudioPipe.kt`（1408B 打包），由 `capture/CarLifeScreenSource.kt` 接 `CarLifeHuClient` 的 `onAudioPcm/onTtsPcm`，输出给 `audio/AudioSender.kt`（外部 PCM 入口）。

可直接参考旧工程：

```text
hybrid63/inject/src/tl/CarLifeAudio.java
hybrid63/inject/src/tl/CarLifeAudioPipe.java
```

### 4.3 触摸

link_plus 触摸链：

```text
TouchRouter → shell/input → 虚拟屏
```

CarLife 需要：

```text
TouchRouter → CarLifeTouchSink → CarLifeHuClient.sendTouchAction(...)
```

- 复用 CarLife 的 9340/触摸协议；
- 坐标要映射到实际视频窗格（P1B 的 contain pane），避免 letterbox 偏移；
- 先支持单点/点击/滑动，再考虑返回/Home 键。

### 4.4 导航

优先级：

1. CarLife `0x10030 NAVI_NEXT_TURN`（需 CarLife 内置导航/P7 数据）；
2. AMap LinkSDK（旧工程 `CarLifeNavAmap`，无 root，最可靠结构化源）；
3. 通知监听文本（link_plus `NavGuidance` 已有，作为兜底）。

上行：

- link_plus 已有 iAP2 会话，新增 0x5201/0x5202 发送；
- 组件 1/2；
- 0x5201 param 2 maneuverState 用 u64；
- 参考旧工程 `CarLifeNav.java` 的 build5201/5202；
- 收到 0x5200 后再发；
- 空数据不发，避免 -1 帧。

### 4.5 设置/权限

保留：

- 通知使用权；
- 媒体/录音等按需权限；
- CarLife 连接（唯一内容源，不设切换开关）；
- 音频注入/TTS；
- 导航 iAP2；
- 安全区/letterbox（P1B 后）；
- 诊断日志导出。

移除：

- Shizuku/Root/内置无线 ADB 激活入口；
- 未使用的高级实验开关；
- 旧工程里与本产品无关的入口；
- 任何“权限服务”文案。

### 4.6 其余功能（详细状态见 PORT_MATRIX.md）

- **媒体 NowPlaying**：CarLife 0x10035 → iAP2 0x5001（`media/CarLifeMedia.kt` + `iap2/Iap2NowPlaying.kt`）；
- **封面 artwork**：0x5001 field 26 + file-transfer（k1 动态会话）；
- **HUD**：`carlife_show_hud_info` 广播 → 导航 0x5201/0x5202；
- **语音键**：车机 HID → `HardKey.VR_START/STOP`（CarLife+ 用手机麦克风起小度）；
- **GPS**：车机 GPS → `0x18010`（900ms 节流）；
- **MAC 身份**：`util/DeviceIdentity.kt` 自动解析 + 设置页手填；
- **诊断**：`util/DebugFileLog.kt`/`util/LogExporter.kt` + 现场脚本；
- **内置壁纸**：迁移旧版 9 张 assets，合成器（P1B）使用；
- **设置页**：link_plus 原生设置页改编，按功能补齐开关；
- **瘦身**：见 PORT_MATRIX §2 删除清单。

---

## 5. 旧工程 → link_plus 迁移映射

旧工程路径：`F:\carplay\_newproj\hybrid63\inject\src`；旧工程没有的实现（GPS/语音键/NowPlaying 组包等）以 [CarPToCarL](https://github.com/jjuugk/CarPToCarL) `7b729fb` 为补充参考（MIT）。

| 旧文件/类（hybrid63） | 本仓库目标/现状 | 状态 | 备注 |
|---|---|---|---|
| `com/turtlelink/carlife/CarLifeHuClient.java` | `carlife/CarLifeHuClient.kt` | 已移植 | TCP/协议/握手/视频/音频/TTS/触摸/媒体/看门狗，约 2000 行 |
| `CarLifeProtocol.java` | `carlife/CarLifeProtocol.kt` | 已移植 | 消息 ID/TLV/98311/98386/媒体信息解析/触摸/GPS |
| `H264Sps.java` | `carlife/H264Sps.kt` | 已移植 | SPS/PPS CSD 提取、真实尺寸解析 |
| `CarLifeVideoDecoder.java` | `carlife/CarLifeVideoDecoder.kt` | 已移植 | MediaCodec 解码到编码器 Surface |
| `CarLifeVideoPipeline.java` | `carlife/CarLifeVideoPipeline.kt` | 已移植 | GOP/CSD 缓存、裸 IDR 补 CSD |
| `tl/CarLifeBridge.java` | `capture/CarLifeScreenSource.kt` + `session/SessionController.kt` | 部分已移植 | 生命周期/内容源接线已按 link_plus 架构重写；诊断/合成部分待补 |
| `tl/CarLifeAudio.java` | `audio/CarLifeAudioSink.kt`（待建） | 待移植 | 48k stereo、TTS ring、44.1k phase、重采样 |
| `tl/CarLifeAudioPipe.java` | `audio/PcmPacketizer.kt` 或并入 `AudioSender`（待建） | 待移植 | 1408B 包、200ms 队列/80ms prime、deadline |
| `tl/CarLifeTouch.java` | `input/CarLifeTouchSink.kt`（待建）+ `input/TouchRouter.kt` | 待移植 | 坐标映射 + 9340 |
| `tl/CarLifeNav.java` | `media/CarLifeNav.kt`（待建）+ `iap2/Iap2SenderSession.kt` | 待移植 | 0x10030/HUD/通知 → 0x5201/0x5202 |
| `tl/CarLifeNavAmap.java` | `media/AmapLinkSdkSource.kt`（待建） | 待移植 | AMap LinkSDK AIDL + auth/register/轮询 |
| `tl/CarLifeMedia.java` | `media/CarLifeMedia.kt`（待建）+ `iap2/Iap2NowPlaying.kt` | 待移植 | 0x10035 → iAP2 0x5001；albumArt 供封面 |
| `tl/CarLifeHudReceiver.java` | `media/CarLifeHudReceiver.kt`（待建） | 待移植 | `carlife_show_hud_info` 广播 → 导航 |
| `tl/MacIdentity.java` | `util/DeviceIdentity.kt`（+ 设置页手填） | 已移植 | 手填优先 + 自动解析 |
| `tl/CarLifeDiagActivity.java` / `tl/CarLifeBridge.java`(diag) | `util/DebugFileLog.kt`、`util/LogExporter.kt`、`MainActivity` 导出 + `SessionController.diagnosticSnapshot()` | 已移植 | 旧诊断 Activity UI 不搬，改用原生导出 |
| `tl/CarLifeHijackActivity.java` | link_plus 原生设置页（`MainActivity.kt`、`launcher/settings/*`） | 已移植（原生改编） | 只保留实际功能，去掉 Shizuku/ADB 入口 |
| `tl/BuiltinWallpapers.java`、`tl/CarLifeWallpaperActivity.java` | `ui/WallpaperStore.kt`、`ui/WallpaperActivity.kt` + 内置图 assets（待迁移） | 待移植（资产） | 壁纸系统已有，缺旧版 9 张内置图与合成器接线 |
| `tl/CarLifeLauncher.java`、`tl/HomeCarLifeEntry.java`、`tl/CarLifeKeepAliveService.java`、`tl/CarLifeConnect.java`、`tl/LocalCarInput.java` | link_plus 手机 UI/前台服务/SessionController 原生实现 | 不移植 | 这些是闭源 APK 的启动/注入/保活适配；本仓库不需要 |
| d9 原版合成器（`base-decoded/smali/` 混淆实现） | `render/CarLifeComposer.kt`（待建） | 待移植 | 按行为用 link_plus GL 重写，不搬 smali |
| Aq 接缝 | 无 | 不移植 | link_plus 不需要 |
| 音频 E0/J0/Oh（smali 接缝） | `AudioSender`/`PcmPacketizer` | 不移植（重写） | 用源码重写，不再 patch smali |
| CarPToCarL 补充：`carlife/CarLifeAudioSink.kt`、`carlife/CarGpsOption.kt`、`iap2/NmeaLocation.kt`、`carlife/VoiceKeyRouter.kt`、`iap2/Iap2NowPlaying.kt` | 对应 §4.2/4.6 目标文件 | 参考 | hybrid63 无 GPS/语音键/NowPlaying 组包，参考 CarPToCarL |

### 迁移原则

1. **协议/解析优先搬**：纯 Java 逻辑可以先放进 `app/src/main/java/.../carlife/`，保证编译；
2. **渲染/音频/触控按 link_plus 架构重写**，不要复制 smali 依赖；
3. 旧 Java 的 Android 依赖尽量收敛到一个模块，避免和 Kotlin 会话层互相踩生命周期；
4. 所有新代码带日志标签，便于实车抓取。

---

### 5.4 复用边界：不是从零重写，也不是整体搬插桩

新工程**不会直接运行**旧 hybrid63 的 smali/注入补丁；也不是让新窗口重新发明 CarLife 协议。

正确的做法是：

**直接复用/适配（旧工程已经验证过的逻辑）**

- `CarLifeHuClient` 的 CarLife TCP/协议/握手；
- `CarLifeProtocol` 的消息 ID/TLV/98311/98386/媒体信息/触摸/GPS；
- `H264Sps`、视频解码器、GOP/CSD 缓存；
- CarLife 媒体/TTS PCM 的 ring、1408B packetizer、44.1k 相位；
- `CarLifeNav` 的 0x10030 → 0x5201/0x5202；
- `CarLifeNavAmap` 的 AMap AIDL；
- `CarLifeHudReceiver` 等调试入口；
- hybrid63 没有的 GPS/语音键/NowPlaying 组包参考 CarPToCarL（MIT）。

这些可以作为 Java 代码先放进新工程，或按 link_plus 的 Kotlin 风格逐个转写；协议细节不重新猜。

**必须按 link_plus 架构重写**

- `ScreenSource`/编码器 Surface 绑定；
- 壁纸 + CarLife OES 纹理 + contain + 圆角合成（`render/CarLifeComposer.kt`）；
- `AudioSender` 的外部 PCM 入口/TTS 混音；
- `TouchRouter` 到 CarLife 触摸通道；
- `SessionController` 生命周期；
- iAP2 NowPlaying（0x5001）与导航上行（0x5201/0x5202）；
- HUD 广播、语音键、GPS 数据源接线；
- 设置页/权限模型（无 ADB 激活 UI）+ 内置壁纸资产；
- 瘦身：删除 link_plus 与 CarLife 重复的桌面/地图/歌词等功能（[PORT_MATRIX.md](PORT_MATRIX.md) §2）。

**不要搬**

- smali 接缝（Aq/d9/eh/vm）；
- 闭源 APK 的布局/队列补丁；
- 旧 hybrid63 的 d9 字段/反射补丁、原版 Surface 直连/反射注入逻辑；
- 旧版 Activity 的 Shizuku/Root/内置无线 ADB 入口。

因此新窗口的第一件事是**读懂并迁移旧 Java 逻辑**，不是重新写一套 CarLife 协议；但最后跑在 link_plus 里的渲染/音频/触摸/会话层是新的源码实现。

---

## 6. 全面移植任务（不再逐阶段验收）

> 口径（2026-11-06）：不再按 P0/P1A/P2… 逐阶段自测与逐阶段验收。功能完成标准 = **逻辑正确 + `:app:assembleBuick` 可编译打包**；全部功能就绪后一次性出实车测试包做实车验证（见 §10）。P1A 阶段记录仅作历史证据保留：[P1A-carlife-video.md](P1A-carlife-video.md)、[audit/P1A_AUDIT_ROUND3.md](audit/P1A_AUDIT_ROUND3.md)。

### 6.1 已完成的阶段记录（历史证据）

| 阶段 | 内容 | 状态 | 记录 |
|---|---|---|---|
| P0 基线 | 环境确认 + 基线构建 | 完成（实车项 PENDING） | [P0-baseline.md](P0-baseline.md) |
| P1A 视频直出 | CarLife H.264 → 编码器 Surface | 已合入（实车待验证） | [P1A-carlife-video.md](P1A-carlife-video.md)、[audit/P1A_AUDIT_ROUND3.md](audit/P1A_AUDIT_ROUND3.md) |

### 6.2 全面移植任务清单

功能级状态以 [PORT_MATRIX.md](PORT_MATRIX.md) 为准；以下按交付顺序组织（**不是验收门槛**）。

| 任务组 | 内容 | 目标文件 |
|---|---|---|
| 视频与合成 | 直出（已移植）+ `CarLifeComposer`（壁纸/安全区/contain/圆角） | `capture/CarLifeScreenSource.kt`、`render/CarLifeComposer.kt` |
| 音频 | 外部 PCM 入口、9240 媒体 + 9241 TTS、ring/混音/duck/相位 | `audio/CarLifeAudioSink.kt`、`audio/PcmPacketizer.kt`、`audio/AudioSender.kt` |
| 触摸 | CarLifeTouchSink + TouchRouter 目标 + pane 坐标映射 | `input/CarLifeTouchSink.kt`、`input/TouchRouter.kt` |
| 导航 | 0x10030/AMap/通知/HUD → iAP2 0x5201/0x5202 | `media/CarLifeNav.kt`、`media/AmapLinkSdkSource.kt`、`media/CarLifeHudReceiver.kt`、`iap2/Iap2SenderSession.kt` |
| 媒体 | 0x10035 → 0x5001 NowPlaying；封面 artwork（file-transfer） | `media/CarLifeMedia.kt`、`iap2/Iap2NowPlaying.kt`、`iap2/ArtworkTransfer.kt` |
| 交互 | 语音键（HID → VR）、GPS 回填（0x18010）、MAC 身份 | `input/VoiceKeyRouter.kt`、`iap2/NmeaLocation.kt`、`carlife/CarGpsOption.kt`、`util/DeviceIdentity.kt` |
| UI/设置 | 设置页 CarLife 开关补齐、内置壁纸资产、诊断导出 | `MainActivity.kt`、`launcher/settings/*`、`ui/WallpaperStore.kt`、`util/LogExporter.kt` |
| 瘦身 | 删除重复功能/权限/资源/compat 占位 | 见 [PORT_MATRIX.md](PORT_MATRIX.md) §2/§3 |
| 发布 | 实车测试包、GitHub 下游同步 | 见 §10、[GITHUB_WORKFLOW.md](GITHUB_WORKFLOW.md) |

### 6.3 完成口径（不再逐阶段自测）

- **不再要求**：逐阶段单测/逐阶段实车验收/每阶段出报告；
- **要求**：逻辑正确、代码能编译、`.\gradlew.bat :app:assembleBuick` BUILD SUCCESSFUL、可打包；
- 单元测试与 lint 作为质量辅助（现有测试保留，不要求为每阶段补测试报告）；
- 全部功能就绪后一次性出实车测试包，按 §10 实车测试清单验证；
- 实车失败项回到对应功能修复，不计“阶段回滚”；回滚以整包/提交为单位（见 §11）。

---

## 7. 排期参考（整体交付，不再按阶段）

| 范围 | 估算 |
|---|---|
| 剩余功能移植（合成/音频/触摸/导航/媒体/HUD/语音/GPS/壁纸/设置） | 3–6 周 |
| APK 瘦身与清理 | 3–5 天 |
| 实车测试包构建 + 实车验证 + 兼容返工 | 1–2 周（+返工） |
| GitHub 下游同步流程建立 | 1–2 天 |

估算前提：一名熟悉 Android MediaCodec/GL/iAP2 的开发者，车机可反复测试；不含车机专有协议逆向。此表仅用于排期，不作为验收门槛。

---

## 8. 旧工程踩过的坑（必须带进新工程）

1. **98311 时序**
   - 先 98386；
   - 等 65570 `featureConfigAcked`，最多 2s；
   - 再发首条 98311；
   - 同尺寸 5s 去重；
   - 首帧后确认尺寸不一致，2.5s 后二次初始化；
   - HU_INFO 报 requested 尺寸。

2. **CarLife 视频**
   - 实际流常见 1280x720；
   - 内容层可能 1452x476，不要直接拉伸；
   - 解码器 CSD 优先从原始帧 SPS/PPS 提取；
   - 不要轻易 reset pipeline，旧工程 startBest+reset 曾导致零帧。

3. **音频**
   - 原版 200ms 队列 / 80ms prime；
   - 必须连续喂 1408B，否则 underrun；
   - TTS 9241 必须独立 ring，不能覆盖媒体 48000/2ch；
   - 44.1k 要跨包保留相位；
   - 不要每包补零，除非真的没有 PCM。

4. **触摸**
   - 画面 contain 后必须映射到实际 pane；
   - 安全区左右边距/ dock 仍会影响内容区；
   - letterbox 区不要误触。

5. **导航**
   - 0x5201 需 0x5200 后发；
   - maneuverState 是 u64；
   - 组件 0 重复 [1,2]；
   - 空数据不发，避免 -1 帧；
   - CarLife 0x10030 只对 CarLife 内置百度导航有效，高德走 AMap LinkSDK 或通知。

6. **NLS**
   - `MediaListenerService` manifest 必须 `exported=false` + BIND 权限；
   - 覆盖安装/force-stop 后 live 绑定会掉；
   - 测试前系统设置“通知使用权”关→开；
   - 日志 `MEDIA: 通知监听已连接` / `notificationListenerBound=true` 才继续。

7. **封面**
   - 真车 file-transfer 会话号是 k1 项，本车可能是 2，不是 12；
   - 不要硬编码 12；
   - 0x5001 field 26 是 u8 artworkTransferID，不是 album；
   - album 字段是 6；
   - 真车不收 START 时不要发 DATA。

8. **熄屏**
   - 旧工程已强制 `SYSTEM`；
   - 不改系统 timeout、不加遮罩；
   - 锁屏/Doze 是否断投屏需实车确认。

9. **NowPlaying / 媒体信息**
   - CarLife 0x10035 只在有媒体时推送；空态不要覆盖车机已有 NowPlaying；
   - 0x5001 的 album 字段是 6，不是 26；26 是 artworkTransferID（u8）；
   - 车机未订阅 0x5000 前不要主动发 0x5001；MIB3 需要空态应答保活。

10. **HUD / 语音 / GPS 数据源**
    - HUD 广播 `carlife_show_hud_info` 不保证所有 ROM 都发；收不到时走 AMap/通知兜底，不要阻塞导航；
    - 语音键走车机 HID/Consumer 事件 → `HardKey.VR_START/STOP`，CarLife+ 用手机麦克风起语音识别；
    - GPS 回填 900ms 节流（NMEA 1Hz），无 fix 不发；避免引擎反复重建 Location。

---

## 9. 权限模型（必须让用户知道）

本分支**不保留** link_plus 内置无线 ADB 激活；用户不再需要：

```text
开发者选项 → 无线调试 → 配对 → 应用内激活内置服务
```

也不需要 Shizuku/root。应用只申请 Android 常规权限（蓝牙、附近 Wi-Fi、通知、录音、悬浮窗、前台服务等），安装后授予即可连接车机。CarLife 画面（H.264：本机 TCP 7240/8240）、音频（9240/9241）、触摸（9340）全部走 CarLife 自身 TCP 通道，不需要 shell 权限。

无 ADB 模型、已移除能力和用户可见权限的完整说明见 [no-adb-model.md](no-adb-model.md)。

不要把本方案描述成“完全无权限/零门槛”：仍需普通运行时权限、车机配对，并且需要手机在 CarLife+ 中手动进入「无线连接」。

---

## 10. 实车测试清单（一次性，交付前执行）

实车测试包 = `app/build/outputs/apk/buick/app-buick.apk` + 版本号（`appVersionName/appVersionCode`）+ SHA-256。测试前记录：手机型号/ROM、车机型号/固件、CarLife+ 版本、应用版本、日志文件。

| 项目 | 通过标准 | 记录 |
|---|---|---|
| 连接 | 蓝牙 iAP2 + Wi-Fi + AirPlay 完成，无内置 ADB/开发者选项依赖 | 车机/手机/ROM/日志 |
| 画面 | CarLife 画面完整，比例正确（P1B 后不变形/留壁纸），无绿屏/黑屏 | `CARLIFE: carlife connected`、`decoder out`、`fit` |
| 音频媒体 | 音乐连续，无持续刺啦；切歌/暂停正常 | `audio`/`AUDIO` 日志 |
| TTS | 导航播报可听，不覆盖媒体格式 | `TTS` 日志 |
| 触摸 | 点击/滑动/长按准确；壁纸/letterbox 区不误触 | 触摸日志 |
| 导航 | 0x5200 后 0x5201/0x5202；无空 -1 帧；HUD 行出现 | `nav`/`5201` 日志 |
| 媒体 NowPlaying | 车机媒体卡歌名/歌手/进度正确 | `0x5001`/`NowPlaying` 日志 |
| 封面 | 媒体卡封面出现（若车机订阅；不出现不阻塞） | `artwork` 日志 |
| 语音键 | 车机语音键可唤起 CarLife 语音识别 | `VR_START` 日志 |
| GPS | 车机 GPS 回填后 CarLife 导航定位改善（按开关） | `车机 GPS 已下发` 日志 |
| MAC 身份 | 占位 MAC 时提示；手填后连接正常 | `MAC:` 日志 |
| 内置壁纸 | 选择内置/自定义壁纸后合成画面正确（P1B 后） | 截图/日志 |
| 熄屏 | 锁屏/Doze 后画面/音频/触摸表现记录 | 现象记录 |
| 重连 | 断开后 10–30s 内可重连 | 日志 |
| 稳定 | 30–60 分钟无解码泄漏/断流 | 日志/性能记录 |
| 性能 | CPU/发热可接受，帧率目标 30/60 | 记录 |
| 日志导出 | 应用内导出日志，含 carlife/airplay/audio/touch/nav | 日志文件 |
| 瘦身 | 无内置 ADB/无关入口；APK 体积对比基线 | APK 大小/SHA-256 |

**日志反馈方式**：应用内「设置→帮助与诊断→导出日志文件」导出完整日志，连同手机/车机型号、版本号、复现步骤提交（GitHub issue 或约定渠道）；开发调试可跑 `scripts/p1a-field-check.ps1` 抓取并自动校验必需标记。

结果记录：填写本表并回填 [P0-baseline.md](P0-baseline.md) 验收表；失败项按 [PORT_MATRIX.md](PORT_MATRIX.md) 回写状态。

---

## 11. 风险与回滚

### 风险

1. 合成器（壁纸/安全区/圆角）与解码 Surface 的尺寸/格式兼容问题（P1B 主要风险）；
2. 音频外部 PCM 与 link_plus 原音频链路冲突/欠载（TTS 独立 ring、44.1k 相位）；
3. 触摸坐标映射依赖 P1B pane，直出期间可能偏移；
4. iAP2 导航（0x5201/0x5202）与媒体 NowPlaying（0x5001）需要真车验证字段/时序；
5. HUD/语音/GPS 数据源受 ROM/权限限制（广播收不到、通知使用权、定位）；
6. 车机兼容/发热/断流；
7. 第三方许可（CarPToCarL MIT、Apache 等组件）发布合规；
8. 旧工程未验证的 CarLife 细节移植后回归；
9. 瘦身误删仍被引用的资源/代码导致构建或运行回归。

### 回滚

- 不做逐阶段回滚；回滚以**整包/提交**为单位；
- 每功能独立提交，实车失败时 revert 对应提交或回退测试包；
- `carlife-port` 保持可构建；上游同步在大冲突时用 `sync/upstream-YYYYMMDD` 分支验证后再合回；
- 旧插桩 UI37 继续保留在 hybrid63，不删除，直到新工程通过实车验收。

---

## 12. 参考资产

旧工程（只读）：

```text
F:\carplay\_newproj\hybrid63\inject\src
F:\carplay\_newproj\hybrid63\base-decoded\smali
F:\carplay\_newproj\hybrid63\work\UI37_FULL_AUDIT.md
```

link_plus 基线：

```text
F:\carplay\_newproj\work\ref_link_plus
```

关键参考文件：

```text
app/src/main/java/io/github/shaonialife/buicklinkplus/capture/CarLifeScreenSource.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/capture/ScreenSource.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeHuClient.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/render/DualDisplayComposer.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/audio/AudioSender.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/input/TouchRouter.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/session/SessionController.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/iap2/Iap2LinkEngine.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/video/VideoSender.kt
```

文档资产：

```text
docs/PORT_MATRIX.md           功能移植矩阵（状态总表）
docs/GITHUB_WORKFLOW.md       上游同步 / 发布流程
docs/no-adb-model.md          无内置 ADB 模型与权限
docs/P0-baseline.md           P0 基线记录（历史证据）
docs/P1A-carlife-video.md     P1A 验证记录（历史证据）
docs/audit/P1A_AUDIT_ROUND3.md P1A 对抗性审计（历史证据）
```

---

## 13. 最新源码快照与更新方式

新窗口开始前，先拉最新源码，不要直接拿旧的本地副本开工。

### 13.1 工作副本与参考仓库

| 用途 | 路径 | 当前快照 |
|---|---|---|
| 工作副本（CarLife 分支） | `F:\carplay\_newproj\linkplus_carlife` | `carlife-port` 当前 `cb5ddc3`（2026-11-06，P1A 已合入；上游基线 `51fb394`） |
| 上游仓库（GitHub 同步） | `schemawisp/link_plus`（remote `upstream`，只 fetch） | 见 [GITHUB_WORKFLOW.md](GITHUB_WORKFLOW.md) |
| CarLife 主实现参考（CarPToCarL） | `reference/CarPToCarL` | `7b729fb`（2026-11-06） |
| iAP2/音频参考（carvex-android） | `reference/carvex-android` | `aa7b750` |
| 协议研究（carvex-carplay-research） | `reference/carvex-carplay-research` | `60dd4fc` |
| 旧插桩工程（只读） | `F:\carplay\_newproj\hybrid63` | UI37 候选 |

`reference/` 已被 `.gitignore` 排除，不提交。

### 13.2 更新命令

网络必须走系统代理 `127.0.0.1:7890`。

```powershell
$env:HTTP_PROXY='http://127.0.0.1:7890'
$env:HTTPS_PROXY='http://127.0.0.1:7890'

# 上游同步（流程见 docs/GITHUB_WORKFLOW.md）
git fetch upstream --prune
git log -1 --format='%H %ad %s' --date=iso upstream/main

# CarLife 参考
git -C reference/CarPToCarL fetch --depth 1 origin
git -C reference/CarPToCarL log -1 --format='%H %ad %s' --date=iso

# iAP2/协议参考
git -C reference/carvex-android fetch --depth 1 origin
git -C reference/carvex-carplay-research fetch --depth 1 origin
```

如果 `reference/` 不存在：

```powershell
mkdir reference
git clone --depth 1 https://github.com/jjuugk/CarPToCarL.git reference/CarPToCarL
git clone --depth 1 https://github.com/iamr0s/carvex-android.git reference/carvex-android
git clone --depth 1 https://github.com/iamr0s/carvex-carplay-research.git reference/carvex-carplay-research
```

### 13.3 为什么参考 CarPToCarL

CarPToCarL 是当前 CarLife 侧最完整的参考，重点看：

```text
CarLifeHuClient.kt / CarLifeAudioSink.kt / CarLifeProtocol.kt
Iap2NowPlaying.kt / SessionController.kt
CarLife 音频档位、TTS、0x10035 媒体解析、98311/98386
```

注意：

- 它的 `Iap2NowPlaying.kt` 曾把 album 发到 26，不能照抄；album 应为 6；
- 它没有封面 file-transfer 完整发送端；
- 它的歌词说法不可靠：官方 iAP2 0x5001 无歌词字段；
- 它的连接/音频修复是最新参考，但不是无 bug 标准答案。

---

## 14. 环境要求（新窗口必须先过这一节）

### 14.1 主机

- Windows 10/11 x64；
- PowerShell 7（`pwsh`）；
- 磁盘 ≥ 10GB（Gradle、Kotlin、NDK、构建缓存）；
- 可访问 GitHub / Google Maven / Maven Central / JitPack，系统代理 `127.0.0.1:7890`。

### 14.2 JDK

link_plus 的 AGP/Kotlin 目标是 Java 17。

本机路径：

```text
F:\carplay\tools\jdk17\jdk-17.0.20.1
```

设置：

```powershell
$env:JAVA_HOME='F:\carplay\tools\jdk17\jdk-17.0.20.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

不要用 JDK21 代替；旧插桩工程用 JDK21，但 link_plus 构建用 JDK17。

### 14.3 Android SDK

本机路径：

```text
F:\carplay\tools\android-sdk
```

link_plus 固定要求：

```text
compileSdk = 34
build-tools = 35.0.0
ndkVersion = 27.0.12077973
minSdk = 31
targetSdk = 34
```

本机当前：

```text
platforms/android-34        已存在
build-tools/34,36,37        有
build-tools/35.0.0          已安装（2026-11-06 本机补装）
ndk/27.3.13750724           有
ndk/27.0.12077973           已安装（2026-11-06 本机补装）
```

安装：

```powershell
$env:JAVA_HOME='F:\carplay\tools\jdk17\jdk-17.0.20.1'
& 'F:\carplay\tools\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat' `
  'platforms;android-34' 'build-tools;35.0.0' 'ndk;27.0.12077973'
```

如果 `sdkmanager` 首次运行要求接受许可，按提示输入 `y`。

### 14.4 local.properties

在仓库根目录创建：

```properties
sdk.dir=F\:\\carplay\\tools\\android-sdk
```

如果路径不对，以 `scripts\check-env.ps1` 的检查结果为准。

### 14.5 签名

首次本地构建不需要正式签名，会自动用 debug 签名。

正式包再根据 `keystore.properties.example` 创建 `keystore.properties`，不要把密钥/密码提交。

测试包：

```powershell
.\gradlew.bat :app:assembleBuick
```

输出：

```text
app/build/outputs/apk/buick/app-buick.apk
```

正式包应用 ID 为 `io.github.shaonialife.buicklinkplus`，buick 测试包会带 `.buick` 后缀，不能与其它签名包直接覆盖安装。

### 14.6 构建版本

- Gradle wrapper：8.13
- AGP：8.13.2
- Kotlin：2.3.21
- ktlint/Spotless：1.3.1 / 7.2.1
- 构建 JDK：17
- 构建工具：Build Tools 35.0.0
- NDK：27.0.12077973

### 14.7 首次检查

```powershell
cd F:\carplay\_newproj\linkplus_carlife
.\scripts\check-env.ps1
.\gradlew.bat :app:assembleBuick
```

2026-11-06 本机已验证：

```text
BUILD SUCCESSFUL in 7m 32s
```

基线 APK：

```text
app/build/outputs/apk/buick/app-buick.apk
SHA-256 E1658D3537D68E6D18B5C2B6C33F57A96CA0E6B459E52AB0594E81C5C99FE905
```

如果 Gradle 下载依赖失败，先确认代理环境变量已设置，再重试。

### 14.8 设备

- Android 12+ 手机；
- 目标车机支持无线 CarPlay；
- 手机安装 CarLife+ 并能手动进入「无线连接」；
- 不需要开发者选项、无线调试或 ADB 配对（无内置 ADB 激活流程）；
- 测试前记录手机/ROM/车机/版本。

### 14.9 第一轮禁用项

- 不装 Shizuku，不使用内置无线 ADB 激活；
- 不使用 root；
- 不修改闭源 APK；
- 不在未验证时发 iAP2 file-transfer；
- 封面/歌词不作为前置；按 [PORT_MATRIX.md](PORT_MATRIX.md) 状态推进，封面不阻塞测试包。

---

## 15. 新窗口的第一步

```powershell
cd F:\carplay\_newproj\linkplus_carlife
.\scripts\check-env.ps1
.\gradlew.bat :app:assembleBuick
```

先确认构建通过；然后按 [PORT_MATRIX.md](PORT_MATRIX.md) 的“移植中/待移植”项补齐功能并更新矩阵状态。不再逐阶段自测：功能逻辑正确、可编译打包即可，全部功能就绪后出实车测试包（§10）。上游同步流程见 [GITHUB_WORKFLOW.md](GITHUB_WORKFLOW.md)。

---

## 16. 提交与实车测试包发布建议

- 提交按功能/修复组织（例如 `feat(carlife): audio sink from 9240/9241`、`feat(carlife): touch sink`），保证每个提交可编译；
- 不要求“每阶段验收后才提交”；要求提交前 `.\gradlew.bat :app:assembleBuick` 通过；
- 实车测试包发布：递增 `appVersionName/appVersionCode` → `assembleBuick` → 记录 APK 大小/SHA-256 → 打 tag/Release（流程见 [GITHUB_WORKFLOW.md](GITHUB_WORKFLOW.md) §7）；
- 上游同步单独成 merge/rebase 提交，不把上游改动混入功能提交。
