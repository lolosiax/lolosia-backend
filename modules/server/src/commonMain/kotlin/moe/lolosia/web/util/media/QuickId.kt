package moe.lolosia.web.util.media

import okio.Buffer

/**
 * `quick_id`：`size` + 首尾各 64KiB 的 SHA-256。
 *
 * 它回答的问题是「改了 mtime 之后，内容到底变没变」—— 只在扫描路径上读最多 128KiB，
 * 机械盘上也能承受；而 `content_sha256`（全量）要读完整文件，只能异步补齐
 * （见 `doc/media-index-design.md` §2.2）。
 *
 * 因此 [compute] 只做纯计算，读文件的事交给 [ContentHash]。
 *
 * @author 洛洛希雅Lolosia
 */
object QuickId {

    /**
     * 计算摘要（十六进制小写）。
     *
     * [head] / [tail] 允许重叠（文件小于 128KiB 时必然重叠），
     * 只要同一个文件每次算出来的输入一致，结果就是稳定的。
     */
    fun compute(size: Long, head: ByteArray, tail: ByteArray): String {
        val buffer = Buffer()
        buffer.writeLong(size)
        buffer.write(head)
        buffer.write(tail)
        return buffer.readByteString().sha256().hex()
    }
}
