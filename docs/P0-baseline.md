# P0 基线验收记录

> 分支：`carlife-port`  
> 基线提交：`33bed66`（上游 `51fb394` 之上仅新增文档、`scripts/check-env.ps1` 与 `.gitignore`，未改动 `app/` 源码）  
> 记录日期：2026-11-06  
> 结论：**构建基线通过**；**安装与实车连接验收 PENDING**（验收时无连接设备，待接手机 + 车机后按第 5 节执行并回填）  
> 模型变更（2026-11-06 用户要求）：内置无线 ADB 激活已移除；P0 验收采用 Android 原生、无需 ADB 权限的路径（装 APK → 直接连接），详见 [no-adb-model.md](no-adb-model.md)。

P0 的目标是确认基线可以在本机环境构建、安装并启动，为 P1A（CarLife 视频直出）提供可回滚的基线。原版依赖内置 ADB/shell 的能力已按无 ADB 模型移除，相关实车能力随 P1A 验收。

## 1. 构建环境

| 项目 | 值 | 状态 |
| --- | --- | --- |
| 构建 JDK | `F:\carplay\tools\jdk17\jdk-17.0.20.1` | 已确认存在 |
| Android SDK | `F:\carplay\tools\android-sdk` | 已确认存在 |
| platforms | `android-34` | 已安装 |
| build-tools | `35.0.0` | 已安装 |
| NDK | `27.0.12077973` | 已安装 |
| `local.properties` | `sdk.dir=F\:\\carplay\\tools\\android-sdk` | 已写入 |

工程自带 Gradle 8.13，使用 AGP 8.13.2、Kotlin 2.3.21；详细版本见 [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md) §14.6。

## 2. 基线构建

```powershell
cd F:\carplay\_newproj\linkplus_carlife
$env:JAVA_HOME='F:\carplay\tools\jdk17\jdk-17.0.20.1'
.\gradlew.bat :app:assembleBuick
```

2026-11-06 基线复验（增量重建）结果：

```text
BUILD SUCCESSFUL in 18s
53 actionable tasks: 3 executed, 50 up-to-date
```

首次全量构建记录见 [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md) §14.7（`BUILD SUCCESSFUL in 7m 32s`）。

产物：

| 项目 | 值 |
| --- | --- |
| 输出路径 | `app/build/outputs/apk/buick/app-buick.apk` |
| 大小 | 27,192,725 字节 |
| SHA-256 | `AFD57E5A654EEBBCD7C85D2D2466EE04C0CC7B7839F7D75E1822417A53F80348` |
| 变体 | `buick`（applicationId 带 `.buick` 后缀，使用本机 debug 签名） |

SHA-256 由本机 `Get-FileHash app\build\outputs\apk\buick\app-buick.apk -Algorithm SHA256` 复核，与构建产物一致。

## 3. P0 改动边界

- 要求：P0 阶段不改动源码；
- 核对：`git diff --stat 51fb394 33bed66` 只涉及 `.gitignore`、`AGENTS.md`、`docs/CARLIFE_PORT_PLAN.md`、`reference/README.md`、`scripts/check-env.ps1`（均为文档与构建辅助文件，`app/` 无改动）；
- 结论：符合 P0「无源码改动」要求。

## 4. 设备与安装状态

- P0 验收时主机 `adb devices` 无连接设备（输出只有 `List of devices attached`）；
- 因此未执行安装与实车连接，本记录不填写任何未实际观察到的结果；
- 本分支没有内置 ADB 激活流程：安装与实车验收统一记为 **PENDING（待接手机 + 车机后执行）**，按下节清单执行并回填状态表。

## 5. 待执行验收清单

1. 安装 buick 包：直接安装 `app\build\outputs\apk\buick\app-buick.apk`（开发调试可用主机 `adb install -r`；手机侧不需要开启 ADB 或无线调试）。`.buick` 后缀与正式包不同，签名不同时不能直接覆盖安装，必要时先卸载旧包。
2. 启动应用，完成首次免责声明、通知使用权等系统授权。
3. 无内置 ADB 激活流程：装 APK → 直接连接（Android 原生权限）；不需要开发者选项、无线调试或 ADB 配对，详见 [no-adb-model.md](no-adb-model.md)。
4. 手机与车机蓝牙配对，连接目标车机，确认车机出现画面。
5. 连接后验证画面、声音、触摸（P1A 起内容源为 CarLife）；记录车机型号、手机型号/ROM、视频 profile（分辨率/帧率/码率）。
6. 应用内导出日志，确认包含蓝牙、iAP2、AirPlay 等关键行。
7. 回填下面的状态表，并按 [CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md) §6（P0 验收）与 §10（实车验收矩阵）补齐记录。

| 验收项 | 结果 | 证据/备注 |
| --- | --- | --- |
| `:app:assembleBuick` 构建 | PASS（2026-11-06） | 见第 2 节 |
| P0 无源码改动 | PASS | 见第 3 节 |
| 安装 buick APK | PENDING | 无连接设备 |
| 启动 / 首次授权 | PENDING | 待接手机 |
| 无内置 ADB 激活（装 APK → 直接连接） | PENDING（待安装验证） | 模型见 [no-adb-model.md](no-adb-model.md) |
| 连接车机（蓝牙 iAP2 + Wi-Fi + AirPlay） | PENDING | 待接手机 + 车机 |
| 车机画面 | PENDING | |
| 声音 | PENDING | |
| 触摸 | PENDING | |
| 日志导出 | PENDING | 日志内需含关键行后再算通过 |

## 6. 证据留存

- 构建日志：本机 `build_p0_baseline.log`（`*.log` 已被 `.gitignore` 忽略，未入库）；
- APK 每次验收前用 `Get-FileHash ... -Algorithm SHA256` 复核 SHA-256；
- 实车验收后把手机/车机型号、ROM 版本、日志导出文件名（及截图，可选）记录到第 5 节状态表，日志文件作为验收附件保存。
