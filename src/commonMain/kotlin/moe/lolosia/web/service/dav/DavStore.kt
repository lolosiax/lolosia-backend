package moe.lolosia.web.service.dav

import moe.lolosia.web.service.files.isHiddenName
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/**
 * WebDAV 存储层：把 URL 相对路径安全映射到 [root] 下的真实路径，并封装 okio 的目录/文件操作。
 *
 * 路径**逐段校验**，拒绝 `.` / `..` / 空段 / 含分隔符与 NUL 的段，因此解析结果必然落在根内
 * （目录穿越防护）。另外隐藏名（见 `isHiddenName`：`.` 开头，以及文件系统自建的 `lost+found`）
 * 默认一律被拒（`resolve` 返回 `null` → 403），目录列举也会跳过它们：这样 `.cache`（缩略图缓存）
 * 与 `lost+found` 在 WebDAV 客户端里完全不可见，不会混进机械盘的正常目录树。
 *
 * [allowHidden] 返回 `true` 时上面的隐藏规则失效 —— 开发机把线上机的 `/dav` 映射成网络驱动器
 * 当本地盘用时，缩略图缓存要落在 `<root>/.cache` 下，客户端得能建目录、能读写。取值走回调而不是
 * 构造参数，是为了让设置面板改了立刻生效（见 `AppSettingService`）。
 *
 * 文件系统访问走 `FileSystem.SYSTEM`，native 与 Windows 同一套 API。
 *
 * @param root 根目录绝对路径，如 `/mnt/hdd1`
 * @param allowHidden 是否允许访问隐藏项；默认一律拒绝
 * @author 洛洛希雅Lolosia
 */
class DavStore(val root: String, private val allowHidden: () -> Boolean = { false }) {

    private val fs: FileSystem = FileSystem.SYSTEM

    /** 根路径。 */
    val rootPath: Path = root.toPath()

    /** 该名字当前是否算不可见项。 */
    private fun hidden(name: String): Boolean = !allowHidden() && isHiddenName(name)

    /** 相对路径分段（已 URL 解码）→ 真实路径；任一段非法返回 `null`。 */
    fun resolve(segments: List<String>): Path? {
        var path = rootPath
        for (seg in segments) {
            if (seg.isEmpty() || seg == "." || seg == "..") return null
            if (seg.any { it == '/' || it == '\\' || it == '\u0000' }) return null
            // 隐藏项（`.cache` 缓存目录、`lost+found` 保留目录）默认对 WebDAV 不暴露：列举不显示，直连路径也拒绝
            if (hidden(seg)) return null
            path = path.resolve(seg)
        }
        return path
    }

    fun exists(path: Path): Boolean = fs.exists(path)

    fun metadata(path: Path): FileMetadata? = fs.metadataOrNull(path)

    /** 目录项，按名字排序（输出稳定，便于客户端对比）；隐藏项跳过。 */
    fun children(path: Path): List<Path> =
        fs.listOrNull(path).orEmpty().filterNot { hidden(it.name) }.sortedBy { it.name.lowercase() }

    /** 新建目录；父目录不存在按 409 处理（与 WebDAV 语义一致，不隐式建父）。 */
    fun createDirectory(path: Path) {
        val parent = path.parent
        if (parent != null && !fs.exists(parent)) throw DavException.Conflict("父目录不存在")
        fs.createDirectory(path, mustCreate = true)
    }

    /** 删除：WebDAV 对集合的 DELETE 是深度删除。 */
    fun delete(path: Path) {
        if (fs.metadataOrNull(path) == null) throw DavException.NotFound()
        fs.deleteRecursively(path, mustExist = true)
    }

    /** 覆盖写入的 sink（自动补齐父目录；已存在则截断）；调用方负责 `close()`。 */
    fun sink(path: Path) = fs.sink(path).buffer()

    /** 只读 source；调用方负责 `close()`。 */
    fun source(path: Path) = fs.source(path).buffer()

    /** 移动：目标已存在时由 [overwrite] 决定先删还是报 412。 */
    fun move(source: Path, target: Path, overwrite: Boolean) {
        if (fs.metadataOrNull(source) == null) throw DavException.NotFound()
        if (fs.exists(target)) {
            if (!overwrite) throw DavException.PreconditionFailed()
            fs.deleteRecursively(target, mustExist = true)
        }
        fs.createDirectories(target.parent ?: rootPath)
        fs.atomicMove(source, target)
    }

    /** 复制：集合逐层重建（目标是已存在的目录时按 Overwrite 合并）。 */
    fun copy(source: Path, target: Path, overwrite: Boolean) {
        val meta = fs.metadataOrNull(source) ?: throw DavException.NotFound()
        if (meta.isDirectory) {
            if (fs.exists(target)) {
                if (!overwrite) throw DavException.PreconditionFailed()
            } else {
                fs.createDirectories(target)
            }
            // 子项已经过顶层 Overwrite 判定，这里一律允许覆盖，避免嵌套目录被误判成冲突
            for (child in children(source)) copy(child, target.resolve(child.name), overwrite = true)
        } else {
            if (fs.exists(target) && !overwrite) throw DavException.PreconditionFailed()
            fs.createDirectories(target.parent ?: rootPath)
            copyFile(source, target)
        }
    }

    private fun copyFile(source: Path, target: Path) {
        val input = fs.source(source).buffer()
        try {
            val output = fs.sink(target).buffer()
            try {
                input.readAll(output)
            } finally {
                output.close()
            }
        } finally {
            input.close()
        }
    }
}
