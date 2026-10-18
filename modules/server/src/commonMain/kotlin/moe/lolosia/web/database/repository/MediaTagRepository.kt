package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.MediaTagEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `media_tag` 的仓库。
 *
 * 「某个标签被引用多少次」是聚合查询，**不写进 `@Query`**（返回会被当实体映射，`group by`
 * 的列对不上表结构），走服务层的原生 SQL 逃生口，见 `doc/media-index-design.md` §4.8-2。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface MediaTagRepository : CrudRepository<MediaTagEntity> {

    @Query("select * from media_tag where id = :id and deleted = 0")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<MediaTagEntity?>

    @Query("select * from media_tag where namespace = :namespace and name = :name and deleted = 0")
    suspend fun findOneByNamespaceAndName(
        context: QueryExecutor,
        namespace: String,
        name: String,
    ): Result<MediaTagEntity?>

    /** 全量词表（层级展开与「输入侧归一」都在服务层做，量级几百~几千，一次取回最省事）。 */
    @Query("select * from media_tag where deleted = 0 order by namespace asc, name asc")
    suspend fun findAllByDeletedFalse(context: QueryExecutor): Result<List<MediaTagEntity>>

    /** 输入侧归一的候选集：名字或别名里含关键词（先粗筛，精确判定在服务层）。 */
    @Query(
        "select * from media_tag where deleted = 0 and (name like :keyword or alias like :keyword) limit :limit"
    )
    suspend fun findAllByNameOrAliasLike(
        context: QueryExecutor,
        keyword: String,
        limit: Int,
    ): Result<List<MediaTagEntity>>

    @Query(
        "insert into media_tag(namespace, name, parent_id, alias, merged_into, description, " +
            "created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:namespace, :name, :parentId, :alias, :mergedInto, :description, " +
            ":createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        namespace: String,
        name: String,
        parentId: Long?,
        alias: String?,
        mergedInto: Long?,
        description: String?,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 改名 / 改命名空间 / 追加别名。 */
    @Query(
        "update media_tag set namespace = :namespace, name = :name, alias = :alias, " +
            "updated_at = :updatedAt, updated_by = :updatedBy where id = :id"
    )
    suspend fun executeUpdateNameById(
        context: QueryExecutor,
        id: Long,
        namespace: String,
        name: String,
        alias: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 挂父级 / 拎回根级（`parentId` 为 `null`）。成环校验在服务层，写入时拒绝（§7.5）。 */
    @Query("update media_tag set parent_id = :parentId, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    suspend fun executeUpdateParentById(
        context: QueryExecutor,
        id: Long,
        parentId: Long?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 合并后的重定向标记。 */
    @Query(
        "update media_tag set merged_into = :mergedInto, deleted = 1, updated_at = :updatedAt, " +
            "updated_by = :updatedBy where id = :id"
    )
    suspend fun executeMergeIntoById(
        context: QueryExecutor,
        id: Long,
        mergedInto: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update media_tag set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    suspend fun executeSoftDeleteById(
        context: QueryExecutor,
        id: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
