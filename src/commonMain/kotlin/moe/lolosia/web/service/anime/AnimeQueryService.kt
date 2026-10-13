package moe.lolosia.web.service.anime

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import moe.lolosia.web.database.model.AnimeReleaseEntity
import moe.lolosia.web.database.model.AnimeSeriesEntity
import moe.lolosia.web.database.repository.*
import moe.lolosia.web.dto.AnimeGroupDto
import moe.lolosia.web.dto.AnimeReleaseDto
import moe.lolosia.web.dto.AnimeSeriesDetailDto
import moe.lolosia.web.dto.AnimeSeriesDto
import moe.lolosia.web.dto.AnimeSubscriptionDto
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context

/**
 * 番组表的**只读**查询：番组表、番详情、搜索、更新历史、我的关注（见 `doc/anime-timetable-design.md` §6）。
 *
 * 出参带「当前用户是否关注 / 关注了哪些组」，用到的关注态只查一次，避免逐行回查。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class AnimeQueryService(
    private val seriesRepo: AnimeSeriesRepository,
    private val releaseRepo: AnimeReleaseRepository,
    private val subscriptionRepo: AnimeSubscriptionRepository,
) {

    /** 番组表：按周几 / 季度过滤，并标注当前用户的关注态。 */
    suspend fun timetable(ctx: Context, weekday: Int?, season: String?): List<AnimeSeriesDto> {
        val exec = ctx.db
        val rows = (if (season.isNullOrBlank()) seriesRepo.findAllByAlive(exec) else seriesRepo.findAllBySeason(exec, season))
            .getOrElse { throw ApiException(500, "读取番剧目录失败") }
            .filter { weekday == null || it.airWeekday == weekday }
        return decorate(ctx, exec, rows)
    }

    /** 关键词搜索番剧（用于关注）。 */
    suspend fun search(ctx: Context, keyword: String?): List<AnimeSeriesDto> {
        val q = keyword?.trim().orEmpty()
        if (q.isBlank()) return emptyList()
        val exec = ctx.db
        val rows = seriesRepo.findAllByTitleLike(exec, "%$q%").getOrElse { throw ApiException(500, "搜索失败") }
        return decorate(ctx, exec, rows)
    }

    /** 番详情：本体 + 各发布组的更新概况。 */
    suspend fun seriesDetail(ctx: Context, id: Long): AnimeSeriesDetailDto {
        val exec = ctx.db
        val row = seriesRepo.findOneById(exec, id).getOrNull() ?: throw ApiException(404, "番剧不存在")
        val dto = decorate(ctx, exec, listOf(row)).first()
        val releases = releaseRepo.findAllBySeriesId(exec, id, DETAIL_RELEASE_LIMIT).getOrElse { emptyList() }
        val groups = releases.groupBy { it.groupName }.map { (group, list) ->
            AnimeGroupDto(
                groupName = group,
                count = list.size.toLong(),
                latestEpisodeNo = list.firstOrNull()?.episodeNo,
                latestPublishedAt = list.firstOrNull()?.publishedAt?.toEpochMilliseconds() ?: 0L,
            )
        }.sortedByDescending { it.latestPublishedAt }
        return AnimeSeriesDetailDto(dto, groups)
    }

    /** 更新历史：按番 / 发布组过滤；都不给则取全站最新。 */
    suspend fun releases(ctx: Context, seriesId: Long?, groupName: String?, limit: Int?): List<AnimeReleaseDto> {
        val exec = ctx.db
        val maxRows = (limit ?: DEFAULT_RELEASE_LIMIT).coerceIn(1, MAX_RELEASE_LIMIT).toLong()
        val rows = when {
            seriesId == null -> releaseRepo.findAllByPublishedDesc(exec, maxRows)
            groupName.isNullOrBlank() -> releaseRepo.findAllBySeriesId(exec, seriesId, maxRows)
            else -> releaseRepo.findAllBySeriesIdAndGroupName(exec, seriesId, groupName, maxRows)
        }.getOrElse { throw ApiException(500, "读取更新历史失败") }
        return toReleaseDtos(exec, rows)
    }

    /** 我的关注。 */
    suspend fun subscriptions(ctx: Context): List<AnimeSubscriptionDto> {
        val userId = ctx.userId ?: throw ApiException(401, "未登录")
        val exec = ctx.db
        return subscriptionRepo.findAllByUserId(exec, userId).getOrElse { emptyList() }.map { sub ->
            val s = seriesRepo.findOneById(exec, sub.seriesId).getOrNull()
            AnimeSubscriptionDto(
                id = sub.id,
                seriesId = sub.seriesId,
                seriesTitle = s?.title.orEmpty(),
                cover = s?.cover,
                groupName = sub.groupName,
                enabled = sub.enabled,
            )
        }
    }

    /** 给一批番补上当前用户的关注态。 */
    private suspend fun decorate(ctx: Context, exec: QueryExecutor, rows: List<AnimeSeriesEntity>): List<AnimeSeriesDto> {
        val subs = ctx.userId?.let { subscriptionRepo.findAllByUserId(exec, it).getOrElse { emptyList() } }.orEmpty()
        val bySeries = subs.groupBy { it.seriesId }
        return rows.map { it.toDto(bySeries[it.id]?.map { sub -> sub.groupName }.orEmpty()) }
    }

    /** 发布记录 → DTO，顺带批量补番名（先收集 distinct seriesId 再各查一次）。 */
    private suspend fun toReleaseDtos(exec: QueryExecutor, rows: List<AnimeReleaseEntity>): List<AnimeReleaseDto> {
        val titles = HashMap<Long, String>()
        for (id in rows.mapNotNull { it.seriesId }.toSet()) {
            titles[id] = seriesRepo.findOneById(exec, id).getOrNull()?.title.orEmpty()
        }
        return rows.map { it.toDto(titles[it.seriesId]) }
    }

    /** 一部长番剧 + 关注组列表 → DTO。 */
    private fun AnimeSeriesEntity.toDto(followedGroups: List<String>) = AnimeSeriesDto(
        id = id,
        bangumiId = bangumiId,
        title = title,
        titleOriginal = titleOriginal,
        cover = cover,
        airWeekday = airWeekday,
        airTime = airTime,
        airDate = airDate,
        season = season,
        rating = rating,
        summary = summary,
        status = status,
        followed = followedGroups.isNotEmpty(),
        followedGroups = followedGroups,
    )

    /** 一条发布 → DTO。 */
    private fun AnimeReleaseEntity.toDto(seriesTitle: String?) = AnimeReleaseDto(
        id = id,
        guid = guid,
        seriesId = seriesId,
        seriesTitle = seriesTitle.orEmpty(),
        groupName = groupName,
        title = title,
        episodeNo = episodeNo,
        category = category,
        torrentUrl = torrentUrl,
        detailUrl = detailUrl,
        matchState = matchState,
        publishedAt = publishedAt?.toEpochMilliseconds() ?: 0L,
    )

    private companion object {
        const val DETAIL_RELEASE_LIMIT: Long = 500L
        const val DEFAULT_RELEASE_LIMIT: Int = 100
        const val MAX_RELEASE_LIMIT: Int = 500
    }
}
