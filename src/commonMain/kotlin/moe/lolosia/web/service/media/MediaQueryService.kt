package moe.lolosia.web.service.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.lolosia.web.database.model.MediaAssetEntity
import moe.lolosia.web.database.model.RATING_UNKNOWN
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
) {

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

    /** 给目录列表补上评级 / 是否已索引 / 拍摄时间（一次 `dir_path` 查询，不做逐项查询）。 */
    suspend fun enrich(ctx: Context, result: FileListResult): FileListResult {
        val rows = summary(ctx, result.path)
        if (rows.isEmpty()) return result
        return result.copy(entries = result.entries.map { it.withMedia(rows[it.path]) })
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

    /** 该资产身上的标签；`negative`（人工否决）的不列出。 */
    private suspend fun tagItems(ctx: Context, assetId: Long): List<MediaTagItem> {
        val links = bindings.findAllByAssetId(ctx.db, assetId).getOrThrow().filter { !it.negative }
        if (links.isEmpty()) return emptyList()
        return links.mapNotNull { link ->
            val tag = tags.findOneById(ctx.db, link.tagId).getOrThrow() ?: return@mapNotNull null
            MediaTagItem(tag.id, tag.namespace, tag.name, tag.parentId)
        }.distinctBy { it.id }
    }

    private fun FileEntry.withMedia(row: MediaAssetEntity?): FileEntry = copy(
        rating = row?.rating ?: RATING_UNKNOWN,
        indexed = row != null,
        takenAt = row?.takenAt?.toEpochMilliseconds() ?: 0L,
        sample = row?.sampleRgb565.orEmpty(),
    )
}
