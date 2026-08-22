package com.phototrans.transport

import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Direct 传输层
 *
 * 兼容所有品牌互传协议:
 *   - 小米/OPPO/vivo/真我/魅族 互传联盟: Wi-Fi Direct + HTTP PUT
 *   - 三星 Quick Share: Wi-Fi Direct + HTTP
 *   - 华为 Share: Wi-Fi Direct + TCP
 *   - Google Nearby Share: Wi-Fi + BLE
 *
 * 统一使用 Wi-Fi Direct + HTTP PUT 传输文件
 *
 * 使用流程:
 *   1. 接收方: startServer(saveDir) → 创建 P2P 群组并监听端口
 *   2. 发送方: connect(device) → 加入群组 → 群组形成后回调 onConnected
 *             携带 GO 地址 (接收方), 用 sendFile 发送
 */