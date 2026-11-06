# GitHub 下游同步流程

> 状态：**已配置**（fork = `LSJKC/link_plus`，upstream = `schemawisp/link_plus`，CI 已启用）。  
> 适用仓库：`F:\carplay\_newproj\linkplus_carlife`（从 `schemawisp/link_plus` fork 的 CarLife 适配分支）。  
> 相关文档：[CARLIFE_PORT_PLAN.md](CARLIFE_PORT_PLAN.md)、[PORT_MATRIX.md](PORT_MATRIX.md)、[carlife-mechanism.md](carlife-mechanism.md)、[no-adb-model.md](no-adb-model.md)。
>
> 最新测试包见 GitHub Releases（当前版本 `0.0.9-carlife.1`，versionCode 158）。  
> CI 构建产物：GitHub Actions workflow `build-buick-apk`（push `carlife-port` 自动构建 `app-buick.apk` artifact）。

## 1. 目标与原则

1. 本仓库是上游 [schemawisp/link_plus](https://github.com/schemawisp/link_plus)（MIT）的**下游 fork**：上游持续修 bug/发版，本仓库要能低成本跟进；
2. `main` 只做上游镜像，不加 CarLife 提交；所有开发在 `carlife-port`（及其功能分支）；
3. 同步动作优先“先合上游 → 再验证 → 再推送”，冲突以“保留本仓库 CarLife/无 ADB 定制 + 吸收上游修复”为原则；
4. 永不提交任何 token、密钥、签名文件、`local.properties`、APK/AAB 或 `reference/` 源码；
5. 上游许可与版权声明必须保留（`LICENSE`、`docs/dependencies.md`、`docs/third-party/`）。

## 2. 远端与分支

| 名称 | 用途 | 地址 |
| --- | --- | --- |
| `upstream` | 上游只读镜像（**只 fetch，不 push**） | `https://github.com/schemawisp/link_plus.git` |
| `origin` | 本仓库 fork | `https://github.com/LSJKC/link_plus.git` |

分支约定：

| 分支 | 用途 | 规则 |
| --- | --- | --- |
| `main` | 上游镜像 | 只允许 fast-forward 合并 `upstream/main`；不直接开发 |
| `carlife-port` | CarLife 开发主线 | 日常提交；同步上游后在此验证构建 |
| `sync/upstream-YYYYMMDD`（可选） | 大版本同步/冲突验证 | 从 `carlife-port` 拉出，验证通过后合并回 `carlife-port` |
| `release/test-vX.Y.Z`（可选） | 实车测试包 | 只放版本号/构建产物信息，不强制 |

## 3. 首次配置（只做一次）

```powershell
cd F:\carplay\_newproj\linkplus_carlife

# 上游只读 remote（fetch 用）
git remote add upstream https://github.com/schemawisp/link_plus.git
# 防误 push：把 upstream 的 push URL 设为 no_push（GitHub fork 文档的常用约定）
git remote set-url --push upstream no_push

# fork remote（URL 待 Lead 补）
git remote add origin <fork-url>

git remote -v
git fetch upstream --prune
git branch -a
```

`git remote set-url --push upstream no_push` 会让误推 upstream 直接报错，是刻意的保护。

## 4. 定期同步上游（建议每周/上游发版时）

```powershell
cd F:\carplay\_newproj\linkplus_carlife
$env:HTTP_PROXY='http://127.0.0.1:7890'   # 网络需要时
$env:HTTPS_PROXY='http://127.0.0.1:7890'

git fetch upstream --prune
git log --oneline carlife-port..upstream/main   # 先看上游新增提交
```

1. **更新 main（上游镜像）**

```powershell
git checkout main
git merge --ff-only upstream/main
git push origin main
```

2. **同步到开发分支**（二选一，默认 rebase 保持线性；`carlife-port` 已推送共享时优先 merge）

```powershell
git checkout carlife-port
git rebase upstream/main          # 未推送的私有提交：rebase
# 或
git merge upstream/main           # 已共享的分支：merge，保留历史
```

3. **解决冲突**（见 §5），确保 `app/` 下 CarLife 相关文件、无 ADB 改动不被上游覆盖。

4. **验证构建（推送前必须）**

```powershell
$env:JAVA_HOME='F:\carplay\tools\jdk17\jdk-17.0.20.1'
.\gradlew.bat :app:assembleBuick
```

构建通过后再推送；推送共享分支前确认没有遗留 `git status` 未提交内容。

5. **推送**

```powershell
git push origin carlife-port
```

若使用 rebase 改写了已推送历史，需要 `--force-with-lease`；**只在确认没有其他协作者基于旧历史工作时使用**。

## 5. 冲突处理

1. 冲突文件先看归属：
   - 上游改动 <-> 本仓库 CarLife/无 ADB 定制冲突：**先吸收上游逻辑，再在其上重新应用本仓库改动**；
   - 本仓库多个功能分支互相冲突：按 `PORT_MATRIX.md` 状态，优先保证已实现功能的可编译与可用；
2. 一次只解决一类文件，解决后 `git add`，不要 `git add -A` 混入未知文件；
3. 冲突大/不确定时：

```powershell
git rebase --abort        # 或 git merge --abort
git checkout -b sync/upstream-YYYYMMDD
git merge upstream/main
# 在小分支上逐步解决并验证构建，再合并回 carlife-port
```

4. 记录冲突清单与取舍到提交信息（例如 `chore(sync): merge upstream 2026-11-xx`），必要时在 `docs/` 记录；
5. 上游若删除/重命名本仓库依赖的 API（`ScreenSource`、`SessionController`、`VideoSender` 等），按上游新接口适配，不硬保留旧 API。

## 6. 提交与推送前检查

- [ ] `git status` 无意外文件；不包含 `local.properties`、`keystore.properties`、`*.jks/keystore`、APK、`reference/`；
- [ ] `.\gradlew.bat :app:assembleBuick` BUILD SUCCESSFUL；
- [ ] （可选）`.\gradlew.bat spotlessCheck`、`:app:testBuickUnitTest`：属于质量校验，不作为逐阶段门槛；
- [ ] 文档同步：功能状态更新到 `docs/PORT_MATRIX.md`，用户可见行为更新 `README.md`/`docs/no-adb-model.md`；
- [ ] 许可声明未删改（`LICENSE`、`docs/dependencies.md`、`docs/third-party/`）。

## 7. 实车测试包发布

1. 在 `app/build.gradle.kts` 递增 `appVersionCode`，更新 `appVersionName`；
2. 构建 buick 包并记录指纹：

```powershell
.\gradlew.bat :app:assembleBuick
Get-FileHash app\build\outputs\apk\buick\app-buick.apk -Algorithm SHA256
```

3. 打 tag（与版本一致）：`git tag vX.Y.Z-carlife && git push origin vX.Y.Z-carlife`；
4. 在 GitHub Release 附上 APK、版本号、SHA-256、变更摘要、日志反馈方式（应用内「设置 → 日志与关于 → 导出日志」）；不随 Release 分发旧闭源应用资源；
5. 实车结果记录到 Release 说明或随日志反馈；失败项按 `PORT_MATRIX.md` 回写状态。

## 8. 禁止事项

- 不向 `upstream` push（remote 已禁用 push）；
- 不提交 token/密钥/签名/`local.properties`/APK/`reference/`；
- 不改上游 `LICENSE` 与版权声明；
- 不在未构建通过时推送 `main`/`carlife-port`；
- 不对已共享分支强推（除经协调的 rebase 同步）。

## 9. 待补充

- 分支保护规则（`main` 禁止直推、`carlife-port` 是否要求 PR）；
- Issue 模板 / 实车反馈渠道（日志上传方式、机型模板）。

> 版本与 Release 命名约定（2026-11-06 起）：versionName 跟随上游，例如上游 0.0.9 → `0.0.9-carlife`；versionCode 每次出包 +1（Android 只认 versionCode）。Release tag 用 `vX.Y.Z-carlife`，资产名 `app-buick-X.Y.Z-carlife.apk`。
