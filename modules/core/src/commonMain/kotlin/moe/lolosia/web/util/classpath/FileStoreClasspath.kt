package moe.lolosia.web.util.classpath

import moe.lolosia.web.util.fs.FileStore

/**
 * 把 [FileStore] 存储根下的 [root] 子目录当作只读 [Classpath] 挂载。
 *
 * 用于让运行期目录（如 `<workDir>/resources`）走 `Route.staticClasspath` 暴露成静态文件。
 * `entries` / `list` 不做目录遍历，恒为空，只支持按路径 [read]；越界（`..`）由 [FileStore] 拒绝。
 *
 * @param store 文件存储，根目录由实现决定（如 `PosixFileStore`）
 * @param root 子目录，`/` 分隔、相对存储根
 * @author 洛洛希雅Lolosia
 */
class FileStoreClasspath(
    private val store: FileStore,
    private val root: String = "",
) : Classpath {

    override val entries: List<String> = emptyList()

    override fun read(path: String): ByteArray? {
        val rel = normalizeClasspathPath(path)
        if (rel.isEmpty()) return null
        val full = listOf(root.trim('/'), rel).filter { it.isNotEmpty() }.joinToString("/")
        return store.read(full)
    }

    override fun list(prefix: String): List<String> = emptyList()

    /** 外部资源用文件真实 mtime（见 [Classpath.lastModified]）。 */
    override fun lastModified(path: String): Long? {
        val rel = normalizeClasspathPath(path)
        if (rel.isEmpty()) return null
        val full = listOf(root.trim('/'), rel).filter { it.isNotEmpty() }.joinToString("/")
        return store.lastModified(full)
    }

    override val scheme: String = "file"

    override val origin: String get() = "$scheme://${root.trim('/')}"
}
