package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.FolderRemarkEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `folder_remark` 的仓库：按**相对路径**读写的目录备注。
 *
 * 一个目录最多一条活行，取最新的一条（历史软删行不参与）。写入只有「插入 / 改文本 / 软删」三种。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface FolderRemarkRepository : CrudRepository<FolderRemarkEntity> {

    @Query("select * from folder_remark where path = :path and deleted = 0 order by id desc limit 1")
    suspend fun findOneByPath(context: QueryExecutor, path: String): Result<FolderRemarkEntity?>

    @Query(
        "insert into folder_remark(path, remark, created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:path, :remark, :createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        path: String,
        remark: String?,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update folder_remark set remark = :remark, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    suspend fun executeUpdateRemarkById(
        context: QueryExecutor,
        id: Long,
        remark: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update folder_remark set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    suspend fun executeSoftDeleteById(
        context: QueryExecutor,
        id: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
