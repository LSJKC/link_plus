# 项目结构

工程只有一个 `app` 模块，使用 Kotlin、View/XML、AIDL 和 OpenGL。本项目基于 [schemawisp/link_plus](https://github.com/schemawisp/link_plus) 开发，在它的 CarPlay 发送链路上增加 CarLife 内容源。

## 连接怎么走

手机与车机之间：蓝牙 iAP2 交换热点和端点信息，手机加入车机网络，再通过 mDNS 发现 AirPlay 服务；配对完成后视频走 H.264、音频走 RTP。

CarLife 内容源：手机 CarLife+ 以「无线连接」模式在本机监听 TCP（控制/视频/音频/触摸端口），本应用作为“车机端”连接这些端口，收 H.264/音频/触摸，交给 link_plus 的 `VideoSender` / `AudioSender` 发送到车机。连接原理与端口时序见 [CarLife 连接机制](carlife-mechanism.md)；无 ADB/root/Shizuku，见 [无 ADB 模型与权限](no-adb-model.md)。

## 代码放在哪

| 目录或类 | 负责什么 |
| --- | --- |
| MainActivity、phone、ui | 手机首页、设置、授权和页面状态 |
| session | 连接状态、取消、重连和资源释放 |
| bt、iap2、net、airplay | 蓝牙、网络发现、配对和 CarPlay 协议通信 |
| carlife | CarLife 协议/客户端、音频、导航、媒体 NowPlaying、GPS、语音键 |
| capture | `CarLifeScreenSource` 等画面内容源的生命周期 |
| render | 画面合成（壁纸背景、安全区 contain、圆角）与显示几何 |
| input | 车机触摸坐标映射与派发、语音键路由 |
| video、audio | H.264 编码/推流与 CarPlay RTP 音频发送 |
| launcher、media | 手机桌面入口、媒体状态/天气等辅助界面 |
| util | 日志、诊断导出、设备身份等工具 |

`SessionController` 管理整个连接，手机界面读取 `SessionSnapshot` 展示状态。取消或断开时先更新状态，再在后台释放资源；旧连接的回调不能改动新连接。

## 显示与线程

CarLife 画面由 `CarLifeComposer` 合成：解码输出到 OES 纹理，按安全区 contain 到编码器画面，支持壁纸背景与圆角，再送入 `VideoSender` 的编码器 Surface。触摸按最近一次成功提交的画面换算；Android 坐标从顶部算，GL 坐标从底部算。

短任务使用 `Bg`，推流、收包和 GL 循环使用独立线程。图片解码、文件读写与日志导出放在后台；日志通过统一的 `CarPlaySender` 标签输出，便于实车抓取。

包名、AIDL 接口、偏好键和配对身份参与安装或协议兼容，调整它们前要检查调用方和已有数据。
