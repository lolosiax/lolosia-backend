package moe.lolosia.web.service.dashboard

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import moe.lolosia.web.database.model.MediaAssetEntity
import moe.lolosia.web.database.model.RATING_EXPLICIT
import moe.lolosia.web.database.repository.MediaAssetRepository
import moe.lolosia.web.dto.DashboardFileRow
import moe.lolosia.web.dto.DashboardOverview
import moe.lolosia.web.dto.DashboardPhotoRow
import moe.lolosia.web.dto.DashboardTagRow
import moe.lolosia.web.dto.MediaTagItem
import moe.lolosia.web.service.files.FileSigner
import moe.lolosia.web.service.files.extensionOf
import moe.lolosia.web.service.media.MediaQueryService
import moe.lolosia.web.service.media.NAMESPACE_FEATURE
import moe.lolosia.web.service.media.NAMESPACE_META
import moe.lolosia.web.service.media.TAG_LIKE
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.media.MediaKind
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.time.nowEpochMs
import kotlin.random.Random

/**
 * 仪表板的「媒体卡片 + 访问排行」：首屏一次取回。
 *
 * 数据来源分工（「能显示什么取决于实际情况」的落点）：
 * - 媒体卡片全部来自**已落地的媒体索引**（`media_asset` / `media_tag` / `media_asset_tag`），
 *   不需要新表；未索引（库为空）时各卡片为空表；
 * - 「为你推荐」取 `rating <> explicit` 的图片池随机抽；
 * - 「受欢迎」口径是**人工标心（`feature:喜欢`）+ 标签热度**（没有真实浏览 / 点赞数据）；
 * - 访问排行来自 [AccessStatService]，本平台没采到就是空表。
 *
 * 每张卡片都单独 `runCatching`：某张表不可用只会让它为空，不影响其余卡片。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class DashboardService(
    settings: AppSettings,
    private val assets: MediaAssetRepository,
    private val media: MediaQueryService,
    private val signer: FileSigner,
    private val access: AccessStatService,
) {

    private val limit: Int = settings.dashboard.topLimit.coerceIn(3, 20)

    /** 首屏汇总：访问排行 + 媒体卡片。 */
    suspend fun overview(ctx: Context): DashboardOverview {
        val exp = signer.expireAt()
        val active = query(ctx) { assets.findAllByOrderByMtimeDesc(it, limit).getOrThrow() }
        val added = query(ctx) { assets.findAllByOrderByCreatedAtDesc(it, limit).getOrThrow() }
        val liked = query(ctx) {
            assets.findAllByTagOrderByMtimeDesc(it, NAMESPACE_FEATURE, TAG_LIKE, limit).getOrThrow()
        }
        val pool = query(ctx) {
            assets.findAllByKindAndRatingNotOrderByMtimeDesc(it, MediaKind.IMAGE.id, RATING_EXPLICIT, POOL_SIZE)
                .getOrThrow()
        }
        return DashboardOverview(
            ipRank = access.topIps(ctx, limit),
            originRank = access.topHosts(ctx, limit),
            activeFiles = active.map { fileRow(ctx, it, exp) },
            newFiles = added.map { fileRow(ctx, it, exp) },
            hotTags = hotTags(ctx),
            recommended = pool.shuffled(Random).take(limit).map { photoRow(ctx, it, exp) },
            popular = popular(ctx, liked, pool, exp),
            generatedAt = nowEpochMs(),
        )
    }

    /** 受欢迎：先人工标心的图，不够再用候选池补齐（同一个路径不重复）。 */
    private suspend fun popular(
        ctx: Context,
        liked: List<MediaAssetEntity>,
        pool: List<MediaAssetEntity>,
        exp: Long,
    ): List<DashboardPhotoRow> {
        val chosen = liked.toMutableList()
        val used = chosen.mapTo(mutableSetOf()) { it.path }
        for (asset in pool) {
            if (chosen.size >= limit) break
            if (used.add(asset.path)) chosen += asset
        }
        return chosen.take(limit).map { photoRow(ctx, it, exp) }
    }

    private suspend fun fileRow(ctx: Context, asset: MediaAssetEntity, exp: Long): DashboardFileRow =
        DashboardFileRow(
            name = asset.name,
            path = asset.path,
            ext = extensionOf(asset.name),
            kind = asset.kind,
            size = asset.size,
            mtime = asset.mtime,
            tag = positiveTags(ctx, asset.id).firstOrNull()?.name.orEmpty(),
            exp = exp,
            sig = signer.sign(asset.path, exp),
        )

    private suspend fun photoRow(ctx: Context, asset: MediaAssetEntity, exp: Long): DashboardPhotoRow {
        val tags = positiveTags(ctx, asset.id)
        return DashboardPhotoRow(
            name = asset.name,
            path = asset.path,
            title = asset.name.substringBeforeLast('.', asset.name),
            tag = tags.firstOrNull()?.name.orEmpty(),
            likes = tags.size.toLong(),
            width = asset.width,
            height = asset.height,
            sample = asset.sampleRgb565.orEmpty(),
            exp = exp,
            sig = signer.sign(asset.path, exp),
        )
    }

    private suspend fun hotTags(ctx: Context): List<DashboardTagRow> {
        val db = runCatching { ctx.db }.getOrNull() ?: return emptyList()
        // 聚合走原生 SQL 逃生口：`@Query` 的返回必须是实体、也不接受聚合列（见 AGENTS.md §5）。
        // 拼进 SQL 的只有常量命名空间与已夹到 3~20 的 limit，没有外部输入。
        val sql = "select t.namespace as ns, t.name as nm, count(a.id) as cnt " +
            "from media_tag t left join media_asset_tag a on a.tag_id = t.id and a.deleted = 0 " +
            "where t.deleted = 0 and t.namespace <> '$NAMESPACE_META' " +
            "group by t.id, t.namespace, t.name order by cnt desc limit $limit"
        val rows = runCatching { db.fetchAll(sql).getOrThrow().rows }.getOrElse { return emptyList() }
        return rows.mapNotNull { row ->
            val cnt = row.get("cnt").asStringOrNull()?.toLongOrNull() ?: return@mapNotNull null
            if (cnt <= 0L) null else DashboardTagRow(row.get("ns").asString(), row.get("nm").asString(), cnt)
        }
    }

    /** 一个资产身上**未被否决**的标签；取不到（库不可用）时为空表。 */
    private suspend fun positiveTags(ctx: Context, assetId: Long): List<MediaTagItem> =
        runCatching { media.tagsOf(ctx, assetId) }.getOrElse { emptyList() }.filter { !it.negative }

    /** 统一包一层：库不可用或查询失败都退化成空表，别让一张卡片拖垮整个首屏。 */
    private suspend fun query(
        ctx: Context,
        block: suspend (QueryExecutor) -> List<MediaAssetEntity>,
    ): List<MediaAssetEntity> {
        val db = runCatching { ctx.db }.getOrNull() ?: return emptyList()
        return runCatching { block(db) }.getOrElse { emptyList() }
    }

    private companion object {
        /** 「为你推荐 / 受欢迎」的候选池大小：够随机又不至于把整库拉进内存。 */
        const val POOL_SIZE: Int = 200
    }
}
