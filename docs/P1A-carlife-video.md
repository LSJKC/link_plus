# P1A：CarLife H.264 直出到 VideoSender Surface

> 分支：`carlife-port`  
> 阶段：P0 基线 + P1A（视频直出）  
> 前置阅读：[CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md)、[P0-baseline.md](P0-baseline.md)、[no-adb-model.md](no-adb-model.md)

## 1. 本阶段目标

在 link_plus 源码基线上，把手机本机 **CarLife+ 引擎**的 H.264 码流作为**唯一画面来源**：

```text
本机 CarLife+（无线连接态，监听 7240/8240/…）
   │ 127.0.0.1 TCP：握手 98305/98386/98311/98313，视频 8240（12B 帧头 + Annex-B H.264）
   ▼
CarLifeHuClient（移植自 CarPToCarL，MIT）
   ▼ onVideoFrame(data, keyFrame, ptsMs)
CarLifeVideoPipeline（SPS/PPS 维护、裸 IDR 补 CSD、真实尺寸解析）
   ▼
CarLifeVideoDecoder（MediaCodec AVC 解码，输出 Surface）
   ▼ 解码帧直接渲染到
VideoSender 编码器输入 Surface（link_plus 原有 H.264 编码 → AirPlay 屏幕流）
   ▼
无线 CarPlay 车机
```

本阶段**不做**壁纸/圆角/letterbox 合成（P1B）、音频（P2）、触摸（P3）、导航（P4）。

> 重要边界：本仓库已按用户要求**移除 link_plus 内置无线 ADB 激活**。
> 画面、音频、触摸全部走 CarLife 自己的 TCP 通道，不需要开发者选项/无线调试/Shizuku/root，
> 也不再使用「隐形屏」虚拟屏桌面作为内容源。详见 [no-adb-model.md](no-adb-model.md)。

## 2. 代码位置

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeProtocol.kt` | CarLife 消息 ID / TLV / protobuf 编码、端口常量（7240/8240/9240/9241/9340/9440） |
| `.../carlife/H264Sps.kt` | Annex-B SPS 解析（真实编码宽高、profile/level） |
| `.../carlife/CarLifeHuClient.kt` | 本机车机端（HU）客户端：7 端口连接、握手时序、视频/音频收流、触摸回传、看门狗 |
| `.../carlife/CarLifeVideoPipeline.kt` | 视频帧预处理：NAL 切分、SPS/PPS 缓存、裸 IDR 补 CSD、真实尺寸回调 |
| `.../carlife/CarLifeVideoDecoder.kt` | MediaCodec AVC 解码器，解码帧直接渲染到编码器输入 Surface |
| `.../capture/CarLifeScreenSource.kt` | link_plus `ScreenSource` 实现：attach 时启动 CarLife 连接/解码线程，detach 时收尾 |
| `.../compat/*` | 无 ADB 化后的遗留占位（不执行任何 ADB/shell 操作） |

集成点：`SessionController` 在收到 AirPlay 屏幕流 `SETUP` 并 `VideoSender.start` 拿到编码器 Surface 后，
创建 `CarLifeScreenSource` 并 `attach(surface, profile.width, profile.height, …)`。
`ScreenSource` 接口保持不变，`HiddenDisplaySource`（shell 虚拟屏）已删除。

## 3. 关键协议与时序（沿用旧工程/参考实现实测结论）

1. 连接：从 `127.0.0.1` 连接 7240（控制）、8240（视频）、9240（媒体）、9241（TTS）、9340（触摸）等端口；
   CarLife+ 必须由用户手动打开并进入「无线连接」态，否则端口不监听 → `CarLifeScreenSource` 后台每 2s 重试。
2. 握手：`98305` 协议版本 → `65538` match → `98343` 统计 → `98307` HU_INFO → `98386` 特征配置 →
   `98311` 视频初始化（宽高/fps/safeArea）→ `98313` 启动推流；之后每 1s 心跳 `131074`。
3. 98311/98386 顺序：默认**先发 98386 并等引擎确认（65570），再发第一条 98311**，同尺寸 5s 去重；
   首帧后 2.5s 做一次「settle reinit」，让引擎在已有编码器上重建以拿到真实申请尺寸。
4. 视频：8240 每包 `[4B len][4B timestamp ms][4B type]` + payload（Annex-B H.264）。
   真实编码尺寸以 SPS 为准（引擎有白名单档位，申请值/确认值都可能与 SPS 不同）。
5. 视频初始化申请的宽高/fps 来自 `VideoSender` 实际生效的 `VideoProfile`（车机分辨率 × 缩放偏好），
   不硬编码。CarLife 引擎可能降档到 1280×720；P1A 直接解码渲染，尺寸不一致时的拉伸/兼容问题由 P1B 合成器解决。

## 4. 日志标签（验收 grep 用）

| 关键字 | 含义 |
| --- | --- |
| `CARLIFE: carlife connected` | 已连上本机 CarLife 引擎并开始握手 |
| `CARLIFE-VIDEO: decoder out` | 解码器开始输出帧（含每 150 帧统计） |
| `CARLIFE-VIDEO: fit` | 码流尺寸 vs 编码器尺寸关系（P1A 直出拉伸风险提示） |
| `MODE: CarLife 唯一内容源` | 本轮会话画面来源已切换为 CarLife |
| `CARLIFE: ` 前缀 | 引擎连接/重连/握手/看门狗诊断 |
| `CARLIFE-VIDEO: ` 前缀 | 解码器/SPS/尺寸诊断 |

## 5. 验收标准

### 已可验证（本机，无设备）

- [x] `.\gradlew.bat :app:assembleBuick` 构建成功（见 [P0-baseline.md](P0-baseline.md)）
- [x] 单元测试：`CarLifeProtocol`、`H264Sps`、`CarLifeVideoPipeline` 纯 JVM 用例
- [x] `FakeCarLifeEngineTest`（JVM 无设备集成测试）：假 CarLife 引擎真实 socket 握手 +
  8240 收到合成 SPS/PPS/IDR 帧，验证生产 `CarLifeHuClient` 回调（详见 §7）
- [x] 现场验收工具链就绪：`scripts/p1a-field-check.ps1` + `scripts/verify-carlife-log.ps1`
  （自检通过，见 §6）
- [x] 无 smali/闭源 APK 改动、无 root/Shizuku、无内置无线 ADB 激活
- [x] 日志标记齐全（静态检查）

### 待实车/真机验证（当前无 adb 设备，PENDING）

1. 安装 `app-buick.apk`，手机打开 CarLife+ 并进入「无线连接」。
2. 连接车机：日志应依次出现 `MODE: CarLife 唯一内容源` → `CARLIFE: carlife connected` →
   `CARLIFE-VIDEO: decoder out` → `VIDEO: 帧 #… 已发送`。
3. 车机应出现 CarLife 画面；操作 CarLife 内切歌/导航，画面应刷新。
4. 断开重连：`CARLIFE: … 重连` 后画面恢复；无解码器泄漏（30–60 分钟）。
5. 日志导出：包含 carlife / video / airplay 标签。

结果记录到 [P0-baseline.md](P0-baseline.md) 的待回填表或 `docs/logs/`。

> 现场验收用 `scripts/p1a-field-check.ps1` 一条命令完成安装/启动/抓日志/标记校验，
> 见下节「现场验收工具（P1A）」。

## 6. 现场验收工具（P1A）

手机 + 车机就绪后，现场验收只需 **一条命令 + 一次自动校验**。工具在 `scripts/`，
抓取的日志落在 `field-logs/`（已加入 `.gitignore`，不随仓库分发）。

### 1. 前置条件

- 手机用 USB 线连接**开发机**并授权 USB 调试——仅用于**宿主电脑侧** `adb install` / `adb logcat`；
  **本应用不内置无线 ADB 激活**，无需开发者选项/无线调试/Shizuku/root（见 [no-adb-model.md](no-adb-model.md)）。
- 开发机有 adb：`F:\carplay\tools\android-sdk\platform-tools\adb.exe`，或
  `$env:ANDROID_HOME\platform-tools\adb.exe`，或 PATH 上的 `adb`。
- APK 已构建：`app\build\outputs\apk\buick\app-buick.apk`（`$env:JAVA_HOME='F:\carplay\tools\jdk17\jdk-17.0.20.1'; .\gradlew.bat :app:assembleBuick`）。

### 2. 一键现场验收

```powershell
cd F:\carplay\_newproj\linkplus_carlife

# 安装/覆盖安装 APK → 启动应用 → 抓 logcat → 标记校验（默认等设备 120s、抓 120s）
pwsh -NoProfile -File scripts\p1a-field-check.ps1

# 常用变体
pwsh -NoProfile -File scripts\p1a-field-check.ps1 -DurationSec 180 -WaitDeviceSec 30
pwsh -NoProfile -File scripts\p1a-field-check.ps1 -NoInstall
pwsh -NoProfile -File scripts\p1a-field-check.ps1 -Apk D:\build\app-buick.apk -LogDir D:\field-logs
```

脚本依次：定位 adb → 等待已授权设备（超时给出排查步骤并以非零码退出）→ `adb install -r` →
`adb logcat -c` → `am start -W -n io.github.shaonialife.buicklinkplus.buick/io.github.shaonialife.buicklinkplus.MainActivity` →
打印操作步骤 → 抓 `adb logcat -v time -s CarPlaySender:V *:S` 到
`field-logs/p1a-<时间戳>.log`（实时回显）→ 自动调用校验器并打印摘要。

抓满 `-DurationSec` 秒（默认 120；`0` = 一直抓到 Ctrl+C）后停止；按 Ctrl+C 也会停止抓取并立即校验已抓内容。

退出码：`0` PASS；`1` 校验 FAIL/运行错误；`2` 无设备或 adb 不可用；`3` 多设备/APK 缺失；
`4` 安装失败；`5` 启动失败；`6` logcat 启动失败。

### 3. 现场操作顺序（须在抓日志窗口内完成）

1. 手机上打开「CarLife+」，点「无线连接」；
2. 回到本应用（Buick Link＋），按正常流程连接车机；
3. 保持 1–2 分钟：车机出现 CarLife 画面，操作 CarLife 内切歌/导航画面应刷新；期间不要锁屏/退出应用。

### 4. 期望日志顺序（`CarPlaySender` 标签）

```text
MODE: CarLife 唯一内容源 已启动（等待 CarLife+ 进入无线连接）
CARLIFE: carlife connected 127.0.0.1（握手成功，等待视频帧）
CARLIFE-VIDEO: 码流真实尺寸 1280x720（编码器 1920x1080）
CARLIFE-VIDEO: 解码器已启动 stream=1280x720 encoder=1920x1080 …
CARLIFE-VIDEO: fit stream=1280x720 encoder=1920x1080 scale=…（尺寸不一致时的拉伸提示）
CARLIFE-VIDEO: decoder out #1 pts=…（之后每 150 帧一行）
VIDEO: 帧 #1 … 已发送（之后每帧一行）
```

必需标记（区分大小写子串，全部出现才 PASS）：

| 必需标记 | 含义 |
| --- | --- |
| `MODE: CarLife` | 本轮会话画面来源已切换为 CarLife |
| `CARLIFE: carlife connected` | 已连上本机 CarLife+ 引擎并握手成功 |
| `CARLIFE-VIDEO: decoder out` | MediaCodec 解码器开始输出帧 |
| `VIDEO: 帧` | 编码帧已写入 AirPlay 车机流 |

建议标记（仅提示，不影响判定）：`CARLIFE-VIDEO: fit`、`CARLIFE: …重连/断开`、`AIRPLAY: `。

失败标记（命中即 FAIL）：`VIDEO: 编码器取帧异常`、`CARLIFE-VIDEO: 解码器错误`、
`解码线程异常退出`、`重建解码器仍失败`。

### 5. 只校验已有日志 / 工具自检

```powershell
# 校验任意一份抓取文件（逐项表格 + 行号；PASS 退出 0 / FAIL 退出 1）
pwsh -NoProfile -File scripts\verify-carlife-log.ps1 -Path field-logs\p1a-20261106-210000.log

# 机器可读 JSON（含逐项明细、行号、退出码）
pwsh -NoProfile -File scripts\verify-carlife-log.ps1 -Path field-logs\p1a-20261106-210000.log -Json

# 工具自检：合成「好日志 / 缺必需标记 / 含失败标记」三份日志，应分别 PASS / FAIL / FAIL
pwsh -NoProfile -File scripts\verify-carlife-log.ps1 -SelfTest
```

`-Json` 顶层字段：`Path`、`LineCount`、`Result`（PASS/FAIL）、`ExitCode`、`Required[]`、
`Advisory[]`、`Failure[]`、`Summary`。

### 6. 限制

- logcat 只抓 `CarPlaySender` 标签，且只覆盖脚本启动之后的事件；先操作再启动会漏标记（脚本启动时会 `logcat -c` 清空缓冲）。
- 手机厂商 ROM 可能要求「USB 安装」授权或解锁屏幕；`am start` 失败时可按提示手动打开应用后用 `-NoInstall` 重跑。
- 校验器只证明「应用侧日志标记齐全」（视频链路已打通）；车机画面是否真的正常仍需人工确认。
  尺寸不一致/拉伸风险看 `CARLIFE-VIDEO: fit`，由 P1B 合成器解决。
- 无设备/无车机时无法自动验证安装、启动、抓取这三段路径；请先跑 `-SelfTest` 与
  `pwsh -NoProfile -File scripts\p1a-field-check.ps1 -WaitDeviceSec 2`（应给出明确无设备提示并非零退出）确认工具本身正常。

## 7. 本轮验证记录（2026-11-06，本机无设备）

环境：JDK 17.0.20.1（`F:\carplay\tools\jdk17\jdk-17.0.20.1`），Android SDK 34 / Build Tools 35.0.0 / NDK 27.0.12077973，
分支 `carlife-port`，版本 `0.1.0 (153)`。

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 代码格式 | `.\gradlew.bat spotlessCheck` | BUILD SUCCESSFUL（ktlint 1.3.1，全部 Kotlin） |
| 单元测试 | `.\gradlew.bat :app:testBuickUnitTest` | **122 个测试类 / 632 个用例**，0 failure / 0 error / 0 skipped；其中 CarLife 相关 40 项全通过 |
| 构建 | `.\gradlew.bat :app:assembleBuick` | BUILD SUCCESSFUL（含 lintVital） |
| Lint | `.\gradlew.bat :app:lintBuick :app:lintDebug` | BUILD SUCCESSFUL（warningsAsErrors 全绿） |
| 产物 | `app/build/outputs/apk/buick/app-buick.apk` | 26,894,933 B（版本 0.1.0/153；debug/buick 签名，重新构建时 hash 会变化，以当次构建输出为准） |
| 实车/真机 | — | **PENDING**：`adb devices` 无设备，安装/连接/画面/音频/触摸验收待接手机+车机后执行 |

CarLife 相关单测明细：`CarLifeProtocolTouchTest` 4、`CarLifeCarGpsTest` 6、`InitOrderTest` 3、
`ConnectTypeProbeTest` 3、`H264SpsTest` 8、`CarLifeVideoPipelineTest` 15、`FakeCarLifeEngineTest` 1；
另有 `DeviceIdentityTest` 7（蓝牙 MAC 手填格式/占位值回归）。

`FakeCarLifeEngineTest`（JVM 无设备集成测试）证据：假 CarLife 引擎在 127.0.0.1 监听
7240/8240/9340/9240/9241/9242/9440，与生产 `CarLifeHuClient` 走真实 socket；命令序列
`98305 → 98343 → 98307 → 98344 → 98386 → 98311 → 98313`，98311 申请 `1280x720@20`、65544 原样确认；
8240 收到 IDR（`keyFrame=true, pts=1234567`）与 P 帧（`keyFrame=false, pts=1234607`）两帧，
SPS 解析 `onStreamSize(1280,720)`、帧负载与假引擎写入逐字节一致；`client.stop()` 后 `isRunning=false`。
覆盖「引擎连接 + 码流接收」到 `CarLifeVideoDecoder` 之前的下层路径；MediaCodec/Surface 解码与车机侧
AirPlay 链路仍需真机验收。

### 本轮诊断加固（2026-11-06 追加）

为让无设备/现场的日志可判读并降低误判成本：

1. **端口失败静默**：`CarLifeHuClient(quietPortFailures = true)` 时不再每次重试打 7 条端口连接失败；
   仅保留 `控制端口未连通` 摘要。
2. **等待提示节流**：`CARLIFE: 等待 CarLife+ 无线连接（127.0.0.1:7240）第 N 次重试…`
   首次立即、之后最多 1 条/10s。
3. **无帧看门狗**：已握手但 ≥5s 无视频帧时打
   `CARLIFE: 已握手但 Ns 无视频帧（…CSD=已就绪|缺失，码流=WxH|未知）`（≤1 条/5s）。
4. **解码器关键帧种子**：`CarLifeVideoPipeline.lastKeyFrame()` 缓存最近一个已补 CSD 的关键帧
   （≤4MB），重建解码器时立即灌入一帧，缩短重建/分辨率变化后的黑屏窗口。
5. **会话诊断**：`SessionController.diagnosticSnapshot()` 与 `SESSION-END` 行新增内容源详情
   （引擎连接、解码帧数、码流尺寸、最近错误）。

## 8. 现场排障速查

现场验收时按日志定位；所有 `CarPlaySender` 标签日志可用 `scripts/p1a-field-check.ps1` 抓取并用
`scripts/verify-carlife-log.ps1` 自动判定。

| 现象 | 可能原因 | 排查 / 处理 |
| --- | --- | --- |
| 车机黑屏，日志一直没有 `CARLIFE: carlife connected` | CarLife+ 未打开或没进「无线连接」，127.0.0.1:7240 未监听 | 手机打开 CarLife+ → 点「无线连接」；应用会每 10s 打一次 `CARLIFE: 等待 CarLife+ 无线连接…`，打开后数秒内自动连上 |
| 有 `carlife connected`，但 >5s 没有 `CARLIFE-VIDEO: 码流真实尺寸` / `decoder out` | 引擎握上手但没推流（98313 未生效 / 编码器未初始化 / 申请分辨率被拒） | 看 `CARLIFE: 已握手但 Ns 无视频帧（…CSD=…，码流=…）`、`协商编码参数`、`客户端 SPS 实测`；重开 CarLife+ 的「无线连接」；核对 65544 确认尺寸 |
| 有 `decoder out`，但没有 `VIDEO: 帧` 或车机仍黑 | 编码器/AirPlay 段问题；解码器输出 Surface 与编码器 profile 尺寸不一致导致编码器无输出 | 查 `CARLIFE-VIDEO: fit stream=… encoder=…` 与 `VIDEO: 编码器取帧异常`；P1B 合成器（contain/GL）解决 |
| 画面被拉伸/变形 | 码流 SPS 尺寸 ≠ 编码器 profile（车机分辨率常非 16:9） | 同上；`fit` 行的 scale 值即拉伸倍率；P1A 接受，P1B 修正 |
| 车机不邀请 / 连不上车机 | 本机蓝牙 MAC 是占位值（Honor/MagicOS 等脱敏机型） | 手机「设置 → 连接与设备 → 本机蓝牙地址（可选手填）」填入真实地址；日志看 `MAC:` 与 ctrl 广播 `id=` |
| 断开后重连不恢复 | CarLife 引擎被系统杀掉/退出无线连接态 | 重新打开 CarLife+ 无线连接；应用每 2s 自动重试（日志 `2s 后重试`） |
| 日志被端口连接失败刷屏 | 旧实现每次重试打 7 条端口失败 | 本版本对端口失败静默、只打 10s 节流的等待行 + 5s 无帧看门狗 |

> 说明：CarLife 握手/建流需要数秒，期间车机端编码器可能还没有首帧；现场请**先打开 CarLife+ 无线连接，再连接车机**。

## 9. 风险与回滚

| 风险 | 处理 |
| --- | --- |
| 解码器输出 Surface 与编码器输入 Surface 尺寸不一致导致绿屏/拉伸/首帧失败 | P1A 记录 `CARLIFE-VIDEO: fit`；P1B 用 GL 合成器 contain + 圆角解决 |
| CarLife 引擎白名单降档（申请尺寸 ≠ SPS 尺寸） | 以 SPS 为准配置解码器；触摸（P3）也按 SPS 坐标系映射 |
| 引擎未启动/未进入无线连接 | `CarLifeScreenSource` 每 2s 重试并打日志；界面由后续阶段补提示 |
| 直接解码→编码 Surface 在部分 ROM 不支持 | 回退方案：解码到 SurfaceTexture + GL 合成到编码器 Surface（P1B 实现） |
| 首帧延迟：CarLife 握手/建流需数秒，期间编码器无输出，个别车机可能等流超时 | 现场先开 CarLife+ 再连车机；P1A 后续优化：连接前 prewarm CarLife 客户端 + GOP/关键帧缓存，attach 后立即灌帧（与旧工程 CarLifeBridge.prewarm 同思路） |
| CarLife 没起来时车机长时间黑屏且无提示 | 日志已加 10s 等待提示 + 5s 无帧看门狗；后续阶段可加手机端状态卡 |
| 本阶段无回滚开关 | 画面唯一来源即 CarLife；旧「隐形屏」路径已按用户要求随内置 ADB 一并移除，无法回退到 shell 桌面 |

## 10. 后续阶段

- **P1B**：`CarLifeComposer`（EGL + OES 纹理 + 壁纸 + contain + 圆角），解决尺寸不一致/拉伸。
- **P2**：CarLife 9240/9241 PCM → `AudioSender.feedExternalPcm`（48k stereo / 1408B / TTS ring）。
- **P3**：车机触摸 → `CarLifeTouchSink` → CarLife 9340；按 P1B pane 映射坐标。
- **P4**：0x10030 → iAP2 0x5201/0x5202 导航上行；设置/日志导出。

## 11. 许可

CarLife 协议/客户端/视频处理移植自 [CarPToCarL](https://github.com/jjuugk/CarPToCarL)（MIT，Copyright (c) 2026 lizhihua），
归属与修改说明见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)。
