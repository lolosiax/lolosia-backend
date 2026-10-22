package moe.lolosia.web.service.artifact

import moe.lolosia.web.util.media.ContentHash
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.random.Random

/**
 * 工件仓库的落盘层：把 URL 分段安全映射到固定根目录，并提供最小文件操作。
 *
 * 布局只允许三类一级目录：
 * - `maven/`：`<group 路径>/<artifact>/<version>/<文件>`，与 Maven 仓库一致；
 * - `raw/`：`<namespace>/<业务 slug>/<...>`，放不便套 Maven 坐标的产物；
 * - `sources/`：`<库名>/<version>/<源码包>`，上游源码 zip / tar.gz 的镜像。
 *
 * 所有分段只允许 ASCII slug（字母数字开头，可含 `.`、`_`、`+`、`-`），
 * 中文展示名只应出现在元数据/界面里，不落到磁盘目录名上。
 *
 * 路径逐段校验并拒绝 `.` / `..` / 空白 / 分隔符，因此解析结果必然落在根内。
 *
 * @param root 根目录；相对路径按启动目录解析，默认取 `<workDir>/repo`
 * @author 洛洛希雅Lolosia
 */
class ArtifactStore(root: String) {

    private val fs: FileSystem = FileSystem.SYSTEM

    /** 仓库根路径。 */
    val rootPath: Path = root.toPath()

    /** 仓库内部使用的临时目录（不对外暴露）。 */
    val tempPath: Path = rootPath.resolve(TEMP_DIR)

    /** 相对分段（已 URL 解码）→ 真实路径；任一段非法时返回 `null`。 */
    fun resolve(segments: List<String>): Path? {
        var path = rootPath
        for (segment in segments) {
            if (!isValidSegment(segment)) return null
            path = path.resolve(segment)
        }
        return path
    }

    fun exists(path: Path): Boolean = fs.exists(path)

    fun metadata(path: Path): FileMetadata? = fs.metadataOrNull(path)

    /** 目录项，按名字排序；内部保留名（`_` 开头）跳过。 */
    fun children(path: Path): List<Path> =
        fs.listOrNull(path).orEmpty()
            .filterNot { it.name.startsWith("_") }
            .sortedBy { it.name.lowercase() }

    /** 覆盖写入的 sink；调用方负责 `close()`。 */
    fun sink(path: Path) = fs.sink(path).buffer()

    /** 只读句柄；调用方负责 `close()`。 */
    fun openReadOnly(path: Path) = fs.openReadOnly(path)

    /** 全量 SHA-256（十六进制小写）；读不到时返回 `null`。 */
    fun sha256(path: Path): String? = ContentHash.full(fs, path)

    /** 写入单行文本（校验和旁车文件用）。 */
    fun writeText(path: Path, text: String) {
        fs.createDirectories(path.parent ?: rootPath)
        fs.write(path) { writeUtf8(text) }
    }

    /** 新建上传临时文件；名字随机，落在 `_tmp/` 下。 */
    fun newTempFile(): Path {
        fs.createDirectories(tempPath)
        val name = "upload-${Random.nextLong().toULong().toString(16)}.part"
        return tempPath.resolve(name)
    }

    /** 删除文件或目录；不存在时静默返回。 */
    fun delete(path: Path) {
        runCatching { fs.deleteRecursively(path, mustExist = false) }
    }

    /**
     * 把临时文件原子地放到目标位置。
     *
     * [overwrite] 为 `false` 且目标已存在时返回 `false`（版本不可变）；
     * 调用方应在此之前完成校验。
     */
    fun commit(source: Path, target: Path, overwrite: Boolean): Boolean {
        if (fs.exists(target)) {
            if (!overwrite) return false
            fs.deleteRecursively(target, mustExist = true)
        }
        fs.createDirectories(target.parent ?: rootPath)
        fs.atomicMove(source, target)
        return true
    }

    /** 分段是否合法：ASCII slug，且不是保留名。 */
    private fun isValidSegment(segment: String): Boolean {
        if (segment.isEmpty() || segment.length > MAX_SEGMENT_LENGTH) return false
        if (segment.startsWith("_")) return false
        if (segment.endsWith(".") || segment.endsWith(" ")) return false
        if (segment.uppercase() in WINDOWS_RESERVED) return false
        return SEGMENT_REGEX.matches(segment)
    }
}

/** 仓库内部分配给上传临时文件的目录名。 */
private const val TEMP_DIR = "_tmp"

private const val MAX_SEGMENT_LENGTH = 160

/** 只允许 ASCII slug：字母数字开头，后续可含 `.`、`_`、`+`、`-`。 */
private val SEGMENT_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")

/** Windows 不允许作为文件/目录名的保留字（大小写不敏感，这里全大写比较）。 */
private val WINDOWS_RESERVED: Set<String> = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    for (index in 1..9) {
        add("COM$index")
        add("LPT$index")
    }
}
