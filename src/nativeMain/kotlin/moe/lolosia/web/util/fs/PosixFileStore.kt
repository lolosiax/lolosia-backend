package moe.lolosia.web.util.fs

/**
 * 把 [root] 目录当作存储根的文件存储实现（posix）。
 *
 * 路径一律拼在根下，并拒绝 `..`（越出根目录的读写直接失败）。
 *
 * @param root 根目录，相对启动目录（如 `work`）
 * @author 洛洛希雅Lolosia
 */
public class PosixFileStore(private val root: String) : FileStore {

    override fun read(path: String): ByteArray? = resolve(path)?.let(::readFileBytes)

    override fun write(path: String, bytes: ByteArray): Boolean =
        resolve(path)?.let { writeFileBytes(it, bytes) } ?: false

    /** 拼出绝对路径；空路径或含 `..` 的路径视为非法。 */
    private fun resolve(path: String): String? {
        val rel = path.trimStart('/')
        if (rel.isEmpty() || rel.contains("..")) return null
        return "${root.trimEnd('/')}/$rel"
    }
}
