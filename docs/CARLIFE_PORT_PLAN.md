# link_plus × CarLife 重构目标与实施文档

> 工作副本：`F:\carplay\_newproj\linkplus_carlife`  
> 分支：`carlife-port`  
> 基线：[schemawisp/link_plus](https://github.com/schemawisp/link_plus) `51fb394`（MIT，基于 cardeck）  
> 旧插桩版：`F:\carplay\_newproj\hybrid63`（只作为行为/协议参考，不再往这边加 smali）  
> 本文是给“新窗口”的目标、边界、迁移映射、阶段任务和验收标准。

---

## 0. 一句话目标

**在 link_plus 的可编译源码上重做一个 “CarLife → 无线 CarPlay” 发送端：CarLife 作为内容源，复用 link_plus 的 iAP2/AirPlay/视频/音频/触摸/会话管理，不修改闭源 APK，不做 smali 插桩，不需要 root/Shizuku。**

首版以“能用、可维护、可实测”为主；不追求一比一复刻旧龟壳的壁纸/安全区外观。原版外观作为可选 P2。

---

## 1. 新窗口启动提示词

把下面这段直接作为新窗口的目标：

```text
在 F:\carplay\_newproj\linkplus_carlife 工作，分支 carlife-port。
先完整阅读 docs/CARLIFE_PORT_PLAN.md、README.md、docs/architecture.md，
以及旧工程 F:\carplay\_newproj\hybrid63 的 inject/src 和 base-decoded 相关实现。

目标：在 link_plus 源码基线上重构 CarLife → 无线 CarPlay 发送端：
- CarLife 作为唯一内容源（视频/音频/触摸/导航），复用 link_plus 的 iAP2/AirPlay/会话/编码/发送；
- 不做 smali 插桩，不修改闭源 APK，不需要 root/Shizuku；
- 保留 link_plus 内置无线 ADB 激活方式；
- 先做 P1A（CarLife 视频直出），再做音频、触摸、导航、设置；
- 每阶段编译、测试、实车/日志验收后再进入下一阶段；
- 不提前提交最终版，不向旧 hybrid63 写代码。
```

建议同时设置 goal：

```text
完成 linkplus_carlife 分支的 CarLife→无线 CarPlay 重构：P1A 视频、P2 音频、P3 触摸、P4 导航/设置、P5 封面；每阶段有编译/测试/日志证据，旧插桩版只读参考，不修改。
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
- 已有自己的车机桌面/地图分屏/音乐/歌词/天气；
- 社区反馈清晰度/连接体验不差。

### 2.3 link_plus 的代价

- **没有 CarLife**：全仓 `CarLife` 0 命中，必须移植；
- 有自己的桌面/虚拟屏，不是原版龟壳的安全区/壁纸布局；
- 无 root/Shizuku，但使用**内置无线 ADB**（开发者选项 → 无线调试 → 配对激活）；
- Android 17 息屏保持连接问题，README 也承认；
- 无结构化 AMap LinkSDK，导航目前是通知文本解析；
- 封面 file-transfer 只有会话声明，没有完整 artwork 发送端。

---

## 3. 产品目标与边界

### 3.1 首版必须做

1. 手机连接 CarLife 引擎（TCP，非 root）；
2. CarLife H.264 解码并显示到 CarPlay 车机；
3. CarLife 媒体音频 + TTS 导航播报进入 CarPlay 音频流；
4. 车机触摸回传给 CarLife；
5. 车机导航指引（0x5201/0x5202）尽量复用旧工程经验；
6. 连接/断开/重连稳定；
7. 设置页只保留实际功能，不出现 Shizuku/root 项；
8. 实车日志可导出。

### 3.2 首版可以不做

- 原版龟壳壁纸/安全区/四角圆角的一比一复刻；
- 仪表/HUD 官方能力（旧工程也是实验性）；
- iAP2 歌词字段（已确认官方没有）；
- 封面 file-transfer 完整发送（P5 单独做）；
- 车机麦克风接管；
- 有线 CarPlay。

### 3.3 明确不做

- 不往闭源 APK 里加 smali；
- 不依赖 root/Shizuku；
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

link_plus 已有：

```text
SessionController → VideoSender.start { surface, profile -> ... }
                 → ScreenSource.attach(surface, width, height, density, geometry, targetFps)
```

当前 `ScreenSource` 实现是 `HiddenDisplaySource`，它把虚拟屏启动器接到编码器 Surface。

新增：

```text
CarLifeScreenSource : ScreenSource
```

两种实现路径：

**P1A 直出（先做）**

- `attach()` 里用 CarLife 的 H.264 参数创建 MediaCodec 解码器；
- decoder output 直接配置到 `VideoSender` 提供的编码器 Surface；
- 不做壁纸/圆角/letterbox，先证明 CarLife 画面能进 CarPlay；
- 适合快速验证，但尺寸由编码器 profile 决定，可能拉伸。

**P1B 合成（再做）**

- `CarLifeComposer`：EGL + OES 纹理 + 壁纸 + contain + 圆角；
- 参考旧工程 d9 的 `uClipRect/uClipRadius` 思路，但用 link_plus 的 GL 代码重写；
- 不再碰 smali；
- 输入 SurfaceTexture 由 CarLife 解码器填充，输出到编码器 Surface；
- 这一阶段再决定是否复刻旧龟壳外观。

### 4.2 音频

link_plus `AudioSender` 当前从 MediaProjection/ADB 采集，**没有公开 feedPcm**。需要新增：

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

- link_plus 内置无线 ADB 激活；
- 通知使用权；
- 媒体/录音等按需权限；
- CarLife 连接开关/内容源；
- 音频注入/TTS；
- 导航 iAP2；
- 安全区/letterbox（P1B 后）；
- 诊断日志导出。

移除：

- Shizuku/Root 入口；
- 未使用的高级实验开关；
- 旧工程里与本产品无关的入口；
- 任何“权限服务”文案。

---

## 5. 旧工程 → link_plus 迁移映射

旧工程路径：`F:\carplay\_newproj\hybrid63\inject\src`

| 旧文件/类 | link_plus 目标 | 备注 |
|---|---|---|
| `com/turtlelink/carlife/CarLifeHuClient.java` | `carlife/CarLifeClient.kt` 或 `carlife/CarLifeClient.java` | TCP/协议/视频/音频/TTS/触摸/导航解析，约 2000 行 |
| `CarLifeProtocol.java` | `carlife/CarLifeProtocol.kt` | 消息 ID/TLV/98311/98386 等 |
| `H264Sps.java` | `carlife/H264Sps.kt` | SPS/PPS CSD 提取 |
| `CarLifeVideoDecoder.java` | `carlife/CarLifeVideoDecoder.kt` | MediaCodec 解码到 Surface |
| `CarLifeVideoPipeline.java` | `carlife/CarLifeVideoPipeline.kt` | GOP/CSD 缓存 |
| `tl/CarLifeAudio.java` | `audio/CarLifePcmSource.kt` | 48k stereo、TTS ring、44.1k phase |
| `tl/CarLifeAudioPipe.java` | `audio/PcmPacketizer.kt` | 1408B 包、预灌、deadline |
| `tl/CarLifeTouch.java` | `input/CarLifeTouchSink.kt` | 坐标映射 + 9340 |
| `tl/CarLifeNav.java` | `media/CarLifeNav.kt` | 0x10030 → 0x5201/0x5202 |
| `tl/CarLifeNavAmap.java` | `media/AmapLinkSdkSource.kt` | AIDL + auth/register/轮询 |
| `tl/CarLifeBridge.java` | `session/CarLifeSession.kt` | 生命周期/源切换/诊断 |
| `tl/CarLifeHijackActivity.java` | `ui/CarLifeSettingsActivity.kt` | 只保留实际设置 |
| `tl/CarLifeHudReceiver.java` | `media/CarLifeHudReceiver.kt` | 可选调试入口 |
| `tl/MacIdentity.java` | `bt/CarLifeIdentity.kt` | 手动 MAC/身份 |
| d9 `hyL/hyR/hyB/hyT/hyRad` | `render/CarLifeComposer` | 重新实现，不搬 smali |
| Aq 接缝 | 无 | link_plus 不需要 |
| 音频 E0/J0/Oh | `AudioSender`/`PcmPacketizer` | 用源码重写，不再 patch smali |

### 迁移原则

1. **协议/解析优先搬**：纯 Java 逻辑可以先放进 `app/src/main/java/.../carlife/`，保证编译；
2. **渲染/音频/触控按 link_plus 架构重写**，不要复制 smali 依赖；
3. 旧 Java 的 Android 依赖尽量收敛到一个模块，避免和 Kotlin 会话层互相踩生命周期；
4. 所有新代码带日志标签，便于实车抓取。

---

## 6. 阶段任务与验收

### P0 基线（1–2 天）

任务：

- 确认 JDK17、SDK34、Build Tools 35、NDK 27；
- `./gradlew.bat :app:assembleBuick` 成功；
- 在目标手机安装，link_plus 原功能连接车机成功；
- 记录：
  - 车机型号、手机型号/ROM；
  - 视频 profile（分辨率/fps/bitrate）；
  - 蓝牙/iAP2/AirPlay 日志；
  - 有线/无线 ADB 激活流程。

验收：

- link_plus 原版能出画面、声音、触摸；
- 无本仓库代码改动；
- 日志可导出。

### P1A CarLife 视频直出（3–5 天）

任务：

- 移植 CarLife 客户端/协议/解码；
- 实现 `CarLifeScreenSource`，decoder output 到 `VideoSender` Surface；
- 在设置页加“CarLife 内容源”开关；
- 处理 98311/98386/65570 时序（旧工程经验，见 §8）；
- 解码器输出尺寸 1280x720，编码 profile 按车机请求；
- 不要求壁纸/圆角。

验收：

- CarLife 启动后车机出现 CarLife 画面；
- 切歌/导航画面刷新；
- 断开重连可恢复；
- 无 `VerifyError`，无解码器泄漏；
- 日志含 `carlife connected` / `decoder out` / `fit`。

### P1B 合成/letterbox/圆角（4–6 天，可选但推荐）

任务：

- `CarLifeComposer`：壁纸 + OES 纹理 + contain + 圆角；
- 安全区/内容区尺寸来自 link_plus `DisplayGeometry`；
- 触摸映射到实际 pane；
- 17/16px 圆角可配置，不在 shader 里做抗锯齿。

验收：

- 画面不变形；
- 左右/上下留壁纸；
- 触摸在 pane 内准确；
- 圆角不黑边。

### P2 音频（3–5 天）

任务：

- `AudioSender` 增加外部 PCM 入口；
- CarLife 9240 媒体 + 9241 TTS；
- 48k stereo 16bit，1408B/352 帧；
- TTS 混音/duck；
- 44.1k 相位连续；
- 停止 MediaProjection 采集（CarLife 模式）；
- 观察 underrun/queue。

验收：

- 音乐连续无刺啦；
- 导航播报可听；
- 切歌/暂停正常；
- 断开后无残留音频。

### P3 触摸（2–4 天）

任务：

- `CarLifeTouchSink`；
- `TouchRouter` 新增 CarLife 目标；
- pane 坐标映射；
- 点击/滑动/长按；
- 可选车机返回/Home。

验收：

- 车机点 CarLife 画面控件准确；
- 壁纸区不误触；
- 与 P1B pane 一致。

### P4 导航/媒体/设置（3–5 天）

任务：

- CarLife 0x10030 解析；
- 0x5201/0x5202 上行（组件 1/2、u64 maneuverState）；
- AMap LinkSDK 可选；
- 通知兜底；
- 设置页清理 Shizuku/无用项；
- 日志导出。

验收：

- 车机仪表/导航卡出现转向/距离（若车机支持）；
- 无空 `-1` 帧；
- 只有实际功能开关。

### P5 封面/长期（5–10 天，可选）

任务：

- 动态取 k1 file-transfer 会话；
- 0x5001 field 26（u8 artworkTransferID）；
- SETUP/START/DATA/SUCCESS；
- 封面源 768x768 JPEG；
- 失败回退为不发 26。

验收：

- 媒体卡封面出现；
- 不卡 loading；
- 文本字段不受影响。

---

## 7. 时间估算

| 范围 | 估算 |
|---|---|
| P0 基线 | 1–2 天 |
| P1A 视频直出 | 3–5 天 |
| P1B 合成/圆角 | 4–6 天 |
| P2 音频 | 3–5 天 |
| P3 触摸 | 2–4 天 |
| P4 导航/设置 | 3–5 天 |
| P5 封面 | 5–10 天 |
| **最小可用（P0+P1A+P2+P3）** | **2–3 周** |
| **接近旧插桩完整度** | **5–8 周** |
| **含实车兼容返工** | **+30–50%** |

估算前提：一名熟悉 Android MediaCodec/GL/iAP2 的开发者，车机可反复测试；不含车机专有协议逆向。

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

---

## 9. 权限/激活模型（必须让用户知道）

link_plus 不需要 Shizuku/Root，但需要：

```text
开发者选项 → 无线调试 → 配对 → 应用内激活内置服务
```

这是高权限 shell 通道，和普通 App 权限不同；如果用户连无线调试都不接受，link_plus 路线不适合。

不要把它描述成“完全无权限/零门槛”。

---

## 10. 实车验收矩阵

每项记录：手机/ROM/车机/版本/日志。

| 项目 | 通过标准 |
|---|---|
| 连接 | 蓝牙 iAP2 + Wi-Fi + AirPlay 完成，车机出画面 |
| 画面 | CarLife 画面完整，比例正确，无绿屏/黑屏 |
| 音频 | 音乐连续，无持续刺啦；导航播报可听 |
| 触摸 | 点击/滑动准确，letterbox 区不误触 |
| 重连 | 断开后 10–30s 内可重连 |
| 导航 | 0x5200 后 0x5201/0x5202，无空 -1 |
| 熄屏 | 锁屏/Doze 后画面/音频/触摸表现记录 |
| 稳定 | 30–60 分钟无解码泄漏/断流 |
| 性能 | CPU/发热可接受，帧率目标 30/60 |
| 日志 | 可导出，含 carlife/airplay/audio/touch/nav |

---

## 11. 风险与回滚

### 风险

1. link_plus 内置 ADB 在目标 Honor/Android 17 上激活失败/掉线；
2. CarLife 解码直出到编码器 Surface 尺寸不匹配；
3. AudioSender 改造破坏原有 MediaProjection 音频；
4. 触摸坐标/权限问题；
5. iAP2 导航上行需要真车验证；
6. 车机兼容/发热/断流；
7. 第三方许可（LGPL SPAKE2/Apache libadb）发布合规；
8. 旧工程未验证的 CarLife 细节移植后回归。

### 回滚

- 每个阶段独立分支/commit；
- P1A 不删旧 `HiddenDisplaySource`，用开关切换；
- P2 保留 MediaProjection 路径；
- 任何阶段实车失败，回到上一 commit；
- 旧插桩 UI37 继续保留，不删除，直到新工程通过验收。

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
app/src/main/java/io/github/shaonialife/buicklinkplus/capture/ScreenSource.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/render/DualDisplayComposer.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/audio/AudioSender.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/input/TouchRouter.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/session/SessionController.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/iap2/Iap2LinkEngine.kt
app/src/main/java/io/github/shaonialife/buicklinkplus/video/VideoSender.kt
```

---

## 13. 最新源码快照与更新方式

新窗口开始前，先拉最新源码，不要直接拿旧的本地副本开工。

### 13.1 工作副本与参考仓库

| 用途 | 路径 | 当前快照 |
|---|---|---|
| 工作基线（link_plus） | `F:\carplay\_newproj\linkplus_carlife` | `51fb394` |
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

# 工作基线
git fetch origin
git log -1 --format='%H %ad %s' --date=iso

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
- USB 调试或无线调试；
- 目标车机支持无线 CarPlay；
- 内置无线 ADB 激活需要在开发者选项开启无线调试并按应用提示配对；
- 测试前记录手机/ROM/车机/版本。

### 14.9 第一轮禁用项

- 不装 Shizuku；
- 不使用 root；
- 不修改闭源 APK；
- 不在未验证时发 iAP2 file-transfer；
- 不先做封面/歌词；先 P0+P1A。

---

## 15. 新窗口的第一步

```powershell
cd F:\carplay\_newproj\linkplus_carlife
.\scripts\check-env.ps1
git -C reference/CarPToCarL log -1 --oneline
.\gradlew.bat :app:assembleBuick
```

先确认原版 link_plus 能构建、安装、连车机；再建 `CarLifeScreenSource`。

---

## 13. 第一个 commit 建议

```text
chore: fork link_plus for CarLife port

- add docs/CARLIFE_PORT_PLAN.md
- keep upstream README/LICENSE unchanged
- no CarLife code yet
```

第二个 commit：

```text
feat(carlife): add CarLife protocol/client skeleton

- carlife/CarLifeProtocol
- carlife/CarLifeClient skeleton (connect/hello/98311)
- no video/audio wiring yet
```

第三个 commit：

```text
feat(carlife): direct H.264 decode to VideoSender surface

- carlife/CarLifeVideoDecoder
- capture/CarLifeScreenSource (P1A)
- toggle in settings
```

之后按 P2/P3/P4/P5 分阶段提交，不一次性合入。
