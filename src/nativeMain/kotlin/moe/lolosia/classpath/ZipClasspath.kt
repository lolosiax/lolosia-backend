package moe.lolosia.classpath

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret

/**
 * 读取 zip 内容的 [Classpath] 实现。
 *
 * 数据来源二选一：
 * - [open] (COpaquePointer)：可执行文件映像内的 `classpath.zip`（Windows `LockResource` / Linux 符号地址），
 *   打开时把映像拷入内存数组（几 MB，一次性；换来解析逻辑与平台无关、无指针歧义）；
 * - [open] (ByteArray)：直接使用给定数组。
 *
 * 只解析 zip 的 **中央目录**（Central Directory）建索引，读取条目时才拷贝该条目，
 * 因此内存占用 ≈ zip 本体 + 单条目。
 *
 * 已知边界：不支持 zip64（`classpath.zip` 不会超过 4GB）；目前只支持 STORED（不压缩）条目，
 * 换成 DEFLATED 需要在 [read] 中补 inflate（可用 Kotlin/Native 自带的 `platform.zlib`）。
 *
 * @author 洛洛希雅Lolosia
 */
public class ZipClasspath private constructor(private val data: ByteArray) : Classpath {

    private class Entry(
        val method: Int,
        val dataOffset: Int,
        val uncompressedSize: Int,
    )

    private val byName: Map<String, Entry> = parseCentralDirectory()

    override val entries: List<String> get() = byName.keys.sorted()

    override fun read(path: String): ByteArray? {
        val entry = byName[normalizeClasspathPath(path)] ?: return null
        return when (entry.method) {
            METHOD_STORED -> data.copyOfRange(entry.dataOffset, entry.dataOffset + entry.uncompressedSize)

            METHOD_DEFLATED -> throw UnsupportedOperationException(
                "classpath 条目 `$path` 使用了 deflate 压缩；当前仅支持 STORED —— " +
                    "请把打包任务 classpathZip* 的压缩方式保持为 STORED"
            )

            else -> throw UnsupportedOperationException("不支持的 zip 压缩方式 ${entry.method}：$path")
        }
    }

    override fun list(prefix: String): List<String> {
        val p = normalizeClasspathPath(prefix)
        return byName.keys.filter { p.isEmpty() || it.startsWith(p) }.sorted()
    }

    // ===== 中央目录解析 =====

    private fun parseCentralDirectory(): Map<String, Entry> {
        val eocd = findEndOfCentralDirectory() ?: return emptyMap()
        val count = u16(eocd + 10)
        var offset = u32(eocd + 16)
        val result = LinkedHashMap<String, Entry>(count)

        var i = 0
        while (i < count && offset + 46 <= data.size) {
            if (u32(offset) != SIG_CENTRAL_FILE_HEADER) break

            val method = u16(offset + 10)
            val uncompressedSize = u32(offset + 24)
            val nameLen = u16(offset + 28)
            val extraLen = u16(offset + 30)
            val commentLen = u16(offset + 32)
            val localHeaderOffset = u32(offset + 42)
            val name = data.decodeToString(offset + 46, offset + 46 + nameLen)

            // 数据起点以「本地头」为准（本地头的 extra 长度可能与中央目录不同）
            val dataOffset = localDataOffset(localHeaderOffset)
            if (!name.endsWith("/")) {
                result[name] = Entry(method, dataOffset, uncompressedSize)
            }

            offset += 46 + nameLen + extraLen + commentLen
            i++
        }
        return result
    }

    private fun localDataOffset(localHeaderOffset: Int): Int {
        if (localHeaderOffset + 30 > data.size) return localHeaderOffset
        if (u32(localHeaderOffset) != SIG_LOCAL_FILE_HEADER) return localHeaderOffset + 30
        val nameLen = u16(localHeaderOffset + 26)
        val extraLen = u16(localHeaderOffset + 28)
        return localHeaderOffset + 30 + nameLen + extraLen
    }

    private fun findEndOfCentralDirectory(): Int? {
        if (data.size < 22) return null
        val minPos = maxOf(0, data.size - 22 - 0xFFFF)
        var i = data.size - 22
        while (i >= minPos) {
            if (u32(i) == SIG_END_OF_CENTRAL_DIRECTORY) return i
            i--
        }
        return null
    }

    // ===== 小端读取 =====

    private fun u8(o: Int): Int = data[o].toInt() and 0xFF

    private fun u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)

    private fun u32(o: Int): Int =
        u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)

    public companion object {
        private const val SIG_LOCAL_FILE_HEADER = 0x04034b50
        private const val SIG_CENTRAL_FILE_HEADER = 0x02014b50
        private const val SIG_END_OF_CENTRAL_DIRECTORY = 0x06054b50

        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        /** 从可执行文件映像内的 zip 打开（拷贝一次映像数据）。 */
        @OptIn(ExperimentalForeignApi::class)
        public fun open(image: COpaquePointer, size: Int): ZipClasspath =
            ZipClasspath(image.reinterpret<ByteVar>().readBytes(size))

        /** 从内存字节打开。 */
        public fun open(bytes: ByteArray): ZipClasspath = ZipClasspath(bytes)
    }
}
