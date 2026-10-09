package moe.lolosia.web.service.files

import okio.Path
import okio.Path.Companion.toPath

/**
 * 文件管理器的路径与类型工具：把 URL 里的相对路径安全地映射成真实路径，并给出 MIME / 图片判定。
 *
 * 路径**逐段校验**（拒绝空段、`.`、`..`、含分隔符或 NUL 的段，以及一切隐藏名，见 [isHiddenName]），
 * 因此解析结果必然落在浏览根内，且天然看不到 `.cache`、`lost+found` 之类不该露出的目录。
 *
 * @author 洛洛希雅Lolosia
 */

/** 能解码成缩略图的扩展名（与 `util.image.Image` 支持解码的格式一致）。 */
val IMAGE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "gif", "webp")

/**
 * 隐藏名：对用户不可见的文件 / 目录 —— 一切以 `.` 开头的名字，外加文件系统保留名。
 *
 * `lost+found` 是 ext4 等文件系统在根分区自动建的目录（fsck 回收孤立 inode 用），
 * 既不属于用户资料，也不该出现在 WebDAV 映射盘和文件管理器里。这里按**名字**判定、不分层级，
 * 无论它出现在哪一层都不会漏出来。
 */
fun isHiddenName(name: String): Boolean = name.startsWith(".") || name == "lost+found"

/** 共享的缩略图缓存版本号：规格或算法变更时递增，旧缓存整体失效（目录名带版本）。 */
const val THUMBNAIL_CACHE_VERSION: Int = 2

/** 缩略图缓存相对浏览根的目录（对 WebDAV 隐藏，见 `DavStore`）。 */
const val CACHE_DIR: String = ".cache/lolosia/thumb"

/** 扩展名 → MIME；未收录的一律 `application/octet-stream`。 */
private val MIME_TYPES: Map<String, String> = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "svg" to "image/svg+xml",
    "mp4" to "video/mp4",
    "webm" to "video/webm",
    "mov" to "video/quicktime",
    "mkv" to "video/x-matroska",
    "mp3" to "audio/mpeg",
    "flac" to "audio/flac",
    "wav" to "audio/wav",
    "m4a" to "audio/mp4",
    "ogg" to "audio/ogg",
    "pdf" to "application/pdf",
    "txt" to "text/plain",
    "md" to "text/markdown",
    "json" to "application/json",
    "xml" to "application/xml",
    "csv" to "text/csv",
    "zip" to "application/zip",
    "7z" to "application/x-7z-compressed",
    "rar" to "application/vnd.rar",
    "gz" to "application/gzip",
)

const val DEFAULT_MIME: String = "application/octet-stream"

/** 取扩展名（小写、不含点）；没有扩展名时返回空串。 */
fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/** 扩展名 → MIME。 */
fun mimeOf(name: String): String = MIME_TYPES[extensionOf(name)] ?: DEFAULT_MIME

/** 是否可能是图片（按扩展名判定，不读文件头）。 */
fun isImageName(name: String): Boolean = extensionOf(name) in IMAGE_EXTENSIONS

/**
 * 把 URL 里的相对路径规范化成 `a/b/c` 形式：去首尾 `/`、把 `\` 当分隔符、丢弃空段。
 * 出现任何非法段（`.`、`..`、[isHiddenName]）返回 `null`。
 *
 * 空串是合法的，表示浏览根本身。
 */
fun normalizeRelative(path: String?): String? {
    val raw = path?.replace('\\', '/')?.trim('/') ?: return ""
    if (raw.isEmpty()) return ""
    val segments = raw.split('/')
    for (seg in segments) {
        if (seg.isEmpty() || seg == "." || seg == "..") return null
        if (isHiddenName(seg)) return null
        if (seg.any { it == '\u0000' }) return null
    }
    return segments.joinToString("/")
}

/** 根 + 相对路径 → 真实路径；[relative] 必须已过 [normalizeRelative]。 */
fun resolve(root: Path, relative: String): Path =
    if (relative.isEmpty()) root else root.resolve(relative.toPath())

/** 相对路径的父目录；根目录返回 `null`。 */
fun parentOf(relative: String): String? {
    if (relative.isEmpty()) return null
    val index = relative.lastIndexOf('/')
    return if (index < 0) "" else relative.substring(0, index)
}

/** 相对路径的末段名；根目录返回空串。 */
fun nameOf(relative: String): String = relative.substringAfterLast('/')

/** 拼接子项相对路径。 */
fun childOf(relative: String, name: String): String = if (relative.isEmpty()) name else "$relative/$name"
