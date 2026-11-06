# Agent 工作说明（CarLife × link_plus）

本仓库是从 [schemawisp/link_plus](https://github.com/schemawisp/link_plus) fork 的 CarLife 适配分支。

开始任何实现前，必须先读：

1. `docs/CARLIFE_PORT_PLAN.md`（目标、架构、阶段、验收、旧工程踩坑、最新源码快照、环境要求）
2. `reference/README.md`（CarPToCarL/carvex 最新快照）
3. `README.md` / `docs/architecture.md`（上游结构）
4. 旧工程只读参考：`F:\carplay\_newproj\hybrid63\inject\src`
5. `docs/no-adb-model.md`（无内置 ADB 激活模型、权限边界与用户操作）

硬约束：

- 不修改闭源 APK，不做 smali 插桩；
- 不保留内置无线 ADB 激活；不引入 root/Shizuku/ADB；只使用 Android 原生、无需 ADB 权限的能力；
- 摄像头/麦克风/媒体权限按需申请；
- 不硬编码 iAP2 file-transfer session 为 12；真车按 k1 动态取；
- 不把歌词写进 iAP2（官方 0x5001 无歌词字段）；
- 每阶段先编译/测试，再实车验收；失败回滚，不一次性合入；
- 旧插桩 UI37 保留，不在本仓库改它。

第一个实现目标：

```text
P0 基线 → P1A CarLife H.264 直出到 VideoSender Surface
```

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
