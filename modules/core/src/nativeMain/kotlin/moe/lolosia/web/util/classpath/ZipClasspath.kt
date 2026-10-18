package moe.lolosia.web.util.classpath

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import moe.lolosia.web.util.time.PROCESS_START_MS
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

/**
 * 读取 zip 内容的 [Classpath] 实现。
 *
 * 只解析 zip 的中央目录建索引，读取条目时才拷贝该条目，因此内存占用 ≈ zip 本体 + 单条目。
 * 打开时会把映像源数据拷入一次内存，使解析逻辑与平台无关。
 *
 * 支持 STORED 与 DEFLATED（用 Kotlin/Native 自带的 `platform.zlib` 做 inflate，仅 raw deflate，
 * 与 zip 条目的存储格式一致）。
 *
 * 已知边界：不支持 zip64（`classpath.zip` 不会超过 4GB）。
 *
 * @author 洛洛希雅Lolosia
 */
class ZipClasspath private constructor(private val data: ByteArray) : Classpath {

    private class Entry(
        val method: Int,
        val dataOffset: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
    )

    private val byName: Map<String, Entry> = parseCentralDirectory()

    override val entries: List<String> get() = byName.keys.sorted()

    override fun read(path: String): ByteArray? {
        val entry = byName[normalizeClasspathPath(path)] ?: return null
        val compressed = data.copyOfRange(entry.dataOffset, entry.dataOffset + entry.compressedSize)
        return when (entry.method) {
            METHOD_STORED -> compressed

            METHOD_DEFLATED -> inflateRaw(compressed, entry.uncompressedSize)

            else -> throw UnsupportedOperationException("不支持的 zip 压缩方式 ${entry.method}：$path")
        }
    }

    override fun list(prefix: String): List<String> {
        val p = normalizeClasspathPath(prefix)
        return byName.keys.filter { p.isEmpty() || it.startsWith(p) }.sorted()
    }

    /** zip 条目没有各自的时间戳，统一取进程启动时间（见 [Classpath.lastModified]）。 */
    override fun lastModified(path: String): Long? =
        if (byName.containsKey(normalizeClasspathPath(path))) PROCESS_START_MS else null

    override val scheme: String = "classpath"

    private fun parseCentralDirectory(): Map<String, Entry> {
        val eocd = findEndOfCentralDirectory() ?: return emptyMap()
        val count = u16(eocd + 10)
        var offset = u32(eocd + 16)
        val result = LinkedHashMap<String, Entry>(count)

        var i = 0
        while (i < count && offset + 46 <= data.size) {
            if (u32(offset) != SIG_CENTRAL_FILE_HEADER) break

            val method = u16(offset + 10)
            val compressedSize = u32(offset + 20)
            val uncompressedSize = u32(offset + 24)
            val nameLen = u16(offset + 28)
            val extraLen = u16(offset + 30)
            val commentLen = u16(offset + 32)
            val localHeaderOffset = u32(offset + 42)
            val name = data.decodeToString(offset + 46, offset + 46 + nameLen)

            // 数据起点以「本地头」为准：本地头的 extra 长度可能与中央目录不同
            val dataOffset = localDataOffset(localHeaderOffset)
            if (!name.endsWith("/")) {
                result[name] = Entry(method, dataOffset, compressedSize, uncompressedSize)
            }

            offset += 46 + nameLen + extraLen + commentLen
            i++
        }
        return result
    }

    /**
     * raw deflate 解压。zip 条目用的是 raw deflate（无 zlib 头），故 `windowBits = -15`。
     *
     * [outputSize] 取中央目录里的未压缩大小，一次性解到位（classpath 里都是小资源）。
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun inflateRaw(input: ByteArray, outputSize: Int): ByteArray {
        if (outputSize == 0) return ByteArray(0)
        return memScoped {
            val strm = alloc<z_stream>()
            // inflateInit2_ 只校验 version 的首字符与 stream_size，取运行时 zlib 版本最稳
            val version = zlibVersion()?.toKString() ?: "1.2.0"
            val init = inflateInit2_(strm.ptr, -15, version, sizeOf<z_stream>().convert())
            check(init == Z_OK) { "inflateInit2 失败：rc=$init" }
            try {
                val out = ByteArray(outputSize)
                input.usePinned { inPinned ->
                    out.usePinned { outPinned ->
                        strm.next_in = inPinned.addressOf(0).reinterpret()
                        strm.avail_in = input.size.convert()
                        strm.next_out = outPinned.addressOf(0).reinterpret()
                        strm.avail_out = outputSize.convert()
                        val rc = inflate(strm.ptr, Z_FINISH)
                        check(rc == Z_STREAM_END) { "inflate 失败：rc=$rc" }
                    }
                }
                out
            } finally {
                inflateEnd(strm.ptr)
            }
        }
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

    private fun u8(o: Int): Int = data[o].toInt() and 0xFF

    private fun u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)

    private fun u32(o: Int): Int =
        u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)

    companion object {
        private const val SIG_LOCAL_FILE_HEADER = 0x04034b50
        private const val SIG_CENTRAL_FILE_HEADER = 0x02014b50
        private const val SIG_END_OF_CENTRAL_DIRECTORY = 0x06054b50

        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        /** 从可执行文件映像内的 zip 打开（会拷贝一次映像数据）。 */
        fun open(image: COpaquePointer, size: Int): ZipClasspath =
            ZipClasspath(image.reinterpret<ByteVar>().readBytes(size))

        /** 从内存字节打开。 */
        fun open(bytes: ByteArray): ZipClasspath = ZipClasspath(bytes)
    }
}
