package moe.lolosia.web.service.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.lolosia.web.database.model.MediaAssetEntity
import moe.lolosia.web.database.model.MediaAssetTagEntity
import moe.lolosia.web.database.model.RATING_UNKNOWN
import moe.lolosia.web.database.repository.FolderTagRepository
import moe.lolosia.web.database.repository.MediaAssetRepository
import moe.lolosia.web.database.repository.MediaAssetTagRepository
import moe.lolosia.web.database.repository.MediaTagRepository
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.FileDetail
import moe.lolosia.web.dto.FileEntry
import moe.lolosia.web.dto.FileListResult
import moe.lolosia.web.dto.MediaDetail
import moe.lolosia.web.dto.MediaExifItem
import moe.lolosia.web.dto.MediaGps
import moe.lolosia.web.dto.MediaTagItem
import moe.lolosia.web.dto.MediaTagRef
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.service.files.FileSigner
import moe.lolosia.web.util.media.kindOf
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context

/**
 * 媒体信息的只读查询：属性页的 [detail]，以及给 `files/…` 补上评级与拍摄时间的 [enrich]。
 *
 * 「补字段」放在这里而不是 [FileBrowser] 里，是为了让文件浏览器保持**不依赖数据库**：
 * 库不可用时目录照样列得出来，只是每一项的 `rating` 恒为 `unknown`（§11.1）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class MediaQueryService(
    private val browser: FileBrowser,
    private val signer: FileSigner,
    private val assets: MediaAssetRepository,
    private val tags: MediaTagRepository,
    private val bindings: MediaAssetTagRepository,
    private val folderBindings: FolderTagRepository,
) {

    /** 标签来源优先级：数字小的赢（`manual > rule > model`，§7.3）。 */
    private val sourceRank: Map<String, Int> = mapOf(SOURCE_MANUAL to 0, SOURCE_RULE to 1, SOURCE_MODEL to 2)

    /** 单个文件的完整媒体信息。GPS 只有拿到 `lolosia.media.location` 才下发（§6.4）。 */
    suspend fun detail(ctx: Context, path: String?): MediaDetail {
        val (relative, _) = browser.requirePath(path)
        if (relative.isEmpty()) throw ApiException(400, "根目录没有详情")
        val entry = browser.stat(relative) ?: throw ApiException(404, "文件不存在：$relative")
        val row = assets.findOneByPath(ctx.db, relative).getOrThrow()
        val exp = signer.expireAt()
        return MediaDetail(
            path = relative,
            name = entry.name,
            kind = row?.kind ?: kindOf(entry.name).id,
            size = entry.size,
            mtime = entry.mtime,
            width = row?.width,
            height = row?.height,
            durationMs = row?.durationMs,
            rating = row?.rating ?: RATING_UNKNOWN,
            ratingSource = row?.ratingSource,
            analysisSkip = row?.analysisSkip ?: false,
            caption = row?.caption,
            captionSource = row?.captionSource,
            takenAt = row?.takenAt?.toEpochMilliseconds(),
            takenAtLocal = row?.takenAtLocal,
            takenAtOffsetMin = row?.takenAtOffsetMin,
            cameraMake = row?.cameraMake,
            cameraModel = row?.cameraModel,
            lens = row?.lens,
            aperture = row?.aperture,
            exposureTime = row?.exposureTime,
            iso = row?.iso,
            focalLength = row?.focalLength,
            orientation = row?.orientation,
            sample = row?.sampleRgb565.orEmpty(),
            gps = gpsOf(ctx, row),
            metadata = exifItems(row?.metadataJson),
            tags = row?.let { tagItems(ctx, it.id) }.orEmpty(),
            indexed = row != null,
            exp = exp,
            sig = signer.sign(relative, exp),
        )
    }

    /**
     * 给目录列表补上评级 / 是否已索引 / 拍摄时间 / 标签。
     *
     * 文件的媒体字段与标签各一次目录级查询（按 `dir_path`），不逐项查；目录的标签在
     * `folder_tag` 里按路径取，子目录通常不多，逐个查即可。
     */
    suspend fun enrich(ctx: Context, result: FileListResult): FileListResult {
        val rows = summary(ctx, result.path)
        val fileTags = if (rows.isEmpty()) emptyMap() else fileTagsByPath(ctx, result.path, rows)
        val folderTags = folderTagsByPath(ctx, result.entries)
        return result.copy(
            entries = result.entries.map { entry ->
                val tags = if (entry.directory) folderTags[entry.path] else fileTags[entry.path]
                entry.withMedia(rows[entry.path]).copy(tags = tags.orEmpty())
            },
        )
    }

    /** 给单文件详情补上同样的三个字段。 */
    suspend fun enrich(ctx: Context, detail: FileDetail): FileDetail {
        val row = runCatching { assets.findOneByPath(ctx.db, detail.entry.path).getOrThrow() }.getOrNull()
        return detail.copy(entry = detail.entry.withMedia(row))
    }

    private suspend fun summary(ctx: Context, dirPath: String): Map<String, MediaAssetEntity> =
        runCatching { assets.findAllByDirPath(ctx.db, dirPath).getOrThrow() }
            .getOrElse { emptyList() }
            .associateBy { it.path }

    /**
     * 一整个目录里**文件**的标签，按相对路径归并。
     *
     * 驱动表是本表（`media_asset_tag`）join `media_asset`，一次取回；再用 [summary] 那份
     * `assetId → path` 把关联行归到各文件头上。同一标签的多来源按优先级合并（[winningLink]），
     * 人工否决（`negative`）不计入 —— 那表示「明确不是」，不该出现在列表里。
     */
    private suspend fun fileTagsByPath(
        ctx: Context,
        dirPath: String,
        rows: Map<String, MediaAssetEntity>,
    ): Map<String, List<MediaTagRef>> {
        val links = runCatching { bindings.findAllByDirPath(ctx.db, dirPath).getOrThrow() }
            .getOrElse { return emptyMap() }
        if (links.isEmpty()) return emptyMap()
        val pathByAsset = rows.values.associate { it.id to it.path }
        val vocab = vocabulary(ctx)
        return links.groupBy { pathByAsset[it.assetId] }
            .filterKeys { it != null }
            .map { (path, group) -> path!! to winningTags(group, vocab) }
            .toMap()
    }

    /**
     * 目录列表里各**子目录**自己的标签（`folder_tag`，按相对路径取）。
     *
     * `folder_tag` 没有「按父目录批量取」的查询、`@Query` 也不支持 `IN`，只能逐个目录查 ——
     * 一个目录的子目录通常只有几个，代价可接受。词表懒加载：一条目录标签都没有时一次都不查。
     */
    private suspend fun folderTagsByPath(ctx: Context, entries: List<FileEntry>): Map<String, List<MediaTagRef>> {
        val dirs = entries.filter { it.directory }
        if (dirs.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, List<MediaTagRef>>()
        var vocab: Map<Long, MediaTagRef>? = null
        for (dir in dirs) {
            val links = runCatching { folderBindings.findAllByPath(ctx.db, dir.path).getOrThrow() }
                .getOrNull().orEmpty()
            if (links.isEmpty()) continue
            val table = vocab ?: vocabulary(ctx).also { vocab = it }
            out[dir.path] = links.map { it.tagId }.distinct().sorted().mapNotNull { table[it] }
        }
        return out
    }

    /** 同一标签的多来源合并出生效那一条，再映射成 `namespace` + `name`；否决的不计入。 */
    private fun winningTags(links: List<MediaAssetTagEntity>, vocab: Map<Long, MediaTagRef>): List<MediaTagRef> =
        links.groupBy { it.tagId }
            .values
            .mapNotNull { winningLink(it) }
            .filter { !it.negative }
            .sortedBy { it.tagId }
            .mapNotNull { vocab[it.tagId] }

    /** 全量词表 `id → (namespace, name)`，一次取回（量级几百到几千，见 §7.5）；取不到时为空表。 */
    private suspend fun vocabulary(ctx: Context): Map<Long, MediaTagRef> {
        val rows = runCatching { tags.findAllByDeletedFalse(ctx.db).getOrThrow() }.getOrNull() ?: return emptyMap()
        return rows.associate { it.id to MediaTagRef(it.namespace, it.name) }
    }

    /** GPS 精确到米、带上时间就是行程轨迹，所以单独占一个权限节点（§6.4）。 */
    private suspend fun gpsOf(ctx: Context, row: MediaAssetEntity?): MediaGps? {
        val lat = row?.gpsLat ?: return null
        val lon = row.gpsLon ?: return null
        if (!ctx.hasPermission(MEDIA_LOCATION)) return null
        return MediaGps(lat, lon)
    }

    /** `metadata_json` 是扁平对象，展开成属性页里的键值列表。 */
    private fun exifItems(text: String?): List<MediaExifItem> {
        if (text.isNullOrBlank()) return emptyList()
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        return obj.entries.map { (key, value) -> MediaExifItem(key, (value as? JsonPrimitive)?.content.orEmpty()) }
    }

    /**
     * 该资产身上的标签。同一个标签可能有多条来源行，这里按 `manual > rule > model` 合并成一条生效的
     * （§7.3），否决的行**照列但带上 `negative`** —— 属性页要把「这张图明确不是什么」显示出来。
     */
    private suspend fun tagItems(ctx: Context, assetId: Long): List<MediaTagItem> {
        val links = bindings.findAllByAssetId(ctx.db, assetId).getOrThrow()
        if (links.isEmpty()) return emptyList()
        val winners = links.groupBy { it.tagId }.values.mapNotNull { winningLink(it) }
        return winners.mapNotNull { link ->
            val tag = tags.findOneById(ctx.db, link.tagId).getOrThrow() ?: return@mapNotNull null
            MediaTagItem(
                id = tag.id,
                namespace = tag.namespace,
                name = tag.name,
                parentId = tag.parentId,
                source = link.source,
                negative = link.negative,
            )
        }.sortedBy { it.id }
    }

    /** 同一标签的多条来源里挑生效的那条：来源优先级高的赢，同来源里**否决的赢**。 */
    private fun winningLink(rows: List<MediaAssetTagEntity>): MediaAssetTagEntity? =
        rows.minWithOrNull(
            compareBy<MediaAssetTagEntity>({ sourceRank[it.source] ?: Int.MAX_VALUE }, { !it.negative })
        )

    private fun FileEntry.withMedia(row: MediaAssetEntity?): FileEntry = copy(
        rating = row?.rating ?: RATING_UNKNOWN,
        indexed = row != null,
        takenAt = row?.takenAt?.toEpochMilliseconds() ?: 0L,
        sample = row?.sampleRgb565.orEmpty(),
    )
}
