package moe.lolosia.web.service.files

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.lolosia.web.util.log.Log
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * 缩略图磁盘缓存。
 *
 * 目录结构（`<浏览根>/.cache/lolosia/thumb/v<版本>/`，该目录对 WebDAV 隐藏）：
 * ```
 * v1/
 *   ab/ab12…ef.json          源文件的缓存元数据（大小 / 修改时间 / 尺寸 / 已生成档位）
 *   ab/ab12…ef.256.webp      最长边 256 的缩略图
 *   ab/ab12…ef.1024.webp
 * ```
 * 之所以按**相对路径的 SHA-1** 分片命名，而不是按内容哈希：机械盘只有 30MB/s，列出目录时
 * 若为了算内容哈希而读一遍原图会慢到不可用；用路径做键才能做到「只 stat 不读」。
 * 文件是否变更由元数据里的 `size` / `mtime` 判定（见 [isFresh]）。
 *
 * 元数据写坏或缺失时按「未生成」处理，直接重新生成即可，不做自愈逻辑。
 *
 * @param root 缓存根目录的绝对路径（含版本号）
 * @param sizes 需要生成的档位（最长边像素）
 * @author 洛洛希雅Lolosia
 */
class ThumbnailCache(private val root: String, val sizes: List<Int>) {

    private val log = Log.of("Thumb")
    private val fs: FileSystem = FileSystem.SYSTEM
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 已规范化排序后的档位。 */
    val orderedSizes: List<Int> = sizes.filter { it > 0 }.distinct().sorted()

    /** 最小档位，用作「这张图有没有缩略图」的哨兵。 */
    val smallestSize: Int = orderedSizes.first()

    private val rootPath: Path = root.toPath()

    /** 某个源文件的元数据文件路径。 */
    fun metaPath(relative: String): Path = shardPath(relative, "json")

    /** 某个源文件某档缩略图的路径。 */
    fun thumbPath(relative: String, size: Int): Path = shardPath(relative, "$size.webp")

    private fun shardPath(relative: String, suffix: String): Path {
        val hash = hashOf(relative)
        return rootPath.resolve(hash.substring(0, 2)).resolve("$hash.$suffix")
    }

    /** 相对路径 → 稳定的缓存键（SHA-1，十六进制小写）。 */
    fun hashOf(relative: String): String = relative.encodeUtf8().sha1().hex()

    /** 是否已有最小档缩略图（目录列举只做这一次 stat）。 */
    fun exists(relative: String): Boolean = fs.exists(thumbPath(relative, smallestSize))

    /** 读取某个档位的缩略图字节；不存在返回 `null`。 */
    fun read(relative: String, size: Int): ByteArray? {
        val path = thumbPath(relative, size)
        if (fs.metadataOrNull(path) == null) return null
        return runCatching { fs.read(path) { readByteArray() } }
            .onFailure { log.warn(it) { "读取缩略图失败：$path" } }
            .getOrNull()
    }

    /** 写入某个档位的缩略图。 */
    fun write(relative: String, size: Int, bytes: ByteArray): Boolean = runCatching {
        fs.createDirectories(thumbPath(relative, size).parent!!)
        fs.write(thumbPath(relative, size)) { write(bytes) }
        true
    }.onFailure { log.warn(it) { "写入缩略图失败：$relative @ $size" } }.getOrDefault(false)

    /** 读取元数据；缺失或损坏返回 `null`。 */
    fun readMeta(relative: String): ThumbnailMeta? {
        val path = metaPath(relative)
        if (fs.metadataOrNull(path) == null) return null
        val text = runCatching { fs.read(path) { readUtf8() } }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(ThumbnailMeta.serializer(), text) }.getOrNull()
    }

    /** 写入元数据。 */
    fun writeMeta(meta: ThumbnailMeta): Boolean = runCatching {
        val path = metaPath(meta.source)
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8(json.encodeToString(ThumbnailMeta.serializer(), meta)) }
        true
    }.onFailure { log.warn(it) { "写入缩略图元数据失败：${meta.source}" } }.getOrDefault(false)

    /** 缓存是否可用：源文件的大小与修改时间都没变，且所有档位都已生成。 */
    fun isFresh(meta: ThumbnailMeta?, size: Long, mtime: Long): Boolean =
        meta != null && meta.size == size && meta.mtime == mtime && meta.sizes.containsAll(orderedSizes)

    /** 删除某个源文件的全部缓存（大小 / 修改时间都变了才需要）。 */
    fun invalidate(relative: String) {
        runCatching {
            for (size in orderedSizes) fs.delete(thumbPath(relative, size), mustExist = false)
            fs.delete(metaPath(relative), mustExist = false)
        }
    }
}

/**
 * 单个源文件的缩略图元数据。
 *
 * @property source 相对浏览根的路径（`a/b/c.jpg`）
 * @property size 源文件字节数（变更判定）
 * @property mtime 源文件修改时间（毫秒，变更判定）
 * @property width 源图像素宽
 * @property height 源图像素高
 * @property sizes 已生成的档位
 * @property generatedAt 生成时间（毫秒）
 */
@Serializable
data class ThumbnailMeta(
    @SerialName("source") val source: String,
    @SerialName("size") val size: Long,
    @SerialName("mtime") val mtime: Long,
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
    @SerialName("sizes") val sizes: List<Int>,
    @SerialName("generatedAt") val generatedAt: Long,
)
