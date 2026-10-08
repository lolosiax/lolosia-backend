package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.MediaEmbeddingEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `media_embedding` 的仓库：只记账，向量本体在磁盘上的 `.f32` 里。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface MediaEmbeddingRepository : CrudRepository<MediaEmbeddingEntity> {

    @Query("select * from media_embedding where model = :model and deleted = 0 order by row_index asc")
    suspend fun findAllByModel(context: QueryExecutor, model: String): Result<List<MediaEmbeddingEntity>>

    @Query("select * from media_embedding where asset_id = :assetId and model = :model and deleted = 0")
    suspend fun findOneByAssetIdAndModel(
        context: QueryExecutor,
        assetId: Long,
        model: String,
    ): Result<MediaEmbeddingEntity?>

    @Query("select count(*) from media_embedding where model = :model and deleted = 0")
    suspend fun countByModel(context: QueryExecutor, model: String): Result<Long>

    @Query(
        "insert into media_embedding(asset_id, model, dim, row_index, norm, created_at, updated_at, deleted) " +
            "values (:assetId, :model, :dim, :rowIndex, :norm, :createdAt, :updatedAt, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        assetId: Long,
        model: String,
        dim: Int,
        rowIndex: Long,
        norm: Double?,
        createdAt: Instant,
        updatedAt: Instant,
    ): Result<Long>

    @Query("update media_embedding set deleted = 1, updated_at = :updatedAt where asset_id = :assetId and deleted = 0")
    suspend fun executeSoftDeleteByAssetId(context: QueryExecutor, assetId: Long, updatedAt: Instant): Result<Long>
}
