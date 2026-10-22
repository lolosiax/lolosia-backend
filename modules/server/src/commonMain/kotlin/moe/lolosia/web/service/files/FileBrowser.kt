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
import moe.lolosia.web.dto.FileOpResult
import moe.lolosia.web.dto.FileTransferItem
import moe.lolosia.web.dto.FileTransferResult
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.fs.WorkPaths
import moe.lolosia.web.util.image.Image
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.media.isMediaName
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import okio.BufferedSink
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.random.Random

/** 目录列表的排序键。 */
enum class FileSortKey { NAME, SIZE, MTIME, TYPE }

/** 上传重名时最多往后试多少个序号（`名字 (1)`…`名字 (n)`），超过就报错而不是无限 stat。 */
private const val MAX_UPLOAD_DEDUP: Int = 200

/**
 * 上传落点：目标目录里不冲突的最终名字，以及「先写临时文件、写完原子改名」这两条路径。
 *
 * [temp] 与 [target] 在**同一个目录**里：一是保证 `atomicMove` 一定不跨文件系统，
 * 二是别的目录（比如隐藏的 `.lolosia`）不一定可写 —— 开发机把线上机的 `/dav` 挂成 `Z:` 时，
 * 线上 WebDAV 默认拒绝隐藏项访问，写 `.lolosia` 下会被 403。
 */
data class UploadTarget(val relative: String, val name: String, val target: Path, val temp: Path)

/**
 * 目录浏览与写操作服务：把浏览根下的目录与文件列成 DTO，并承担改名 / 新建目录 / 删除 / 上传落盘。
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
class FileBrowser(
    settings: AppSettings,
    private val signer: FileSigner,
    workPaths: WorkPaths,
) {

    private val log = Log.of("Files")
    private val fs: FileSystem = FileSystem.SYSTEM

    private val config = settings.files

    /** 浏览根（绝对路径）。 */
    val root: String = config.resolveRoot(settings.webdav.root)

    val rootPath: Path = root.toPath()

    /** 缩略图缓存根：`<workDir>/cache/thumb/v<版本>`（与浏览根解耦，不再写进机械盘目录树）。 */
    val cacheRoot: Path = workPaths.cache("thumb").resolve("v$THUMBNAIL_CACHE_VERSION")

    /** 缩略图缓存（目录结构见 [ThumbnailCache]）。 */
    val cache: ThumbnailCache = ThumbnailCache(cacheRoot.toString(), config.thumbnail.sizes)

    init {
        log.info { "文件管理器根目录：$root（缓存 $cacheRoot，缩略图档位 ${cache.orderedSizes}）" }
    }

    /** 把 URL 里的相对路径解析成真实路径；非法或越界抛 400。 */
    fun requirePath(path: String?): Pair<String, Path> {
        val relative = normalizeRelative(path) ?: throw ApiException(400, "路径非法")
        return relative to resolve(rootPath, relative)
    }

    /**
     * 校验 [path] 确实是一个**存在的目录**（含浏览根），返回归一化后的相对路径。
     *
     * 只做一次 stat，不枚举子项 —— 机械盘上列一次目录很贵，给文件夹打标签这类操作不该顺手把
     * 目录列一遍（[list] / [dirs] 都做不到「只校验不枚举」）。
     */
    fun requireDir(path: String?): String {
        val (relative, dir) = requirePath(path)
        if (fs.metadataOrNull(dir)?.isDirectory != true) {
            throw ApiException(404, "目录不存在：${relative.ifEmpty { "/" }}")
        }
        return relative
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
        if (isHiddenName(nameOf(relative))) return null
        return toEntry(nameOf(relative), relative, meta)
    }

    /**
     * 重命名**目录或文件**（同一父目录内改名，不支持跨目录移动），返回改名后的相对路径。
     *
     * 落盘走 [FileSystem.atomicMove] —— 同目录内的改名本来就是原子的，成功与否都只有两种状态，
     * 不会留下「半个」目录。失败按原因分：重名 409、其余 500（盘只读、被占用等）。
     *
     * 缓存与索引都按路径索引，改名后旧记录成为孤儿：
     * - **文件**的缩略图缓存在这里就地失效（旧路径的那几个 hash 文件没有别的办法回收），
     *   新路径打开目录时按需重生成；
     * - **目录**改名会牵连整棵子树，逐个失效的代价比重新生成还高，故不动（旧缓存成为孤儿，属已知问题）；
     * - 媒体索引由下一轮扫描用内容指纹（`quick_id`）认回新路径并迁移，见 `doc/media-index-design.md`。
     */
    fun rename(path: String?, newName: String): FileOpResult {
        val (relative, source) = requirePath(path)
        if (relative.isEmpty()) throw ApiException(400, "不能重命名浏览根")
        validateEntryName(newName)?.let { throw ApiException(400, it) }
        val meta = fs.metadataOrNull(source) ?: throw ApiException(404, "不存在的项：$relative")
        val currentName = nameOf(relative)
        if (newName == currentName) return FileOpResult(relative, newName)

        val targetRelative = childOf(parentOf(relative).orEmpty(), newName)
        val target = resolve(rootPath, targetRelative)
        if (fs.metadataOrNull(target) != null) throw ApiException(409, "同级已有同名项：$newName")

        runCatching { fs.atomicMove(source, target) }.onFailure {
            log.warn(it) { "重命名失败：$relative -> $targetRelative" }
            throw ApiException(500, "重命名失败：${it.message ?: "未知错误"}")
        }
        if (!meta.isDirectory) cache.invalidate(relative)
        log.info { "已重命名：$relative -> $targetRelative" }
        return FileOpResult(targetRelative, newName)
    }

    /** 在 [path] 目录下新建子目录 [name]；同级已有同名项抛 409。 */
    fun mkdir(path: String?, name: String): FileOpResult {
        val (relative, dir) = requirePath(path)
        if (fs.metadataOrNull(dir)?.isDirectory != true) throw ApiException(404, "目录不存在：${relative.ifEmpty { "/" }}")
        validateEntryName(name)?.let { throw ApiException(400, it) }
        val targetRelative = childOf(relative, name)
        val target = resolve(rootPath, targetRelative)
        if (fs.metadataOrNull(target) != null) throw ApiException(409, "已有同名项：$name")

        runCatching { fs.createDirectory(target) }.onFailure {
            log.warn(it) { "新建目录失败：$targetRelative" }
            throw ApiException(500, "新建目录失败：${it.message ?: "未知错误"}")
        }
        log.info { "已新建目录：$targetRelative" }
        return FileOpResult(targetRelative, name)
    }

    /**
     * 把 [paths] 里的项**复制**或**移动**到 [dest] 目录下（`copy = true` 为复制）。
     *
     * 移动走 [FileSystem.atomicMove]（浏览根是同一挂载点，不跨文件系统）；复制是递归复制，
     * 每进一层让出一次，免得在机械盘上长时间独占 IO。目标目录里重名时**自动加序号**
     * （与上传同一套口径，见 [uniqueName]）—— 既覆盖不了用户的东西，也不必为一次粘贴先想名字。
     *
     * 单个项失败不中断整批，原因逐条收集到 [FileTransferResult.failed]；
     * 把目录搬进它自己（含等于目标目录本身）会被拒绝，否则复制会无限递归、移动会把整棵树弄丢。
     *
     * 移动后源文件路径的缩略图缓存就地失效（与 [rename] 同理）；**目录**内部的缓存
     * 与媒体索引仍是被动的：前者成为孤儿，后者靠下一轮扫描用 `quick_id` 认回。
     */
    suspend fun transfer(paths: List<String>, dest: String?, copy: Boolean): FileTransferResult {
        val (destRelative, destPath) = requirePath(dest)
        if (fs.metadataOrNull(destPath)?.isDirectory != true) {
            throw ApiException(404, "目标目录不存在：${destRelative.ifEmpty { "/" }}")
        }

        val items = ArrayList<FileTransferItem>()
        val failed = ArrayList<String>()
        for (raw in paths) {
            val relative = normalizeRelative(raw)
            if (relative.isNullOrEmpty()) {
                failed.add("${raw.ifEmpty { "/" }}：路径非法")
                continue
            }
            val source = resolve(rootPath, relative)
            val meta = fs.metadataOrNull(source)
            if (meta == null) {
                failed.add("$relative：源已不存在")
                continue
            }
            if (destRelative == relative || destRelative.startsWith("$relative/")) {
                failed.add("$relative：不能${if (copy) "复制" else "移动"}到它自己的里面")
                continue
            }

            val name = uniqueName(destRelative, nameOf(relative))
            val targetRelative = childOf(destRelative, name)
            val target = resolve(rootPath, targetRelative)
            runCatching {
                if (copy) copyRecursive(source, target) else fs.atomicMove(source, target)
            }.onFailure {
                log.warn(it) { "${if (copy) "复制" else "移动"}失败：$relative -> $targetRelative" }
                failed.add("$relative：${it.message ?: "未知错误"}")
                continue
            }
            // 复制不动源，只有移动会让源路径下的缩略图缓存变成孤儿
            if (!copy && !meta.isDirectory) cache.invalidate(relative)
            items.add(FileTransferItem(relative, targetRelative, name))
        }

        val verb = if (copy) "复制" else "移动"
        val hint = if (failed.isEmpty()) "" else "，${failed.size} 项失败"
        log.info { "已$verb ${items.size} 项到 ${destRelative.ifEmpty { "/" }}$hint" }
        return FileTransferResult(items, failed)
    }

    /**
     * 递归复制文件或整棵目录树。
     *
     * 隐藏项（见 [isHiddenName]）一律跳过：它们在文件管理器里本来就看不见，
     * 一并搬过去只会让用户莫名其妙多出一份 `.lolosia`。
     */
    private suspend fun copyRecursive(source: Path, target: Path) {
        val meta = fs.metadataOrNull(source) ?: error("源已不存在：$source")
        if (!meta.isDirectory) {
            fs.copy(source, target)
            return
        }
        fs.createDirectory(target)
        for (child in fs.listOrNull(source).orEmpty()) {
            if (isHiddenName(child.name)) continue
            delay(1)
            copyRecursive(child, target.resolve(child.name))
        }
    }

    /**
     * 删除 [path] 指向的文件或目录；**目录按深度删除**（连子内容一起）。
     *
     * 机械盘上没有回收站，删掉就是删掉，所以前端必须二次确认（见 `views/files/index.vue`）。
     * 文件的缩略图缓存顺手清掉；目录内部的缓存无从一一回收，同样成为孤儿。
     */
    fun delete(path: String?): FileOpResult {
        val (relative, target) = requirePath(path)
        if (relative.isEmpty()) throw ApiException(400, "不能删除浏览根")
        val meta = fs.metadataOrNull(target) ?: throw ApiException(404, "不存在的项：$relative")

        runCatching {
            if (meta.isDirectory) fs.deleteRecursively(target, mustExist = true)
            else fs.delete(target, mustExist = true)
        }.onFailure {
            log.warn(it) { "删除失败：$relative" }
            throw ApiException(500, "删除失败：${it.message ?: "未知错误"}")
        }
        if (!meta.isDirectory) cache.invalidate(relative)
        log.info { "已删除：$relative" }
        return FileOpResult(relative, nameOf(relative))
    }

    /**
     * 解析上传落点：[dir] 是目标目录的相对路径（空串表示浏览根），[fileName] 是浏览器给的原始文件名。
     *
     * 目录里已有同名项时**自动加序号**（`照片.jpg` → `照片 (1).jpg`），既不覆盖也不报错 ——
     * 拖进来一批同名文件（不同目录各有一份 `IMG_0001.JPG`）是最常见的用法，报错反而要人手工改名。
     */
    fun uploadTarget(dir: String?, fileName: String): UploadTarget {
        val (relative, dirPath) = requirePath(dir)
        if (fs.metadataOrNull(dirPath)?.isDirectory != true) throw ApiException(404, "目录不存在：${relative.ifEmpty { "/" }}")
        val name = fileName.trim()
        validateEntryName(name)?.let { throw ApiException(400, it) }

        val finalName = uniqueName(relative, name)
        // 临时名带随机后缀：同一目录并发传两个同名文件时，两边算出的 finalName 会一样，临时名不能也跟着一样
        return UploadTarget(
            relative = childOf(relative, finalName),
            name = finalName,
            target = dirPath.resolve(finalName),
            temp = dirPath.resolve("$finalName.${Random.nextLong().toULong().toString(16).take(6)}.part"),
        )
    }

    /** 打开上传临时文件的写入流；调用方负责 `close()`。 */
    fun openSink(path: Path): BufferedSink {
        fs.createDirectories(path.parent ?: rootPath)
        return fs.sink(path).buffer()
    }

    /**
     * 收尾一次上传：[success] 为真时把临时文件原子改名到目标，否则直接删掉。
     *
     * 无论成败都清理临时文件 —— 失败后留在目录里的 `.part` 是垃圾，不如当场删干净。
     * （进程被杀这类意外来不及清理，会在目录里留下一个 `.part`，需要人工删。）
     */
    fun finishUpload(target: UploadTarget, success: Boolean) {
        if (!success) {
            runCatching { fs.delete(target.temp, mustExist = false) }
            return
        }
        runCatching { fs.atomicMove(target.temp, target.target) }.onFailure {
            runCatching { fs.delete(target.temp, mustExist = false) }
            log.warn(it) { "上传落盘失败：${target.relative}" }
            throw ApiException(500, "写入失败：${it.message ?: "未知错误"}")
        }
        log.info { "已上传：${target.relative}" }
    }

    /** 目录内不冲突的名字：重名时插在扩展名前加 ` (n)`（`a.jpg` → `a (1).jpg`）。 */
    private fun uniqueName(dirRelative: String, name: String): String {
        val dot = name.lastIndexOf('.')
        // dot == 0 表示 `.开头` 的名字，但校验已经把它们挡掉了；这里只认「有点且不在首位」
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var index = 1
        while (fs.metadataOrNull(resolve(rootPath, childOf(dirRelative, candidate))) != null) {
            if (index > MAX_UPLOAD_DEDUP) throw ApiException(409, "同名文件太多，无法自动改名：$name")
            candidate = "$base ($index)$ext"
            index++
        }
        return candidate
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

    /**
     * 打开源文件的只读句柄（原图 / 视频按 `Range` 分段下发用）；调用方负责 `close`。
     *
     * 给的是 [FileHandle] 而不是流：`Range` 要的是「从第 N 字节起」，只有句柄的定位读能 O(1) 做到 ——
     * 用流就得 `skip(offset)`，而那是「读出来丢掉」，几百 MB 的视频取一次尾部要几十秒。
     */
    fun openHandle(relative: String): FileHandle? {
        val path = resolve(rootPath, relative)
        if (fs.metadataOrNull(path)?.isDirectory != false) return null
        return runCatching { fs.openReadOnly(path) }
            .onFailure { log.warn(it) { "打开文件失败：$relative" } }
            .getOrNull()
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
     * 每层目录之间让出一次（`delay`），避免在机械盘上长时间独占 IO；跳过隐藏名（见 [isHiddenName]），
     * 因此 `.lolosia`、`lost+found` 不会被扫到。返回的条目**不算缩略图存在性**（那是一次额外 stat，
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
            if (isHiddenName(child.name)) continue
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
            if (isHiddenName(child.name)) continue
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
     * 递归列出 [relative] 子树下的**目录**相对路径（含自身），跳过隐藏名（见 [isHiddenName]）。
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
            if (isHiddenName(child.name)) continue
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
        if (isHiddenName(child.name)) return null
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
        val children = fs.listOrNull(dir).orEmpty().filterNot { isHiddenName(it.name) }
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
