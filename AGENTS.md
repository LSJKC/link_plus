# Agent 工作说明（CarLife × link_plus）

本仓库是从 [schemawisp/link_plus](https://github.com/schemawisp/link_plus) fork 的 CarLife 适配分支：把 hybrid63 插桩版已验证的 CarLife 功能**全面移植为源码**，CarLife 作为无线 CarPlay 的**唯一内容源**；去掉内置无线 ADB 激活，瘦身 APK，出实车测试包，并建立与上游的 GitHub 下游同步。

开始任何实现前，必须先读：

1. `docs/CARLIFE_PORT_PLAN.md`（目标、架构、迁移映射、实车测试清单、旧工程踩坑、环境要求）
2. `docs/PORT_MATRIX.md`（功能移植矩阵：状态/目标文件/参考路径/去重清单）
3. `docs/GITHUB_WORKFLOW.md`（上游同步与实车测试包发布流程）
4. `reference/README.md`（CarPToCarL/carvex 最新快照）
5. `README.md` / `docs/architecture.md`（上游结构）
6. 旧工程只读参考：`F:\carplay\_newproj\hybrid63\inject\src`
7. `docs/no-adb-model.md`（无内置 ADB 激活模型、权限边界与用户操作）

硬约束：

- 不修改闭源 APK，不做 smali 插桩；
- 不保留内置无线 ADB 激活；不引入 root/Shizuku/ADB；只使用 Android 原生、无需 ADB 权限的能力；
- 摄像头/麦克风/媒体权限按需申请；
- 不硬编码 iAP2 file-transfer session 为 12；真车按 k1 动态取；
- 不把歌词写进 iAP2（官方 0x5001 无歌词字段）；
- 不再逐阶段自测/验收：功能逻辑正确 + 可编译打包即可；全部功能就绪后统一出实车测试包验证，失败按提交/整包回滚；
- 旧插桩 UI37 保留，不在本仓库改它。

当前目标：

```text
把 hybrid63 已验证功能全面移植为源码 + 去掉内置无线 ADB 激活 + 瘦身 APK + 出实车测试包 + 建立 GitHub 下游同步
```

功能级进度以 `docs/PORT_MATRIX.md` 为准；不要再新写“逐阶段验收/自测”流程文档。

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

实车测试与反馈：按 `docs/CARLIFE_PORT_PLAN.md` §10 清单出实车测试包并记录结果；日志用应用内「设置 → 帮助与诊断 → 导出日志文件」导出，随手机/车机型号与复现步骤反馈。上游同步见 `docs/GITHUB_WORKFLOW.md`。
