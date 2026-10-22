package moe.lolosia.web.util.media

import moe.lolosia.web.util.log.Log
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.HashingSource
import okio.Path

/**
 * 文件内容哈希的读取端。
 *
 * 两个口径（见 `doc/media-index-design.md` §2.2）：
 * - [quick]：只读首尾各 64KiB（[QUICK_ID_CHUNK_BYTES]），扫描路径上就能算；
 * - [full]：流式读完整文件算 SHA-256，**异步**补齐，用于「是不是同一份内容」的最终判定。
 *
 * [full] 是流式的（每读完一块就丢），不会把整张图读进内存 —— 视频动辄几百 MB。
 *
 * 两者都不 suspend：okio 的文件 API 是阻塞的，调用方负责放到后台调度器上跑。
 *
 * @author 洛洛希雅Lolosia
 */
object ContentHash {

    private val log = Log.of("Media")
    private const val CHUNK: Long = 64L * 1024

    /** 全量 SHA-256（十六进制小写）；读不到时返回 `null`。 */
    fun full(fs: FileSystem, path: Path): String? = runCatching {
        fs.read(path) {
            val hashing = HashingSource.sha256(this)
            val scratch = Buffer()
            while (hashing.read(scratch, CHUNK) != -1L) scratch.clear()
            hashing.hash.hex()
        }
    }.onFailure { log.warn(it) { "计算全量哈希失败：$path" } }.getOrNull()

    /** `quick_id`；读不到或文件为空时返回 `null`。 */
    fun quick(fs: FileSystem, path: Path, size: Long): String? = runCatching {
        val chunk = minOf(QUICK_ID_CHUNK_BYTES, size)
        if (chunk <= 0L) return@runCatching null
        val handle = fs.openReadOnly(path)
        try {
            val head = readAt(handle, 0L, chunk)
            val tail = readAt(handle, size - chunk, chunk)
            QuickId.compute(size, head, tail)
        } finally {
            handle.close()
        }
    }.onFailure { log.warn(it) { "计算 quick_id 失败：$path" } }.getOrNull()

    /** 从 [offset] 起读至多 [byteCount] 字节；到文件尾就返回实际读到的那些。 */
    private fun readAt(handle: FileHandle, offset: Long, byteCount: Long): ByteArray {
        val sink = Buffer()
        while (sink.size < byteCount) {
            val read = handle.read(offset + sink.size, sink, byteCount - sink.size)
            if (read == -1L) break
        }
        return sink.readByteArray()
    }
}
