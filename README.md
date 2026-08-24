<div align="center">

# 📸 PhotoTrans

### 跨品牌照片 / 文件无线传输 · Android

无需流量 · 无需云端 · 局域网直连 · 三端互通

[![Build](https://github.com/nmhwsygxb/PhotoTransApp/actions/workflows/build-android.yml/badge.svg)](https://github.com/nmhwsygxb/PhotoTransApp/actions/workflows/build-android.yml)
[![Release](https://img.shields.io/github/v/release/nmhwsygxb/PhotoTransApp?include_prereleases)](https://github.com/nmhwsygxb/PhotoTransApp/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/nmhwsygxb/PhotoTransApp/total)](https://github.com/nmhwsygxb/PhotoTransApp/releases/latest)
[![License](https://img.shields.io/github/license/nmhwsygxb/PhotoTransApp)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://github.com/nmhwsygxb/PhotoTransApp)

**同项目的另外两端：** [iOS · SwiftUI](https://github.com/nmhwsygxb/PhotoTrans-iOS) · [HarmonyOS · ArkTS](https://github.com/nmhwsygxb/PhotoTrans-HarmonyOS)

</div>

---

## 为什么做这个

各家手机品牌的照片封装格式各不相同——华为的 HDR+、苹果的 HEIC/Live Photo、小米/OPPO/vivo 的动态照片——互相传时要么打不开、要么丢元数据。**PhotoTrans 在传输时自动做格式转译**，让任意两台手机之间都能无缝传照片，同时保留 HDR、动态效果等原始信息。

普通文件和文件夹也能直接传，不限于照片。

## 核心特性

| 特性 | 说明 |
|---|---|
| 🔗 跨平台互通 | 与 iOS / HarmonyOS 版协议完全兼容，三端互传 |
| 🏷 品牌格式转译 | HEIC⇄JPEG、动态照片解析、HDR 元数据保留 |
| 🧠 离线格式识别 | 基于文件头魔数，不联网、不依赖云端 |
| 📁 全类型传输 | 照片 / 视频 / 动态图 / HDR / 任意文件 / 整个文件夹 |
| 📶 双模式连接 | 近场：同 Wi-Fi 自动发现 · 远场：扫码 / 输入 IP 直连 |
| 🔒 隐私优先 | 纯局域网 P2P，文件不经任何服务器 |
| 📚 可学习模型库 | 支持持续学习新设备格式，本地存储 |

## 下载

从 [Releases](https://github.com/nmhwsygxb/PhotoTransApp/releases/latest) 页直接下载 APK 安装：

> 当前版本 **android-v1.0.2** · 含全部 bug 修复（握手校验 / 读超时 / 非 2xx 不误报成功 / 背压防 OOM）

## 构建

1. 用 Android Studio 打开本项目目录
2. 同步 Gradle（需 JDK 17、Android SDK 34）
3. 连接设备或启动模拟器
4. 点击 **Run**

```bash
# 命令行构建
./gradlew assembleDebug
```

## 项目结构

```
app/src/main/java/com/phototrans/
  MainActivity.kt                    主 Activity
  CrashHandler.kt                    全局崩溃捕获
  model/LocalModelStore.kt           格式检测模型本地存储
  format/FormatDetector.kt           格式检测（魔数）
  format/FormatConverter.kt          格式转译（HEIC→JPEG / HDR / 动态照片）
  ui/                                设备列表 / 品牌选择 / 模型管理 / 设置
  service/LearningService.kt         格式学习后台服务
  service/TransferService.kt         传输前台服务
  transport/WifiDirectTransport.kt   传输层（TCP + PT-HI 握手 + HTTP PUT）
```

## 传输协议（三端通用）

```
握手    S→R:  PT-HI <deviceName>\n
        R→S:  PT-HI <deviceName>\n

传输    S→R:  PUT /<filename> HTTP/1.1\r\nContent-Length: <n>\r\n\r\n<raw bytes>
        R→S:  HTTP/1.1 200 OK\r\n\r\n
```

- UDP 发现：端口 **47809**，beacon `PT-BEACON|name|brand|ip|port|`，每 2s 广播，8s 超时清理
- TCP 传输：端口 **47808**，同一 socket 双向 PT-HI 握手后开始 PUT

## 开源协议

[MIT License](LICENSE)