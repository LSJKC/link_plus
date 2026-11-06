# Agent 工作说明（CarLife × link_plus）

本仓库**基于 [schemawisp/link_plus](https://github.com/schemawisp/link_plus)（Buick Link+，MIT）开发**：保留其蓝牙 iAP2 / AirPlay / VideoSender / AudioSender / 会话管理链路，增加 **CarLife 内容源**——手机 CarLife+ 以「无线连接」模式在本机监听 TCP，本应用作为车机端接入画面/音频/触摸，再经 link_plus 发送到车机。CarLife 协议/客户端/视频/音频部分使用了 [CarPToCarL](https://github.com/jjuugk/CarPToCarL) 的 MIT 开源实现（commit `7b729fb`，Copyright (c) 2026 lizhihua）并按 link_plus 链路改造。无需 root / ADB / Shizuku。

开始任何实现前，必须先读：

1. `docs/CARLIFE_PORT_PLAN.md`（目标、架构、实现要点、历史阶段、实车测试清单）
2. `docs/PORT_MATRIX.md`（功能与界面说明：实现位置 / UI 入口或设置 key / 状态）
3. `docs/carlife-mechanism.md`（连接原理：CarLife+ 本机端口与本应用的车机端角色）
4. `docs/no-adb-model.md`（无内置 ADB 的权限模型与用户操作）
5. `docs/GITHUB_WORKFLOW.md`（上游同步与测试包发布流程）
6. `README.md` / `docs/architecture.md`（项目结构与模块）
7. `reference/README.md`（本地参考实现快照，只读、不入库）

硬约束：

- 不提供、不分发旧闭源应用资源；不修改或再分发第三方闭源应用；
- 不保留内置无线 ADB 激活；不引入 root/Shizuku/ADB；只使用 Android 原生、无需 ADB 权限的能力；
- 摄像头/麦克风/媒体权限按需申请；
- 不硬编码 iAP2 file-transfer session 为 12；真车按 k1 动态取；
- 不把歌词写进 iAP2（官方 0x5001 无歌词字段）；
- 不再逐阶段自测/验收：功能逻辑正确 + 可编译打包即可；全部功能就绪后统一出实车测试包验证，失败按提交/整包回滚；
- 历史参考工程只读，不对外、不随仓库分发。

当前目标：

```text
基于 link_plus 完成 CarLife 内容源（视频/画面合成/音频/TTS/触摸/导航/媒体/HUD/语音/GPS/设置页等）+ 去掉内置无线 ADB 激活 + 瘦身 APK + 出实车测试包 + 建立 GitHub 下游同步
```

功能状态以 `docs/PORT_MATRIX.md` 为准；不要再新写“逐阶段验收/自测”流程文档。

开工前：

```powershell
cd F:\carplay\_newproj\linkplus_carlife
$env:HTTP_PROXY='http://127.0.0.1:7890'
$env:HTTPS_PROXY='http://127.0.0.1:7890'
$env:JAVA_HOME='F:\carplay\tools\jdk17\jdk-17.0.20.1'
.\scripts\check-env.ps1
.\gradlew.bat :app:assembleBuick
```

提交前确认：

```powershell
.\gradlew.bat :app:assembleBuick
```

实车测试与反馈：按 `docs/CARLIFE_PORT_PLAN.md` 的实车测试清单出测试包并记录结果；日志用应用内「设置 → 帮助与诊断」分享/保存（ZIP），随手机/车机型号与复现步骤反馈。上游同步见 `docs/GITHUB_WORKFLOW.md`。
