# PhotoTrans (Android)

> 跨品牌照片/文件无线传输 - Android (Kotlin) 实现。
> TCP 直连 + PT-HI 握手 + HTTP PUT 文件传输（与 iOS 和 HarmonyOS 协议兼容）。

## 功能

- **近场模式**：同一 Wi-Fi 局域网自动发现设备
- **远场模式**：扫码或手动输入 IP 地址直连
- **全格式传输**：照片 / 视频 / 动态照片 / HDR / 文件 / 文件夹
- **格式转译**：HEIC→JPEG、动态照片解析、HDR 元数据保留
- **智能格式识别**：基于文件头魔数，无需联网
- **可更新模型库**：持续学习新设备格式

## 项目结构

```
app/src/main/java/com/phototrans/
  MainActivity.kt          主 Activity
  CrashHandler.kt          全局崩溃捕获
  model/
    LocalModelStore.kt     格式检测模型本地存储
  format/
    ByteArrayExtensions.kt 字节数组扩展
    FormatConverter.kt     格式转换器
    FormatDetector.kt      格式检测器
  ui/
    DeviceAdapter.kt       设备列表适配器
    BrandAdapter.kt        品牌选择适配器
    ModelManagementActivity.kt 模型管理界面
    SettingsActivity.kt    设置界面
  service/
    LearningService.kt     格式学习服务
    TransferService.kt     传输服务
  transport/
    WifiDirectBroadcastReceiver.kt 广播接收器
    WifiDirectTransport.kt 传输层 (TCP+PT-HI+HTTP PUT)
```

## 构建方式

1. 用 Android Studio 打开项目目录
2. 同步 Gradle
3. 连接设备或启动模拟器
4. 点击 Run

## 传输协议 (与 iOS/HarmonyOS 兼容)

### 握手
```
S→R:  PT-HI <deviceName>\n
R→S:  PT-HI <deviceName>\n
```
### 文件传输
```
S→R:  PUT /<filename> HTTP/1.1\r\nContent-Length: <n>\r\n\r\n<raw bytes>
R→S:  HTTP/1.1 200 OK\r\n\r\n
```
默认端口：**47808**

## 开源协议

MIT License