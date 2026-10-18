package moe.lolosia.web.service.dashboard

import moe.lolosia.web.controller.DashboardController
import moe.lolosia.web.database.model.MediaAssetEntity
import moe.lolosia.web.database.repository.MediaAssetRepository
import moe.lolosia.web.service.files.FileSigner
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context

/**
 * 仪表盘概览的只读查询：最近新增 / 近期活跃 / 最活跃标签 / 为你推荐 / 受欢迎。
 *
 * 前两者走 [MediaAssetRepository] 的按时间排序查询；后三者是**聚合 / 随机**查询，
 * 不写进 `@Query`（返回会被当实体映射、列对不上表结构），走服务层的**原生 SQL 逃生口**
 * `ctx.db.fetchAll(...)`，逐行手工取值组装 DTO。
 *
 * 「受欢迎」优先取带 `feature:喜欢` 标签的资产，取不到再回退「为你推荐」（随机）。
 *
 * 每行文件都带一份直链签名（`exp`/`sig`），前端 `<img>` 直接拼 `files/thumb` 即可取缩略图；
 * 同一批共用一次 [FileSigner.expireAt]，省去逐行算过期时间。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class DashboardQueryService(
    private val assets: MediaAssetRepository,
    private val signer: FileSigner,
) {

    /** 最近新增（按登记时间倒序）。 */
    suspend fun recentAdded(ctx: Context, limit: Int): List<DashboardController.DashboardFileRow> {
        val exp = signer.expireAt()
        return assets.findAllByCreatedAtOrderDesc(ctx.db, limit.coerceAtLeast(1)).getOrThrow()
            .map { it.toFileRow(exp) }
    }

    /** 近期活跃（按源文件修改时间倒序）。 */
    suspend fun recentActive(ctx: Context, limit: Int): List<DashboardController.DashboardFileRow> {
        val exp = signer.expireAt()
        return assets.findAllByMtimeOrderDesc(ctx.db, limit.coerceAtLeast(1)).getOrThrow()
            .map { it.toFileRow(exp) }
    }

    /** 最活跃标签（被引用次数倒序，前 12）。 */
    suspend fun hotTags(ctx: Context, limit: Int): List<DashboardController.DashboardTagRow> {
        val rs = runCatching { ctx.db.fetchAll(HOT_TAGS_SQL).getOrThrow() }.getOrNull() ?: return emptyList()
        return rs.rows
            .map {
                DashboardController.DashboardTagRow(
                    namespace = it.get("ns").asString(),
                    name = it.get("name").asString(),
                    count = it.get("cnt").asString().toLongOrNull() ?: 0L,
                )
            }
            .take(limit.coerceAtLeast(1))
    }

    /** 为你推荐：随机取若干非露骨资产。 */
    suspend fun recommended(ctx: Context, limit: Int): List<DashboardController.DashboardFileRow> =
        files(ctx, RECOMMENDED_SQL).take(limit.coerceAtLeast(1))

    /** 受欢迎：优先带 `feature:喜欢` 标签，取不到回退随机推荐。 */
    suspend fun popular(ctx: Context, limit: Int): List<DashboardController.DashboardFileRow> {
        val rows = files(ctx, POPULAR_SQL).take(limit.coerceAtLeast(1))
        return rows.ifEmpty { recommended(ctx, limit) }
    }

    private suspend fun files(ctx: Context, sql: String): List<DashboardController.DashboardFileRow> {
        val rs = runCatching { ctx.db.fetchAll(sql).getOrThrow() }.getOrNull() ?: return emptyList()
        val exp = signer.expireAt()
        return rs.rows.map {
            row(
                path = it.get("path").asString(),
                name = it.get("name").asString(),
                size = it.get("size").asString().toLongOrNull() ?: 0L,
                mtime = it.get("mtime").asString().toLongOrNull() ?: 0L,
                kind = it.get("kind").asString(),
                width = it.get("width").asStringOrNull()?.toIntOrNull(),
                height = it.get("height").asStringOrNull()?.toIntOrNull(),
                exp = exp,
            )
        }
    }

    private fun MediaAssetEntity.toFileRow(exp: Long): DashboardController.DashboardFileRow =
        row(path = path, name = name, size = size, mtime = mtime, kind = kind, width = width, height = height, exp = exp)

    /** 组装一行并附上直链签名（签名覆盖「相对路径 + 过期时间」）。 */
    private fun row(
        path: String,
        name: String,
        size: Long,
        mtime: Long,
        kind: String?,
        width: Int?,
        height: Int?,
        exp: Long,
    ) = DashboardController.DashboardFileRow(
        path = path,
        name = name,
        size = size,
        mtime = mtime,
        kind = kind,
        width = width,
        height = height,
        exp = exp,
        sig = signer.sign(path, exp),
    )

    private companion object {
        const val HOT_TAGS_SQL: String =
            "select mt.namespace as ns, mt.name as name, count(*) as cnt " +
                "from media_asset_tag mat join media_tag mt on mt.id = mat.tag_id " +
                "where mat.deleted = 0 and mat.negative = 0 and mt.deleted = 0 " +
                "group by mt.namespace, mt.name order by cnt desc limit 12"

        const val RECOMMENDED_SQL: String =
            "select a.path as path, a.name as name, a.size as size, a.mtime as mtime, a.kind as kind, " +
                "a.width as width, a.height as height " +
                "from media_asset a " +
                "where a.deleted = 0 and a.kind = 'image' and a.rating <> 'explicit' order by rand() limit 8"

        const val POPULAR_SQL: String =
            "select a.path as path, a.name as name, a.size as size, a.mtime as mtime, a.kind as kind, " +
                "a.width as width, a.height as height " +
                "from media_asset a " +
                "join media_asset_tag mat on mat.asset_id = a.id and mat.deleted = 0 and mat.negative = 0 " +
                "join media_tag mt on mt.id = mat.tag_id and mt.deleted = 0 " +
                "where a.deleted = 0 and a.kind = 'image' and a.rating <> 'explicit' " +
                "and mt.namespace = 'feature' and mt.name = '喜欢' " +
                "order by a.mtime desc limit 8"
    }
}
