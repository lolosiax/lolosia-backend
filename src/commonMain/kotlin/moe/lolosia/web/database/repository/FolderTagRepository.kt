package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.FolderTagEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `folder_tag` 的仓库：按**相对路径**读写的目录标签关联。
 *
 * 与 [MediaAssetTagRepository] 同构，只是驱动列从 `asset_id` 换成 `path`；同样没有唯一键，
 * 去重交给服务层（见 `sql/folder_tag.sql`）。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface FolderTagRepository : CrudRepository<FolderTagEntity> {

    @Query("select * from folder_tag where path = :path and deleted = 0")
    suspend fun findAllByPath(context: QueryExecutor, path: String): Result<List<FolderTagEntity>>

    @Query("select * from folder_tag where path = :path and tag_id = :tagId and deleted = 0")
    suspend fun findAllByPathAndTagId(
        context: QueryExecutor,
        path: String,
        tagId: Long,
    ): Result<List<FolderTagEntity>>

    @Query(
        "insert into folder_tag(path, tag_id, source, created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:path, :tagId, :source, :createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        path: String,
        tagId: Long,
        source: String,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 解除关联：这个目录上这个标签的所有来源一起软删（目录只有 manual，等于整条摘掉）。 */
    @Query(
        "update folder_tag set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy " +
            "where path = :path and tag_id = :tagId and deleted = 0"
    )
    suspend fun executeSoftDeleteByPathAndTagId(
        context: QueryExecutor,
        path: String,
        tagId: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
