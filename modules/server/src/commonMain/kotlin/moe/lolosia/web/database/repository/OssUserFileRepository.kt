package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.OssUserFileEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `oss_user_file` 的仓库。
 *
 * 文件以 `created_by` 归属某个用户；`md5` 是内容指纹，同一个 md5 可以对应多行
 * （不同 `service` 各一行，即「引用」语义）。`@Query` 不支持 `IN (列表)`，
 * 按 md5 批量查询时请在服务层循环。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface OssUserFileRepository : CrudRepository<OssUserFileEntity> {

    @Query(
        "insert into oss_user_file(id, md5, file_name, service, created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:id, :md5, :fileName, :service, :createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        id: String,
        md5: String,
        fileName: String,
        service: String,
        createdAt: Instant,
        createdBy: String,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 该用户名下是否存在某个 md5（不区分 service）。 */
    @Query("select * from oss_user_file where md5 = :md5 and created_by = :createdBy and deleted = 0 limit 1")
    suspend fun findOneByMd5AndCreatedBy(
        context: QueryExecutor,
        md5: String,
        createdBy: String,
    ): Result<OssUserFileEntity?>

    /** 该用户名下某个 md5 在指定 service 下的元数据。 */
    @Query(
        "select * from oss_user_file where md5 = :md5 and service = :service and created_by = :createdBy " +
            "and deleted = 0 limit 1"
    )
    suspend fun findOneByMd5AndServiceAndCreatedBy(
        context: QueryExecutor,
        md5: String,
        service: String,
        createdBy: String,
    ): Result<OssUserFileEntity?>

    /** 某用户在某 service 下的全部文件。 */
    @Query(
        "select * from oss_user_file where service = :service and created_by = :createdBy and deleted = 0 " +
            "order by created_at desc, id desc"
    )
    suspend fun findAllByServiceAndCreatedBy(
        context: QueryExecutor,
        service: String,
        createdBy: String,
    ): Result<List<OssUserFileEntity>>
}
