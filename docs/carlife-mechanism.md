# CarLife 到底是怎么连上的？（机制说明）

> 面向使用者的说明：本应用**没有修改手机上的 CarLife+，也没有向 CarLife+ 进程注入任何代码，不需要 root / ADB / Shizuku**。
> 连接方式：你在手机上**手动把 CarLife+ 切到「无线连接」**后，CarLife+ 会在本机监听 TCP 端口，
> 本应用作为「车机端」去连接本机回环地址（`127.0.0.1`）上的这些端口。
> 本文同时说明荣耀手机（当前首要验证目标）的入口、版本与端口稳定性结论，以及其它品牌安卓机的兼容边界。

---

## 0. 先回答最常见的问题

- **"是它自动连上我的，还是应用主动去连它？"**
  都不是"自动"。准确说法是：**CarLife+ 在无线模式下是本机 TCP 服务端，等"车机"来连；本应用把
  "车机"这个角色自己演了，连的是 `127.0.0.1`（同一台手机上的自己连自己）。**
  所以必须由你先把 CarLife+ 切到「无线连接」，本应用才有东西可连。
- **需要 root / ADB / Shizuku / 改包吗？**
  不需要。全部是普通 App 权限 + 本机回环 socket（见 [无 ADB 模型与权限](no-adb-model.md)）。
- **需要手机先做什么？**
  需要你**手动打开手机上的 CarLife+（荣耀手机是「荣耀车联」里的 CarLife）并进入「无线连接」**，
  让引擎进入等待连接的无线态；本应用会每 2 秒自动重试，成功后车机出现 CarLife 画面。
- **这套接法安全吗？会不会破坏 CarLife+？**
  本应用不改动、不替换手机上的 CarLife+ 应用，也不读取它的私有数据，只在它自己监听的本地端口上
  按公开协议收发数据；最坏情况只是"CarLife 画面出不来"，不会修改 CarLife+ 的配置或数据。
  本应用同时需要的是蓝牙、附近 Wi-Fi、通知等普通权限。

---

## 1. 两个"身份"：手机同时是 CarPlay 发送端 + CarLife 车机端

```
                手机（Android）
┌──────────────────────────────────────────────────────────────┐
│  CarLife+ / 荣耀车联（引擎）                                  │
│    「无线连接」态：本机 TCP 服务端（7240/8240/9240/…）        │
│            ▲                                                  │
│            │ 127.0.0.1 回环（本应用扮演"车机端 HU"）          │
│  ┌─────────┴──────────────┐                                   │
│  │ 本应用（link_plus 分支）│                                   │
│  │  • CarLifeHuClient     │── 解码 H.264 → 重编码             │
│  │  • 音频/触摸/导航桥     │                                   │
│  └─────────┬──────────────┘                                   │
│            │ 无线 CarPlay（蓝牙 iAP2 + Wi-Fi AirPlay）        │
└────────────┼──────────────────────────────────────────────────┘
             ▼
        别克车机（无线 CarPlay 接收端，本版本只做别克车型）
```

- 对 **CarLife+**：本应用是"车机端（HU）"，连接它在本机打开的端口；
- 对 **别克车机**：本应用是"无线 CarPlay 发送端（配件）"，把 CarLife 的画面/声音/触摸转成车机认识的 CarPlay；
- 两条链路彼此独立："CarLife 能不能连上"只取决于手机上的 CarLife+；"车机能不能出画面"取决于别克车机和 CarPlay 链路。

---

## 2. CarLife+ 在无线模式下开了哪些端口

CarLife+ 无线连接的原始设计是：**手机 CarLife+ 起 TCP 服务端，车机作为客户端连手机**。
所以这些端口本来就是给"车机"准备的；本应用只是把目标地址从"车机 IP"换成本机 `127.0.0.1`。

| 端口 | 用途 | 方向/内容 |
|---|---|---|
| 7240 | 命令/握手 | 版本协商、设备信息、特征配置、心跳、媒体信息、导航回传（0x10030） |
| 8240 | H.264 视频 | 手机 → 车机的视频裸流（12B 包头 + 帧数据） |
| 9240 | 媒体音频 | 裸 PCM 字节流（12B 包头 + PCM） |
| 9241 | TTS/导航播报 | 独立 PCM 流（导航播报、提示音） |
| 9242 | VR/语音 | 语音识别相关（本应用预留，不参与连接状态判断） |
| 9340 | 触摸回传 | 车机 → 手机：点击/滑动/双指动作 |
| 9440 | 数据通道 | 辅助数据 |

> 端口号已通过实车链路日志与公开协议行为核对确认；本应用常量见
> `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeProtocol.kt` 的 `PORT_*`。

### 2.1 握手时序（本应用实际走的流程）

```text
-> 98305  协议版本 3.0（关键：不是 4.0）      <- 65538 match=1
-> 98343  统计信息                          <- 65563/65540/65574
-> 98307  HU_INFO（车机设备信息）            <- 65574 模块状态 → 回 98344（模块全开）
-> 98386  特征配置（连接类型/I 帧间隔/分辨率直通）   ← 主动推，不等手机
-> 98311  视频编码初始化（宽高/帧率/安全区）
                                           <- 65544 手机确认编码参数
-> 98313  启动推流                          <- 8240 开始推 H.264
每 1 秒 -> 131074 心跳
```

几个关键点（通过实车链路日志与公开协议行为验证，均已落到本仓库代码）：

1. **协议版本必须发 3.0**。荣耀引擎对 4.0（或 3.2）会走"新车机版本"的 RSA/AES 加密鉴权路径，
   之后不再下发 65611，会话静默挂死；发 3.0 既能让引擎接受，又走老版明文路径。
2. **车机必须主动推进**，不能只回 HU_INFO 等手机：引擎在本场景不会主动发鉴权，必须由我们主动推
   98386 + 98311，手机才会回 65544 并开始推流。视频协商成功不依赖鉴权通过。
3. **98386 要赶在 98311 之前**（分辨率直通开关 `DISABLE_RESOLUTION_OPTION=1` 必须先被引擎接受），
   否则引擎按白名单把非标分辨率降档到 1280×720。
4. 视频/音频帧头与 CSD（SPS/PPS）的解析在本仓库代码注释里有完整说明：
   `CarLifeHuClient`（各端口帧格式、包长度/时间戳/类型）与 `CarLifeVideoPipeline`（CSD/GOP 与关键帧处理）。

---

## 3. 荣耀手机（当前首要目标）

### 3.1 包名与入口

| 角色 | 包名 | 说明 |
|---|---|---|
| 宿主 App（荣耀车联） | `com.hihonor.auto`（别名 `com.honor.auto`） | 用户可见入口；无线连接页在 `com.hihonor.auto.carlifeplus.connect.wlan.WlanGuideActivity` |
| CarLife 引擎 | `com.baidu.carlife.honor` | 实际开 TCP 端口、跑协议的进程（实测 8.8.5 / CarLife+ 4.0.0） |

**正确入口是宿主 App（荣耀车联）→ CarLife → 无线连接**，而不是直接打开 `com.baidu.carlife.honor`
的车机形态界面：荣耀车联的无线连接状态机不会被推进，引擎可能只开端口不应答协议
（实车链路日志记录：直接打开引擎界面时 65538 仅偶发应答；停在无线引导页时无应答）。

### 3.2 用户操作（荣耀手机）

1. 手机安装/更新「荣耀车联」（系统自带，含 CarLife+ 引擎；也可从应用市场安装 CarLife+）；
2. **先让手机和别克车机保持蓝牙连接**（本应用连接车机本身就需要蓝牙，荣耀车联的"下一步"有时也要求
   有车机蓝牙连接才解锁；这一步和 CarPlay 连接共用蓝牙）；
3. 打开「荣耀车联」→ 进入 CarLife → 点「无线连接」，停在「正在连接」界面；
4. 回到本应用，点「启动连接」（或保持已启动状态）——应用会自动连本机 7240 等端口；
5. 车机出现 CarLife 画面后，操作全部在车机上（画面/音乐/导航/触摸都来自手机 CarLife+）。

> 本应用在等引擎期间每 2 秒重试；**部分系统的后台冻结会暂停重试**，所以请尽量保持 CarPlay 会话在
> 前台（应用有前台服务），或从荣耀车联切回本应用一次以触发重试。

### 3.3 版本与端口稳定性结论

- **端口号在荣耀 8.8.5 上是固定的 7 个**（7240/8240/9240/9241/9242/9340/9440），协议 3.0 明文路径可用；
  本仓库的客户端常量、握手流程与实车验证都基于这一版本。
- 荣耀应用市场里存在 8.2.x / 8.6.x / 8.8.x 等多个 CarLife+ 版本；**OEM 版本/引擎升级后协议可能变化**，
  端口号属于协议常量、通常稳定，但"是否要求加密鉴权 / 入口路径 / 厂商适配层字段"可能变。
  升级后请先看日志是否出现 `CARLIFE: carlife connected` 与 `65538 match=1`；失败时按第 6 节诊断。
- 实车链路日志记录：荣耀引擎在**未进入无线等待态**时也可能 accept 端口连接但不对 98305 应答；
  **"端口能连上"不等于"协议就绪"**，以实际握手日志为准。

---

## 4. 其它品牌安卓手机（理论兼容，未逐一实车验证）

CarLife+ 内核同源，OEM 定制包名与宿主 App 不同。本应用只按"已知包名 + 桌面 label 模糊匹配"做检测，
不强制包名；连接时一律连本机回环端口，因此**理论上同样可用**，差异主要在：

| OEM | 引擎包名 | 宿主 App（用户入口） |
|---|---|---|
| 荣耀 | `com.baidu.carlife.honor` | 荣耀车联（`com.hihonor.auto`） |
| 官方 | `com.baidu.carlife` | CarLife+ |
| 小米 | `com.baidu.carlife.xiaomi` | 小米 CarWith（`com.miui.carlink`） |
| OPPO | `com.baidu.carlife.oppo` | OPPO 车联（`com.oplus.ocar`） |
| vivo | `com.baidu.carlife.vivo` | vivo 车机（`com.vivo.car.networking`） |
| 三星 | `com.baidu.carlife.samsung` | 三星 CarLink（`com.samsung.android.carlink`） |
| 联想/摩托罗拉 | `com.baidu.carlife.lenovo` / `com.baidu.carlife.motorola` | 对应车联 App（未验证，检测兜底） |

> 表内包名来自公开的 OEM 适配信息与实车链路日志中的身份/包名记录；
> 各 OEM 可能随时改包名/入口/校验强度，**以本机检测 + 日志为准**。

---

## 5. 需要 Android 11+ 包可见性（`<queries>`）

Android 11+ 默认只能看到"自己 + 匹配 `<queries>` 的包"。本仓库 manifest 里已有
`<intent MAIN/LAUNCHER>` 查询（桌面枚举用），所以带桌面入口的车联/引擎 App 通常可见；
若某些版本引擎（`com.baidu.carlife.*`）没有桌面入口，检测会列不出来，需要在现有 `<queries>` 里补：

```xml
<!-- app/src/main/AndroidManifest.xml 的 <queries> 内追加；不改也能先跑（检测不到就提示） -->
<package android:name="com.baidu.carlife" />
<package android:name="com.baidu.carlife.honor" />
<package android:name="com.hihonor.auto" />
<package android:name="com.honor.auto" />
<package android:name="com.baidu.carlife.xiaomi" />
<package android:name="com.miui.carlink" />
<package android:name="com.baidu.carlife.oppo" />
<package android:name="com.oplus.ocar" />
<package android:name="com.baidu.carlife.vivo" />
<package android:name="com.vivo.car.networking" />
<package android:name="com.baidu.carlife.samsung" />
<package android:name="com.samsung.android.carlink" />
<package android:name="com.baidu.carlife.lenovo" />
<package android:name="com.baidu.carlife.motorola" />
```

> 说明：`<queries><package>` 只能列出**普通应用**，不需要 `QUERY_ALL_PACKAGES`（上架也不需要危险权限）；
> 检测不到时功能不受影响，只是状态页无法显示已装版本。

---

## 6. 本应用怎么做检测/诊断（给 UI/日志用）

`app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeEngineDetector.kt`（只读，不启动任何应用）：

| API | 说明 |
|---|---|
| `detect(context): List<EngineInfo>` | 已装 CarLife/车联（displayName / packageName / versionName / versionCode / kind），荣耀优先去重排序 |
| `portsListening(): List<Pair<Int, Boolean>>` | 本机 7240/8240/9240/9241/9340/9440 是否监听（阻塞，约 ≤900ms，**勿在主线程**） |
| `isAnyPortListening(): Boolean` | 是否有任一端口监听（快捷判断） |
| `isPortOpen(port, timeoutMs)` | 单端口探测（默认 150ms 超时） |
| `statusText(context): String` | 多行摘要：已装引擎 + 版本 + 端口表 + 结论 + 下一步提示（阻塞，**勿在主线程**） |

连接是否真的成功，看应用日志：

- `CARLIFE: carlife connected` —— 已连上引擎并握手成功；
- 只有端口监听没有该行 —— 大概率 CarLife+ 还没进入「无线连接」等待态，或引擎被系统冻结/杀死；
- 有 `carlife connected` 但长时间无画面 —— 看 `CARLIFE: 已握手但 Ns 无视频帧`、`协商编码参数`、`客户端 SPS 实测`。

---

## 7. 兼容边界与不做的事

- **支持**：CarLife+ 走 3.0 明文协议的版本（荣耀 8.8.5 实测；其它 OEM 待验证）。
- **不支持**：要求 4.0 加密鉴权 / 车机证书 / 外接设备防盗版校验的新版本——本应用**不伪造证书、不绕过校验**，
  这类版本会连不上（日志表现为 65538 不应答或握手后静默）。
- **不做**：不改动手机上的 CarLife+ 应用本身、不向它注入代码、不需要 root/ADB/Shizuku、
  不采集麦克风/摄像头等无关权限。
- 本版本只做**别克车型**的 CarPlay 接收端；CarLife 侧机制与车型无关，换车机只影响 CarPlay 链路。

---

## 8. 参考资料

- 本仓库：[无 ADB 模型与权限](no-adb-model.md)、[功能与界面说明](PORT_MATRIX.md)、
  [CarLife 迁移计划](CARLIFE_PORT_PLAN.md)
- 协议依据：本项目在荣耀 8.8.5（CarLife+ 4.0.0）实车上抓取的链路日志、协议字段记录与握手复现结果
  （字段/时序同时写在 `CarLifeProtocol.kt`、`CarLifeHuClient.kt` 的代码注释里）
- 开源参考实现：[CarPToCarL](https://github.com/jjuugk/CarPToCarL)（MIT License）——本项目的 CarLife
  协议/客户端/视频部分在其开源实现基础上按本仓库链路改造，许可与署名见
  [CarPToCarL-NOTICE.md](third-party/CarPToCarL-NOTICE.md)
- 外部资料：荣耀官方支持页[通过荣耀车联连接 CarLife 车机](https://www.honor.com/cn/support/content/zh-cn15854553/)；
  荣耀车联 / CarLife+ 版本会更新，端口/协议以本机日志为准
