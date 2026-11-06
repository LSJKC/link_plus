# 实车证据材料说明与脱敏规则（reference-logs）

> 本目录存放 **CarLife × CarPlay 桥** 的“实车已验证行为规格”汇编与证据索引，供移植/排障时直接查阅，
> 避免每次都去翻几十 MB 的原始日志、反编译产物与历史交付文档。
>
> - 编写时间：2026-11-06（Asia/Shanghai）
> - 本仓当前实现快照：`c284f4d`（`carlife-port`；编写本文时分支已从 Lead 所述 `cb5ddc3` 前进 2 个提交）
> - 关联文档：[`FEATURE_EVIDENCE.md`](FEATURE_EVIDENCE.md)（行为规格正文）、
>   [`../PORT_MATRIX.md`](../PORT_MATRIX.md)（当前移植进度）、[`../CARLIFE_PORT_PLAN.md`](../CARLIFE_PORT_PLAN.md)（总体目标）
>
> **本目录只放文档，不放原始日志/抓包/APK/反编译大文件。** 所有结论只引用必要片段并脱敏。

---

## 1. 材料位置（全部为只读外部材料，未纳入本仓库）

### 1.1 真机导出日志（TurtleLink 0.3.63 插桩版，Honor BKQ-AN90 / Android 17）

| 别名 | 绝对路径 | 内容 | 可读性 |
|---|---|---|---|
| `LOG1/` | `F:\carplay\_newproj\port_evidence\turtlelink-logs-1-20261106-1414` | 最完整一轮：`carlife_diag.log`(358 KB)、`main_debug.log`(287 KB)、`carplay-info.txt`(17 KB)、17 个 H.264/会话 `B-*.log`、`previous-process-*.log` 等 | 明文可读；**hex/媒体字节被导出器隐去**（`hex=[omitted]`） |
| `LOG2/` | `F:\carplay\_newproj\port_evidence\turtlelink-logs-2-20261106-1411` | 同上，但 `main_debug.log` 仅 11 KB（该轮导出较晚/被轮转） | 明文可读 |
| `LOG3/` | `F:\carplay\_newproj\port_evidence\turtlelink-logs-3-20261106-1400` | 较早一轮（`main_debug.log` 353 KB，`carplay-info.txt` 17 KB） | 明文可读 |

- 三轮日志指向同一台车：`BUICK_2874` / Saic GM `E2LB` / `SA8155-1.0` / firmware `aegean_n-user 12 SQBP5-39 83`，
  详见 `LOG1/carplay-info.txt` 与 `LOG1/environment.txt`。
- `LOG1/environment.txt`：TurtleLink `0.3.63 (202)`，`package=com.turtlelink.carlife`，
  `device=HONOR/BKQ-AN90 Android=17 sdk=37`；`apkSha256=8338b2…8caa`。
- `LOG1/README.txt` 原文口径：**“密码、密钥、认证及媒体字节隐藏。包含设备与网络标识，请按需分享。”**
  因此日志里的 `hex=[omitted]`、MAC/IP、序列号等**本来就是隐私字段，不得回填进仓库**。
- `LOG1/carplay-info.txt` 含 AirPlay `/info`（两块屏、safeArea、音频能力/HID 描述符）、iAP2 身份声明与
  实际收发消息统计，是“车机侧能力”的第一手证据。

### 1.2 手机端 CarLife 自带日志（xlog）

| 别名 | 绝对路径 | 状态 |
|---|---|---|
| `XLOG/` | `D:\QQ\Downloadscarlife_20261029(1).xlog`（138,329 B，修改时间 2026-10-29 22:43） | **未解密/不可直接读**。文件头 `08 00 00 00…`，属腾讯 mars xlog 的压缩块（magic `0x08` = `MAGIC_COMPRESS_START2`，通常为压缩 + 可选加密；本机没有对应解码工具/私钥）；可读部分只有 mmap 头与时间戳块（`[2026-10-29 +0800 00:59:36]`、`01:01:09`）。**无法解出正文**，本目录不引用其内容。 |

### 1.3 旧工程（hybrid63）源码与反编译

| 别名 | 绝对路径 | 说明 |
|---|---|---|
| `HYB/src/` | `F:\carplay\_newproj\hybrid63\inject\src` | 旧插桩工程里 **我们自己的 Java 注入代码**（`tl/*.java`、`com/turtlelink/carlife/*.java`）。行为规格的“已验证实现”主要来源。 |
| `HYB/decoded/` | `F:\carplay\_newproj\hybrid63\base-decoded` | 0.3.63 反编译工程（apktool smali + resources）。`smali/d9.1.smali`、`smali/Aq.smali` 等处的 `# hybrid:` 注释是原版龟壳合成器接缝的**字节级证据**。注意：它同时含注入后的 `smali/tl/*` 与原始 `defpackage/*`，两者不要混淆。 |
| `HYB/work/` | `F:\carplay\_newproj\hybrid63\work`、`work_rc1`、`work6`、`out` | 构建/打包工作目录；`work/UI37_FULL_AUDIT.md` 是 UI37 静态核对清单。 |
| `DEC/` | `D:\QQ\_turtle`、`_turtle_0319`、`_turtle_dbg`、`_turtle_jadx`、`_dec_all` | 0.3.63 插桩版 APK 的反编译产物（应用包名以 `LOG1/environment.txt` 为准：`com.turtlelink.carlife`；代码命名空间见 `io.github.mrdong916.turtlelink.*` 与 `defpackage.*`，含 `tl.*` 注入类）。**不是手机 CarLife 引擎本体**，只能用于回看旧实现；带 `_0319/_dbg/_jadx` 的是不同反编译批次，内容基本同源。 |
| `DEC/target/` | 原版闭源宿主（本任务材料未单独提供完整包） | 历史交付文档中记录的原版类（`d9`/`Aq`/`Vf`/`Uf`/`E0`/`Q0` 等）来自旧工程构建时的注入前 smali 快照；本次材料里由 `HYB/decoded/` 的 `defpackage/*.smali` 与本仓交付文档间接引用。 |

### 1.4 历史交付文档 / 验收记录（关键证据已二次整理）

| 别名 | 绝对路径 | 最有价值的内容 |
|---|---|---|
| `DEL/0347/` | `F:\carplay\_delivery\0.3.47-carlife-vc195-rc\docs` | **最深的证据挖掘**：`P1_CARLIFE_STUDY.md`、`P2_AUDIO_ROUTING.md`、`P2_TOUCH_WIRING.md`、`P5_EVIDENCE_IAP2_MEDIA_NAV.md`、`P5_ANALYSIS_MEDIA_CLUSTER.md`、`P6_FINAL_AUDIT.md` 等（含大量原版类行号证据） |
| `DEL/0363-hybrid/` | `F:\carplay\_delivery\0.3.63-carlife-hybrid-rc1\docs` | `HYBRID63_PORT_INTERSECTION.md`（hybrid63↔0.3.63 53 项功能对照）、`HYBRID63_SEAM_MAP_CONTENT.md`/`_CLUSTER.md`（接缝位置）、`TASK_BOOK_0363_HYBRID.md`、`HYBRID63_WORKLOG.md` |
| `DEL/0363-v*/` | `F:\carplay\_delivery\0.3.63-carlife-hybrid-v*`、`-rc*`、`-ui*`、`-sup*` | 各轮交付的 `ACCEPTANCE_TRACE.md`、`进度记录-vXX.md`、`UI*_TEST.md`、`*_STATIC_CHECK.txt` 与 **screens/ 截图证据**（导航测试帧、音频自检、安全区画面、合成器输出等） |
| `DEL/0363-audit/` | `F:\carplay\_delivery\0.3.63-carlife-ui37-20261106\docs\UI37_FULL_AUDIT.md` | UI37 全量静态核对：d9 圆角字段、publishFit 触摸、1408B+8 包预灌、TTS 独立 ring、**封面 artwork 剩余缺口（动态 k1 + field26 + SETUP/START/DATA/SUCCESS）** |
| `SPEC/` | `F:\carplay\_newproj\evidence\CARLIFE_HIJACK_SPEC.md`、`COMPOSER_SPEC.md`、`BUICKLINK_INVENTORY.md`、`SYSDIAG_CARPLAY_REF.md`、`TURTLE_0347_FEATURES.md` | 本仓早期证据汇编：CarLife 协议/握手/帧格式、合成器与安全区、CarPlay sysdiagnose 参考、0.3.47 功能盘点 |

> 所有交付文档都是“我方历史结论”，引用时在 `FEATURE_EVIDENCE.md` 里同时给出**一手材料**（日志/源码/smali）位置；
> 只有一手材料缺失时才标 `[二手]`。

---

## 2. 脱敏规则（写文档/引用时强制遵守）

1. **不把原始大文件复制进仓库**：`carlife_diag.log`、`main_debug.log`、`carplay-info.txt`、`*.xlog`、
   APK、抓包、反编译整树都不入库；只摘**必要行段/字段**。
2. **MAC / 蓝牙地址**：`AA:BB:CC:DD:EE:FF` → `AA:XX:XX:XX:XX:FF`；本机蓝牙地址同样只留首尾。
3. **IP / IPv6 链路地址**：`fe80::xxxx:xxxx:xxxx:xxxx%wlan0` → `fe80:…%wlan0`；IPv4 → `192.168.x.x`。
4. **序列号/设备唯一标识**：`serialNumber`、`pi`、`deviceID`、`uuid`（屏幕 UUID 可保留格式但截断）、
   `connId`、SSID/BSSID、账号/手机号一律以 `…` 或 `REDACTED` 代替；确实需要区分时保留前 4/后 2 位。
5. **媒体字节/封面**：不转录 `albumArt`、H.264 帧负载、PCM、文件传输内容；只写长度/类型/时间戳。
6. **日志时间戳**：保留（用于对齐功能时序），但**不要**附带原日志整行中的其它隐私字段。
7. **截图**：只允许引用截图路径与“截图内容描述”（如“0x5201 77B 全字段 hex 弹窗”），
   不把截图二进制拷进仓库；若必须嵌图，先裁掉状态栏/账号等隐私区域。
8. 引用格式统一为 `别名/相对路径:行号`（如 `LOG1/carlife_diag.log:1279`）或
   `别名/相对路径:时间戳`；交付文档用 `DEL/0347/docs/P2_AUDIO_ROUTING.md:145` 这种形式。

---

## 3. 如何复核本目录的结论（不依赖本仓代码）

```powershell
# 1) 音频采样率：真实日志里 44100/48000 各出现在哪些轮次
Select-String -Path "$env:CARLIFE_LOG1\carlife_diag.log" -Pattern '媒体音频格式宣告|audio format '
# 2) 触摸安全区：每一轮 geometry
Select-String -Path "$env:CARLIFE_LOG1\carlife_diag.log" -Pattern 'touch geometry'
# 3) 视频真实尺寸链路
Select-String -Path "$env:CARLIFE_LOG1\carlife_diag.log" -Pattern 'start size=|video params|码流真实分辨率|decoder out '
# 4) iAP2：对端会话表（封面 k1 证据）
Select-String -Path "$env:CARLIFE_LOG1\main_debug.log" -Pattern '采纳对端会话表|MEDIA NowPlaying subscribed'
# 5) 0x5200 导航订阅
Select-String -Path "$env:CARLIFE_LOG1\main_debug.log" -Pattern 'CARPLAY_INFO observed Network 0x5200'
# 6) 原版合成器 d9 字段（注入 patch）
Select-String -Path 'F:\carplay\_newproj\hybrid63\base-decoded\smali\d9.1.smali' -Pattern 'hybrid:|hyL|hyRad'
```

> 行号会随日志/文件版本变化；引用时以“文件 + 内容片段 + 时间戳”三者互相校验。
> 若材料目录被清理，本文的行号引用将失效，但 `DEL/0347/docs`、`DEL/0363-*/docs` 里的
> 证据索引仍然可用（它们是更稳定的一手整理层）。

---

## 4. 已知限制（避免误用）

- **xlog 不可读**（1.2）；其中可能包含手机 CarLife 引擎侧日志，但缺私钥无法解出，本文不依赖它。
- 真机日志由旧版导出器生成：`hex=[omitted]` 表示字段被主动隐藏，**不代表线协议没这些字节**；
  需要真实 hex 时引用交付截图（如 `DEL/0363-hybrid-v28/screens/18-nav-iap2-full-tlvs.png`）。
- `carplay-info.txt` 的 `/info` 是 **AirPlay 车机侧声明**（1972×520 + safeArea 1408×476@278,44），
  与旧注入实现日志里的 `touch geometry car=1972x520 safe=376,44 1413x476` **不是同一矩形**：
  后者是原版合成器按自身安全区/圆角/百分比处理后的最终窗格，触摸必须以窗格为准（见 `FEATURE_EVIDENCE.md` §8.3）。
- 旧工程与 0.3.63 的类名是两代混淆（`C0xxx` vs `defpackage` 短名），**不能按类名直接对拷**；
  `FEATURE_EVIDENCE.md` 只描述行为/字节，不描述代码搬运方式。
- 本目录结论全部来自 2026-11-06 之前材料；CarLife+ 升级（荣耀 8.8.5 → 更新版本）后协议可能变化，
  实车测试包验证时以现场日志为准。
