package moe.lolosia.web.controller

import moe.lolosia.web.dto.AnimeReleaseDto
import moe.lolosia.web.dto.AnimeReleaseMapReq
import moe.lolosia.web.dto.AnimeSeriesDetailDto
import moe.lolosia.web.dto.AnimeSeriesDto
import moe.lolosia.web.dto.AnimeSubscriptionDeleteReq
import moe.lolosia.web.dto.AnimeSubscriptionDto
import moe.lolosia.web.dto.AnimeSubscriptionSaveReq
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.anime.ANIME_ADMIN
import moe.lolosia.web.service.anime.ANIME_READ
import moe.lolosia.web.service.anime.ANIME_WRITE
import moe.lolosia.web.service.anime.AnimeFeedService
import moe.lolosia.web.service.anime.AnimeMatchAiService
import moe.lolosia.web.service.anime.AnimeMetaService
import moe.lolosia.web.service.anime.AnimeQueryService
import moe.lolosia.web.service.anime.AnimeSubscriptionService
import moe.lolosia.web.util.router.*
import moe.lolosia.web.util.session.Context

/**
 * **番组表**接口（对应前端 `src/views/anime`，见 `doc/anime-timetable-design.md` §6）。
 *
 * 读接口挂 [ANIME_READ]，关注增删挂 [ANIME_WRITE]，目录同步 / 手动映射 / 立即拉取等运维动作挂
 * [ANIME_ADMIN]（默认只给 admin）。搜索路径用 `/anime/search`（而非 `/anime/series/search`），
 * 避免与 `/anime/series/{id}` 的通配段在路由层抢匹配。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object AnimeController {

    /** 番组表：按周几 / 季度过滤，带当前用户的关注态。 */
    @GET("/anime/timetable")
    @Permission(ANIME_READ)
    suspend fun timetable(
        ctx: Context,
        query: AnimeQueryService,
        @Query weekday: Int?,
        @Query season: String?,
    ): List<AnimeSeriesDto> = query.timetable(ctx, weekday, season)

    /** 关键词搜索番剧（用于关注）。 */
    @GET("/anime/search")
    @Permission(ANIME_READ)
    suspend fun search(
        ctx: Context,
        query: AnimeQueryService,
        @Query q: String?,
    ): List<AnimeSeriesDto> = query.search(ctx, q)

    /** 番详情：本体 + 各发布组的更新概况。 */
    @GET("/anime/series/{id}")
    @Permission(ANIME_READ)
    suspend fun seriesDetail(
        ctx: Context,
        query: AnimeQueryService,
        @Path id: Long,
    ): AnimeSeriesDetailDto = query.seriesDetail(ctx, id)

    /** 更新历史。 */
    @GET("/anime/releases")
    @Permission(ANIME_READ)
    suspend fun releases(
        ctx: Context,
        query: AnimeQueryService,
        @Query seriesId: Long?,
        @Query groupName: String?,
        @Query limit: Int?,
    ): List<AnimeReleaseDto> = query.releases(ctx, seriesId, groupName, limit)

    /** 我的关注。 */
    @GET("/anime/subscriptions")
    @Permission(ANIME_READ)
    suspend fun subscriptions(ctx: Context, query: AnimeQueryService): List<AnimeSubscriptionDto> =
        query.subscriptions(ctx)

    /** 新建 / 更新关注。 */
    @POST("/anime/subscription/save")
    @Permission(ANIME_WRITE)
    suspend fun saveSubscription(
        ctx: Context,
        subs: AnimeSubscriptionService,
        @Body req: AnimeSubscriptionSaveReq,
    ): MessageResult = subs.save(ctx, req)

    /** 取消关注。 */
    @POST("/anime/subscription/delete")
    @Permission(ANIME_WRITE)
    suspend fun deleteSubscription(
        ctx: Context,
        subs: AnimeSubscriptionService,
        @Body req: AnimeSubscriptionDeleteReq,
    ): MessageResult = subs.delete(ctx, req)

    /** 手动把一条发布映射到番剧（可追加手动别名）。 */
    @POST("/anime/release/map")
    @Permission(ANIME_ADMIN)
    suspend fun mapRelease(
        ctx: Context,
        subs: AnimeSubscriptionService,
        @Body req: AnimeReleaseMapReq,
    ): MessageResult = subs.mapRelease(ctx, req)

    /** 对全部「未识别」发布跑一轮 AI 匹配（匹配不到的会被标记，不再重复匹配）。 */
    @POST("/anime/release/rematch")
    @Permission(ANIME_ADMIN)
    suspend fun rematchReleases(ctx: Context, ai: AnimeMatchAiService): MessageResult =
        MessageResult("AI 匹配新增 ${ai.rematchAll(ctx.db)} 条")

    /** 手动同步 Bangumi 番剧目录（中继模式下只发通知，秒回；结果由后台任务取回）。 */
    @POST("/anime/series/refresh")
    @Permission(ANIME_ADMIN)
    suspend fun refreshSeries(meta: AnimeMetaService): MessageResult =
        MessageResult(meta.requestRefresh())

    /** 立即触发一次 RSS 拉取（排障用）。 */
    @POST("/anime/feed/poll")
    @Permission(ANIME_ADMIN)
    suspend fun pollFeed(feed: AnimeFeedService): MessageResult =
        MessageResult("新增 ${feed.pollNow()} 条")
}
