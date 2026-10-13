package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.AnimeSeriesAliasEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `anime_series_alias` 的仓库：番剧别名（见 `doc/anime-timetable-design.md` §3.2）。
 *
 * 采集侧每轮把全表读进内存当匹配弹药（量级小）；目录同步时按番剧整表重建（先删后插）。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface AnimeSeriesAliasRepository : CrudRepository<AnimeSeriesAliasEntity> {

    @Query("select * from anime_series_alias")
    suspend fun findAllByAlive(context: QueryExecutor): Result<List<AnimeSeriesAliasEntity>>

    @Query("select * from anime_series_alias where series_id = :seriesId")
    suspend fun findAllBySeriesId(context: QueryExecutor, seriesId: Long): Result<List<AnimeSeriesAliasEntity>>

    @Query(
        "insert into anime_series_alias(series_id, alias, alias_norm, source, created_at) " +
            "values (:seriesId, :alias, :aliasNorm, :source, :createdAt)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        seriesId: Long,
        alias: String,
        aliasNorm: String,
        source: String,
        createdAt: Instant,
    ): Result<Long>

    @Query("delete from anime_series_alias where series_id = :seriesId")
    suspend fun deleteBySeriesId(context: QueryExecutor, seriesId: Long): Result<Long>
}
