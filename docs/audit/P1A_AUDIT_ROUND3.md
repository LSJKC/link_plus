# P1A 第三轮对抗性审计（CarLife H.264 → 编码器 Surface 直出）

> **历史记录**：本文是 P1A 阶段的审计证据，保留供追溯；当前口径为“全面移植 + 实车验证优先、不再逐阶段自测/验收”，功能状态见 [../PORT_MATRIX.md](../PORT_MATRIX.md)。

> 审计对象：`F:\carplay\_newproj\linkplus_carlife`，分支 `carlife-port`。
>
> **审计快照：`df3e852`**（任务给定的是 `2beb13f`；审计期间上游合入了
> `df3e852 chore(carlife): harden P1A field diagnostics and decoder recovery`，
> 工作区干净。本文行号全部以 `df3e852` 为准；若后续还有源码编辑，行号可能再次漂移）。
>
> 审计方式：只读。未修改生产代码、未运行 gradle、未 git commit；唯一写入是本文件
> （`docs/audit/` 目录在本轮新建）。构建/测试数字来自提交说明与 `docs/P1A-carlife-video.md`，
> 不是本轮的复跑结果。
>
> **重要：审计结束前（2026-11-06 21:39）工作区又出现未提交的 hardening 修改**
> （`CarLifeScreenSource.kt` / `CarLifeVideoDecoder.kt` / `VideoSender.kt` + 两个未跟踪测试），
> 见 §0.1。本文同时保留 `df3e852` 基线和审计结束工作区的状态；**C0 与 §0.1 使用当前工作区
> 行号，C1~L1 的旧行号用于基线，引用前请按当前文件核对。**
>
> 已完整阅读：`capture/CarLifeScreenSource.kt`、`carlife/CarLifeVideoPipeline.kt`、
> `carlife/CarLifeVideoDecoder.kt`、`carlife/CarLifeHuClient.kt`、`video/VideoSender.kt`、
> `session/SessionController.kt` 的视频/ScreenSource 段、`capture/ScreenSource.kt`；
> 参考实现 `reference/CarPToCarL/.../CarLifeHijack.kt` 相关段、
> `F:\carplay\_newproj\hybrid63\inject\src\com\turtlelink\carlife\CarLifeVideoDecoder.java`、
> `F:\carplay\_newproj\hybrid63\inject\src\tl\CarLifeBridge.java`、
> `F:\carplay\_newproj\hybrid63\inject\src\tl\DecoderReplacementGate.java`。

---

## 结论摘要

**结论：P1A 的“链路接线”已经完整，正常路径上的日志标记都有确定产出；但 MediaCodec /
Surface 的生命周期目前被拆在三条线程（CarLife 视频读线程、codec 执行器、视频 drain 线程）
和多个回收入口（正常 retire、`onEnded`、start 失败、分辨率重建）里。按已提交基线
`df3e852` 是 2 Critical / 4 High / 5 Medium / 1 Low；审计结束前工作区出现未提交 hardening，
把 C1/H1/H4 在代码层面修掉、把 H3 的锁内 join 移走，但**异步 detach 让 C2 的
Surface 释放顺序重新失效，形成当前工作区唯一的 Critical（C0）**。建议先修 C0，再验证
H1/H4 的未提交修改并安排实车验收；Medium 在上车前合并修。**

**按审计结束工作区（含未提交修改）预计：Critical 1（C0）/ High 1（H2）/ Medium 6
（H3 降级、M1~M5）/ Low 1（L1）；另有 C1、H1、H4 三项“基线问题已在工作区修复，待构建/真机
验证”。** 基线 `df3e852` 的 12 项仍保留在下文，作为修复依据与回归核对清单。

最关键的结论：

1. **当前工作区的新 Critical（C0）：`CarLifeScreenSource.detach()` 改成异步 cleanup 后
   `source.release()` 立即返回，`SessionController.retireSession` 紧接着 `videoSender.stop()`
   释放编码器输入 Surface，而解码器的 `stopAndWait` 在另一个 daemon 线程里才执行。**
   VideoSender 已经把 `onEnded` 提前到 `stop()` 之前（方向正确），但源头释放是异步的，
   所以“先停解码器、再释放 Surface”的顺序在正常断开和车机主动结束两条路径上都不再成立。
2. **基线 C1（同一 Surface 双解码器）在工作区已用 `RetiringDecoderGate` + `isThreadAlive`
   修掉 in-session 路径**（`CarLifeScreenSource.kt:523-557, 782-812`）；但 surface 释放
   仍然不等旧线程退出，见 C0。
3. **基线 H1（有帧无 CSD/解码零输出无看门狗）在工作区已加 `NoDecodeWatchdog`（8s/15s）+
   `requestVideoReinit` + 可选解码器重建**（`CarLifeScreenSource.kt:278-316, 814-872`）；
   逻辑方向正确，但未构建、未跑新增测试、未真机验证。
4. **基线 H2（单 Surface 尺寸/格式不匹配）仍未解决。** NoDecodeWatchdog 只能发现“没有解码
   输出”并触发恢复，不能保证跨 codec 直出被目标 HAL 接受；大多数设备预期 fill 拉伸，少数
   会黑屏/报错。必须真机确认。
5. **基线 H3（SessionController 锁内 2.3s join）在工作区已大幅缓解**：detach 的慢路径移到
   daemon cleanup，主线程取消不再等 0.8+1.5s；但 `retireSession` 仍在全局锁内调
   `video.stop()`（`MediaCodec.stop()` 耗时不受控），`cancelConnecting` 仍从 UI 线程同步
   调 `@Synchronized disconnect`，因此降级为 Medium 残余。

**关于任务描述中“attach 在 SessionController 锁内 join 1.5s”的前提纠正：** 当前
`SessionController` 每个会话都 `new CarLifeScreenSource`（`SessionController.kt:2039-2044`），
因此 `attach()` 开头的 `detach()` 是空操作，**现在没有** 1.5s 锁内 join。真正的 2.3s
（0.8s decoder + 1.5s manager，见 H3）发生在 `retireSession()` 的 `source.release()` 里；
如果 P1B 复用 `ScreenSource` 或允许 re-attach，这个耗时就会转移进 `onSurfaceReady` 的
`synchronized(SessionController)`，必须在复用前修掉。

**关于最近的 no-ADB 重构：** 在本轮读到的 P1A 启动路径上没有发现会直接崩溃的 ADB 残留
（详见“已验证正常的点”第 9 条）。`VirtualLauncher` 在 P1A 里不再 attach；`Shell` /
`CarInputClient` 兼容层恒失败并被调用方降级；音频改为必须 MediaProjection，异常被
`startAudioChannel` 的 catch 接住。剩余的是“能力消失后的功能退化”，不是 P1A 启动崩溃。

## 0.1 审计结束前的工作区变更（必须一起看）

审计开始时 HEAD 是任务给定的 `2beb13f`；中途上游提交了 `df3e852`（诊断增强）。审计结束前
（2026-11-06 21:39）工作区又出现了**尚未提交**的 hardening（作者不是本轮审计）：

| 文件 | 未提交改动（摘要） | 哈希（审计时） |
| --- | --- | --- |
| `capture/CarLifeScreenSource.kt` | detach 异步 cleanup；`RetiringDecoderGate`；`NoDecodeWatchdog`；`framesReceived`；`retireDecoder/isThreadAlive` | `A8536E4C…A28627` |
| `carlife/CarLifeVideoDecoder.kt` | 新增 `isThreadAlive()`（stopAndWait 超时后 running=false 但线程仍活） | `89B8B913…400CE` |
| `video/VideoSender.kt` | `lifecycleLock`/`starting`/`endedNotified`；start 安装阶段检查 stopped；`releaseOrphan`；`onEnded` 提前到 `stop()` 之前 | `29A65E52…E666A` |
| `app/src/test/.../carlife/` | 新增未跟踪 `RetiringDecoderGateTest.kt`、`NoDecodeWatchdogTest.kt` | 未跟踪 |

**未构建、未跑测试、未真机**（任务禁止 gradle；且这些修改在审计结束时仍在进行）。下文
C1~L1 是 `df3e852` 的基线问题，**括号里的“工作区状态”表示这轮未提交修改是否已经处理**：

| 基线项 | 工作区状态（审计结束时） | 备注 |
| --- | --- | --- |
| C1（同 Surface 双解码器） | **代码已修** | `retireDecoder` + `RetiringDecoderGate` + `isThreadAlive` 已覆盖 in-session 重建；detach 仍见 C0 |
| C2（Surface 先于解码器释放） | **未解决，变成 C0** | VideoSender 的 `onEnded` 顺序已修，但 `detach` 异步化让 `source.release()` 立即返回，`video.stop()` 仍先释放 Surface |
| H1（有帧无 CSD/无解码输出） | **代码已修，待验证** | `NoDecodeWatchdog` 8s + 15s 重发 98311 + 可选解码器重建 |
| H2（单 Surface 尺寸/格式） | **未解决** | 直出兼容性只能真机确认；看门狗只能恢复不能保证 HAL 接受 |
| H3（锁内 2.3s join） | **缓解，降级 Medium** | join 移到 daemon cleanup；但 `retireSession` 仍在锁内 `video.stop()`，UI 取消仍同步 |
| H4（VideoSender start/stop 泄漏） | **代码已修，待验证** | `lifecycleLock` + 安装检查 + `releaseOrphan`；残余 late-start/回调锁外问题 |
| M1（旧客户端迟到帧） | **未修** | 仍无 client/pipeline generation；异步 detach 让旧 manager 更可能多活 |
| M2（lastKeyFrame 只回放 IDR + PTS 0） | **未修** | 新代码未改 seed 逻辑 |
| M3（keyframe 判定 3 字节/首 NAL） | **未修** | 仍是 4 字节、首 NAL 判定 |
| M4（HuClient start/stop 无互斥） | **未修** | 异步 detach 后 manager 不再被 join，重叠窗口更大 |
| M5（日志验收误判/刷屏） | **未修** | 可恢复 `解码器错误` 仍可能 FAIL；quietPortFailures 仍未压汇总 |
| L1（音频空转） | **未修** | 仍启动 9240/9241 |

**这是本轮最重要的状态判断：不要因为工作区已经出现 “gate/watchdog/lifecycleLock” 就认为
P1A 已可上车。当前最危险的是 C0——修 C1/C2 的同一个改动把 detach 变成异步，重新打开了
“Surface 先于解码器释放”的窗口，而且这次覆盖正常用户断开路径。**

---

## Critical

### C0.（当前工作区新增）异步 detach 让 `source.release()` 立即返回，`retireSession` 随即释放编码器 Surface——C2 的释放顺序重新失效

**位置（审计结束工作区）：**

- `app/src/main/java/io/github/shaonialife/buicklinkplus/capture/CarLifeScreenSource.kt:141-177`
  （`detach()` 只做 O(1) 停止/关 socket/`shutdownNow`，然后 **启动 daemon cleanup 线程并立即返回**）
- `CarLifeScreenSource.kt:185-210`（`cleanupDetached` 才在另一个线程里
  `decoder.stopAndWait(800)` + `manager.join(1500)`）
- `app/src/main/java/io/github/shaonialife/buicklinkplus/session/SessionController.kt:544-551`
  （`retiringSource.release()` 返回后**立刻** `retiringVideo.stop()`）
- `app/src/main/java/io/github/shaonialife/buicklinkplus/video/VideoSender.kt:359-367`
  （`onEnded` 已提前到 `stop()`，方向正确）
- `VideoSender.kt:259-284`（`stop()` → `releaseResourcesLocked()` 释放 encoder + inputSurface）

**问题：**

这轮修 C1/C2 时把 `CarLifeScreenSource.detach()` 改成了“快速返回 + daemon cleanup”。但
`SessionController.retireSession` 的调用顺序没有改：

```text
retireSession
  ├─ retiringSource.release()   // detach(): 只 spawn cleanup 线程，立即返回
  └─ retiringVideo.stop()       // 立刻 encoder.stop/release + inputSurface.release
                                 // cleanup 线程此刻可能还没开始 decoder.stopAndWait()
```

于是：

1. 正常用户断开/取消：`source.release()` 返回 → `video.stop()` 释放 Surface → cleanup 线程
   稍后才 `stopAndWait` 解码器。解码器在这段窗口里仍然 `configure` 在已释放的 Surface 上，
   并可能继续 `releaseOutputBuffer(render=true)`。
2. 车机主动结束：`VideoSender` 虽然改成先 `onEnded` 再 `stop()`，但 `onEnded` →
   `retireSession` → `source.release()` 同样是异步返回，`retireSession` 内紧接着的
   `video.stop()` 依旧先释放 Surface。
3. 时间窗口不是“微秒级”：cleanup 线程要等调度，`stopAndWait` 最多 800ms 才超时；正常断开
   时 `retireSession` 在主线程/锁内的 `video.stop()` 可能立刻执行。**窗口大小由线程调度
   决定，最坏接近 800ms**，且每次会话结束都会出现。
4. `detach()` 还调了 `executor.shutdownNow()` 但**不等待在途 codec 任务**，且 `cleanupDetached`
   捕获的是 detach 当时 `session.decoder` 的快照（可能为 null）。如果 `createDecoder` 正在
   `MediaCodec.configure/start`，cleanup 没有对象可停，`video.stop()` 已经释放 Surface——
   这是同一类“Surface 先于 codec 操作”的窗口，`RetiringDecoderGate` 管不到（它只防新解码器，
   不防 Surface 释放，也不等待在途创建任务）。

**影响：**

- 这正是基线 C2 想消除的“解码器向已释放 Surface 渲染”；现在从“车机主动结束才触发”变成
  “正常断开也触发”。
- 部分 ROM 上 `Surface.release()` 与仍挂着的 MediaCodec producer 并发会 native crash/永久
  codec 错误；至少会造成 `releaseOutputBuffer` 异常、解码线程卡死。
- 更糟的时序：解码线程可能卡在 `releaseOutputBuffer` 等编码器消费，cleanup 的
  `stopAndWait` 在等解码线程，`video.stop()` 的 `encoder.stop()` 在等输入 buffer 回收——
  三者可互相等待，表现为“断开时卡住一段时间/ANR”；而原来的同步 detach 至少是先停解码器。
- cleanup 线程的 `retiringGate` 只防止“同一 Surface 再建新解码器”，**不能阻止 Surface
  被释放**；这个 gate 对跨组件释放顺序无效。
- 因为 cleanup 是 daemon 且无人等待，`SessionController` 的 `SESSION-END`/`内容源已停止`
  日志顺序会倒挂；`cleanupDetached` 可能在**新会话已经 attach 之后**才打旧会话的停止日志，
  进一步误导现场排查。

**建议修复（按优先级）：**

1. 把 `source.release()` 改成可等待：返回 `Boolean`/`Future`/`CountDownLatch`（或新增
   `releaseAndAwait(timeoutMs)`），`SessionController` 在 `video.stop()` 之前 await 解码器
   真正退出；await 在 **SessionController 锁外**执行。
2. 或者保留异步 detach，但 `VideoSender.stop()` 增加 `beforeSurfaceRelease: (() -> Boolean)`
   回调，由 `SessionController` 传入“等待 source cleanup 完成”的 latch，只有 latch 完成才
   `inputSurface.release()`；超时就保留 Surface/报错，不要释放。
3. cleanup 线程不要每个 detach 新建：用单线程 executor + 可追踪的 `Future`，支持 await 和
   “新 attach 前先等旧 cleanup 完成”的 generation 约束。
4. 如果暂时不想改接口：至少让 `detach()` 同步 `stopAndWait(800)`，但这会把 800ms 重新带回
   `retireSession` 锁内（H3）；因此正确的解是“锁外两阶段 + latch”，不是二选一。

**与基线 C2 的关系：** C2 的 `VideoSender` 内部顺序已修（onEnded→stop），但源头 `release()`
异步化使 C2 的外部顺序重新失效。因此当前工作区按 C0 计；C2 保留作为修复依据，修复时两者
必须一起验收。

### C1. 解码器替换/收尾不检查 `stopAndWait` 结果，会在同一编码器 Surface 上出现双生产者

> **工作区状态（审计结束）：in-session 已修**——新增
> `CarLifeVideoDecoder.isThreadAlive()`、`CarLifeScreenSource.retireDecoder()`、
> `RetiringDecoderGate`（当前 `CarLifeScreenSource.kt:523-557, 782-812`）；重建/teardown
> 在旧线程未退出时挂门并拒绝新建。**但 detach 的 Surface 释放问题见 C0**，本条的基线
> 行号与描述保留作修复依据。

**位置：**

- `app/src/main/java/io/github/shaonialife/buicklinkplus/capture/CarLifeScreenSource.kt:370`
  （重建路径：`old?.stopAndWait(DECODER_STOP_TIMEOUT_MS)` 的返回值被丢弃，紧接着 `createDecoder`）
- `CarLifeScreenSource.kt:448`（断线 `teardownDecoder` 同样丢弃返回值）
- `CarLifeScreenSource.kt:152-156`（`detach` 只打一行日志，未保留未退出的引用）
- `CarLifeScreenSource.kt:148-156`（`executor.shutdownNow()` 之后不等待在途的
  `createDecoder`，直接继续释放 Surface）
- `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeVideoDecoder.kt:214-216, 241-257`
  （契约原文：超时返回 false，保留线程引用，禁止在同一 Surface 上再建新解码器）
- 放大因素：`app/src/main/java/io/github/shaonialife/buicklinkplus/video/VideoSender.kt:390-392`
  （`output.write(header); output.write(sealed); output.flush()`，Java `Socket` 没有写超时）

**问题：**

`CarLifeVideoDecoder.stopAndWait()` 在 800ms 内没等到线程退出时返回 `false`，并把
`decodeThread` 引用留在对象里。`CarLifeScreenSource` 对这个 false 的处理是：重建路径继续
`createDecoder()`、teardown 路径直接丢引用、detach 路径只打日志。于是：

- 旧解码线程仍然活着并继续向 `session.surface`（编码器输入 Surface）渲染；
- 新的 `CarLifeVideoDecoder` 被创建并 `configure(format, surface, ...)`，成为同一个
  Surface 的第二个生产者；
- 旧解码器不再被任何字段引用，无法再 join/stop，只能等它自己某次 codec 操作失败退出。

这不是纯理论：`VideoSender.drainEncoder` 在同一个线程里同步 `write + flush`。当车机 WiFi
拥塞/接收端不读时，drain 线程会长时间阻塞在 socket 写；编码器输出缓冲耗尽 → 编码器停止
消费输入 Surface → BufferQueue 满 → 解码器的 `releaseOutputBuffer(index, true)`（
`CarLifeVideoDecoder.kt:404`）或 `codec.stop()` 变慢，800ms 很容易被吃掉。也就是说，
**“网络卡一下”可以触发“同一 Surface 双解码器”**。

另外，`detach()` 里 `shutdownNow()` 只中断任务、不 await；如果 `createDecoder` 正在
`MediaCodec.configure/start`，`retireSession` 随后就会 `VideoSender.stop()` 释放这个
Surface（见 C2），出现“在已 release 的 Surface 上 configure/start”的窗口。

**影响：**

- 两个解码器交错向同一个编码器输入 Surface 送帧：时间戳/内容交错、花屏、编码器进错误态，
  在部分 ROM 上有 native crash 风险（BufferQueue 双生产者是未定义行为）。
- 旧解码器的回调仍然满足 `isCurrent(session)`（同一 Session 对象），会继续
  `deliveredFrames++`、继续打 `decoder out`/`解码器错误`，**掩盖**双生产者现场，验收日志
  看起来“还在出帧”。
- 解码器线程/MediaCodec 实例泄漏；如果 Surface 随后被释放，旧线程可能卡在
  `releaseOutputBuffer`/`stop`，形成不可回收的 codec 线程。
- 分辨率变化、码流错误重建、断线收尾三条路径都会经过这里；30–60 分钟多次会话/重连后，
  codec 资源可能被耗尽，后续会话直接起不来。

**建议修复：**

- 把 `stopAndWait` 的返回值当契约用：`val dead = old?.stopAndWait(2_000) ?: true`；`!dead`
  时**不要** `createDecoder`，把旧实例放进 `pendingDecoders`，由 codec 线程每 100–200ms 重试
  `isThreadAlive()/stopAndWait`，直到为空再建。直接移植 hybrid63 的
  `DecoderReplacementGate`（`F:\carplay\_newproj\hybrid63\inject\src\tl\DecoderReplacementGate.java:10-14, 68-89`）
  及其 `CarLifeBridge.start/ensureDecoder` 的用法（`CarLifeBridge.java:415-440, 651-661`）。
- `detach()` 同样不能丢引用；至少保留到 `retireSession` 完成，并保证 `VideoSender.stop()`
  之前旧解码线程已退出；退出不了就不要释放 Surface，宁可让会话多等/报错，也不要 native 竞态。
- 在 `VideoSender` 侧给发送加边界：单线程 writer + 有界队列 + 写超时/超时后主动关 socket，
  避免 drain 线程无限阻塞；至少要保证编码器输入 Surface 的消费者会持续回收 buffer。
- 把 `executor.shutdownNow()` 改成“先 epoch++ 禁止新任务，再 awaitTermination(1s) 或等
  在途 Future 完成”，然后再释放 Surface；`createDecoder` 内部在 `configure/start` 前后都
  检查 `active/current/epoch`。

### C2. 车机侧主动结束（`onEnded`）路径先释放编码器输入 Surface，再停解码器

> **工作区状态（审计结束）：VideoSender 内部顺序已修，但外部顺序仍失效，见 C0。**
> `VideoSender.drainEncoder` 现在先 `onEnded`、后 `stop()`（当前 `VideoSender.kt:359-367`），
> 方向正确；但 `onEnded` → `retireSession` → `CarLifeScreenSource.release()` 已经改成
> “spawn cleanup 后立即返回”，`retireSession` 紧接着 `video.stop()` 仍然先释放 Surface。
> 因此当前工作区不要按“C2 已修”验收，必须连同 C0 的 latch 一起修。

**位置：**

- `app/src/main/java/io/github/shaonialife/buicklinkplus/video/VideoSender.kt:269-274`：

  ```kotlin
  } finally {
      val unexpected = running.getAndSet(false)
      stop()                       // encoder.stop/release + inputSurface.release()
      if (unexpected) runCatching { onEnded?.invoke() }
  }
  ```

- `app/src/main/java/io/github/shaonialife/buicklinkplus/session/SessionController.kt:542-551`
  （`retireSession` 里的正确顺序：先 `retiringSource.release()`，再 `retiringVideo.stop()`）
- `CarLifeScreenSource.kt:128-172`（`release() = detach()`，内部才 `stopAndWait` 解码器）
- `CarLifeVideoDecoder.kt:344`（`created.configure(format, outputSurface, null, 0)`）
- `CarLifeVideoDecoder.kt:269-271`（每轮只检查一次 `outputSurface.isValid`）

**问题：**

正常“用户断开/控制通道结束”路径走 `retireSession`：先释放内容源，再 `video.stop()`，顺序
是对的。但“车机侧结束推流”（接收端关屏、TCP 断开、编码器异常）走的是
`drainEncoder` 的 finally：

1. `running=false`；
2. `stop()` 关闭 socket，`encoder.stop()/release()`，`inputSurface.release()`；
3. 才调用 `onEnded` → `synchronized(SessionController)` → `retireSession` →
   `CarLifeScreenSource.release()` → `decoder.stopAndWait()`。

在 2 和 3 之间，CarLife 解码器仍然 `configure` 在这个已释放的 Surface 上，并可能继续
`releaseOutputBuffer(index, true)`。如果此时 `SessionController` 的监视器被主线程
“取消连接”/其他 `@Synchronized` 调用占着（H3），窗口可以拉长到秒级。解码线程虽然每轮
检查 `outputSurface.isValid`，但检查发生在循环顶部，`releaseOutputBuffer` 已经在飞行中。

**影响：**

- 车机主动收摊是最常见的结束方式之一（车机锁屏/熄火/退出 CarPlay），也就是说这是**每次由
  车机侧主动结束都可能走到**的路径，不是边角。
- `Surface.release()` 与仍然挂着的 MediaCodec producer 并发，在部分厂商 ROM 上存在 native
  crash 风险（SIGSEGV/abort），在另一些 ROM 上可能是 codec 永久错误/黑屏；至少也会让解码
  线程 `releaseOutputBuffer` 抛异常，日志落在“解码错误”而不是“干净退出”。
- 与 C1 叠加：`stopAndWait` 更容易超时，然后 manager 重连路径又建新解码器。

**建议修复：**

- 不要在 finally 里先 `stop()`。应先通知 owner（`onEnded` 或新增 `onBeforeSurfaceRelease`），
  由 `SessionController` 完成 `source.release()` 后再调 `stop()`；`finally` 里只做兜底
  `stop()`（`stopped` CAS 已经保证幂等）。
- 或者把 Surface 释放收口到 `SessionController`：`VideoSender` 只关 socket/停 drain，不
  release `inputSurface`，由 `retireSession` 在 source 释放后显式释放。
- 外部 `stop()` 与 drain 线程并发时，先置 running/关 socket、join drain 线程，再
  `encoder.stop()/release()` 与 `inputSurface.release()`；MediaCodec 不是线程安全的，
  当前 `retireSession` 在另一个线程 `encoder.stop()` 时 drain 可能还在 codec 调用里。

---

## High

### H1. 有视频帧但没有 CSD / 解码器零输出时没有看门狗和恢复，黑屏可长期静默

> **工作区状态（审计结束）：代码已加恢复路径，待构建/真机验证。**
> 新 `NoDecodeWatchdog`（8s 无解码进度 + 15s 重复间隔）+ 管理线程里
> `client.requestVideoReinit()` + 必要时 `scheduleDecoderRecreate`
> （当前 `CarLifeScreenSource.kt:278-316, 814-872`）。逻辑上覆盖了“有帧但 CSD 缺失/解码器
> 静默”的盲区；仍要验证：98311 重发是否真能让引擎补 SPS、watchdog 在 20fps/8s 下是否误报、
> 以及 `deliveredFrames` 全局计数与旧解码器回调的交互。下面保留基线描述。

**位置：**

- `CarLifeScreenSource.kt:300-307`（`handleFrame` 只在 `pipeline.hasCsd == true` 时调度建
  解码器；否则什么都不做，连日志都没有）
- `CarLifeScreenSource.kt:239-259`（新增的 5s 无帧诊断用的是 `session.lastFrameAt`，而
  `handleFrame` 每帧都会刷新它，**只要 P 帧还在到就永不触发**）
- `CarLifeScreenSource.kt:266-267`（每次控制通道重连 `teardownDecoder + pipeline.reset()`，
  `pipeline.reset()` 会清空 CSD）
- `CarLifeVideoPipeline.kt:171-181`（`reset` 清空 SPS/PPS/lastKeyFrame/streamSize）
- `CarLifeHuClient.kt:1139-1172`（无帧看门狗只看“有没有收到帧”，不区分“帧解不出”；
  只有 `idle >= 10s` 才重发 98311）
- `CarLifeHuClient.kt:1376-1388, 1403-1415`（视频口重连后的 `restartEncoding` 走
  `sendEncoderInit()`，会被 5s 同尺寸去重静默跳过）
- `CarLifeVideoPipeline.kt:149-164`（裸 IDR 补 CSD 依赖 `keyFrame` 和缓存 CSD）

**问题：**

以下任一场景都会进入“帧在流、但 CSD/解码器没有”的状态：

1. 引擎冷启动先 accept 8240 再立刻 reset（参考实现注释里明确记录过这个行为）。第一个
   `SPS+PPS+IDR` 被 reset 掉；`videoSupervisor` 重连 8240 后调 `restartEncoding()`，但
   这条路径的 98311 可能还在 5s 去重窗口内（尤其握手后 1–2s 内就 reset），于是引擎继续
   推裸 P 帧/裸 IDR，而 SPS 只存在于已丢失的那个连接里。
2. 任何一次控制通道重连：`pipeline.reset()` 清空 CSD；如果引擎没有按预期重发 SPS，
   `hasCsd` 永远为 false。
3. 解码器已创建、但 `MediaCodec` 因 Surface 格式/尺寸不匹配（H2）或输入缓冲问题（见“其他
   观察”）一帧都不输出：`deliveredFrames` 不增长，但视频帧仍在到，5s 诊断不触发。

此时日志里只有 `CARLIFE: carlife connected`、`HANDOFF`/`实测帧率 …fps`，**没有**
`CARLIFE-VIDEO: 码流真实尺寸`、没有 `解码器已启动`、没有 `decoder out`，也没有任何错误；
现场只能看到“车机黑屏 + 日志正常”。这正好是验收脚本最需要区分的场景。

**影响：**

- 当前会话内黑屏无法自愈，只能靠用户重开 CarLife+ 或重新建立会话；如果是首次上车，会被
  误判成 AirPlay/编码器问题，实际上 CarLife 层已经断链/缺 CSD。
- 现场日志没有可执行线索；`verify-carlife-log.ps1` 只会报“缺 decoder out”，无法指出
  是 CSD 缺失还是解码器没输出。
- 在 30–60 分钟里，引擎重连/编码器重建都可能踩到，概率不低。

**建议修复：**

- 在 `Session` 里加 `framesReceived`、`lastDecodedAt` 两个计数，并提供统一看门狗：
  - `framesReceived > 0 && !hasCsd` 连续 5s → 打
    `CARLIFE: 已握手且收到 N 帧但 CSD 缺失（sps/pps/stream=…），请求引擎重建编码器`，
    并调用 `client.requestVideoReinit()`（或内部强制 98311，绕过去重）。
  - `hasCsd && framesReceived 增长 && deliveredFrames 5s 不增长` → 打
    `CARLIFE-VIDEO: 解码器无输出（codec=… surface=…）`，重建一次解码器；仍无输出则
    `requestVideoReinit()`。
- `restartEncoding()` / 视频口重连路径改用 `sendEncoderInit(force = true)`（或重连时清
  `lastEncInitSig/lastEncInitAt`），否则注释里“去重不会拦视频口重连”的假设是错的。
- CSD 不应只在内存里活一代客户端：把最近一次 SPS/PPS（以及 lastKeyFrame）保存在
  `CarLifeScreenSource` 层，跨 `CarLifeHuClient` 重连复用；参考实现是保留 SPS/PPS 并主动
  `requestVideoReinit`（`reference/.../CarLifeHijack.kt:919-966, 564-608`），hybrid63 在
  解码器无输出时还会 `requestKeyFrame`（`CarLifeBridge.java:576-582`）。
- 新 5s 诊断改用“帧数 + CSD + deliveredFrames”三元组，不要只看 `lastFrameAt`。

### H2. 单 Surface 直出的尺寸/格式兼容性未验证，且没有首帧兜底（预期 fill 拉伸，最坏黑屏）

> **工作区状态（审计结束）：未解决。** `NoDecodeWatchdog` 能发现“解码零输出”并尝试重建，
> 但不能保证编码器输入 Surface 接受跨 codec 的 SPS 尺寸/格式；如果 HAL 直接拒绝 producer，
> 重建/重发只会循环。仍需真机确认，并准备 P1B 的 GL/SurfaceTexture 回退。
> 另外这个 watchdog 目前没有“连续恢复失败就停止”的上限/指数退避：如果 Surface 永久不兼容，
> 会每 15s 重发 98311 + 重建解码器，形成周期性编码器重建/黑屏和日志刷屏；建议加
> “3 次仍无输出 → 停止重试并明确报错/提示 P1B”的熔断。

**位置：**

- `CarLifeScreenSource.kt:399-408`（解码器 `size = pipeline.streamSize ?: encoder size`，
  即按 SPS 配置）
- `VideoSender.kt:150-162`（编码器按 VideoProfile 配置，`createInputSurface()` 的 Surface
  以编码器尺寸/格式创建）
- `CarLifeVideoDecoder.kt:344`（解码器 `configure(format, outputSurface, ...)` 直接使用该
  Surface）
- `CarLifeScreenSource.kt:484-507`（`logFit` 只计算 scale，不改变任何行为）
- `CarLifeVideoDecoder.kt:375-408`（输出尺寸只用于日志/内部字段，不触发重建）

**问题与可能行为（按优先级）：**

1. **最常见：能出画面，但 fill 拉伸。** 解码输出被缩放到编码器输入 Surface 的 buffer 尺寸，
   CarLife 的 1280×720 / 1452×476 等比例被拉进车机画布（如 1412×654、1920×1080）。
   日志会显示 `CARLIFE-VIDEO: fit stream=… encoder=… scale=…`，画面变形；P3 触摸坐标
   还会再偏一次。这不是“黑屏”，但 P1A 文档把它当作可接受风险，验收时必须人工确认。
2. **少数 HAL：配置成功但零输出/花屏/绿屏。** 编码器输入 Surface 的 BufferQueue 尺寸和
   format/usage 由编码器侧固定，跨 codec 作为 producer 在部分厂商实现上不被接受（或
   buffer geometry 不匹配）。日志会是：`解码器已启动 stream=…` → `解码输出格式 …`（或没有）
   → 没有 `decoder out`；`VIDEO: keepAlive` 继续有，但 `VIDEO: 帧` 没有。当前没有任何代码
   把这种状态识别为错误。
3. **解码器输出 format 可能报 coded size（含 crop padding），而 SPS 解析的是 visible size。**
   `drainOutput` 收到 `INFO_OUTPUT_FORMAT_CHANGED` 后更新 `codecWidth/Height`，但
   `CarLifeScreenSource.onOutputFormatChanged` 只打日志，不比较/不重建；如果实际渲染尺寸
   与编码器 Surface 的 aspect 差得离谱，变形会更明显。

**影响：**

- 车机画面要么被拉伸（可接受但不达标），要么黑屏且日志“看起来都正常”；现场无法区分
  “引擎没给对尺寸”和“Surface 直出不被支持”。
- 如果黑屏，`decoder out` 缺失会让验收 FAIL，但没有修复动作；用户只能重连碰运气。

**建议修复：**

- 加“解码首帧”看门狗（与 H1 合并）：解码器 `start()` 后 N 秒（例如 3–5s）`deliveredFrames`
  仍为 0，则打 `CARLIFE-VIDEO: 解码器已启动但 Ns 无输出（stream=… encoder=…）`，先重建
  解码器，再 `requestVideoReinit()`；仍失败则明确建议 P1B 的 GL 合成路径。
- 把 `fit` 行升级为可判定信息：同时打印 `decoderConfigured=… encoderSurface=…` 和
  `onOutputFormatChanged` 的实际值；验收脚本把“有 fit 但没有 decoder out”单独归类。
- 真机确认前不要把 `解码器已启动`/`fit` 当作“画面已通”的证据；P1B 的
  SurfaceTexture/GL contain 方案是尺寸不匹配的最终解。
- 如果目标机型 Surface 直出不稳定，P1A 的安全回退是“解码到 ImageReader/SurfaceTexture +
  GL blit 到编码器 Surface”（P1B 已规划），不要继续在单 Surface 上硬重试。

### H3. `retireSession` 在 SessionController 全局监视器内做最长约 2.3s 的 join；UI 取消路径同步调用，主线程卡顿/ANR 风险

> **工作区状态（审计结束）：已缓解，降级为 Medium 残余。** `CarLifeScreenSource.detach()`
> 已把 0.8s decoder + 1.5s manager join 移到 daemon cleanup（当前 `CarLifeScreenSource.kt:
> 141-210`），主线程取消不再等 2.3s；但：
> - `retireSession` 仍在 `@Synchronized` 锁内调 `video.stop()`（`MediaCodec.stop()` 耗时不受控，
>   `SessionController.kt:551`）；
> - `cancelConnecting` 仍从 UI 线程同步调 `@Synchronized disconnect`（`:340-343, 500-506`）；
> - 异步 cleanup 引入 C0，且 `cleanupDetached` 可能在新会话 attach 后才打旧会话日志。
> 下面保留基线耗时分析，作为残余锁内阻塞的上界参考（2.3s 主要由 join 构成，已移走）。

**位置：**

- `app/src/main/java/io/github/shaonialife/buicklinkplus/session/SessionController.kt:529-551`
  （`@Synchronized fun retireSession`；`retiringSource.release()` 在锁内）
- `CarLifeScreenSource.kt:152-167`（`decoder.stopAndWait(800)` + `manager.join(1500)`）
- `SessionController.kt:340-343` + `500-506`（`cancelConnecting` 在 UI 线程调用
  `@Synchronized disconnect`，后者内联 `retireSession`）
- `SessionController.kt:1987-2000`（`onEnded` 在 video-drain 线程进入
  `synchronized(SessionController)`，再调 `retireSession`）
- `SessionController.kt:259-267` / `598-613`（同样 `@Synchronized` 的
  `onProjectionStopped`/`maybeStartAudioNow` 会在清理期间被阻塞）

**问题：**

`retireSession` 的临界区包含：

1. `retiringSource.release()` → `detach()`：
   - `client.stop()`（关闭最多 7 个 socket，通常快）；
   - `executor.shutdownNow()`（不等待在途 codec 任务）；
   - `decoder.stopAndWait(800)`；
   - `manager.join(1500)`；
2. `retiringClient.close()`；
3. `retiringVideo.stop()`（`MediaCodec.stop()`/`release()`，耗时不受控）；
4. `audioSender.stop()`（内部还有同步块）；
5. 多次 `appendLog`（同步文件 I/O）。

**时间估算：** 两个 join 的硬上限是 `0.8s + 1.5s = 2.3s`；这是“两个线程都没按时退出”的
最坏值。`MediaCodec.stop()`、`AudioSender` 的锁、文件日志会再叠加。单看 2.3s 不一定触发
Android 5s 输入 ANR，但足以让 UI 明显冻结，且如果同时有 Activity 生命周期/系统回调/
StrictMode 慢 I/O，风险会叠加；更重要的是它把 `SessionController` 的**全局锁**占住 2s+，
其他 `@Synchronized` 入口（连接、投影停止、补启音频）都被卡住。

**关于 attach 的纠偏：** 当前 `attach()` 的 `detach()` 是空操作（每个会话新建 source），
所以“attach 在锁内 join 1.5s”目前不会发生；但 `VideoSender.start` 的 `onSurfaceReady`
本身是在 `synchronized(SessionController)` 内（`SessionController.kt:2013`）。一旦 P1B
复用 source 或增加 re-attach，`attach → detach → join` 就会变成在编码器启动过程中的锁内
2.3s，直接卡住配对线程。建议按“未来会复用”来修。

**影响：**

- 用户点“取消连接”时 UI 线程冻结 2s+（`DarkMessageDialog` 点击回调 → `cancelConnecting`
  → `disconnect` → `retireSession`），容易被用户当成“应用死了”；后台 `onEnded` 也会把
  全局锁占住，导致下一次连接/取消排队。
- 与 C1/C2 叠加：长时间持锁扩大了解码器/编码器 Surface 的释放窗口。

**建议修复：**

- 把 `retireSession` 拆成两阶段：锁内只做“撤销所有权 + 取走引用”（`currentClient=null`、
  `videoSender=null`、`currentScreenSource=null`），锁外执行 `source.release()` /
  `client.close()` / `video.stop()` / `audio.stop()`；用 generation 防止新会话在清理期间
  被旧回调影响。
- `detach()` 不要在 `release()` 路径里做 1.5s join：manager 线程本来就是 daemon，设
  `active=false` + `notifyAll` 后让它自己退出；需要确认退出时用统一 deadline（例如总共
  300ms）而不是两个 0.8/1.5 上限相加。
- `cancelConnecting` 走 `SessionCommands.disconnect`（已有的异步 `AsyncSessionStop`），
  不要在 UI 线程直接调 `@Synchronized disconnect`。
- 把 `MediaCodec.stop()/release()` 移到专用 teardown 执行器，避免持有 SessionController 锁。
- 加日志打点：`retireSession` 总耗时、detach 各段耗时，真机用日志确认实际值。

### H4. `VideoSender.start()` 与 `stop()` 竞态：stop 被先消费后，start 创建的 encoder/inputSurface 永不释放

> **工作区状态（审计结束）：代码已修，待构建/回归验证。** 当前 `VideoSender` 增加
> `lifecycleLock`/`starting`/`endedNotified`，start 安装资源前检查 stopped 并
> `releaseOrphan`（当前 `VideoSender.kt:119-298`）。残余风险：
> `onSurfaceReady` 仍在 `lifecycleLock` 外，若未来有非 `SessionController` 路径在回调期间
> `stop()`，Surface 可在 attach 前被释放；`start` 晚于 `stop` 执行时仍会把 `stopped` 重置为
> false（当前 SessionController 只在 `retireSession` 里 stop，正常调用点不会重复 start，
> 所以暂不可达；P1B 复用 sender 时需要重新检查）。

**位置：**

- `VideoSender.kt:103-191`（`start`：105 `stopped.set(false)` → 159 `createInputSurface` →
  161-162 赋 `encoder/inputSurface` → 178 `running.set(true)` → 180 `onSurfaceReady`）
- `VideoSender.kt:197-208`（`stop`：198 `stopped.compareAndSet(false,true)` 成功后才释放）
- `VideoSender.kt:187-190`（catch 里只调 `stop()`）
- `SessionController.kt:2011-2014`（`onSurfaceReady` 里的 `check(currentClient === client &&
  videoSender === startingVideo && !client.cancelled)`）

**问题：**

`start()` 和 `stop()` 没有互斥。典型时序：

1. 配对线程进入 `start()`，`stopped=false`，正在探测/创建编码器；
2. 用户取消或车机断开 → `retireSession` 调 `retiringVideo.stop()`：CAS 成功，关 socket，
   此时 `encoder` 可能还是 null，Surface 还没创建；
3. `start()` 继续执行：创建 encoder + inputSurface，赋值，`running=true`；
4. `onSurfaceReady` 的 `check` 发现 `videoSender` 已被 null，抛异常；
5. catch 里调 `stop()`，但 `stopped` 已经是 true，**CAS 失败，直接 return**；
6. 刚创建的 encoder/inputSurface 再也没人释放，`running` 还停在 true。

**影响：**

- 每次“连接中取消/会话在编码器启动窗口内结束”泄漏一个 MediaCodec 编码器和一块 gralloc
  Surface；连续几次后可能 codec 资源不足，后续会话 `VIDEO: 启动失败`。
- 由于 `running=true` 但对象已被 `videoSender=null` 摘掉，这个 sender 不会再被
  `retireSession` 找到；泄漏是永久的（进程级）。
- 这属于 `VideoSender` 自身的生命周期结构；P1A 让“连接中取消”更容易发生在启动窗口，
  建议本轮一并修。

**建议修复：**

- 给 `start/stop` 加同一把锁或 generation token：`start` 先登记“启动中”，`stop` 置
  generation++；`start` 每完成一个阶段（socket、encoder、surface、callback）检查 generation，
  不一致就释放本阶段资源并返回 false。
- 把“创建 encoder + surface”放进 `try/finally`，任何异常/取消路径都释放局部 `codec` 和
  `surface`，不要依赖 `stop()` 的 CAS。
- `onSurfaceReady` 回调抛异常时，catch 应强制 `stopped.set(false)` 后再 `stop()`，或直接
  在 catch 里显式 `encoder?.stop/release` + `inputSurface?.release`。

---

## Medium

### M1. 旧 CarLife 客户端的迟到帧没有代际门闸：reconnect 收尾后仍可重建解码器/回填 CSD

> **工作区状态（审计结束）：未修。** 新代码仍未加 client/pipeline generation；
> 且 `detach()` 异步 cleanup 让旧 manager 不再被同步 join，旧客户端迟到帧/旧 manager 多活
> 的窗口比基线更大。见下文。

**位置：**

- `CarLifeScreenSource.kt:189-190`（`onVideoFrame` lambda 直接 `pipeline.feed(...)`，没有
  client 身份/generation 检查）
- `CarLifeScreenSource.kt:266-267`（manager 收尾：`client.stop()` → `teardownDecoder` →
  `pipeline.reset()`）
- `CarLifeScreenSource.kt:278-308`（`handleFrame` 只检查 `isCurrent(session)`；重连期间
  Session 仍是同一个，所以旧帧也能通过）
- `CarLifeHuClient.kt:1056-1123`（video reader 读到一帧后先回调，再看 `running`）
- 参考：hybrid `CarLifeBridge.java:496-505, 554-557` 用 `clientGen/pipelineGen` 明确丢弃
  旧回调；P1A 没有对应机制

**问题：**

manager 重连时，旧 `CarLifeHuClient.stop()` 只是关 socket；旧 video reader 可能已经读完
一帧、正阻塞在 `pipeline.feed` 或 `handleFrame`。manager 紧接着 `teardownDecoder` 和
`pipeline.reset()`，旧帧仍可能在 reset **之后**进入：

1. `pipeline.feed` 重新缓存 SPS/PPS/lastKeyFrame；
2. `handleFrame` 看到 `hasCsd=true`，在会话仍 active/current 的情况下
   `scheduleDecoderCreate`，在 2s 重连等待期间新建一个“上一代码流”的解码器；
3. 新客户端连上后，这个解码器可能已经存在，继续吃新帧；尺寸不同时靠 `handleStreamSize`
   再重建一次，中间有一段旧 CSD/旧尺寸。

**影响：**

- `teardown` 名义上完成、实际解码器被复活；日志出现“刚停完又启动”。
- 旧 `lastKeyFrame` 可能把上一代 IDR 渲染到编码器 Surface，车机上闪一下旧画面。
- 如果新旧 SPS 不同，可能先按旧尺寸建 codec、再重建，增加一次黑屏/花屏窗口和 codec 压力。

**建议修复：**

- manager 每次创建 client 前捕获 `val generation = session.clientGeneration.incrementAndGet()`；
  `onVideoFrame` lambda 里先判断 `session.client === client && generation ==
  session.clientGeneration && session.active`，不满足直接 return。
- `teardownDecoder` 时把 `session.pipeline` 置 null（或换新 pipeline），旧 feed 没有入口；
  新客户端创建时再建 pipeline。
- 参考 hybrid 的 `clientGen/pipelineGen` 写法，并在 `handleFrame` 里再校验一次 generation。

### M2. `lastKeyFrame` 兜底只回放 IDR、且 PTS 固定 0：参考链断裂 / 时间戳回退

**位置：**

- `CarLifeScreenSource.kt:425-429`：

  ```kotlin
  val seed = session.pendingKeyFrame ?: pipeline.lastKeyFrame()?.let { PendingKeyFrame(it, 0L) }
  session.pendingKeyFrame = null
  seed?.let { decoder.queueFrame(it.data, 0, it.data.size, it.ptsMs, true) }
  ```

- `CarLifeVideoPipeline.kt:161-164`（只缓存 `frameData`，不缓存 PTS，也不缓存 GOP）
- `CarLifeVideoPipeline.kt:107`（`lastKeyFrame()` 返回副本）
- 参考：hybrid `CarLifeBridge.java:724-735` 在新建解码器时回放**从关键帧开始的整个缓存
  GOP**，不是只回放 IDR；P1A 新加的兜底只回放一帧。

**问题：**

`pendingKeyFrame` 路径（刚建解码器时对应的当前关键帧）是安全的；但 `lastKeyFrame` 兜底
会在“解码器中途重建”（解码错误、线程退出、尺寸变化）时使用：

- 如果当前码流已经走过这个 IDR 之后的若干 P 帧，新解码器只回放旧 IDR，**中间的 P 帧没有
  重放**；紧接着到来的实时 P 帧引用了新解码器没解码过的参考帧 → 花屏/绿块/马赛克，直到
  下一个 IDR（默认 I 帧间隔 20 帧 ≈ 1s，但引擎实际可能更长）。
- `PendingKeyFrame(it, 0L)` 把 PTS 设成 0；之前解码器输出的是 CarLife 帧头的毫秒时间戳
  （通常是很大值）。编码器输入 Surface 收到时间戳回退到 0，部分编码器会做非单调处理
  （丢帧/时间戳重置），`decoder out` 日志也会出现 `pts=0ms` 的误导行。
- `lastKeyFrame` 可能是旧分辨率的（见 M1/尺寸变化），把它喂给按新 SPS 配置的解码器，
  会触发一次 in-band SPS 切换，增加不确定性。

**影响：**

- 解码器每次重建后有约 1s 的可见花屏/绿块；如果 I 帧间隔更大，会更久。
- 时间戳回退可能让编码器输出/码率控制抖动，诊断日志失真。
- 与 H1 的“无输出”不同，这是“有输出但烂”，验收容易漏掉。

**建议修复：**

- 缓存 `(data, ptsMs)`，喂回时使用原始 PTS；或者缓存从该关键帧到当前的**有界 GOP**
  （参考 hybrid，上限按 2s/6MB），重建时整段回放。
- 更保守：`lastKeyFrame` 只在“它确实是当前帧”（`pendingKeyFrame` 已覆盖）时使用；其他
  重建场景不 seed 旧 IDR，直接等下一个实时 IDR（≤1s 黑屏但参考链完整）。
- 在解码器重建日志里打印 seed 来源（pending/lastKeyFrame）和 PTS，便于现场核对。

### M3. 关键帧判定只认 4 字节起始码且只看首个 NAL；3 字节流/带 AUD/SEI 的帧会全部判成非关键帧

**位置：**

- `CarLifeHuClient.kt:1433-1450`（`isKeyFrame` 只匹配 `00 00 00 01`，且只看遇到的第一个
  起始码后的 NAL；没有起始码时直接读 `data[0]`）
- `CarLifeVideoPipeline.kt:149-164`（裸 IDR 补 CSD、`latestKeyFrame` 缓存都依赖这个
  `keyFrame` 参数）
- `CarLifeScreenSource.kt:303-305, 427-429`（`pendingKeyFrame`/`lastKeyFrame` seed 也依赖）
- `CarLifeHuClient.kt:1463-1472`（`isSpsCandidate` 反而同时支持 3/4 字节，说明两类扫描器
  不一致）
- `app/src/test/.../FakeCarLifeEngine.kt:73-76` + `SyntheticH264`（测试只用 4 字节起始码，
  覆盖不到 3 字节）

**问题：**

如果真实 CarLife 引擎使用 3 字节起始码（`00 00 01 65 ...`），`isKeyFrame` 的 4 字节匹配
永远不命中；最后回退到 `data[0] & 0x1F`，而 `data[0]=0x00` → 所有帧 `keyFrame=false`。
逐条影响：

- `pipeline.feed` 不会为裸 IDR 前置 CSD（`keyFrame && scan.sps == null && scan.hasIdr`）；
- `latestKeyFrame` 不缓存（`keyFrame && scan.hasIdr`）；
- `handleFrame` 不保存 `pendingKeyFrame`；
- 解码器的 `acceptFrame` 关键帧闸门永远打不开，只能等 3s 超时后投喂非关键帧。

如果帧以 AUD（type 9）/SEI（type 6）开头、后面才是 SPS+IDR，`isKeyFrame` 同样返回 false
（它只看第一个 NAL），上面的路径也会退化。

**影响：**

- 首帧延迟 3s+，或在“解码器晚建/重建”时一直等不到下一关键帧（如果引擎 IDR 间隔大）。
- CSD 补前缀失效，晚建解码器可能永远配置不出来（黑屏）。
- 单测/假引擎全绿也发现不了（测试码流是 4 字节起始码、首 NAL 就是 SPS）。

**建议修复：**

- 抽出**唯一**的 Annex-B NAL 扫描器（支持 3/4 字节、遍历所有 NAL），返回
  `hasIdr/hasSps/hasPps/首个 VCL NAL 类型`；`isKeyFrame`、`isSpsCandidate`、pipeline、
  `H264Sps` 全部共用。
- 关键帧判定改为“帧内存在 type 5（IDR）或 type 7（SPS）”，不要假设首 NAL。
- 增加 3 字节起始码和 `AUD+SEI+SPS+PPS+IDR` 的 JVM 用例；真机日志里打印首个 NAL 类型和
  `keyFrame`，确认引擎真实格式。

### M4. `CarLifeHuClient.start()/stop()` 无互斥：stop 后 start 仍可把 `running` 置 true，detach 后 manager 最长多活约 15s 并可能重开 8240

> **工作区状态（审计结束）：未修，且异步 detach 让它更容易发生。** `detach()` 不再
> `manager.join(1500)`，旧 manager 可以在新会话 attach 之后继续跑/开 8240；新的
> `cleanupDetached` 只是在另一个线程里 join，不阻止新会话启动。见下文。

**位置：**

- `CarLifeHuClient.kt:460-516`（`start`：463 遍历 7 端口，476 `cmd = sockets[CMD]`，
  482-484 `cmdOut=…; running.set(true)`，501-514 启动读线程/看门狗，516 版本协商）
- `CarLifeHuClient.kt:519-529`（`stop`：`running=false` + `closeAll`）
- `CarLifeScreenSource.kt:139-167`（detach：先 `client.stop()`，再 `manager.join(1500)`）

**问题：**

`start()` 的端口连接循环内部没有 `running` 检查，也没有和 `stop()` 共享锁。若 `stop()` 发生
在 `running.set(true)` 之前：

1. stop 把 `running=false`，`closeAll()` 清空 sockets；
2. start 继续为剩余端口建立**新** socket 并放进 `sockets`；
3. start 拿到 `cmd`，设置 `cmdOut`，然后 `running.set(true)` —— **把已停止的 client 复活**；
4. start 启动 reader/看门狗，进入 `sendVersionNegotiation()`；如果引擎能回应，`start()`
   会在停止后仍返回 true；
5. manager 随后才检查 `!session.active`，再 `client.stop()`；中间最长一个版本协商窗口
   （15s）里，这个旧 client 的 `videoSupervisor` 可能 `reopenVideo()` 成功（它只检查
   `running.get()`），向引擎再开一条 8240；
6. 与此同时，新的 `CarLifeScreenSource`/新 `CarLifeHuClient` 可能已经连上同一个引擎。

**影响：**

- 引擎可能把视频流推到旧 HU 的 8240，导致新会话黑屏；旧 client 在 detach 后还多活 15s，
  `manager.join(1500)` 超时并打“已放弃等待”。
- 多出来的 socket/reader 线程在短时间重复出现；如果旧 client 的 cmdOut 还有效，它还可能
  发 98311，抢新会话的编码器状态。
- 用户“取消连接”/车机断链正好落在握手窗口时触发概率最高。

**建议修复：**

- `start/stop` 加同一把锁或 `closed` generation；`start` 在每个 connect 前后检查
  `running/closed`，`stop` 设置 `closed=true` 并关闭“正在连接中”的 socket。
- `running.set(true)` 只在确认 `!closed` 后执行；`start` 返回前若已被 stop，主动
  `closeAll()` 并返回 false。
- `stop()` 里保存并 interrupt/join manager 之外，还应让 manager 在每轮循环确认
  `session.client === client`，旧 client 不得再进入 `reopenVideo/restartEncoding`。

### M5. 日志验收会把可恢复错误判 FAIL；新增降噪也没有达到文档承诺

**位置：**

- `CarLifeVideoDecoder.kt:502`（每次可重试失败都 `listener.onDecoderError("$reason（第
  $attempt/$MAX_ERROR_ATTEMPTS 次，退避后重建 codec）")`）
- `CarLifeScreenSource.kt:555-559`（统一落成 `CARLIFE-VIDEO: 解码器错误 - …`）
- `scripts/verify-carlife-log.ps1:92-112`（`CARLIFE-VIDEO: 解码器错误` 是“命中即 FAIL”）
- `CarLifeHuClient.kt:462, 478`（即使 `quietPortFailures=true`，仍每次重试打
  `CARLIFE: 连接本机车机端口 …` + `CARLIFE: 控制端口未连通 …`，2s 一次）
- `CarLifeScreenSource.kt:227-233`（等待日志 10s 限流）
- `docs/P1A-carlife-video.md:225-228, 247`（文档宣称“首次后最多 1 条/10s”和“只打 10s
  节流的等待行”）
- `SessionController.kt:2054-2058`（`MODE: CarLife 唯一内容源 启动失败…` 也以
  `MODE: CarLife` 开头；验收脚本的必需标记是子串匹配）

**问题：**

1. **可恢复错误 → 验收 FAIL。** `retryAfterFailure` 每次失败都会发 `onDecoderError`，而
   P1A 验收脚本把 `CARLIFE-VIDEO: 解码器错误` 当致命失败。冷启动/资源竞争造成的第一次
   codec 打开失败（重试后成功）会让整个验收 FAIL。
2. **`quietPortFailures` 只压了逐端口 7 行，没压汇总。** 引擎未启动时每 2s 仍有
   `连接本机车机端口` + `控制端口未连通` 两行；10s 内约 10 行，和文档“只打 10s 节流行”
   不符，现场日志仍会被刷。
3. **必需标记 `MODE: CarLife` 会匹配失败文案。** 如果 `attach` 返回 false（当前永远 true，
   但 H4 的异常路径/未来改动可能），日志 `MODE: CarLife 唯一内容源 启动失败` 仍会让
   `MODE: CarLife` 判定 PASS；必须改成匹配“已启动”或增加失败标记。
4. **happy path 的四个标记本身都有代码路径产出**（`MODE: CarLife 已启动`、
   `CARLIFE: carlife connected`、`CARLIFE-VIDEO: decoder out #1`、`VIDEO: 帧 #1`），
   但 H1/H2 的黑屏场景会让 `decoder out`/`VIDEO: 帧` 缺失；`CARLIFE-VIDEO: fit` 是
   advisory，不代表画面成功。

**影响：**

- 现场验收出现“日志显示 FAIL 但实际只是重试一次”“日志 PASS 但车机黑屏”的两类误判。
- 日志刷屏会掩埋真正的首帧/解码错误。

**建议修复：**

- 日志分级：重试路径用 `CARLIFE-VIDEO: 解码器重试（第 n/5 次）`；只有
  `reportFatal`/“放弃”才用 `解码器错误`。同步更新 `verify-carlife-log.ps1` 的
  FailureChecks，或把“第 n/5 次”排除。
- `quietPortFailures=true` 时把 `连接本机车机端口` 和 `控制端口未连通` 一起纳入 10s 限流
  （或只在状态变化时打印）；文档与代码二选一，保持一致。
- 验收脚本的 `MODE` 必需标记改为 `MODE: CarLife 唯一内容源 已启动`（或增加
  `MODE: CarLife … 启动失败` 失败标记），并增加“有 fit 无 decoder out”的专门提示。

---

## Low

### L1. P1A 不消费音频，但每条会话仍启动 9240/9241 读线程与音频看门狗

> **工作区状态（审计结束）：未修。**

**位置：**

- `CarLifeScreenSource.kt:185-207`（构造 `CarLifeHuClient` 时没有覆盖任何 `onAudio*`，
  默认全是空实现）
- `CarLifeHuClient.kt:507-514`（start 无条件打开媒体/TTS 音频 socket 并起读线程）
- `CarLifeHuClient.kt:1627-1697`（`audioSupervisor` 在 P1A 也常驻；媒体通道沉默时还会重连）

**问题：**

P1A 只做视频直出，但客户端仍会：

- 连接 9240/9241，起 `carlife-media`/`carlife-tts` 两条读线程；
- 每包分配 `ByteArray` 后交给默认空回调（PCM 被丢弃、GC 掉）；
- 掉链后音频看门狗无限重连（媒体沉默看门狗受 `audioExpected=false` 限制，但断链重连
  不设上限）。

**影响：**

- 30–60 分钟里增加不必要的 CPU/GC、socket 与线程；如果引擎音频端口反复 reset，会周期性
  重连并刷日志。
- 不会直接导致视频错误，但会让“音频通道正常但 P1A 不用”的现场日志更难读；P2 接入音频时
  也要重新梳理这段生命周期。

**建议修复：**

- 给 `CarLifeHuClient` 增加 `enableAudio: Boolean = true`；P1A 传 false，跳过
  9240/9241 端口、`openAudio` 和 `audioSupervisor`；P2 再打开。
- 或者在 `CarLifeScreenSource` 侧用 `onAudioFormat/onAudioPcm` 是否为空来判断，但显式开关
  更清晰。

---

## 已验证正常的点

1. **CSD 格式正确。** `CarLifeVideoDecoder.buildFormat` 把 pipeline 缓存的 SPS/PPS 原样放进
   `csd-0/csd-1`，两者都含 Annex-B 起始码（`CarLifeVideoPipeline.kt:95-99` 返回的正是
   `scanNals` 从起始码开始的 NAL，`CarLifeVideoDecoder.kt:358-366` 设置），符合 AVC
   MediaCodec 对 csd 的要求；`setCsd` 在 `start()` 之前调用，顺序正确。
2. **`BUFFER_FLAG_KEY_FRAME` 与零长度输入缓冲合法。** 解码器输入用
   `MediaCodec.BUFFER_FLAG_KEY_FRAME`（`CarLifeVideoDecoder.kt:435`）是合法/提示性标志，
   不影响 NAL 解析；`queueInputBuffer(index, 0, 0, pts, 0)`（`:431`）是参考实现验证过的
   “归还空 buffer 防饥饿”写法，不是必然错误。
3. **`INFO_OUTPUT_FORMAT_CHANGED` 已处理。** `drainOutput` 读取输出宽高、更新字段并回调
   `onOutputFormatChanged`（`CarLifeVideoDecoder.kt:381-395`），不会把 format change 当帧。
4. **解码器不会阻塞 CarLife 视频读线程。** `queueFrame` 只做有界队列 + 拷贝
   （`CarLifeVideoDecoder.kt:126-164`），MediaCodec 创建/配置/停止都在 `carlife-codec`
   单线程执行器（`CarLifeScreenSource.kt:604-608`），解码/渲染在 `CarLifeDec` 线程
   （`:190-193`、`:265+`）。视频读线程上只剩 NAL/SPS 扫描和一次拷贝。
5. **旧会话回调有基本隔离。** `isCurrent(session)` 身份比较 + `session.active` 标志覆盖了
   attach/detach 的主要交接；`pendingKeyFrame` 是 `@Volatile` 且通常只有视频读线程写；
   `sleepWhileActive` 可被 detach 唤醒；`CarLifeVideoDecoder` 的 start/stop 幂等（除超时
   处理，见 C1）。
6. **基线正常 retire 顺序正确，但当前工作区异步 detach 使其失效（见 C0）。** 基线
   `retireSession` 先 `currentScreenSource.release()` 再 `videoSender.stop()`，符合“先停
   解码器再释放编码器 Surface”的意图；工作区把 `release()` 改成异步 cleanup 后，这句话
   不再成立。`VideoSender.stop` 的幂等、`onEnded` 的同一线程可重入 + `currentClient`
   身份检查本身没有问题（不是死锁点）。
7. **日志标记在真正 happy path 上都有产出。** `MODE: CarLife 唯一内容源 已启动` 在
   `onSurfaceReady` 内、`source.attach()` 之后（`SessionController.kt:2054-2058`）；
   `CARLIFE: carlife connected 127.0.0.1` 在版本协商成功后（`CarLifeScreenSource.kt:237-240`）；
   `CARLIFE-VIDEO: decoder out #1` 在首帧 `onFrameRendered`（`:542-552`）；
   `VIDEO: 帧 #1` 在编码器输出并 `configSent` 后（`VideoSender.kt:353-400`）。注意
   H1/H2 会让后两者缺失，见对应条目。
8. **`attach` 目前不在 SessionController 锁内做长 join。** `SessionController.kt:2039-2044`
   每会话新建 `CarLifeScreenSource`，所以 `attach` 开头的 `detach()` 立即返回；任务描述中的
   “1.5s join”发生在 `release()`（H3），不是 attach。若 P1B 复用 source 会改变这个结论。
9. **最近的 no-ADB 重构没有发现 P1A 启动崩溃路径。** `ConnectionPreflight` 已移除
   `INPUT_SERVICE/SHELL` 要求；`VirtualLauncher.attach` 不在 P1A 路径；`VirtualLauncher`/
   `AppOrientationCompat` 仍会调用 `Shell.exec`，但兼容层恒返回失败且调用方有
   `runCatching`/降级；`AudioSender.createCaptureSource` 现在 `checkNotNull(projection)`，
   但 `startAudioChannel` 用 `try/catch (Exception)` 包住，最坏是音频不启动，视频继续。
   `ScreenDimmer`/`TouchRouter` 的 no-ADB 分支不会在 P1A 启动时抛异常。
10. **测试覆盖的边界是清楚的。** `FakeCarLifeEngineTest` 用真实 socket 覆盖
    `CarLifeHuClient` 握手、65544、SPS 解析和帧回调；`CarLifeVideoPipelineTest`/`H264SpsTest`
    覆盖纯 JVM 处理。它**不覆盖** MediaCodec/Surface，这一点 docs 已声明；本轮不把
    “619/632 测试绿”当作硬件路径证据。审计结束工作区新增的 `RetiringDecoderGateTest.kt`/
    `NoDecodeWatchdogTest.kt` 只覆盖纯状态机，未覆盖“异步 detach + Surface 释放”的时序（C0）。

---

## 需真机确认的假设

1. **单 Surface 跨 codec 直出**：解码器（SPS 尺寸）向编码器输入 Surface（VideoProfile
   尺寸）生产帧，在目标机型上到底是“正常 fill 拉伸 / 黑屏 / 绿屏 / configure 失败”。
   需要在真机上看 `CARLIFE-VIDEO: fit`、`解码输出格式`、`decoder out`、`VIDEO: 帧` 四行
   的组合，并用 `dumpsys media.codec` 确认有没有双 codec。
2. **车机首帧超时**：CarLife 握手 + 引擎建流 + 解码首帧需要数秒；车机端在只有 keepAlive、
   没有 `VIDEO: 帧` 的窗口里能等多久。如果车机等不到首帧就拆会话，P1A 需要 prewarm
   （连接前先起 CarLife 客户端/缓存首帧），docs 的“先开 CarLife+ 再连接”只是操作规避。
3. **真实码流的起始码长度与关键帧形态**：3 字节还是 4 字节、帧首 NAL 是否可能为 AUD/SEI、
   SPS 是否只在编码器初始化后的首个 IDR 出现。直接决定 M3 是否在目标机上发生。
4. **8240 冷启动 reset 时序**：引擎 accept 后立刻 reset 的窗口是否落在
   `restartEncoding` 的 5s 98311 去重窗内；reopen 后引擎是否真的重发 SPS。决定 H1 的
   “CSD 缺失”概率。
5. **65544/98313 重发语义**：视频口重连后，单发 98311 是否足够让引擎重建并补 SPS；还是
   必须重走 98386/98313。P1A 的 `restartEncoding` 目前假设前者。
6. **CarLife 帧时间戳语义**：帧头 `[4..7]` 是毫秒吗？是否单调、是否会在编码器重建后
   归零/跳变？决定 decoder PTS 和 M2 的 `pts=0` 回放是否会让编码器异常。
7. **`MediaCodec` 输入 buffer 容量**：`KEY_MAX_INPUT_SIZE=4MB` 是否被目标 codec 采纳；
   `getInputBuffer().capacity()` 是否可能小于一个 1080p IDR。如果小于，`feedInput`
   当前会先 `acceptFrame`（消费关键帧闸门）再因容量丢弃（`CarLifeVideoDecoder.kt:416,
   428-432`），可能永远等不到可投喂的 IDR；需要真机日志确认“输入缓冲不足”是否出现。
8. **`stopAndWait` 正常耗时**：目标 ROM 上解码线程退出是否稳定 <800ms；网络拥塞/编码器
   输入 Surface 背压时最坏多久。决定 C1 是“理论”还是“每次重连都可能”。
9. **`onEnded` 路径的 Surface 释放**：车机侧主动结束（锁屏/熄火/关屏）时，目标 ROM 上
   “Surface.release 时解码器仍挂着”是否只是异常，还是会 native crash。
10. **主线程卡顿时间**：目标 ROM 上 `cancelConnecting` 同步路径实际冻结多久；2.3s 是
    join 上限，`MediaCodec.stop`/音频/文件 I/O 叠加后是否接近 ANR 阈值。
11. **引擎对重复 HU 连接的容忍度**：M4 的旧 manager 多活 15s 并重开 8240 时，引擎是
    “后连接替换前连接”还是“两条都收/都断”；目标 CarLife+ 版本的行为决定 M4 的实际伤害。
12. **P1A 音频通道空转**：9240/9241 常驻、PCM 丢弃，目标引擎是否会因为“HU 在收音频但
    用户听不到”出现路由/播放状态异常（L1）。
13. **30–60 分钟稳定性**：codec 数量、`carlife-*`/`CarLifeDec` 线程数、fd 数是否随
    重连增长；建议真机定期 `dumpsys media.codec`、`ps -T`、`ls /proc/<pid>/fd | wc -l`。
14. **`onOutputFormatChanged` 的 crop**：目标 codec 是否把 `KEY_WIDTH/HEIGHT` 报成 coded
    size（含 padding），而 SPS 解析的是 visible size；两者不一致时 H2 的拉伸/裁切表现。
15. **no-ADB 后的音频路径**：`AudioSender.createCaptureSource` 现在必须
    `MediaProjection`；真机上授权晚到/被系统回收时，`startAudioChannel` 的 catch 是否
    覆盖所有异常（视频应继续）。

---

## 其他未单列观察（不改变上述排序）

- **`pendingKeyFrame` 最新帧竞态（轻微）**：`createDecoder` 先读 `seed` 再清空
  `pendingKeyFrame`（`CarLifeScreenSource.kt:427-428`）；如果这中间恰有新关键帧写入，新帧
  会被清掉，只能等下一个 IDR。影响 ≤1 个 I 帧间隔，且 `pipeline.lastKeyFrame` 通常已缓存
  该帧；建议把“读+清”放在同一把锁或改用 AtomicReference.getAndSet。
- **输入 buffer 容量/key gate（需真机确认）**：`feedInput` 先 `acceptFrame` 消费关键帧
  闸门，再检查 `frame.data.size > buffer.capacity()`（`CarLifeVideoDecoder.kt:416,
  428-432`）。如果目标 codec 的输入容量小于 IDR，会丢掉 IDR 却认为“已起解”，后续 P 帧
  永远无输出。建议把容量检查移到 `acceptFrame` 之前，并对该事件做一次性错误上报/重建。
- **`MODE` 与 `carlife connected` 的顺序**：`attach` 先启动 manager 线程再打
  “内容源已挂载”（`CarLifeScreenSource.kt:117-124`），SessionController 的 `MODE` 在
  attach 返回后才打（`:2054`）；如果引擎已经在跑，manager 可能先打 `carlife connected`。
  验收脚本不校验顺序（P1A docs §5 写了“依次出现”），建议文档改成“均出现”或脚本加顺序
  检查。
- **`attach` 永远返回 true**：即使 Surface 无效，`attach` 也启动 manager 并返回 true；
  `MODE: … 已启动` 因此不代表 Surface 可用。当前无效 Surface 只会在调度解码器时打
  “编码器 Surface 已失效”（`CarLifeScreenSource.kt:477-482`）。
- **`CarLifeHuClient.videoReader` 在视频读线程写同步文件日志**：每 5s 的帧率行、SPS 行
  都会走 `appendLog → DebugFileLog.append`（同步 open/write/close）；量不大，但视频读线程
  上做文件 I/O 不是零成本，30–60 分钟高负载时可作为“丢帧”排查项。若现场看到队列丢帧，
  优先把这两行改成异步/限流。
- **`VideoSender.stop()` 从其他线程调 `encoder.stop()`**：MediaCodec 不是线程安全的，
  正常 retire 时 drain 线程可能还在 `dequeueOutputBuffer`/`releaseOutputBuffer`。虽然
  `runCatching` 能接住异常，仍建议先关 socket + join drain，再 stop/release（与 C2 的修
  法合并）。

---

## 与参考实现的差异对照

| 能力 | hybrid63 参考 | P1A 当前 | 风险 |
| --- | --- | --- | --- |
| 解码器替换 | `DecoderReplacementGate`：pending 未退出则 DEFER，绝不丢引用后重建（`DecoderReplacementGate.java:10-14, 68-89`；`CarLifeBridge.java:430-441, 651-661`） | `stopAndWait` 返回值被忽略，直接建新解码器（`CarLifeScreenSource.kt:370, 448`） | C1：同一 Surface 双生产者 |
| 解码器重建后补帧 | 回放从关键帧开始的整个 GOP（`CarLifeBridge.java:724-735`） | 只回放最新 IDR，PTS 置 0（`CarLifeScreenSource.kt:427-429`） | M2：参考链断裂/花屏 |
| 解码器无输出检测 | 帧数/时间阈值后 `requestKeyFrame`（`CarLifeBridge.java:576-582`） | 无（只看“有没有收到帧”，且在 HuClient 层） | H1/H2：黑屏无恢复 |
| 旧客户端迟到回调 | `clientGen/pipelineGen` 双层过滤（`CarLifeBridge.java:496-505, 554-557`） | 只靠 `isCurrent(session)`，重连期间同一 Session | M1：收尾后解码器被复活 |
| onEnded/收尾顺序 | 参考工程由桥接层统一 stop，解码输出不是编码器输入 Surface | 基线：drain finally 先释放 Surface 再通知；工作区：`onEnded` 已提前，但 `source.release()` 异步化 | **C0**：Surface 仍先于解码器释放 |
| 连接预热 | `CarLifeHijack` 在 attach 时主动 reinit/等当期 IDR（`CarLifeHijack.kt:564-608`） | attach 后才开始连 CarLife+ | 黑屏延迟/车机首帧超时（需真机确认） |

---

## 审计方法与限制

- 只读代码审查 + 参考实现对照；没有 Android 设备/真车、没有 MediaCodec 运行时、没有
  gradle 构建或测试执行（按任务约束）。
- 审计基线是 `df3e852`；审计结束（2026-11-06 21:39）工作区还有未提交的
  `CarLifeScreenSource.kt` / `CarLifeVideoDecoder.kt` / `VideoSender.kt` 修改。**C0 与 §0.1
  的行号按该工作区快照；C1~L1 的旧行号用于 `df3e852` 基线**，引用前请核对当前文件。
  `docs/P1A-carlife-video.md` 中的 632 测试/构建结论来自上游提交，本轮未复跑；工作区新增
  测试也未运行。
- 未把“代码里存在某条日志”当作“运行时一定出现”；日志路径均按控制流逐条核对，并区分
  happy path 与异常/竞态路径。
- 未报告纯风格问题、未报告协议常量本身的问题（按要求跳过）。建议处理顺序：
  **C0（当前工作区唯一 Critical）→ H2（真机确认 + P1B 回退）→ 验证 C1/H1/H4 的未提交修复
  → M1~M5 → L1**；其中 C1/H1/H4 的基线修复必须和 C0 的 latch 一起做回归。
