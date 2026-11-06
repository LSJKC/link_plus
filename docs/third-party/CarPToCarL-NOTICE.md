# CarPToCarL 源码来源与 MIT 许可声明

本仓库的 CarLife 协议、客户端与视频解码逻辑移植/适配自 [CarPToCarL](https://github.com/jjuugk/CarPToCarL)，按其 MIT 许可保留版权与许可声明。

## 1. 上游信息

| 项目 | 值 |
| --- | --- |
| 仓库 | https://github.com/jjuugk/CarPToCarL |
| 提交 | `7b729fb986492aab0cd492b98ef8d1ad1ec7377`（2026-11-06） |
| 许可 | MIT License |
| 版权 | Copyright (c) 2026 lizhihua |

## 2. 本仓库的移植文件（P1A）

| 本仓库文件 | 与上游的关系 |
| --- | --- |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeProtocol.kt` | 由上游 `app/src/main/java/io/github/shaonialife/carptocar/carlife/CarLifeProtocol.kt` 适配 |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/H264Sps.kt` | 由上游 `.../carlife/H264Sps.kt` 适配 |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeHuClient.kt` | 由上游 `.../carlife/CarLifeHuClient.kt` 适配 |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeVideoPipeline.kt` | 由上游 `CarLifeHuClient.kt` 的视频帧/CSD 处理逻辑适配拆出（GOP/CSD 缓存、裸 IDR 前补 CSD） |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/carlife/CarLifeVideoDecoder.kt` | 由上游 `CarLifeHuClient.kt` 的 H.264 参数处理适配，新增「解码到编码器 Surface」输出链路（上游为码流直通、不重编码） |
| `app/src/main/java/io/github/shaonialife/buicklinkplus/util/LogThrottle.kt` | 由上游 `app/src/main/java/io/github/shaonialife/carptocar/util/LogThrottle.kt` 适配 |

## 3. 本仓库做的修改

- 包名重命名：`io.github.shaonialife.carptocar.*` → `io.github.shaonialife.buicklinkplus.*`；
- 解码目标改造：上游把 CarLife 的 H.264 码流直通发给 CarPlay（不重编码）；本仓库需要把 CarLife 码流解码后送到 link_plus `VideoSender` 的编码器 Surface，因此新增 `CarLifeVideoDecoder` / `CarLifeVideoPipeline` 完成「CarLife H.264 → 解码 → 编码器 Surface」适配；
- 按 link_plus 的 `ScreenSource` / `SessionController` 生命周期接入，保留上游协议报文内容与处理顺序，未改协议语义；
- 日志与注释按本仓库风格调整（中文、统一日志标签）。

完整移植进度见 [PORT_MATRIX.md](../PORT_MATRIX.md)，目标与实车测试口径见 [CARLIFE_PORT_PLAN.md](../CARLIFE_PORT_PLAN.md)。

## 4. 本仓库许可

本仓库根目录 [LICENSE](../../LICENSE) 同为 MIT 许可，Copyright (c) 2026 lizhihua。第 2 节所列移植文件在 MIT 许可下使用；上游的版权声明与完整许可全文如下，按 MIT 要求随本仓库保留。

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
