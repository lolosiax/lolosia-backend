package moe.lolosia.web.util.fs

import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.*

/**
 * 读取整个文件（posix）；文件不存在或读不全返回 `null`。
 *
 * mingw 上 C 的 `long` 是 32 位，`ftell`/`fseek` 的签名与 Linux 不同，这里统一转成 `Long`。
 */
@OptIn(UnsafeNumber::class)
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
fun readFileBytes(absPath: String): ByteArray? {
    val file = fopen(absPath, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val length = ftell(file).toLong()
        if (length <= 0L) return null
        rewind(file)
        val out = ByteArray(length.toInt())
        val readCount = out.usePinned { pinned -> fread(pinned.addressOf(0), 1uL, length.toULong(), file).toLong() }
        return if (readCount == length) out else null
    } finally {
        fclose(file)
    }
}

/** 覆盖写入整个文件（posix）；父目录不存在时逐级创建。 */
@OptIn(UnsafeNumber::class)
fun writeFileBytes(absPath: String, bytes: ByteArray): Boolean {
    makeDirectories(absPath.substringBeforeLast('/', ""))
    val file = fopen(absPath, "wb") ?: return false
    try {
        if (bytes.isEmpty()) return true
        val written = bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file).toLong()
        }
        return written == bytes.size.toLong()
    } finally {
        fclose(file)
    }
}

/** 逐级创建目录；已存在时创建失败，忽略即可（真正的失败留给随后的写入去暴露）。 */
fun makeDirectories(dir: String) {
    if (dir.isEmpty()) return
    val from = if (dir.startsWith("/")) 1 else 0
    for (index in from until dir.length) {
        if (dir[index] == '/') makeDirectory(dir.substring(0, index))
    }
    makeDirectory(dir)
}
