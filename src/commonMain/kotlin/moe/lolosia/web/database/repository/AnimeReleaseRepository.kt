package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.AnimeReleaseEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `anime_release` 的仓库：发布条目（见 `doc/anime-timetable-design.md` §3.3）。
 *
 * 采集侧按 `guid` 去重（`countByGuid`），入库用单语句 `executeInsert`。
 * 跨表/分页一律把本表当驱动表；`IN` 不可用，批量在服务层循环。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface AnimeReleaseRepository : CrudRepository<AnimeReleaseEntity> {

    @Query("select count(*) from anime_release where guid = :guid")
    suspend fun countByGuid(context: QueryExecutor, guid: String): Result<Long>

    @Query("select * from anime_release where id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<AnimeReleaseEntity?>

    @Query("select * from anime_release where guid = :guid")
    suspend fun findOneByGuid(context: QueryExecutor, guid: String): Result<AnimeReleaseEntity?>

    @Query("select * from anime_release order by published_at desc limit :maxRows")
    suspend fun findAllByPublishedDesc(context: QueryExecutor, maxRows: Long): Result<List<AnimeReleaseEntity>>

    @Query("select * from anime_release where series_id = :seriesId order by published_at desc limit :maxRows")
    suspend fun findAllBySeriesId(
        context: QueryExecutor,
        seriesId: Long,
        maxRows: Long,
    ): Result<List<AnimeReleaseEntity>>

    @Query(
        "select * from anime_release where series_id = :seriesId and group_name = :groupName " +
            "order by published_at desc limit :maxRows"
    )
    suspend fun findAllBySeriesIdAndGroupName(
        context: QueryExecutor,
        seriesId: Long,
        groupName: String,
        maxRows: Long,
    ): Result<List<AnimeReleaseEntity>>

    @Query("select * from anime_release where match_state = :matchState order by published_at desc limit :maxRows")
    suspend fun findAllByMatchState(
        context: QueryExecutor,
        matchState: String,
        maxRows: Long,
    ): Result<List<AnimeReleaseEntity>>

    @Query(
        "insert into anime_release(guid, source, series_id, group_name, title, episode_no, category, " +
            "torrent_url, detail_url, match_state, published_at, created_at) " +
            "values (:guid, :source, :seriesId, :groupName, :title, :episodeNo, :category, " +
            ":torrentUrl, :detailUrl, :matchState, :publishedAt, :createdAt)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        guid: String,
        source: String,
        seriesId: Long?,
        groupName: String,
        title: String,
        episodeNo: Int?,
        category: String,
        torrentUrl: String?,
        detailUrl: String?,
        matchState: String,
        publishedAt: Instant?,
        createdAt: Instant,
    ): Result<Long>

    @Query("update anime_release set series_id = :seriesId, match_state = :matchState where id = :id")
    suspend fun executeUpdateSeriesId(
        context: QueryExecutor,
        id: Long,
        seriesId: Long,
        matchState: String,
    ): Result<Long>

    @Query("delete from anime_release where published_at < :cutoff")
    suspend fun executeDeleteBefore(context: QueryExecutor, cutoff: Instant): Result<Long>
}
