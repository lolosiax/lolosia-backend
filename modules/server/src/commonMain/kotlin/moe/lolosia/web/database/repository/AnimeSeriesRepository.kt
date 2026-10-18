package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.AnimeSeriesEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `anime_series` 的仓库：番剧目录的读写（见 `doc/anime-timetable-design.md` §3.1）。
 *
 * 目录同步按 `bangumi_id` 幂等 upsert（先查后插/改）；手工建的番 `bangumi_id` 为 `null`，
 * 更新走 `id`。写入一律单语句 `@Query`（MySQL 方言下不用 `CrudRepository` 自带的 insert/update）。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface AnimeSeriesRepository : CrudRepository<AnimeSeriesEntity> {

    @Query("select * from anime_series where deleted = 0 order by air_weekday asc, air_time asc, id asc")
    suspend fun findAllByAlive(context: QueryExecutor): Result<List<AnimeSeriesEntity>>

    @Query("select * from anime_series where deleted = 0 and season = :season order by air_weekday asc, air_time asc, id asc")
    suspend fun findAllBySeason(context: QueryExecutor, season: String): Result<List<AnimeSeriesEntity>>

    @Query("select * from anime_series where deleted = 0 and title like :keyword order by title asc")
    suspend fun findAllByTitleLike(context: QueryExecutor, keyword: String): Result<List<AnimeSeriesEntity>>

    /** AI 匹配的检索工具用：标题或原名命中关键词，限量返回。 */
    @Query(
        "select * from anime_series where deleted = 0 and (title like :keyword or title_original like :keyword) " +
            "order by title asc limit :maxRows"
    )
    suspend fun findAllByTitleOrOriginalLike(
        context: QueryExecutor,
        keyword: String,
        maxRows: Long,
    ): Result<List<AnimeSeriesEntity>>

    @Query("select * from anime_series where id = :id and deleted = 0")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<AnimeSeriesEntity?>

    @Query("select * from anime_series where bangumi_id = :bangumiId and deleted = 0")
    suspend fun findOneByBangumiId(context: QueryExecutor, bangumiId: Long): Result<AnimeSeriesEntity?>

    /** 还没回源到本地封面的番（`cover` 仍是原始 URL / 为空，且是 Bangumi 番）；分批回源用。 */
    @Query(
        "select * from anime_series where deleted = 0 and bangumi_id is not null " +
            "and (cover is null or cover not like '/res/%') order by id asc limit :limit"
    )
    suspend fun findAllByPendingCover(context: QueryExecutor, limit: Long): Result<List<AnimeSeriesEntity>>

    /** 回源成功后把封面改写成本地资源路径（`/res/resources/anime/cover/{bangumiId}.jpg`）。 */
    @Query("update anime_series set cover = :cover, updated_at = :updatedAt where id = :id")
    suspend fun executeUpdateCover(context: QueryExecutor, id: Long, cover: String, updatedAt: Instant): Result<Long>

    @Query(
        "insert into anime_series(bangumi_id, title, title_original, cover, air_weekday, air_time, air_date, " +
            "season, site_url, rating, summary, status, created_at, updated_at, deleted) " +
            "values (:bangumiId, :title, :titleOriginal, :cover, :airWeekday, :airTime, :airDate, " +
            ":season, :siteUrl, :rating, :summary, :status, :createdAt, :updatedAt, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        bangumiId: Long?,
        title: String,
        titleOriginal: String?,
        cover: String?,
        airWeekday: Int?,
        airTime: String?,
        airDate: String?,
        season: String?,
        siteUrl: String?,
        rating: Double?,
        summary: String?,
        status: String,
        createdAt: Instant,
        updatedAt: Instant,
    ): Result<Long>

    @Query(
        "update anime_series set title = :title, title_original = :titleOriginal, cover = :cover, " +
            "air_weekday = :airWeekday, air_time = :airTime, air_date = :airDate, season = :season, " +
            "site_url = :siteUrl, rating = :rating, summary = :summary, status = :status, " +
            "updated_at = :updatedAt where id = :id"
    )
    suspend fun executeUpdateById(
        context: QueryExecutor,
        id: Long,
        title: String,
        titleOriginal: String?,
        cover: String?,
        airWeekday: Int?,
        airTime: String?,
        airDate: String?,
        season: String?,
        siteUrl: String?,
        rating: Double?,
        summary: String?,
        status: String,
        updatedAt: Instant,
    ): Result<Long>
}
