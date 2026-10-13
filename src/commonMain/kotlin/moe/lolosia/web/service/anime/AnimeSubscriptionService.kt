package moe.lolosia.web.service.anime

import moe.lolosia.web.database.model.ALIAS_SOURCE_MANUAL
import moe.lolosia.web.database.model.MATCH_MATCHED
import moe.lolosia.web.database.repository.*
import moe.lolosia.web.dto.AnimeReleaseMapReq
import moe.lolosia.web.dto.AnimeSubscriptionDeleteReq
import moe.lolosia.web.dto.AnimeSubscriptionSaveReq
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import kotlin.time.Clock

/**
 * 番组表的**写入**：关注增删与发布映射（见 `doc/anime-timetable-design.md` §6）。
 *
 * 关注按 (用户, 番, 组) 幂等 upsert；取消是软删（生成列 `alive_key` 允许重新关注）。
 * 手动映射同时可追加一条手动别名，形成匹配学习闭环。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class AnimeSubscriptionService(
    private val seriesRepo: AnimeSeriesRepository,
    private val releaseRepo: AnimeReleaseRepository,
    private val aliasRepo: AnimeSeriesAliasRepository,
    private val subscriptionRepo: AnimeSubscriptionRepository,
) {

    /** 新建 / 更新关注；`groupName` 留空表示该番任意发布组。 */
    suspend fun save(ctx: Context, req: AnimeSubscriptionSaveReq): MessageResult {
        val userId = ctx.requireUserId()
        val exec = ctx.db
        seriesRepo.findOneById(exec, req.seriesId).getOrNull() ?: throw ApiException(404, "番剧不存在")
        val group = req.groupName?.trim().orEmpty()
        val now = Clock.System.now()
        val existing = subscriptionRepo
            .findOneByUserIdAndSeriesIdAndGroupName(exec, userId, req.seriesId, group)
            .getOrNull()
        if (existing == null) {
            subscriptionRepo.executeInsert(
                exec, userId, req.seriesId, group, req.enabled ?: true, now, userId, now, userId,
            )
        } else {
            subscriptionRepo.executeUpdateEnabledById(exec, existing.id, req.enabled ?: existing.enabled, now, userId)
        }
        return MessageResult("success")
    }

    /** 取消关注（按 id，或按 (番, 组) 定位）。 */
    suspend fun delete(ctx: Context, req: AnimeSubscriptionDeleteReq): MessageResult {
        val userId = ctx.requireUserId()
        val exec = ctx.db
        val now = Clock.System.now()
        val id = req.id ?: run {
            val seriesId = req.seriesId ?: throw ApiException(400, "缺少 id 或 seriesId")
            subscriptionRepo
                .findOneByUserIdAndSeriesIdAndGroupName(exec, userId, seriesId, req.groupName?.trim().orEmpty())
                .getOrNull()?.id
        }
        if (id != null) subscriptionRepo.executeSoftDeleteById(exec, id, userId, now, userId)
        return MessageResult("success")
    }

    /** 手动把一条发布映射到番剧；`alias` 非空时追加手动别名。 */
    suspend fun mapRelease(ctx: Context, req: AnimeReleaseMapReq): MessageResult {
        val exec = ctx.db
        releaseRepo.findOneById(exec, req.releaseId).getOrNull() ?: throw ApiException(404, "发布不存在")
        seriesRepo.findOneById(exec, req.seriesId).getOrNull() ?: throw ApiException(404, "番剧不存在")
        releaseRepo.executeUpdateSeriesId(exec, req.releaseId, req.seriesId, MATCH_MATCHED)
        val alias = req.alias?.trim()
        if (!alias.isNullOrBlank()) {
            val norm = AnimeTitleMatcher.normalize(alias)
            if (norm.isNotBlank()) {
                aliasRepo.executeInsert(exec, req.seriesId, alias, norm, ALIAS_SOURCE_MANUAL, Clock.System.now())
            }
        }
        return MessageResult("success")
    }
}
