# 实车已验证行为规格与证据（CarLife × CarPlay 桥）

> 目的：把 2026-11-06 之前 **真车日志 + 旧工程源码 + 0.3.63 反编译 smali + 历史交付证据文档** 里
> 已经验证过的 CarLife 行为，按功能整理成可直接照做的“规格 + 坑 + 差异”。
>
> - 编写时间：2026-11-06；材料与引用别名见 [`README.md`](README.md)
> - “当前实现”指本仓库 `carlife-port` **已提交快照 `c284f4d`**（`cb5ddc3` 之后 2 个提交，全量移植 wave 1 已合入）；
>   编写本文时工作区还有**并行未提交实现**（`git status`：`CarLifeNowPlaying.kt`、`CarLifeGpsBridge.kt`、
>   `CarLifeVoiceKeyRouter.kt`、`ui/CarLifeHudReceiver.kt` 及 `VirtualIap2Link/CarLifeScreenSource/
>   CarLifeAudioSink/CarLifeHuClient/Iap2MessageIds/SessionController` 改动）——这些不计入“当前实现”，
>   合入后需重跑 §10 差异表。
> - 证据等级：**A** = 真机日志/真机截图/源码字节级；**B** = 旧工程源码 + 实车验收记录（已实际跑通该行为）；
>   **C** = 第三方参考/逆向推断（未在本车闭环）
> - 与当前实现冲突/缺失的条目在每节 **差异/需移植** 和 §10 汇总表标出。
> - 所有引用只取必要片段；脱敏规则见 `README.md` §2。

---

## 1. 七个端口与两类帧格式（所有功能的前提）

| 端口 | 名称 | 方向 | 帧头 | 本车实测用途 |
|---|---|---|---|---|
| 7240 | CMD | 双向 | **8B** `[u16 len][u16 rsv][u32 type]` | 握手/心跳/模块状态/媒体信息/导航 0x10030 |
| 8240 | VIDEO | MD→HU | **12B** `[u32 len][u32 tsMs][u32 type]` + Annex-B H.264 | 视频码流 |
| 9240 | MEDIA | MD→HU | 12B（同上） | 媒体 PCM（0x30001/0x30002/0x30003/0x30006） |
| 9241 | TTS | MD→HU | 12B | 导航播报 PCM（0x40001/0x40002/0x40003） |
| 9242 | VR | 连上即可 | — | 手机引擎接受，本车未用 |
| 9340 | TOUCH | HU→MD | **8B 控制帧**（不是 12B！） | 触摸/复合手势 |
| 9440 | DATA | MD→HU | 8B | 数据订阅（DATA_SUBSCRIBE_DONE 等，本车仅记录） |

**A**：`SPEC/CARLIFE_HIJACK_SPEC.md:105-190`（端口/帧格式表）；**A**：真机日志逐帧解析
`LOG1/carlife_diag.log:44-48`（`video params`/`decoder out`）、`LOG1/carlife_diag.log:17-18`（touch）。
**B**：`HYB/src/com/turtlelink/carlife/CarLifeProtocol.java`（常量表）与 `HYB/src/com/turtlelink/carlife/CarLifeHuClient.java`（读循环）。

> 坑：9340 走的是 **控制帧（8B）**，不是视频那种 12B 流帧；9440 也按控制帧理解。
> 视频 12B 头的第二字段是 **引擎毫秒时间戳（按 u32 无符号读）**，不是序号（A：`SPEC/CARLIFE_HIJACK_SPEC.md:178-184`）。

---

## 2. 握手时序（0.3.63 插桩版 + 真机日志）

```
-> 98305 协议版本 3.0（payload = 08 03 10 00）
<- 65538 match=1
-> 98343 统计信息
<- 65563 前台 / 65540 手机设备信息 / 65574 模块状态
-> 98307 HU_INFO（字段 1..8 全字符串）
<- 65574 模块状态（有 status=0 → 回 98344 全置 1）
-> 98386 特征配置（7 个 key，见 §2.1）
<- 65570 已接受
-> 98311 视频编码初始化（9 字段，见 §2.2）
<- 65544 引擎确认编码参数
-> 98313 启动推流
<- 8240 开始推 H.264（首帧 SPS+PPS+IDR）
-> 每 1s 131074 心跳
```

**A（真机）**：`LOG1/carlife_diag.log:41-48` 完整握手日志（`连接本机车机端口` → `版本匹配成功（协议 3.0）` →
`模块状态 [...]` → `手机确认编码参数` → `码流真实分辨率` → `decoder out`）；`LOG1/main_debug.log`/`LOG1/carplay-info.txt`
中的 `LINK`/`CARPLAY_INFO` 行给出车机侧对照。
**A（字节）**：`SPEC/CARLIFE_HIJACK_SPEC.md:239-260`（消息表 + 98305 首包 `00 04 00 00 00 01 80 01 | 08 03 10 00`）。

**B（坑）**：协议版本必须发 **3.0**，不能 4.0（4.0 触发加密鉴权后静默挂死）；
`SPEC/CARLIFE_HIJACK_SPEC.md:233-238`、`HYB/src/com/turtlelink/carlife/CarLifeProtocol.java`。

### 2.1 98386 特征配置：**7 个 key**（当前实现只有 4 个）

旧工程最终版（实车 0.3.63 用的版本）发送的 key 列表（**B**：`HYB/src/com/turtlelink/carlife/CarLifeProtocol.java:303-320`）：

| # | key | value | 作用/证据 |
|---|---|---|---|
| 1 | `I_FRAME_INTERVAL` | 20 | 每 20 个 P 帧插 1 个 I 帧（约 1s 一个 IDR） |
| 2 | `CONNECT_TYPE` | 5 | Wi-Fi 局域网编码档位 |
| 3 | `DISABLE_RESOLUTION_OPTION` | 1 | 引擎直接使用 98311 的宽高，不再按白名单分档（**21:9 车机不被横向拉伸**） |
| 4 | `MEDIA_LYRIC_SUPPORT` | 1 | 引擎 dispatch 歌词支持（只影响手机本地，iAP2 无歌词字段） |
| 5 | **`MEDIA_SAMPLE_RATE`** | **1** | **媒体 PCM 直接给 48K LPCM**；不配时引擎按 **44100** 出 |
| 6 | **`FOCUS_UI`** | **1** | 焦点/UI 相关（旧工程实车随 5/7 一起开） |
| 7 | **`VOICE_MIC`** | **1** | 语音/麦克风相关（旧工程实车随 5/6 一起开） |

- **B（关键注释原文）**：`HYB/src/com/turtlelink/carlife/CarLifeProtocol.java:313-314`
  “CarLink Cast 实测：**这三个键决定 CarLife 媒体音频直接给 48K LPCM，不配置时引擎按 44100 出**，
  原版编码器是 48000，必须重采样，容易刺啦。”
- **A（日志佐证，时间分界与推断）**：11-05 的轮次（旧包）为 `44100Hz 2ch`，11-06 的轮次（带 7 key 的新包）
  为 `48000Hz 2ch`，与注释“这三个键决定 48K”在时间上完全吻合（高置信推断；非受控对照实验）：
  - 11-05（旧包）：`LOG1/carlife_diag.log:1279-1283`（20:28:07 等 6 轮）`媒体音频格式宣告 44100Hz 2ch`；
  - 11-06（新包）：`LOG1/carlife_diag.log:2846-2850`（10:54:16）、`:3020-3024`（12:14:12）、
    `:3460-3464`（12:20:08）、`:4039-4043`（13:58:36）均为 `48000Hz 2ch fmt=16`。
- **需移植**：当前仓 `REPO/app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeProtocol.kt:338-352`
  的 `featureConfig()` 只有 4 个 key（I_FRAME_INTERVAL/CONNECT_TYPE/DISABLE_RESOLUTION_OPTION/MEDIA_LYRIC_SUPPORT），
  **缺 `MEDIA_SAMPLE_RATE=1`、`FOCUS_UI=1`、`VOICE_MIC=1`** → 音频大概率回落到 44100 并触发重采样链。
  建议优先级 **P0**（直接影响音频清晰度/重采样“刺啦”风险）。

报文封装（**B**：同文件 `:307-320`）：
`pbVarint(1, cnt=7) + 7×(pbBytes(2, pbString(1,key)+pbVarint(2,value))) + pbVarint(3,0) + pbString(4,"CarPToCarL") + pbString(5,"00:11:22:33:44:55")`。

### 2.2 98311 视频编码初始化：9 字段（安全区必须下发）

| 字段 | 类型 | 语义 | 证据 |
|---|---|---|---|
| 1/2/3 | int32 | width / height / frameRate（车机画布，不是安全区） | **B** `HYB/src/com/turtlelink/carlife/CarLifeProtocol.java:330-340`；**A** 真机 `LOG1/carlife_diag.log:3867-3870` 申请 `1972x520` |
| 4/5/6/7 | int32 | **saveAreaWidth / saveAreaHeight / saveAreaX / saveAreaY**（安全区，来自 AirPlay `/info`） | **B** 同上；**A** 车机 `/info` safeArea=1408×476@(278,44)（`LOG1/main_debug.log:522`） |
| 8 | bool | drawUIOutsideSafeArea | **B** 旧实现默认 false |
| 9 | bool | needAdapterScreen（比例不一致时引擎等比适配、不拉伸） | **B** 默认 true |

**B（坑，实测）**：只发 width/height 时，荣耀 mix 厂商适配层拿不到“真实画布/安全区/是否需适配”，
会按自己的规则回 1280×720 → 21:9 车机被横向拉伸（`HYB/src/com/turtlelink/carlife/CarLifeProtocol.java:341-350`、
`DEL/0347/docs/P2_INTEGRATION_PLAN.md:326-327`）。
**A（真机）**：申请 1972×520 时 65544 却回 1280×720@20（`LOG1/carlife_diag.log:3870-3871`），
说明**引擎确认值 ≠ 申请值**；真实尺寸只看 SPS（见 §8.3）。
**需移植**：当前仓 `CarLifeHuClient` 已有 `saveArea` 构造参数与字段 4-7 的编码
（`REPO/.../CarLifeHuClient.kt:129-142,1467-1475`），但 `CarLifeScreenSource` 建客户端时
**没有传 `saveArea`**（`REPO/.../capture/CarLifeScreenSource.kt:322-330`），即真机 98311 缺 4-7 字段。
建议 P0/P1 从 `geometry`（安全区 content/画布）换算 `saveArea=[w,h,x,y]` 传入。

---

## 3. 音频（9240 媒体 / 9241 TTS）

### 3.1 通道格式与包类型（A/B）

| 通道 | 包头 | 包类型 | 负载 |
|---|---|---|---|
| 9240 媒体 | 12B `[u32 len][u32 ts][u32 type]` | `0x30001` INIT=`CarlifeMusicInit{1:sampleRate, 2:channelConfig, 3:sampleFormat}`；`0x30002` 开始；`0x30003` 停止；`0x30006` NORMAL=裸 PCM | 16bit 交错 PCM |
| 9241 TTS | 12B | `0x40001` INIT；`0x40002` 开始；`0x40003` = **裸 PCM（导航播报）** | 1280B 负载的 TTS 实测（`SPEC/CARLIFE_HIJACK_SPEC.md` 引用的真机日志） |

- **A**：`LOG1/carlife_diag.log:3405`（12:19:53）`TTS 音频格式宣告 16000Hz 1ch（导航播报）`；
  `:3630`（12:21:57）、`:3983`（13:58:14）、`:4554`（14:13:11）同。
- **B**：`HYB/src/tl/CarLifeAudio.java:186-196`（TTS 独立 ring、默认 16000/1）；
  `HYB/src/tl/CarLifeAudio.java:208-231`（TTS PCM 入 ring，400ms 活跃窗口）。
- **坑（真机）**：9241/9240 会被手机引擎频繁 reset。真机日志中媒体通道断开 24 次、TTS 断开 19 次
  （`LOG1/carlife_diag.log:110,433,753,1016,1206,1358,1699,2173,2228,2491,3358,3813,3939,4178,4241…`），
  必须像视频一样有**看门狗重连**；否则“画面早有了、声音要等很久才出来”。

### 3.2 输出时钟：1408B / 48k / 44.1k

| 项 | 值 | 证据 |
|---|---|---|
| 输出包长 | **1408B = 352 帧 stereo 16bit** | **B** `HYB/src/tl/CarLifeAudioPipe.java:23`；`HYB/src/tl/CarLifeAudio.java`（fill/read 按 `len/4` 帧）；`DEL/0347/docs/P2_AUDIO_ROUTING.md:67` |
| 48k 包时长 | 352/48000 ≈ **7.33ms** | B 同上 |
| 44.1k 包时长 | 352/44100 ≈ **7.98ms** | B 同上 |
| 车机协商采样率 | **48000 优先，44100 回退**（原版 `Ji.b` 只接受 48k/44.1k） | **B** `DEL/0347/docs/P2_AUDIO_ROUTING.md:12,66`；`DEL/0347/docs/P6_FINAL_AUDIT.md:56` |
| 真机日志协商结果 | `audio pipe open rate=48000`（本车始终协商到 48k） | **A** `LOG1/carlife_diag.log:2115,2381,2598,2717,2831,2980,3436,3681,3873,4005` |
| 源 PCM | 媒体 9240 16bit/2ch（44100 或 48000 由 §2.1 的 key 决定）；TTS 默认 16k/1ch | A/B 见 3.1 |
| 重采样 | 媒体 44.1↔48k 线性插值（跨包相位连续）；TTS 一律重采样到输出率 + 单声道复制双声道 | **B** `HYB/src/tl/CarLifeAudio.java:115-168`（媒体，`resamplePhase` 跨包）；`:239-292`（TTS） |
| 真实日志的 44.1k | 11-05 旧版本 6 轮会话（见 §2.1）；11-06 起 7 key 版本全部 48k | **A** `LOG1/carlife_diag.log:1279,1570,1908,2074,2342,2609,2734`（44100）；`:2846,3020,3460,4039`（48000） |

**B（坑）**：旧工程注释明确“44.1k 分支只做了 resamplePhase 跨包修复，未做真机听感验证”
（`DEL/0363-audit/docs/UI37_FULL_AUDIT.md:23`）；44.1k→48k 时若相位不连续会有周期“刺啦”。

### 3.3 原版队列 / 预灌 / 门控（B，旧工程实车验证）

| 机制 | 值/行为 | 证据 |
|---|---|---|
| **预灌** | 起播先写 **8 包**（8×7.33ms≈**59ms**），跨过原版编码器 **80ms prime** 门槛后才按实时节拍送 | **B** `HYB/src/tl/CarLifeAudioPipe.java:96-103`；`DEL/0347/docs/P2_AUDIO_ROUTING.md:34-70` |
| **实时节拍** | `nextAt += 352*1e9/outputRate`，进程卡顿时**跳过错过的节拍、不突发补包** | B `HYB/src/tl/CarLifeAudioPipe.java:104-119` |
| **欠载** | 不足 1408B 时补静音（`Arrays.fill(...,0)`），保持满长返回，绝不缩短 | B `HYB/src/tl/CarLifeAudioPipe.java:124-140`；`DEL/0347/docs/P2_AUDIO_ROUTING.md:128` |
| **媒体 ring** | 48 KB（低延迟，最多保留约 200ms；超了丢最旧） | B `HYB/src/tl/CarLifeAudio.java:17,64-87` |
| **TTS ring** | 32 KB，独立，**不能覆盖媒体格式**；格式变更 reset | B `HYB/src/tl/CarLifeAudio.java:186-206` |
| **原版抖动缓冲** | AirPlay 音频缓存 **active=200ms、prime=80ms**（发送前队列上限，非端到端延迟） | **A** `LOG1/main_debug.log:617`；真机 `AUDIO BUFFER ...` 统计 `LOG1/main_debug.log:1079` |
| **Q0 门控坑** | 原版读线程在“远端音频不活跃”时会把整块 1408B **清零静音**；CarLife 模式必须改恒 true（或只门控媒体、TTS 永远放行） | **B** `DEL/0347/docs/P2_AUDIO_ROUTING.md:152,306-307` |

### 3.4 TTS 混音（duck）——两版实现都用“真混音”

| 参数 | 早期实现 | 后期实现 | 证据 |
|---|---|---|---|
| 媒体 duck | **0.45** | **0.5**（带 50~100ms 渐变） | **B** `HYB/src/tl/CarLifeAudio.java:241,268-269`；`DEL/0347/docs/P2_IMPL_NOTES.md:38`；`DEL/0347/docs/P2_AUDIO_ROUTING.md:145` |
| TTS 增益 | **0.90**（上混到双声道） | 0.9（同上） | B 同上 |
| TTS 活跃窗口 | 最近 TTS PCM **400ms** 内才混（之后停 duck） | 同上 | **B** `HYB/src/tl/CarLifeAudio.java:248` |
| TTS 是否受媒体门控 | **不受**（0x30003/暂停只门控媒体，导航必须出声） | 同上 | **B** `HYB/src/tl/CarLifeAudio.java:239-292`；`DEL/0347/docs/P2_AUDIO_ROUTING.md:147-148` |
| 部分消费 | 只消费本包实际用到的 TTS 源帧，剩余留给下一包（否则每包丢 100+ 帧） | 同上 | **B** `HYB/src/tl/CarLifeAudio.java:278-290` |
| 队列上限 | 媒体/TTS 各 512 KB，超限丢最旧（后期 mixer） | — | **B** `DEL/0347/docs/P2_IMPL_NOTES.md:38` |

**真机日志（A）**：TTS 格式宣告 16k/1ch 出现于 `LOG1/carlife_diag.log:3405,3630,3983,4033,4554`；
TTS 通道反复 reset（`LOG1/carlife_diag.log:110` 等，见 3.1）。注意真机日志里**没有** TTS duck 的实际音频
测量（导出器不保存音频字节），duck 参数来自旧工程已上车验证的实现（B）。

### 3.5 与当前实现差异 / 需移植

| 项 | 实车规格 | 当前仓（`c284f4d`） | 动作 |
|---|---|---|---|
| 98386 音频 key | MEDIA_SAMPLE_RATE/FOCUS_UI/VOICE_MIC=1 | **缺失** | **P0** 补 3 个 key（见 §2.1） |
| 1408B 包 + 8 包预灌 | 已实现并实车出声 | 已移植：`REPO/.../carlife/CarLifeAudioPipe.kt`（1408B、PREFILL_PACKETS、prime 80ms） | 保持 |
| TTS 独立 ring + 400ms duck | 45%/90%（旧）/50% ramp（新） | 已移植：`REPO/.../carlife/CarLifeAudioSink.kt:24,421-451` | 保持 |
| 44.1k 重采样跨包相位 | 已实现（未做真机听感） | 已移植（`CarLifeAudioSink` KDoc 声明 44.1k 跨包相位） | 实车听感验证 |
| 媒体环形缓冲 48KB / TTS 32KB | 低延迟 | 需按 sink 实现核对容量（非阻塞） | 低 |
| 原版 Q0 门控 | 必须恒 true/只门控媒体 | 已改为外部 PCM 直喂 AudioSender（无 Q0 链路） | 架构已绕过，无需移植 |

---

## 4. 触摸（9340）

### 4.1 协议（A：官方 HU SDK；B：旧工程实现）

| 消息 ID | 名称 | 负载（手写 protobuf） |
|---|---|---|
| **425985 (0x68001)** | `MSG_TOUCH_ACTION` 复合动作 | `CarlifeTouchAction{1 action(int32 MotionEvent), 2 x, 3 y}`；**指针索引在 action 高字节**（`action or (index shl 8)`） |
| **425986 (0x68002)** | `MSG_TOUCH_ACTION_DOWN` | `CarlifeTouchSinglePoint{1 x, 2 y}` |
| **425987 (0x68003)** | `MSG_TOUCH_ACTION_UP` | 同上 |
| **425988 (0x68004)** | `MSG_TOUCH_ACTION_MOVE` | 同上 |

- **A/B**：`HYB/src/com/turtlelink/carlife/CarLifeProtocol.java:99-106`（常量与官方 HU SDK 字段表）+
  `HYB/src/tl/CarLifeTouch.java:53-79`（动作映射）；当前仓同常量
  `REPO/app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeProtocol.kt:108-141`
  （TOUCH_ACTION/TOUCH_DOWN/TOUCH_UP/TOUCH_MOVE、`TouchAction.withPointer`）。
- **B（踩坑，2026-10-25 真机）**：曾按连号猜“0x68001 DOWN / 0x68002 MOVE / 0x68003 UP”，
  结果整条手势错位——手指一动引擎就收到“抬起”，表现为**能点、点得准但拖不动**。
  正确映射以官方 HU SDK 为准：`0x68002=DOWN / 0x68003=UP / 0x68004=MOVE`，
  双指必须走 `0x68001` 复合动作（单点消息没有指针概念）。
  证据：`REPO/.../carlife/CarLifeProtocol.kt:108-113`（迁移自 CarPToCarL 的逆向注释）。
- **A（真机）**：真机日志只观察到单指 action=0/1/2（`LOG1/carlife_diag.log` 内 18/11/46 次），
  **没有观察到 5/6（多指）**——多指路径只有代码/协议证据（B），未经本车验证。

### 4.2 坐标映射（pitfall：不能用 AirPlay `/info` 原始 safeArea）

旧工程最终实现（**B**：`HYB/src/tl/CarLifeTouch.java:42-51`）：

```
nx = (x - safeX) * engineW / safeW   // x/y 是车机 display 像素（原版合成器收到的事件）
ny = (y - safeY) * engineH / safeH   // engineW/H = 引擎实际编码尺寸（SPS/video params）
再 clamp 到 [0, engineW-1] / [0, engineH-1]
```

其中 `safeX/Y/W/H` 不是 `/info` 原始 safeArea，而是 **原版合成器最终窗格（publishFit 后的 pane）**：
`HYB/src/tl/CarLifeBridge.java:298-335`（`publishFit` 里用 contain 计算 pane，并
`CarLifeTouch.setGeometry(canvasW, canvasH, paneX, paneY, paneW, paneH)`，`:322-327`）。

**A（真机日志的三种几何，说明“窗格 ≠ /info safeArea”）**：

| 时间 | 日志行 | 车机画布 | 触摸窗格（原版合成器结果） | 说明 |
|---|---|---|---|---|
| 11-05 10:41:53 | `LOG1/carlife_diag.log:25` | 1920×720 | safe=**48,0 1834×720** | 测试台/另一显示源（非本车 BUICK） |
| 11-05 20:27:38 | `LOG1/carlife_diag.log:1267` | 1972×520 | safe=**376,44 1413×476** | 本车；`/info` 原始 safeArea 是 1408×476@(278,44)（`LOG1/main_debug.log:522`） |
| 11-06 12:22:30 | `LOG1/carlife_diag.log:3676` | 1972×520 | safe=**376,44 1452×476** | 同一台车，选到外层 C 层后 pane 变 1452×476（= p 层 1408 + 圆角外扩/取整差异） |

即：`x=376 = 278 + 98`、`w=1413/1452 ≠ 1408` 都来自原版合成器的二次布局（具体取整/层选择算法
本次材料未完全反推）；**触摸必须以最终 pane 为基准，直接拿 `/info` safeArea 会偏移**
（差异 98px 在 1972 宽下约 5%）。
**A**：触摸事件真实样例 `LOG1/carlife_diag.log:1939-1945`（action=0 911,530 → action=2 … 438,425 拖动）、
`:2066-2070`（action=0 806,366 → move）、`:2631-2636`；这些坐标是 1972×520 车机 display 域。

### 4.3 手势/队列/节流（B）

| 机制 | 行为 | 证据 |
|---|---|---|
| 单指 | DOWN → MOVE… → UP，动作码 = Android MotionEvent | `HYB/src/tl/CarLifeTouch.java:53-79` |
| 双指 | 第二指 DOWN→`ACTION_POINTER_DOWN(5)` + 指针索引；UP→`POINTER_UP(6)`；坐标一律走 0x68001 | 同上 `:59-78` |
| 缺 UP 兜底 | 原版 0.3.63 触摸桥有 220ms/3000ms 缺 UP 兜底（本仓旧实现 `CarLifeTouch` 简化版无） | `DEL/0363-hybrid/docs/HYBRID63_PORT_INTERSECTION.md:49` |
| MOVE 合并/队列 | 旧注入版队列上限 8，MOVE 只保留最新，DOWN/UP 不丢 | `HYB/src/com/turtlelink/carlife/CarLifeHuClient.java` 触摸队列段；当前仓 `CarLifeHuClient` 同语义 |
| 触摸节流 | 原版 `move_throttle_ms`（0–48ms，步长 8）消费点在 `Io.smali:1645`；UI 可省 | `DEL/0363-hybrid/docs/HYBRID63_PORT_INTERSECTION.md:93` |

### 4.4 与当前实现差异

| 项 | 实车规格 | 当前仓 | 动作 |
|---|---|---|---|
| 9340 控制帧 + 0x68001/2/3/4 | 已验证 | 已移植（`CarLifeHuClient.touchDown/Move/Up/Action`） | 保持 |
| 坐标映射基准 | 最终 pane（contain 后） | 已移植 `REPO/.../input/CarLifeTouchSink.kt:457-501`（containedPane + 比例映射，坐标域=引擎 SPS/协商尺寸） | 与 §4.2 一致；实车校准 |
| 多指 pointer | 已实现（代码级） | 已移植（`POINTER_DOWN/POINTER_UP` + `withPointer`） | 实车校准 |
| 缺 UP 兜底 | 220ms/3000ms | 当前实现有 `STALE_TOUCH_MS=3000ms` 重置（`CarLifeTouchSink.kt:534`） | 保持 |

---

## 5. 导航 / HUD（CarLife 0x10030 → iAP2 0x5201/0x5202）

### 5.1 CarLife `0x10030` `MSG_CMD_NAVI_NEXT_TURN_INFO`（HU→MD 控制通道）

| 字段 | 类型 | 语义 | 证据（B） |
|---|---|---|---|
| 1 | int32 | action（导航动作/提示码） | `HYB/src/com/turtlelink/carlife/CarLifeHuClient.java:1701-1732`（`parseNaviTurn`） |
| 2 | int32 | nextTurn（下一机动类型） | 同上 |
| 3 | string | roadName | 同上 |
| 4 | int32 | totalDistance | 同上 |
| 5 | int32 | remainDistance | 同上 |
| 6 | int32 | icon（KDoc 定义；旧解析器**未读取**该字段） | 同上（注释：icon=6） |

- 日志格式（旧版）：`CARLIFE: NAVI_NEXT_TURN 0x10030 action=… next=… road=… total=… remain=…`
  （`HYB/src/com/turtlelink/carlife/CarLifeHuClient.java:1108-1114`）；
  回调后桥接 `onNaviHud(...)`（`HYB/src/tl/CarLifeBridge.java:600-605`）。
- **前提（重要）**：`0x10030` 只对 **CarLife+ 内置百度导航** 有效；高德/外部导航不会走这条
  （`HYB/src/com/turtlelink/carlife/CarLifeHuClient.java:193-195`；`DEL/0363-ui*/docs/UI14_TEST.md:19`、
  `UI15_TEST.md:26`、`UI17_TEST.md:17`）。
- 真机日志：本次材料中 **没有** `0x10030` 数据（手机 CarLife 导航未运行；多条交付记录都写“仍没有导航数据”），
  只有旧工程 UI19 曾要求出现 `NAVI_NEXT_TURN 0x10030` 日志（`DEL/0363-ui19/docs/UI19_TEST.md:12`）。
- `0x1003B`（65595）`MSG_CMD_NAVI_INFO`：旧工程只做 **长度/hex 记录**，无字段解析
  （`HYB/src/com/turtlelink/carlife/CarLifeHuClient.java:1120-1122`）→ **C 级，待真车抓包**。

### 5.2 车机订阅 `0x5200 StartRouteGuidanceUpdates`（A：本车真机）

**A 真机**（`LOG1/main_debug.log:795`、`LOG1/carplay-info.txt:530-532`、
`LOG1/1793892517107-B-1*.log:778`）：

```
CARPLAY_INFO observed Network 0x5200 StartRouteGuidanceUpdates bytes=24
params=0:2B, 0:2B, 1:0B, 2:0B, 3:0B
```

- 解析（**推断，高置信**）：**参数 0 出现两次，每次 2 字节 = 两个组件 ID（u16 BE）**；参数 1/2/3 为空。
  依据是长度算术 4+2+4+2+4+4+4=24B 与 iPhone 侧组件列表一致（详见
  `DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:100-112`）；本车日志的 hex 被导出器隐去，未看到原始字节。
- 组件语义（**A 二手**：`DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:100-112` 引 iPhone sysdiagnose）：
  **Component 1 = Cluster（仪表）**、**Component 2 = CardView（媒体卡）**；
  本车两个组件 lane guidance capacity=0 → **0x5204 车道不需要**（详见 5.6）。
- `0x5203 StopRouteGuidanceUpdates`：车机停止订阅；收到即停发（A 车机声明清单含 0x5203：
  `LOG1/carplay-info.txt:424`）。
- 本车还声明可收 `0x5201/0x5202/0x5204`（`LOG1/carplay-info.txt:425`）。

### 5.3 `0x5201 RouteGuidanceUpdate`（A 真机测试帧 77B + B 全字段表）

**A（真机测试帧 hex，77B，来源截图）**：`DEL/0363-hybrid-v28/screens/18-nav-iap2-full-tlvs.png`
（测试路 / 剩余 1200m / 下一机动 500m / 剩余 180s；TLV 编码逐字节可信）：

```
0005 0001 03
000E 0003 E6B58BE8AF95E8B7AF 00          # "测试路"
000C 0005 000000006AEBE77F                # ETA（u64 epoch 秒）
000C 0006 00000000000000B4                # 剩余 180s
0008 0007 000004B0                        # 总剩余 1200m
0008 000A 000001F4                        # 到下一机动 500m
0006 000D 0001                            # 机动索引 [1]
000C 0013 4361724C696665 00               # "CarLife"
```

| TLV id | 类型 | 值（本帧） | 语义 | 说明 |
|---|---|---|---|---|
| 0x01 | u8 | 3 | RouteGuidanceState | 早期 v24 帧用 1（`DEL/0363-hybrid-v24/进度记录-v24.md:11`）；3=导航中（另一车实测） |
| 0x03 | string(NUL) | “测试路” | 当前路名 | ≤40~80B（组件声明上限 40） |
| 0x05 | u64 | epoch 秒 | ETA | 0=无 |
| 0x06 | u64 | 180 | 剩余秒 | |
| 0x07 | u32 | 1200 | 总剩余距离（米） | |
| 0x0A | u32 | 500 | 到下一机动距离（米） | |
| 0x0D | u16[] | [1] | 机动索引表 | 首元素=当前机动 |
| 0x13 | string | “CarLife” | 导航源名 | ≤24~40B |

> 该 77B 帧是“发送测试导航帧”路径（旧 `testAll()`），**没有**组件表/显示串等 HUD 全字段；
> 真实 HUD 发送走 `buildHudRoute()`，每个组件各一份。两者 TLV 编码一致，字段是否出现取决于数据源。

**B（HUD 版 `buildHudRoute` 全字段，代码级）**：`HYB/src/tl/CarLifeNav.java:294-321`：

| TLV id | 类型 | 语义 |
|---|---|---|
| 0x00 | u16 | **组件 ID**（comp=1 Cluster / 2 CardView） |
| 0x01 | u8 | state（HUD 版=1） |
| 0x02 | **u64** | **maneuverState**：按到下一机动距离分档 <100m=3、<500m=2、<2000m=1、否则 0 |
| 0x03 | string(80) | 当前路名 |
| 0x04 | string(80) | 备用路名（同 road） |
| 0x05 | u64 | ETA epoch 秒 |
| 0x06 | u64 | 剩余秒（=total/11 估算） |
| 0x07 | u32 | 总剩余距离（米） |
| 0x08 | string(24) | 总剩余距离显示串 |
| 0x09 | u8 | 单位（<1000m→2，否则 0） |
| 0x0A | u32 | 到下一机动距离（米） |
| 0x0B | string(24) | 下一机动距离显示串 |
| 0x0C | u8 | 单位 |
| 0x0D | u16 | 1 |
| 0x0E | u16 | 1 |
| 0x0F | u8 | 1 |
| 0x13 | string(24) | “CarLife” |
| 0x14 | u8 | 1 |

> **坑**：`0x02 maneuverState` 固件要求 **u64**；旧实现曾写成 u8，车机会整条拒绝
> （`HYB/src/tl/CarLifeNav.java:303-304`）。当前仓已改为 u64（`REPO/.../carlife/CarLifeNav.kt:258`）。

### 5.4 `0x5202 RouteGuidanceManeuverUpdate`（A 真机测试帧 + B 代码）

**A（真机测试帧 hex，30B）**：`DEL/0363-hybrid-v28/screens/18-nav-iap2-full-tlvs.png`
（索引 1 / 右转 / “测试路” / 右侧通行）：

```
0006 0001 0001
0005 0003 02
000E 0004 E6B58BE8AF95E8B7AF 00   # "测试路"
0005 0008 00
```

| TLV id | 类型 | 值（本帧） | 语义 |
|---|---|---|---|
| 0x01 | u16 | 1 | 机动索引（与 0x5201 的 0x0D 对应） |
| 0x03 | u8 | 2 | Apple RouteGuidanceManeuverType（2=右转；见 5.5 映射） |
| 0x04 | string | “测试路” | 机动后路名 |
| 0x08 | u8 | 0 | DrivingSide：0=右侧通行（中国） |

**B（HUD 版 `buildHudManeuver` 全字段，代码级）**：`HYB/src/tl/CarLifeNav.java:323-337`：

| TLV id | 类型 | 语义 |
|---|---|---|
| 0x00 | u16 | 组件 ID |
| 0x01 | u16 | 1（机动索引占位） |
| 0x02 | string(80) | 动作文案（“右转”/“直行”/…，表见 5.5） |
| 0x03 | u8 | HUD 动作类型码（`HUD_TYPE_MAP[turn]`） |
| 0x04 | string(80) | 路名 |
| 0x05 | u32 | 到机动距离（米） |
| 0x06 | string(24) | 距离显示串 |
| 0x07 | u8 | 单位 |
| 0x08 | u8 | 0（右侧通行） |
| 0x09 | u8 | 0 |

**旧版（0.3.47 通知兜底）`0x5201/0x5202`**（B：`HYB/src/tl/CarLifeNav.java:361-384`）：
0x5201 = `{0: u16list[1,2], 1: state=3, 3: road, 5: eta u64, 6: remainSec u64, 7: remainM u32,
0x0A: nextM u32, 0x0D: u16list[idx], 0x13: "CarLife"}`；
0x5202 = `{0: u16list[1,2], 1: idx u16, 3: AppleType u8, 4: afterRoad, 8: 0}`。

### 5.5 Apple 机动类型 / HUD 动作映射（B）

| 语义 | Apple type（旧 `detectManeuverType`） | HUD turn index（`hudTurnFromAppleType`） | HUD 动作文案（`HUD_ACTION[turn]`） |
|---|---|---|---|
| 左转 | 1 | 7 | 左转 |
| 右转 | 2 | 3 | 右转 |
| 掉头 | 4 | 5 | 掉头 |
| 环岛进入 | 6 | 14 | 进入环岛 |
| 环岛驶出 | 7 | 23 | 进入环岛（文案表） |
| 到达 | 10 | 24 | 到达目的地 |
| 直行/继续 | 11 | 1 | 直行 |

证据：`HYB/src/tl/CarLifeNav.java:207-218`（文本识别）、`:268-286`（映射表 `HUD_TYPE_MAP`/`HUD_ACTION`）。

### 5.6 时序 / 去重 / **空帧避坑** / 结束（B，全部旧工程已实车跑过）

| 规则 | 行为 | 证据 |
|---|---|---|
| 发送前提 | **收到车机 0x5200 订阅后才发** 0x5201/0x5202；0x5203/断链后停发 | `DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:190-260`；当前仓 `Iap2SenderSession.navSendAllowed` |
| 节流/去重 | 通知路径 900ms；HUD 路径 700ms + 相同 (turn,total) 去重；AMap 同 | `HYB/src/tl/CarLifeNav.java:124-128,238-245` |
| 每组件都发 | comp=1（Cluster）、2（CardView）各一份；0x5203 停止 | `HYB/src/tl/CarLifeNav.java:248-251` |
| **空帧避坑** | 通知里没有距离/时间时**不发**（否则把车机已有 HUD 覆盖成空）：`if (nextM<0 && remainM<0 && remainSec<0) return;` | `HYB/src/tl/CarLifeNav.java:151-157` |
| 无总距离不发 | `totalMeters<=0` 直接 return | `HYB/src/tl/CarLifeNav.java:245` |
| 结束清屏 | 结束/到达发每组件 0x5201 清理帧（state=0、距离 0、单位 2）；`0x5202` 不补 | `HYB/src/tl/CarLifeNav.java:259-266,339-357` |
| 0x5204 车道 | 本车组件 `MaxLaneGuidanceStorageCapacity=0` + iPhone 实测 0 条 → **首版不做** | `DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:160-176` |
| 0x5202 不重发 | iPhone 在路由启动瞬间一次性发（3 机动×2 组件），之后不重发 → 需缓存 | `DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:114-118` |
| 空机动列表 | rerouting 时 `CurrentList=()` 不应立刻清仪表，保留上一条 | `DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:120-122` |

**A（真机日志：真实 iAP2 发送记录）**：`LOG1/carlife_diag.log:3809,3850,3899`
（`nav iAP2 0x5201 len=74/48 next=-1 remainM=-1 remainSec=-1 idx=1`）——
现场确实在发“字段全 -1 的空态/清理帧”（len 48/74），说明该路径存在；
**但正常更新绝不能发这种空帧**，否则会把车机已有 HUD 覆盖成空（§5.6 空帧避坑规则的由来）。

### 5.7 HUD 广播桥（原版车机/手机广播 → 0x5201/0x5202，B）

旧工程 `CarLifeHudReceiver`（**B**：`HYB/src/tl/CarLifeHudReceiver.java:13-18,25-46,49-68`）：

| 广播 action | extras | 行为 |
|---|---|---|
| `carlife_show_hud_info` | `action`(int，3=停止)、`navi_turn`、`remain_distance`、`total_distance`、`road_name` | 转 `CarLifeNav.onHud(turn, remain, total, road)`；action=3 → `onHudStop()` |
| `mix_notify_start_navi` | `isNavi`(boolean) | false → `onHudStop()` |

- 只注册**普通动态接收器**，不 Hook 宿主；某些 ROM 显式广播收不到也不阻塞导航（走 AMap/通知兜底）。
- **A（真机）**：日志只有 `hud broadcast receiver installed` 安装记录
  （`LOG1/carlife_diag.log:2401,2481,2659,2765,2898,3579,3783,3800,3912,4154,4356,4445`），
  **没有收到过 HUD 广播数据**（该车/该轮导航未开或 ROM 非小米 CarWith）。
- **需移植**：当前仓没有 `media/CarLifeHudReceiver.kt`（PORT_MATRIX `待移植`）；广播 action/extras 如上。

### 5.8 导航数据源现状（B）

| 数据源 | 现状/证据 | 可行性 |
|---|---|---|
| AMap LinkSDK（高德 AIDL） | 旧工程 `com/amap/linksdk/aidldefine/*` + `CarLifeNavAmap.java`（JSON 解析 maneuver/nextRoad/currentRoad/距离/时间），实车用过 | 高（本仓已移植 `CarLifeNavAmap.kt` + AIDL） |
| 通知监听文本兜底 | `MediaListenerService` 导航通知 → 文本解析（转向/距离/时间），旧工程已实现 | 中（本仓有 `media/NavGuidance.kt` 通知兜底） |
| CarLife 0x10030 | 只有手机内置百度导航时才有；本次材料无真机数据 | 中（本仓已接线 `CarLifeScreenSource.navSink` → `CarLifeNav.submitNextTurn`） |
| CarLife 9241 TTS | 只有 PCM（导航播报），**没有结构化字段** | 低（不能拿转向/距离/ETA） |

### 5.9 与当前实现差异

| 项 | 实车规格 | 当前仓（`c284f4d`） | 动作 |
|---|---|---|---|
| 0x5201/0x5202 TLV | 见 5.3/5.4（含 maneuverState u64、空帧不发、清理帧） | **已移植**：`REPO/.../carlife/CarLifeNav.kt:247-358`（u64、total>0、dedupe、清理帧、组件 1/2） | 保持 |
| 订阅门控 | 收到 0x5200 才发 | 已移植（`Iap2SenderSession.observeRouteGuidance` + `VirtualIap2Link.forwardRouteGuidanceSubscription`） | 保持 |
| 组件动态表 | 以 0x5200 参数 0 的 u16 列表为准（本车 1/2） | `Iap2SenderSession.navComponents` 已解析，但 `CarLifeNav` 仍用 **DEFAULT_COMPONENTS=[1,2] 硬编码** | P2：用动态组件表 |
| HUD 广播桥 | `carlife_show_hud_info`/`mix_notify_start_navi` | **缺失**（无 `CarLifeHudReceiver`） | P2 |
| 0x10030 | 仅内置百度导航、1Hz、解析 6 字段 | 已移植（`CarLifeHuClient.kt:902` 起 `0x10030` 分支 + `onNaviTurn`） | 保持；实车需内置导航 |
| 0x1003B | 只记 hex，无解析 | 当前实现只日志 | C 级待抓包 |
| 0x5204 | 本车不需要 | 不实现 | 保持 |
| AMap LinkSDK | 已实车用过 | 已移植 | 实车校准 |

---

## 6. 媒体 / NowPlaying（CarLife 0x10035 → iAP2 0x5001）

### 6.1 CarLife 0x10035 / 0x10036 载荷（B）

- `65589 = MD_MEDIA_INFO (0x10035)`：`CarlifeMediaInfoList { 1: cnt; 2: repeated CarlifeMediaInfo }`，
  子消息 `source=1, song=2, artist=3, album=4, albumArt=5(bytes), duration=6, playlistNum=7, songId=8, mode=9`。
- `65590 = MD_MEDIA_PROGRESS (0x10036)`：u32 进度（ms）。
- **B**：`HYB/src/com/turtlelink/carlife/CarLifeProtocol.java:558-606`（MediaInfo 定义/解析）；
  当前仓同解析 `REPO/.../carlife/CarLifeProtocol.kt:548-656`。
- 旧工程媒体桥默认关闭（`CarLifeMedia.enabled=false`）：实车已确认**保留原版媒体文本方案稳定**，
  不按 CarPlay 抓包替换文本；`albumArt` 只用于手机本地媒体卡/封面，**不进 iAP2**
  （`HYB/src/tl/CarLifeMedia.java:14-23,33-39`）。
- 旧工程媒体桥版本实车日志：**本次 LOG1 中没有出现 CarLife 65589 的“媒体信息”行**
  （旧版桥默认关/未触发）；只有车机侧 `MEDIA NowPlaying`（来自手机 MediaSession，见 §6.4）。
  因此“CarLife 65589 实际载荷/字段内容”在本材料里只有协议定义，没有真机字节样例（B/C）。

### 6.2 车机 0x5000 订阅字段（A：本车真机）

**A**：`LOG1/main_debug.log:961`、`LOG1/1793892517107-B-1*.log:834`：

```
MEDIA NowPlaying subscribed fields={
  0=[0,1,4,6,7,8,9,10,12,16,18,21,22,23,24,26,27],
  1=[0..17],
  2=[0,1,6,12,14,16,18]
}
```

- **group0 明确包含 26 = artworkTransferID**（封面）→ 车机确实订阅封面（见 §7）。
  （TLV id 用十六进制记：**album=0x0006**、**artworkTransferID=0x001A**=十进制 26；
  不要把 0x001A 当专辑名——这是 0.3.47 参考实现里的历史 bug。
  见 `DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:68-75`；工作区
  `REPO/.../carlife/CarLifeNowPlaying.kt` 代码注释也按官方字段表修正。）
- group0 已实现字段（旧工程）：`1=Title, 4=Duration(u32ms), 6=Album, 12=Artist`（**B 二手**：
  `DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:71-75`）；剩余 7/8/9/10/16/18/21/22/23/24/26/27 未发。
- 龟壳（0.3.63）已发 0x5001（媒体来自**手机 MediaSession**，不是 CarLife），本车实测可显示；
  当 CarLife 播放时若车机没歌名/封面，需要把 CarLife 65589/65590 桥到 0x5001
  （`DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:28-30,93`）。

### 6.3 歌词：官方 iAP2 **没有歌词字段**（A/B 结论）

- iAP2 0x5001 官方字段表无歌词；龟壳进程内有 `C0247ig.q` 歌词对象但**从未进入 payload**
  （`DEL/0347/docs/P5_ANALYSIS_MEDIA_CLUSTER.md:16,114-116`）。
- CarLife 98386 的 `MEDIA_LYRIC_SUPPORT=1` 只影响引擎派发歌词支持事件，不等于 iAP2 能发歌词。
- **硬约束（本仓 AGENTS.md）**：不把歌词写进 iAP2。
- 封面同理：group0 订阅了 26，但 0x5001 必须额外发 26 + 走 file-transfer（§7）。

### 6.4 A（真机）媒体事件日志

- 车机侧 0x5000 订阅 + `MEDIA NowPlaying` 发送摘要（tag `MediaListenerService`/`iap-link-pacer`）：
  `LOG1/1793892517107-B-1*.log:861,921,950,968`
  （`player=com.netease.cloudmusic status=0/1/2 queue=0/31 bytes=86/168/198`）。
- 本车媒体来自手机 MediaSession（网易云），**不是 CarLife 65589**；
  因此“CarLife 播放时车机媒体卡”在当前材料里**没有闭环证据**，只有旧工程桥接代码与 P5 计划（B/C）。

### 6.5 与当前实现差异

| 项 | 实车规格 | 当前仓 | 动作 |
|---|---|---|---|
| CarLife 0x10035 解析 | 已实现 | `CarLifeProtocol.parseMediaInfoList` + `CarLifeHuClient.onMediaInfo` 回调 | 保持 |
| 0x10035 → 0x5001 桥 | 旧工程默认关；P5 建议桥接（先文本后封面） | **未接线**：`CarLifeScreenSource` 未接 `onMediaInfo`；`VirtualIap2Link` 0x5001 仍是空态（`VirtualIap2Link.kt:759-761`） | P2：桥接 + 订阅字段子集 |
| 0x5001 发送字段 | 至少 group0 的 1/4/6/12（旧实现） | 空态；未发真实字段 | P2 |
| album 字段 6 | 必须发 group0 字段 6 = Album | 未接 | 随 P2 |
| 歌词 | 不能发 | 已遵守 | 保持 |
| art 封面 | 需 26 + file-transfer | 未实现 | 见 §7 |

---

## 7. 封面 / artwork（iAP2 file-transfer，**未实车闭环**）

### 7.1 现状（A 文档 + 代码级缺口）

- 旧工程只把 `albumArt` 解成 Bitmap 供手机本地媒体卡；**从未进入 iAP2**（`HYB/src/tl/CarLifeMedia.java:14-23`）。
- UI37 审计明确列为剩余缺口原文（**A**：`DEL/0363-audit/docs/UI37_FULL_AUDIT.md:21-22`）：
  > “封面实际发送端：**动态 k1 + field26 + SETUP/START/DATA/SUCCESS**；需实车 k1 会话是否回 START 的证据。”
- 车机订阅 group0 包含 **26**（§6.2）→ 车机确实要封面。

### 7.2 **k1 = 对端 file-transfer 会话号，必须动态取**（A：本车真机）

真机链路协商日志（**A**：`LOG1/main_debug.log:29`、各 `B-*.log:29/1379`）：

```
LINK: 采纳对端会话表 maxOutgoing=5 maxLen=2048 sessions=1/k0/v2, 2/k1/v2
```

格式 `id/kind/version`：**`1/k0/v2` = kind=0（control）会话 id=1；`2/k1/v2` = kind=1（file-transfer）会话 id=2**。
即本车 **file-transfer 会话号 = 2，不是 12**；本仓当前把 `SESSION_FILE_TRANSFER=12` 固定
（`REPO/.../iap2/Iap2LinkEngine.kt:712-714,820-823`），只采纳了对端 control id（`adoptedPeerControlSessionId`）。
**硬约束**：不要在未验证情况下硬编码 session=12（`docs/CARLIFE_PORT_PLAN.md:124`）；
发送封面必须用对端会话表里 **kind=1 的 id（k1）**，并处理对端不回 START 的失败回退。

### 7.3 协议线索（C：第三方 DiPlay 源码 + iPhone sysdiagnose；**需本车实车确认**）

| 项 | 值 | 证据 |
|---|---|---|
| 0x5001 media item | **26 = artworkTransferID（u8）** | `DEL/0347/docs/P5_EVIDENCE_IAP2_MEDIA_NAV.md:34,71-75` |
| 0x5001 小包 | 封面传输时会有 ~15B 的“只带 artworkTransferID”增量包 | 同上 `:84` |
| file-transfer datagram | byte0=id、byte1=flags/opcode（**Data=0 / Start=1 / Cancel=2 / Setup=4 / Success=5**，bit `0x40`=Last） | 同上 `:34,94-98` |
| Setup v2 负载 | `u64 BE size + u16 BE type=2（NowPlayingArtworkData）` | 同上 |
| 会话号 | 第三方参考实现写死 `12/v2`；**本车实测 k1=2**，以 7.2 为准 | 同上 vs `LOG1/main_debug.log:29` |
| 回退 | 传输未就绪/失败时**不发 field 26**（宁可不显示封面，不能让 0x5001 指向不存在的传输） | UI37 缺口 + 本仓 PLAN |

### 7.4 待真车验证清单（阻塞项）

1. 对端 file-transfer 会话（k1）是否在 SETUP 后回 **START**（UI37 明确“缺其证据”）；
2. 车机是否接受 `field26 + file-transfer(v2)` 组合（本车是 Buick E2LB，与 DiPlay 参考车型不同）；
3. 失败回退：START 超时/取消时 0x5001 是否仍正常（去掉 26 后媒体卡是否正常）；
4. 封面数据格式（JPEG/PNG）与 size 上限（第三方参考按原图发送，未验证车机上限）。

### 7.5 与当前实现差异

| 项 | 实车规格 | 当前仓 | 动作 |
|---|---|---|---|
| k1 动态会话 | kind=1 对端会话 id（本车 2） | 未采纳 kind=1；`SESSION_FILE_TRANSFER=12` 硬编码 | **P2**：协商时保存 `peerFileTransferSessionId`，发送用该值 |
| field26 | 0x5001 发 26 | 无 | P2 |
| SETUP/START/DATA/SUCCESS | 见 7.3 | 无 | P2 |
| 失败回退 | 不发 26 | 无 | P2（默认安全） |

---

## 8. 安全区 / 画面合成（原版龟壳 d9 语义）

### 8.1 本车显示能力（A：AirPlay `/info`，`LOG1/main_debug.log:522-523`、`LOG1/carplay-info.txt:312-370`）

| 显示 | 类型 | 画布 | 主 viewArea | safeArea | 备注 |
|---|---|---|---|---|---|
| 显示 1（中控） | 110 | **1972×520** | 1972×520@(0,0) | **1408×476@(278,44)**，`drawUIOutsideSafeArea=true` | 超宽屏（1972/520≈3.79:1） |
| 显示 2（主驾侧） | 111 | **2194×700** | 2194×700@(0,0) | **1162×486@(680,82)** | 导航组件 CardView 所在屏 |
| maxFPS | 30 | — | — | — | 音频/SETUP 另见 `/info` |

另：该车 AirPlay `features=416679199360`、`sourceVersion=450.14`、音频 48k/立体声、HID TouchDescriptor uuid=3、
logical 坐标范围 x[0,3082] y[0,814]（`LOG1/1793892517107-B-1*.log:552`）。

### 8.2 原版龟壳合成器 d9 字段与 publishFit 算法（A/B 字节级）

注入 patch 在 `HYB/decoded/smali/d9.1.smali`（注释 `# hybrid:` 是补丁标记）：

| 字段 | 类型 | 语义 | 证据 |
|---|---|---|---|
| `hyL` / `hyR` / `hyB` / `hyT` | float | contain 后视频窗格在**画布窗口坐标（GL 左下原点）**的左/右/下/上边界（绝对像素） | `d9.1.smali:77-86`；`CarLifeBridge.smali:2785,2937` |
| `hyRad` | float | 原版圆角裁剪半径（默认来自原版 `h7.g`；UI37 审计记为 **16px 圆角**，本仓 Composer 默认 17px） | `d9.1.smali:86,215-264`；`DEL/0363-audit/docs/UI37_FULL_AUDIT.md:14` |
| 默认值 | — | 初始 fit = 原版 `O8.e`（m8: x/y/w/h），hyRad=原版 `h7.g`；Java 随后覆盖 | `d9.1.smali:215-264` |
| 生效范围 | — | 仅 **CarLife 内容层 C** 使用 fitted pane + rounded clip；其它层走原版路径 | `d9.1.smali:2375-2394` |

`publishFit` 算法（**B**：`HYB/src/tl/CarLifeBridge.java:298-335`）：

```
pane = contain(srcW×srcH, contentW×contentH)      # 等比放入内容区并居中
hyL = x + dx
hyR = x + dx + paneW
hyB = carH - (y + dy + paneH)                      # GL 左下原点 → 底部
hyT = carH - (y + dy)                              # → 顶部
触摸几何 = (canvasW, canvasH, paneX, paneY, paneW, paneH)   # 触摸跟 pane，不跟原始 safeArea
```

- 无码流尺寸时用内容区 identity（`cw/ch` 回退 1452×476，`x/y` 取 fitX/fitY）。
- `attachCompositor(d9)` 由原版 `Aq` 接缝调用（`HYB/decoded/smali/Aq.smali:6830-6873`）：
  先启动原版合成器 → 把安全区内容层 C 的 Surface 给 CarLife 解码器 → 把车机画布+内容 rect 给触摸映射。

**A（真机）**：compositor 层实选记录（`LOG1/carlife_diag.log:3859-3862`）：
```
compositor layer C=1452x476 valid=true
compositor layer D=1452x476 valid=true
compositor layer p=1408x476 valid=true
compositor choose layer=C 1452x476
```

### 8.3 真实尺寸链路：申请值 / 65544 / SPS / 解码输出（A 真机表）

| 会话（LOG1 行号/时间） | 车机画布 | 启动 size（内容层） | 98311 申请 | 65544 回显 | SPS/解码输出 | 备注 |
|---|---|---|---|---|---|---|
| `:9` 10:41:34（测试源） | 1280×720 | 1280×720 | — | — | — | 初测 Surface |
| `:23-48` 10:41:53（测试源） | 1920×720 | **1834×720** | 1834×720@60 | 1834×720@60 | **SPS 1834×720 → 解码 1840×720** | 宽 1834（非 16 倍数）→ 解码器输出 1840 |
| `:1264-1267` 20:27:38（本车） | 1972×520 | **1413×476** | 1413×476 → 1972×520 | — | 1280×720（后续） | 1413/1452 来自合成器 |
| `:3859-3871` 13:49:37（本车） | 1972×520 | **1452×476** | 1452×476 → 1972×520 | **1280×720@20** | 1280×720 | **引擎降档**：申请 1972×520 回 720p |
| `:3432-3449` 12:20:00（本车） | 1972×520 | **1413×476** | 1413×476 → 1972×520 | **1972×520@20** | **SPS/解码 2128×992** | **回显 ≠ 真实码流**；SPS 才是真相（2128×992） |
| `:2985` 12:13:57（本车） | 1972×520 | — | — | — | 解码 1280×720 | 降档轮次 |
| `:4019` 13:58:27（本车） | 1972×520 | — | — | — | 解码 1280×720 | 降档轮次 |

结论（与 `HYB/src/tl/CarLifeBridge.java:534-543`、`REPO/.../CarLifeHuClient` 的 `videoSize`/`seedSps` 语义一致）：

1. **98311 申请的是车机画布**（如 1972×520），**不是安全区**（1408×476）；安全区只放字段 4-7。
2. **65544 回显 ≠ 真实码流尺寸**：本车出现过“申请 1972×520 / 回显 1972×520 / SPS 却是 2128×992”
   与“申请 1972×520 / 回 1280×720”两类降档；**触摸/画布必须以 SPS 解析值为准**。
3. 解码器输出会做 16 对齐（1834→1840、2118→2128），只影响 Surface 尺寸，不改变 SPS 语义。

### 8.4 超宽屏 / 21:9（A 本车 + C 第三方参考）

- 本车中控 **1972×520 ≈ 3.79:1**（超宽），安全区 1408×476 ≈ 2.96:1；必须 contain + 壁纸填充，不能拉伸。
  **A**：`LOG1/main_debug.log:522`、`LOG1/carplay-info.txt:312-343`。
- 第三方 CarPToCarL 记录：奥迪 MIB3 为 **21:9**，申请 1412×654 被引擎降档为 1280×720@20，
  必须靠 `DISABLE_RESOLUTION_OPTION=1` 有序初始化（先 98386 等 65570 再发首条 98311）才能拿到 1412×654。
  **C**：`reference/CarPToCarL/README.md:300-310`、`reference/CarPToCarL/app/src/main/java/io/github/shaonialife/carptocar/carlife/CarLifeHuClient.kt:120-175`。
  → 本车证据（§8.3）支持“引擎会降档”这一现象；21:9 具体尺寸未在本车复现，**C 级**。
- 真机日志还记录过 1920×720 测试画面（safe=48,0 1834×720；`LOG1/carlife_diag.log:25`）→ **8:3**
  （不是 21:9），说明测试台/另一显示源与真车不同；不要把该尺寸当车机规格。

### 8.5 截图证据（真机/合成器输出）

| 截图 | 内容 | 路径（`DEL/...`） |
|---|---|---|
| 导航 0x5201/0x5202 测试帧 hex 弹窗 | 77B/30B 完整 hex（§5.3/5.4 引用） | `DEL/0363-hybrid-v28/screens/18-nav-iap2-full-tlvs.png`（另有 v23/v24 早期版） |
| 合成壁纸 + 安全区 CarLife 画面 | 原版合成器把 CarLife 解码画面放进安全区/壁纸 | `DEL/0363-hybrid-rc1/screens/24-composited-wallpaper-safearea-carlife.png`、`DEL/0363-hybrid-v34/screens/23-compositor-output-capture.png`、`24-composited-wallpaper-safearea-carlife.png` |
| 安全区 CarLife 帧 1920×720 | 解码帧截图（`荣耀车联` 欢迎页，作为“链路已通”证据） | `DEL/0363-hybrid-v70/screens/58-safearea-carlife-frame-1920x720.png` |
| 音频注入自检 | 1408B/48k 混音自检结果 | `DEL/0363-hybrid-v31/screens/21-audio-inject-selftest.png`、`DEL/0363-rc2/screens/34-audio-inject-selftest.png` |
| 引擎已连 551 帧 | 真机 CarLife 收帧计数 | `DEL/0363-hybrid-v68/screens/56-carlife-engine-connected-551frames.png` |

### 8.6 与当前实现差异

| 项 | 实车规格 | 当前仓（`c284f4d`） | 动作 |
|---|---|---|---|
| contain + 圆角 + 壁纸 | d9 hyL/R/B/T + hyRad（原版 16px；本仓默认 17px）语义 | **已移植**：`REPO/.../render/CarLifeComposer.kt:43-44,74,278-310,772`（paneInCanvas、DEFAULT_CORNER_RADIUS_PX=17、contain 算法对应 publishFit） | 保持；实车校准 |
| 触摸基准 = pane | 触摸跟最终 pane（§4.2） | 已移植 `CarLifeTouchSink.mapPoint/containedPane` | 保持 |
| 98311 画布 + saveArea | 请求画布；安全区字段 4-7 | 请求画布 ✅；**saveArea 未传**（§2.2） | P0/P1 |
| 尺寸真相 = SPS | SPS 决定触摸/画布；65544 仅诊断 | 已移植（`onStreamSize`/`videoSize`/`seedSps`） | 保持 |
| 21:9 有序初始化 | 先 98386 等 65570 再发首条 98311 | 已移植（`orderedInit/settleReinit`，`CarLifeHuClient` KDoc） | 保持；实车验证 |
| P1A 直出拉伸 | 直出到全屏 Surface 会拉伸 | 历史问题；P1B Composer 已解决（当前用 encoder Surface 前合成） | 实车验证 |

---

## 9. 其他：HUD/语音键/GPS/身份/诊断

### 9.1 语音键（车机 HID ↔ CarLife VR，C：仅参考实现，本材料无真机闭环）

- CarLife 控制通道 `0x68008 TOUCH_CAR_HARD_KEY_CODE` 负载 `CarlifeCarHardKeyCode{1 keycode}`；
  `KEYCODE_VR_START=0x21`、`KEYCODE_VR_STOP=0x22`。
  证据（**C**，协议注释来自 CarPToCarL 对官方车机 SDK 的逆向）：
  `reference/CarPToCarL/app/src/main/java/io/github/shaonialife/carptocar/carlife/CarLifeProtocol.kt:157-171,391-392`；
  当前仓已同源移植 `REPO/.../carlife/CarLifeProtocol.kt`（`TOUCH_CAR_HARD_KEY_CODE`/`HardKey`/`carHardKeyCode`）。
- **hybrid63 旧工程没有硬键发送/接收实现**（`HYB/src` 全树无 `0x68008`/`HardKey`）；
  参考 CarPToCarL `VoiceKeyRouter.kt`：把语音键接在 iAP2 `0x6802 AccessoryHIDReport` 的 Consumer `0xCF`，
  短按只发 VR_START（收尾交给 CarLife+），长按≥阈值松手才补 VR_STOP
  （`reference/CarPToCarL/.../carlife/VoiceKeyRouter.kt:8-40,61-74`）。
- 真机日志：本车 HID 有 `0x6800 StartHID`/`0x6802 AccessoryHIDReport`/`0x6803 StopHID`
  （`LOG1/carplay-info.txt:554-564`），但**没有 VR 键实际触发记录**（材料内）。
- 当前仓差异：`CarLifeHuClient.sendHardKeyCode` 已有；**缺**车机 HID/按键 → HardKey 的派发
  （`input/VoiceKeyRouter.kt` 不存在）。

### 9.2 GPS 回填（C：仅参考实现/协议，本材料无真机闭环）

- `0x18010 HU_CAR_GPS = 98320` 负载 `CarlifeCarGps` 25 字段，**前 24 个 required**（缺一个 protobuf 解析即丢弃）；
  单位：经纬度 1/1e6 度、海拔 0.1m、速度 0.01km/h、航向 0.1 度、hdop/pdop/vdop 0.1、时间戳 u64 ms。
- 证据（**C**）：`reference/CarPToCarL/.../carlife/CarLifeProtocol.kt:155,407`（`carGps`）；
  数据源参考 `reference/CarPToCarL/.../iap2/NmeaLocation.kt` + `carlife/CarGpsOption.kt:26-43`
  （开关 prefs `car_gps_option_v1/enabled`，默认 true；900ms 节流）。
  **hybrid63 旧工程没有 GPS 发送**（`HYB/src` 全树无 `0x18010`/`carGps`）→ 没有旧工程实车证据。
- 真机日志：`LOG1/carplay-info.txt` 未含 GPS 字段；`LOG1/main_debug.log` 无 GPS 相关行 → 本车材料**无 GPS 回填闭环证据**。
- 当前仓差异：发送 API ✅（`CarLifeHuClient.sendCarGps` + `CarLifeProtocol.carGps`）；**缺** NMEA 来源与开关
  （`carlife/CarGpsOption.kt`、`iap2/NmeaLocation.kt` 不存在）。

### 9.3 MAC 身份（B，旧工程实车使用）

- 原版用 prefs `device_identity/bluetooth_address` 作为 iAP2 传输标识/ctrl TXT id；
  接受规则：大写 `([0-9A-F]{2}:){5}[0-9A-F]{2}`，并拒绝 `02:00:00:00:00:00`、`00:00:00:00:00:00`、`FF:FF:FF:FF:FF:FF`。
- **B**：`HYB/src/tl/MacIdentity.java:40-50`（normalize）、`:53-58`（save）、`:69-90`（清 X7 缓存）。
- **注意（口径冲突，需 Lead 确认）**：hybrid63 搬运清单明确“手填 MAC UI/门禁**不搬**”
  （`DEL/0363-hybrid/docs/HYBRID63_PORT_INTERSECTION.md:124`），但当前仓实际保留了
  “手填优先”的覆盖项 `settings/bt_mac_override` 与设置页对话框
  （`REPO/.../util/DeviceIdentity.kt:19,39`、`MainActivity.kt:1005`、`SettingsCategoryFragment.kt:653`）。
  自动读取真实地址仍是基线；是否保留手填覆盖需按产品口径裁决（不影响协议正确性）。

### 9.4 诊断日志（B/A）

- 旧工程固定写 `filesDir/connection_logs/carlife_diag.log`（1MB 后滚动 `.old`），行格式
  `MM-dd HH:mm:ss.SSS <msg>`；`CarLifeBridge.diag(...)` 是所有注入诊断的出口
  （**B**：`HYB/src/tl/CarLifeBridge.java:760-781`）。
- 原版日志导出会自动收集 `connection_logs/*.log`（**B**：`DEL/0363-hybrid/docs/HYBRID63_PORT_INTERSECTION.md:80`），
  因此 `LOG1/carlife_diag.log` 能被完整导出。
- 当前仓：`util/DebugFileLog.kt`/`util/LogExporter.kt` + `scripts/verify-carlife-log.ps1` 现场工具（已移植）。

---

## 10. 差异 / 需移植总表（对照已提交 `c284f4d`；工作区并行实现见文首说明）

| # | 功能 | 实车已验证规格（本文） | 当前仓状态（`c284f4d`） | 结论/优先级 |
|---|---|---|---|---|
| 1 | 98386 音频 key | MEDIA_SAMPLE_RATE/FOCUS_UI/VOICE_MIC=1 → 媒体 48k | **缺 3 个 key**（`CarLifeProtocol.featureConfig` 仅 4 key） | **P0 需移植** |
| 2 | 98311 安全区字段 | saveArea 字段 4-7（1408×476@278,44 等） | 已实现字段编码，但 `CarLifeScreenSource` 未传 `saveArea` | **P0/P1 需接线** |
| 3 | 触摸坐标基准 | 最终 pane（1413×476/1452×476），非 `/info` safeArea | 已按 pane 映射（`CarLifeTouchSink`） | 保持；实车校准 |
| 4 | 多指手势 | 0x68001 + pointer 索引 | 已移植（代码级，未实车） | 实车验证 |
| 5 | 0x10030 导航 | 内置百度导航 6 字段，1Hz | 已移植解析+回调 | 实车需内置导航 |
| 6 | HUD 广播 | `carlife_show_hud_info`/`mix_notify_start_navi` | 已提交快照无；**工作区已有 `ui/CarLifeHudReceiver.kt`（未提交）** | P2；合入后复测 |
| 7 | 0x5200/0x5201/0x5202 | 组件 1/2、u64 maneuverState、空帧不发、清理帧 | 已移植；但组件仍默认 1/2 硬编码 | P2：动态组件 |
| 8 | 0x10035 → 0x5001 | CarLife 媒体信息桥（先文本后封面） | 已提交快照未接线；**工作区已有 `carlife/CarLifeNowPlaying.kt`（未提交）** | P2；合入后复测 |
| 9 | 封面 artwork | 动态 k1 + field26 + SETUP/START/DATA/SUCCESS | **未实现**；session 12 硬编码 | P2；先取真车 k1/START 证据 |
| 10 | 歌词 | 官方无歌词字段，禁止写入 | 已遵守 | 保持 |
| 11 | 安全区合成 | d9 hyL/R/B/T/hyRad + publishFit | 已移植 `CarLifeComposer` | 实车校准 |
| 12 | 音频 1408B/预灌/duck | 1408B、8 包≈59ms、48k/44.1k、TTS duck | 已移植 `CarLifeAudioPipe/Sink` | 保持；44.1k 听感验证 |
| 13 | TTS 通道 | 0x40001/0x40002/0x40003、16k/1ch、独立 ring | 已移植 | 保持 |
| 14 | GPS 回填 | 0x18010 25 字段、900ms（**C：参考实现，无本车闭环**） | 已提交快照仅发送 API；**工作区已有 `carlife/CarLifeGpsBridge.kt`（未提交，Android Location 源）** | P2；合入后实车验证 |
| 15 | 语音键 | 0x68008 HardKey 0x21/0x22（**C：参考实现，无本车闭环**） | 已提交快照仅发送 API；**工作区已有 `input/CarLifeVoiceKeyRouter.kt`（未提交）** | P2；合入后实车验证 |
| 16 | MAC 身份 | 自动读取真实地址（旧工程手填 UI/门禁不搬） | 自动读取 ✅；当前仍保留手填覆盖 `bt_mac_override`（口径待裁决，见 §9.3） | 保持自动；手填去留 Lead 裁决 |

---

## 11. 证据缺口（需要下一轮真车/抓包补齐）

1. **封面 file-transfer**：本车 k1=2 已证；缺“SETUP 后回 START / 车机是否收 field26 / 15B 增量包”实车证据。
2. **CarLife 0x10030**：本材料无一条真实数据；需要手机 CarLife+ 内置百度地图导航运行时抓 `NAVI_NEXT_TURN`。
3. **0x1003B**：仅有“记录 hex”的旧代码，字段未知。
4. **HUD 广播**：只有 receiver 安装日志，没有收到 `carlife_show_hud_info` 的记录（该车/ROM 不广播）。
5. **CarLife 媒体 → 车机媒体卡**：当前车机媒体来自手机 MediaSession；CarLife 播放时车机媒体卡表现未验证。
6. **0x5201 组件 TLV**：HUD 版把组件放在 0x00 还是必须另发“组件 ID TLV”未确认；需实车看仪表显示。
7. **44.1k 听感**：只做了相位修复，无真机听感验证。
8. **GPS**：协议/API 有，数据源与车机接受度未验证。
9. **TTS 与媒体重复混音**：UI37 审计第 25 行指出“TTS 混音未验证是否与 CarLife 9240 已混 TTS 重复”。
10. **CarLife+ 版本**：材料基于荣耀 8.8.5（CarLife+ 4.0.0）；升级后需复测。

---

## 12. 附：关键数值/hex 速查

| 项 | 值 |
|---|---|
| 端口 | 7240 cmd / 8240 video / 9240 media / 9241 tts / 9242 vr / 9340 touch / 9440 data |
| 控制帧 | 8B `[u16 len][u16 rsv][u32 type]`；数据帧 12B `[u32 len][u32 ts][u32 type]` |
| 协议版本 | 98305 payload `08 03 10 00`（3.0） |
| 媒体格式 | 0x30001 INIT / 0x30002 START / 0x30003 STOP / 0x30006 NORMAL |
| TTS | 0x40001 INIT / 0x40002 START / 0x40003 PCM（1280B 实测） |
| 音频输出包 | 1408B = 352 帧 stereo 16bit；48k=7.33ms、44.1k=7.98ms；预灌 8 包≈59ms（原版 prime 80ms） |
| TTS duck | 媒体 45%（旧）/50%（新 ramp）；TTS 90%；TTS 活跃窗口 400ms |
| 触摸 ID | 425985/425986/425987/425988（0x68001/2/3/4）；9340 用 8B 控制帧 |
| 视频 keyframe | Annex-B 首 NAL type 5(IDR)/7(SPS) |
| 0x10030 | 字段 1 action / 2 nextTurn / 3 road / 4 total / 5 remain / 6 icon |
| 0x5200（本车） | 24B，params `0:2B,0:2B,1:0B,2:0B,3:0B`；组件 1=Cluster、2=CardView |
| 0x5201 示例 | 77B hex 见 §5.3；maneuverState 必须 **u64** |
| 0x5202 示例 | 30B hex 见 §5.4；字段 8 DrivingSide=0（右行） |
| 0x5000 订阅（本车） | group0=[0,1,4,6,7,8,9,10,12,16,18,21,22,23,24,26,27]（含 26 artwork） |
| 车机显示 | 1972×520 type110 safe 1408×476@(278,44)；2194×700 type111 safe 1162×486@(680,82) |
| 触摸窗格（本车日志） | `safe=376,44 1413×476` / `1452×476`（随 compositor 层选择变化） |
| d9 字段 | hyL/hyR/hyB/hyT（GL 左下原点窗格）+ hyRad（原版 16px；本仓 17px） |
| 对端会话表（本车） | `sessions=1/k0/v2, 2/k1/v2`（k1=file-transfer=2，**不是 12**） |
