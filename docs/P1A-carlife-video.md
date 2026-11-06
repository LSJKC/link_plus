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

## 9. 本轮验证记录（2026-11-06，本机无设备）

环境：JDK 17.0.20.1（`F:\carplay\tools\jdk17\jdk-17.0.20.1`），Android SDK 34 / Build Tools 35.0.0 / NDK 27.0.12077973，
分支 `carlife-port`，版本 `0.1.0 (153)`。

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 代码格式 | `.\gradlew.bat spotlessCheck` | BUILD SUCCESSFUL（ktlint 1.3.1，全部 Kotlin） |
| 单元测试 | `.\gradlew.bat :app:testBuickUnitTest` | 120 个测试类 / 618 个用例，0 failure / 0 error；其中 CarLife 相关 33 项全通过 |
| 构建 | `.\gradlew.bat :app:assembleBuick` | BUILD SUCCESSFUL（1m28s，含 lintVital） |
| Lint | `.\gradlew.bat :app:lintBuick :app:lintDebug` | BUILD SUCCESSFUL（warningsAsErrors 全绿） |
| 产物 | `app/build/outputs/apk/buick/app-buick.apk` | 26,894,933 B，SHA-256 `EB68FF5B6AA5B0B68681C205D8383A1C06079B8B4CB18DFE9C7B63CC17198F3E`；versionCode=153 / versionName=0.1.0（debug/buick 签名，重新构建时 hash 会变化） |
| 实车/真机 | — | **PENDING**：`adb devices` 无设备，安装/连接/画面/音频/触摸验收待接手机+车机后执行 |

CarLife 相关单测明细：`CarLifeProtocolTouchTest` 4、`CarLifeCarGpsTest` 6、`InitOrderTest` 3、
`ConnectTypeProbeTest` 3、`H264SpsTest` 8、`CarLifeVideoPipelineTest` 9。

## 6. 风险与回滚

| 风险 | 处理 |
| --- | --- |
| 解码器输出 Surface 与编码器输入 Surface 尺寸不一致导致绿屏/拉伸/首帧失败 | P1A 记录 `CARLIFE-VIDEO: fit`；P1B 用 GL 合成器 contain + 圆角解决 |
| CarLife 引擎白名单降档（申请尺寸 ≠ SPS 尺寸） | 以 SPS 为准配置解码器；触摸（P3）也按 SPS 坐标系映射 |
| 引擎未启动/未进入无线连接 | `CarLifeScreenSource` 每 2s 重试并打日志；界面由后续阶段补提示 |
| 直接解码→编码 Surface 在部分 ROM 不支持 | 回退方案：解码到 SurfaceTexture + GL 合成到编码器 Surface（P1B 实现） |
| 本阶段无回滚开关 | 画面唯一来源即 CarLife；旧「隐形屏」路径已按用户要求随内置 ADB 一并移除，无法回退到 shell 桌面 |

## 7. 后续阶段

- **P1B**：`CarLifeComposer`（EGL + OES 纹理 + 壁纸 + contain + 圆角），解决尺寸不一致/拉伸。
- **P2**：CarLife 9240/9241 PCM → `AudioSender.feedExternalPcm`（48k stereo / 1408B / TTS ring）。
- **P3**：车机触摸 → `CarLifeTouchSink` → CarLife 9340；按 P1B pane 映射坐标。
- **P4**：0x10030 → iAP2 0x5201/0x5202 导航上行；设置/日志导出。

## 8. 许可

CarLife 协议/客户端/视频处理移植自 [CarPToCarL](https://github.com/jjuugk/CarPToCarL)（MIT，Copyright (c) 2026 lizhihua），
归属与修改说明见 [third-party/CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)。
