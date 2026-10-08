package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.MediaAssetTagEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `media_asset_tag` 的仓库。
 *
 * 「一次取回整个目录的标签」以**本表为驱动表** join 回 `media_asset`，符合「`@Query` 的主表必须与
 * 实体 `@Table` 一致」的约定（`doc/media-index-design.md` §4.8-1）。
 *
 * 批量写入只能循环单条（`@Query` 不支持 `IN`），由服务层包在一个事务里。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface MediaAssetTagRepository : CrudRepository<MediaAssetTagEntity> {

    @Query("select * from media_asset_tag where asset_id = :assetId and deleted = 0")
    suspend fun findAllByAssetId(context: QueryExecutor, assetId: Long): Result<List<MediaAssetTagEntity>>

    @Query("select * from media_asset_tag where asset_id = :assetId and tag_id = :tagId and deleted = 0")
    suspend fun findAllByAssetIdAndTagId(
        context: QueryExecutor,
        assetId: Long,
        tagId: Long,
    ): Result<List<MediaAssetTagEntity>>

    @Query("select * from media_asset_tag where tag_id = :tagId and deleted = 0 limit :limit")
    suspend fun findAllByTagId(
        context: QueryExecutor,
        tagId: Long,
        limit: Int,
    ): Result<List<MediaAssetTagEntity>>

    /** 整个目录的标签：列目录时与 `findAllByDirPath` 配对使用。 */
    @Query(
        "select t.* from media_asset_tag t join media_asset a on a.id = t.asset_id " +
            "where a.dir_path = :dirPath and a.deleted = 0 and t.deleted = 0"
    )
    suspend fun findAllByDirPath(context: QueryExecutor, dirPath: String): Result<List<MediaAssetTagEntity>>

    @Query(
        "insert into media_asset_tag(asset_id, tag_id, source, provider, confidence, negative, " +
            "created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:assetId, :tagId, :source, :provider, :confidence, :negative, " +
            ":createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        assetId: Long,
        tagId: Long,
        source: String,
        provider: String?,
        confidence: Double?,
        negative: Boolean,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 人工否决位的翻转（重跑模型时不覆盖，只改这一位）。 */
    @Query("update media_asset_tag set negative = :negative, updated_at = :updatedAt where id = :id")
    suspend fun executeUpdateNegativeById(
        context: QueryExecutor,
        id: Long,
        negative: Boolean,
        updatedAt: Instant,
    ): Result<Long>

    /** 按「资产 + 标签 + 来源」翻否决位：属性页打标 / 否决都走它（§7.3）。 */
    @Query(
        "update media_asset_tag set negative = :negative, updated_at = :updatedAt, updated_by = :updatedBy " +
            "where asset_id = :assetId and tag_id = :tagId and source = :source and deleted = 0"
    )
    suspend fun executeUpdateNegativeByAssetIdAndTagIdAndSource(
        context: QueryExecutor,
        assetId: Long,
        tagId: Long,
        source: String,
        negative: Boolean,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 人工解除关联：这个资产上这个标签的**所有来源**一起软删（规则下次扫描可能再加回来，要挡住用 deny）。 */
    @Query(
        "update media_asset_tag set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy " +
            "where asset_id = :assetId and tag_id = :tagId and deleted = 0"
    )
    suspend fun executeSoftDeleteByAssetIdAndTagId(
        context: QueryExecutor,
        assetId: Long,
        tagId: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 清掉某个资产某一来源的全部关联（重跑模型前先清 `model` 行）。 */
    @Query(
        "update media_asset_tag set deleted = 1, updated_at = :updatedAt " +
            "where asset_id = :assetId and source = :source and deleted = 0"
    )
    suspend fun executeSoftDeleteByAssetIdAndSource(
        context: QueryExecutor,
        assetId: Long,
        source: String,
        updatedAt: Instant,
    ): Result<Long>

    /** 合并标签时把关联整体挪到目标标签上。 */
    @Query("update media_asset_tag set tag_id = :intoTagId, updated_at = :updatedAt where tag_id = :fromTagId and deleted = 0")
    suspend fun executeUpdateTagIdByTagId(
        context: QueryExecutor,
        fromTagId: Long,
        intoTagId: Long,
        updatedAt: Instant,
    ): Result<Long>

    @Query("update media_asset_tag set deleted = 1, updated_at = :updatedAt where id = :id")
    suspend fun executeSoftDeleteById(context: QueryExecutor, id: Long, updatedAt: Instant): Result<Long>
}
