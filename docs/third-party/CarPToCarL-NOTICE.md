# CarPToCarL 开源实现来源与 MIT 许可声明

本仓库的 CarLife 协议、客户端与视频/音频处理部分**使用了 [CarPToCarL](https://github.com/jjuugk/CarPToCarL) 的 MIT 开源实现，并按 link_plus 的链路改造**（本项目基于 [schemawisp/link_plus](https://github.com/schemawisp/link_plus) 开发）；按其 MIT 许可保留版权与许可声明。

## 1. 上游信息

| 项目 | 值 |
| --- | --- |
| 仓库 | https://github.com/jjuugk/CarPToCarL |
| 提交 | `7b729fb986492aab0cd492b98ef8d1ad1ec7377`（2026-11-06） |
| 许可 | MIT License |
| 版权 | Copyright (c) 2026 lizhihua |

## 2. 本仓库中的相关实现文件

| 本仓库文件 | 与上游的关系 |
| --- | --- |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeProtocol.kt` | 基于上游 `app/src/main/java/io/github/shaonialife/carptocar/carlife/CarLifeProtocol.kt` 改写 |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/H264Sps.kt` | 基于上游 `.../carlife/H264Sps.kt` 改写 |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeHuClient.kt` | 基于上游 `.../carlife/CarLifeHuClient.kt` 改写 |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeVideoPipeline.kt` | 使用上游 `CarLifeHuClient.kt` 的视频帧/CSD 处理逻辑，按 link_plus 结构拆出（GOP/CSD 缓存、裸 IDR 前补 CSD） |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeVideoDecoder.kt` | 使用上游 `CarLifeHuClient.kt` 的 H.264 参数处理，并按 link_plus 链路新增「解码到 VideoSender 编码器 Surface」的输出链路 |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/util/LogThrottle.kt` | 基于上游 `app/src/main/java/io/github/shaonialife/carptocar/util/LogThrottle.kt` 改写 |
| CarLife 音频/媒体/导航等实现（`carlife/CarLifeAudioSink.kt`、`CarLifeAudioPipe.kt`、`CarLifeNav.kt` 等） | 使用上游对应逻辑，按 link_plus 的 `AudioSender` / iAP2 导航上行链路改造 |

## 3. 本仓库做的修改

- 包名重命名：`io.github.shaonialife.carptocar.*` → `io.github.shaonialife.buicklinkplus.*`；
- 接入 link_plus 链路：CarLife 视频解码到 link_plus `VideoSender` 的编码器 Surface；音频经 `AudioSender` 送入 CarPlay 音频流；导航/媒体信息转为 link_plus 的 iAP2 上行；
- 无 ADB 化：去除本项目不需要的高权限激活路径，只使用 Android 原生能力；
- 日志与注释按本仓库风格调整（中文、统一日志标签），并保留上游来源注释。

功能与界面说明见 [PORT_MATRIX.md](../PORT_MATRIX.md)，开发目标与实车测试口径见 [CARLIFE_PORT_PLAN.md](../CARLIFE_PORT_PLAN.md)。

## 4. 本仓库许可

本仓库根目录 [LICENSE](../../LICENSE) 为 MIT 许可。第 2 节所列实现文件在 MIT 许可下使用；上游的版权声明与完整许可全文如下，按 MIT 要求随本仓库保留。

上游仓库的 `LICENSE` 在 MIT 全文后另附 `ADDITIONAL NOTICE`（该说明不修改、不扩大 MIT 授权范围，仅说明许可不授予 Apple / 百度 / 车厂相关的专利、商标与认证权利，使用者需自行取得相应授权）。对外分发包含本节文件的版本时，建议同时保留该说明；原文见上游仓库 `LICENSE`。

## 5. MIT 许可全文

```text
MIT License

Copyright (c) 2026 lizhihua

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
