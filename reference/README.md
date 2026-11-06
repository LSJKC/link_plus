# 参考源码快照

本目录只读，不参与本仓库构建，也不提交到 git。

| 项目 | 路径 | 当前快照 |
|---|---|---|
| link_plus（工作基线） | 本仓库 `carlife-port` | `51fb394` |
| CarPToCarL（CarLife 主实现参考） | `reference/CarPToCarL` | `7b729fb`（2026-11-06，音频 48K AAC 更新） |
| carvex-android（iAP2/音频参考） | `reference/carvex-android` | `aa7b750` |
| carvex-carplay-research（协议研究） | `reference/carvex-carplay-research` | `60dd4fc` |
| 旧插桩工程（只读） | `F:\carplay\_newproj\hybrid63` | UI37 候选 |

## 更新命令（新窗口先跑）

```powershell
$env:HTTP_PROXY='http://127.0.0.1:7890'
$env:HTTPS_PROXY='http://127.0.0.1:7890'

git fetch origin
git -C reference/CarPToCarL fetch --depth 1 origin
git -C reference/carvex-android fetch --depth 1 origin
git -C reference/carvex-carplay-research fetch --depth 1 origin

git -C reference/CarPToCarL log -1 --format='%H %ad %s' --date=iso
git -C reference/carvex-android log -1 --format='%H %ad %s' --date=iso
```

不要把 `reference/` 提交进主仓库；它只用于对照。
