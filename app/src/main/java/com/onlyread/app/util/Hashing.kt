package com.onlyread.app.util

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 轻量文件指纹：首/尾各 64KB + 文件长度。
 * 用于 FR-02 身份键的变更快检，不整文件哈希（大书友好）。
 */
object Hashing {

    private const val CHUNK = 64 * 1024

    fun shortHash(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        RandomAccessFile(file, "r").use { raf ->
            val len = file.length()
            val headLen = minOf(CHUNK.toLong(), len).toInt()
            val head = ByteArray(headLen)
            raf.readFully(head)
            md.update(head)
            if (len > CHUNK) {
                val tailLen = minOf(CHUNK.toLong(), len - CHUNK).toInt()
                raf.seek(len - tailLen)
                val tail = ByteArray(tailLen)
                raf.readFully(tail)
                md.update(tail)
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return "${file.length().toString(16)}-$hex"
    }
}
