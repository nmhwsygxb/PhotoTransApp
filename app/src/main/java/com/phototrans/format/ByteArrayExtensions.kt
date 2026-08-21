package com.phototrans.format

/**
 * ByteArray 子序列搜索扩展函数
 *
 * Kotlin 标准库的 ByteArray.indexOf / lastIndexOf 只支持单个 Byte,
 * 这里补齐 Python 原型中 bytes.indexOf(ByteArray) 的语义,
 * 用于在 JPEG/HEIC 二进制中查找 XMP 标记、品牌签名等。
 */

/** 查找子数组 pattern 首次出现的位置, 返回起始索引; 未找到返回 -1 */
fun ByteArray.indexOfBytes(pattern: ByteArray, startIndex: Int = 0): Int {
    if (pattern.isEmpty() || startIndex < 0 || startIndex > size) return -1
    val maxStart = size - pattern.size
    if (maxStart < 0 || startIndex > maxStart) return -1
    outer@ for (i in startIndex..maxStart) {
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) continue@outer
        }
        return i
    }
    return -1
}

/** 查找子数组 pattern 最后一次出现的位置, 返回起始索引; 未找到返回 -1 */
fun ByteArray.lastIndexOfBytes(pattern: ByteArray): Int {
    if (pattern.isEmpty()) return -1
    var i = size - pattern.size
    if (i < 0) return -1
    while (i >= 0) {
        var j = 0
        while (j < pattern.size && this[i + j] == pattern[j]) j++
        if (j == pattern.size) return i
        i--
    }
    return -1
}

/** 从 startIndex 开始查找单个字节, 返回索引; 未找到返回 -1 */
fun ByteArray.indexOfByte(value: Byte, startIndex: Int = 0): Int {
    for (i in startIndex until size) {
        if (this[i] == value) return i
    }
    return -1
}

/** 从 fromIndex (含) 向前查找单个字节, 与 Python rfind(..., end) 语义一致 */
fun ByteArray.lastIndexOfByte(value: Byte, fromIndex: Int = size - 1): Int {
    var i = if (fromIndex < size) fromIndex else size - 1
    while (i >= 0) {
        if (this[i] == value) return i
        i--
    }
    return -1
}