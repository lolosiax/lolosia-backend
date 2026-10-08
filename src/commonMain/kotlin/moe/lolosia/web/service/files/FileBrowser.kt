package moe.lolosia.web.service.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.FileDetail
import moe.lolosia.web.dto.FileEntry
import moe.lolosia.web.dto.FileListResult
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.image.Image
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.media.isMediaName
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** 目录列表的排序键。 */
enum class FileSortKey { NAME, SIZE, MTIME, TYPE }

/**
 * 只读的目录浏览服务：把浏览根下的目录与文件列成 DTO。
 *
 * 一次 [list] 对每个子项只做 **一次 stat**（okio `metadataOrNull`），图片再额外做一次
 * 「最小档缩略图是否存在」的 stat，**子目录**再多做一次目录枚举（用于 `hasChildren`）——
 * 不做任何文件内容读取，否则在 30MB/s 的机械盘上列一个上千张图的目录会慢到不可用。
 *
 * 同时持有 [cache]，供缩略图服务复用同一份路径规则（避免两处各写一遍缓存目录结构）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class FileBrowser(settings: AppSettings, private val signer: FileSigner) {

    private val log = Log.of("Files")
    private val fs: FileSystem = FileSystem.SYSTEM

    private val config = settings.files

    /** 浏览根（绝对路径）。 */
    val root: String = config.resolveRoot(settings.webdav.root)

    val rootPath: Path = root.toPath()

    /** 缩略图缓存（目录结构见 [ThumbnailCache]）。 */
    val cache: ThumbnailCache = ThumbnailCache("$root/$CACHE_DIR/v$THUMBNAIL_CACHE_VERSION", config.thumbnail.sizes)

    init {
        log.info { "文件管理器根目录：$root（缩略图档位 ${cache.orderedSizes}）" }
    }

    /** 把 URL 里的相对路径解析成真实路径；非法或越界抛 400。 */
    fun requirePath(path: String?): Pair<String, Path> {
        val relative = normalizeRelative(path) ?: throw ApiException(400, "路径非法")
        return relative to resolve(rootPath, relative)
    }

    /** 列目录。 */
    suspend fun list(path: String?, sort: String?, order: String?): FileListResult {
        val (relative, dir) = requirePath(path)
        val meta = fs.metadataOrNull(dir) ?: throw ApiException(404, "目录不存在：${relative.ifEmpty { "/" }}")
        if (!meta.isDirectory) throw ApiException(400, "不是目录：$relative")

        val entries = mapChildren(dir, relative)
        // 一次列表共用同一个过期时间，逐项签名后才下发给前端拼 `<img src>`
        val exp = signer.expireAt()
        return FileListResult(
            path = relative,
            parent = parentOf(relative),
            entries = entries.map { it.signedBy(signer, exp) }.sortedWith(comparator(sort, order)),
        )
    }

    /**
     * 只列子目录（左侧目录树懒加载用）：越过了文件，省掉一大批 stat 与缩略图存在性检查。
     *
     * 这里会额外算 [FileEntry.hasSubdirs]（目录树要据此决定画不画展开箭头），
     * 所以每个子目录比 [list] 多一轮「枚举 + 逐个 stat 找目录」。
     */
    suspend fun dirs(path: String?): List<FileEntry> {
        val (relative, dir) = requirePath(path)
        if (fs.metadataOrNull(dir)?.isDirectory != true) throw ApiException(404, "目录不存在：${relative.ifEmpty { "/" }}")
        return mapChildren(dir, relative, checkSubdirs = true).filter { it.directory }.sortedBy { it.name.lowercase() }
    }

    /**
     * 把 [dir] 的子项并发转成 DTO。
     *
     * 每个**子目录**的 `hasChildren` 都要多一次目录枚举，串行在 WebDAV 映射盘上就是「子目录个数」
     * 次往返（实测 14 个子目录要 1.3s）。这些都是元数据操作、不碰文件内容，并发跑只等最慢的一次。
     */
    private suspend fun mapChildren(
        dir: Path,
        relative: String,
        checkSubdirs: Boolean = false,
    ): List<FileEntry> =
        withContext(Dispatchers.Default) {
            coroutineScope {
                fs.listOrNull(dir).orEmpty()
                    .map { child -> async { entryOf(dir, relative, child, checkChildren = true, checkSubdirs = checkSubdirs) } }
                    .awaitAll()
                    .filterNotNull()
            }
        }

    /** 按相对路径取单个条目；不存在或非法返回 `null`（不抛异常，供后台线程用）。 */
    fun stat(path: String?): FileEntry? {
        val relative = normalizeRelative(path) ?: return null
        if (relative.isEmpty()) return null
        val meta = fs.metadataOrNull(resolve(rootPath, relative)) ?: return null
        if (nameOf(relative).startsWith(".")) return null
        return toEntry(nameOf(relative), relative, meta)
    }

    /** 单个文件的详情（预览面板用）：会在缓存元数据缺失时读一次文件头取原图尺寸。 */
    fun detail(path: String?): FileDetail {
        val (relative, target) = requirePath(path)
        if (relative.isEmpty()) throw ApiException(400, "根目录没有详情")
        val entry = (entryOf(rootPath, "", target) ?: throw ApiException(404, "文件不存在：$relative"))
            .signedBy(signer, signer.expireAt())
        val meta = cache.readMeta(relative)
        val thumbs = if (meta != null && cache.isFresh(meta, entry.size, entry.mtime)) meta.sizes.sorted() else emptyList()
        var width = meta?.width
        var height = meta?.height
        if (entry.image && (width == null || height == null)) {
            // 只读文件头（几百字节），不解码像素
            val head = readSource(relative, limit = 256 * 1024)
            val size = head?.let { Image.sizeOf(it) }
            width = size?.width
            height = size?.height
        }
        return FileDetail(entry = entry, width = width, height = height, thumbs = thumbs)
    }

    /** 读源文件内容；[limit] 非空时只读前 [limit] 字节（读文件头用，避免拉整个大文件）。 */
    fun readSource(relative: String, limit: Int? = null): ByteArray? {
        val path = resolve(rootPath, relative)
        val meta = fs.metadataOrNull(path) ?: return null
        if (meta.isDirectory) return null
        val size = meta.size ?: return null
        val count = if (limit == null) size else minOf(limit.toLong(), size)
        if (count <= 0L) return ByteArray(0)
        return runCatching { fs.read(path) { readByteArray(count) } }
            .onFailure { log.warn(it) { "读取文件失败：$relative" } }
            .getOrNull()
    }

    /**
     * 递归收集 [relative] 子树下的图片条目（用于后台全量扫描）。
     *
     * 每层目录之间让出一次（`delay`），避免在机械盘上长时间独占 IO；跳过隐藏目录，
     * 因此 `.cache` 不会被扫到。返回的条目**不算缩略图存在性**（那是一次额外 stat，
     * 这里不需要）。
     */
    suspend fun collectImages(relative: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        collectInto(resolve(rootPath, relative), relative, out)
        return out
    }

    private suspend fun collectInto(dir: Path, relative: String, out: MutableList<FileEntry>) {
        val children = fs.listOrNull(dir).orEmpty().sortedBy { it.name.lowercase() }
        for (child in children) {
            if (child.name.startsWith(".")) continue
            val meta = fs.metadataOrNull(child) ?: continue
            if (meta.isDirectory) {
                delay(1)
                collectInto(child, childOf(relative, child.name), out)
            } else if (isImageName(child.name)) {
                out.add(toEntry(child.name, childOf(relative, child.name), meta, checkThumb = false))
            }
        }
    }

    /**
     * 递归收集 [relative] 子树下的**媒体**条目（图片 / 视频 / 音频，用于媒体索引的全量扫描）。
     *
     * 与 [collectImages] 同构：每层让出一次、跳过隐藏目录、不算缩略图存在性。
     */
    suspend fun collectMedia(relative: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        collectMediaInto(resolve(rootPath, relative), relative, out)
        return out
    }

    private suspend fun collectMediaInto(dir: Path, relative: String, out: MutableList<FileEntry>) {
        val children = fs.listOrNull(dir).orEmpty().sortedBy { it.name.lowercase() }
        for (child in children) {
            if (child.name.startsWith(".")) continue
            val meta = fs.metadataOrNull(child) ?: continue
            if (meta.isDirectory) {
                delay(1)
                collectMediaInto(child, childOf(relative, child.name), out)
            } else if (isMediaName(child.name)) {
                out.add(toEntry(child.name, childOf(relative, child.name), meta, checkThumb = false))
            }
        }
    }

    /**
     * 递归列出 [relative] 子树下的**目录**相对路径（含自身），跳过 `.` 开头的隐藏目录。
     *
     * 媒体索引用它做「本轮扫描没见到哪些已登记的文件」的逐目录比对（`doc/media-index-design.md` §5.3）。
     */
    suspend fun collectDirs(relative: String): List<String> {
        val out = ArrayList<String>()
        out.add(relative)
        collectDirsInto(resolve(rootPath, relative), relative, out)
        return out
    }

    private suspend fun collectDirsInto(dir: Path, relative: String, out: MutableList<String>) {
        val children = fs.listOrNull(dir).orEmpty().sortedBy { it.name.lowercase() }
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (fs.metadataOrNull(child)?.isDirectory != true) continue
            val childRelative = childOf(relative, child.name)
            out.add(childRelative)
            delay(1)
            collectDirsInto(child, childRelative, out)
        }
    }

    /** 单个子项 → DTO。[parentRelative] 是 [child] 所属目录的相对路径。 */
    private fun entryOf(
        parentPath: Path,
        parentRelative: String,
        child: Path,
        checkChildren: Boolean = false,
        checkSubdirs: Boolean = false,
    ): FileEntry? {
        val meta = fs.metadataOrNull(child) ?: return null
        if (child.name.startsWith(".")) return null
        return toEntry(child.name, childOf(parentRelative, child.name), meta, checkChildren = checkChildren, checkSubdirs = checkSubdirs)
    }

    /**
     * 目录内可见子项的概况，一次枚举给出两个独立标志位：
     * `first` = 是否有可见子项（「是否为空」的反面），`second` = 是否有可见**子目录**（「能否展开」）。
     *
     * 第二个要多花一轮「逐个 stat 子项直到撞见一个目录」：目录列表本身不带类型，
     * 而常见的「一堆图、没有子目录」的目录正是这条路径的最坏情况（子项个数次 stat）。
     * 好在这属于同目录元数据、redirector 会缓存枚举结果，实测代价可忽略。
     */
    private fun visibleSummary(dir: Path): Pair<Boolean, Boolean> {
        val children = fs.listOrNull(dir).orEmpty().filterNot { it.name.startsWith(".") }
        if (children.isEmpty()) return false to false
        return true to children.any { fs.metadataOrNull(it)?.isDirectory == true }
    }

    /** 真实路径 + 相对路径 + stat → DTO；[checkThumb] 为 `false` 时省掉一次缩略图存在性 stat。 */
    fun toEntry(
        name: String,
        relative: String,
        meta: FileMetadata,
        checkThumb: Boolean = true,
        checkChildren: Boolean = false,
        checkSubdirs: Boolean = false,
    ): FileEntry {
        val image = !meta.isDirectory && isImageName(name)
        val summary = if (meta.isDirectory && (checkChildren || checkSubdirs)) visibleSummary(resolve(rootPath, relative)) else (false to false)
        return FileEntry(
            name = name,
            path = relative,
            directory = meta.isDirectory,
            size = if (meta.isDirectory) 0 else (meta.size ?: 0L),
            mtime = meta.lastModifiedAtMillis ?: 0L,
            ext = extensionOf(name),
            mime = if (meta.isDirectory) DEFAULT_MIME else mimeOf(name),
            image = image,
            thumb = checkThumb && image && cache.exists(relative),
            hasChildren = checkChildren && summary.first,
            hasSubdirs = checkSubdirs && summary.second,
        )
    }

    /** 给条目补上直链签名；只在下发给前端的接口（list / detail）里调。 */
    private fun FileEntry.signedBy(signer: FileSigner, exp: Long): FileEntry =
        copy(exp = exp, sig = signer.sign(path, exp))

    /** 排序：目录恒在前，其余按 [sort] / [order]。 */
    private fun comparator(sort: String?, order: String?): Comparator<FileEntry> {
        val ascending = !"desc".equals(order, ignoreCase = true)
        val key = runCatching { FileSortKey.valueOf(sort?.uppercase() ?: "NAME") }.getOrDefault(FileSortKey.NAME)
        val byKey: Comparator<FileEntry> = when (key) {
            FileSortKey.NAME -> compareBy { it.name.lowercase() }
            FileSortKey.SIZE -> compareBy { it.size }
            FileSortKey.MTIME -> compareBy { it.mtime }
            FileSortKey.TYPE -> compareBy({ it.ext }, { it.name.lowercase() })
        }
        val directed = if (ascending) byKey else byKey.reversed()
        return compareByDescending<FileEntry> { it.directory }.then(directed)
    }
}
