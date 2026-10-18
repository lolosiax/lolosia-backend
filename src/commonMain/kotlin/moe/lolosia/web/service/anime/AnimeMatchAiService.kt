package moe.lolosia.web.service.anime

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import moe.lolosia.web.database.model.AnimeReleaseEntity
import moe.lolosia.web.database.model.AnimeSeriesAliasEntity
import moe.lolosia.web.database.model.AnimeSeriesEntity
import moe.lolosia.web.database.model.MATCH_AI_UNMATCHED
import moe.lolosia.web.database.model.MATCH_MATCHED
import moe.lolosia.web.database.model.MATCH_UNMATCHED
import moe.lolosia.web.database.model.STATUS_AIRING
import moe.lolosia.web.database.repository.AnimeReleaseRepository
import moe.lolosia.web.database.repository.AnimeSeriesAliasRepository
import moe.lolosia.web.database.repository.AnimeSeriesRepository
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.service.media.TokenLedger
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.llm.ChatClient
import moe.lolosia.web.util.llm.ChatTool
import moe.lolosia.web.util.llm.ChatUsage
import moe.lolosia.web.util.llm.ToolChoice
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service

/**
 * **AI 兜底匹配**：规则匹配（[AnimeTitleMatcher]）漏掉的 RSS 条目，交给大模型再判一次
 * （见 `doc/anime-timetable-design.md` §4.2）。
 *
 * 两处「不一次性全吞」的收敛：
 * - **发布分批**：每 [AppSettings.AnimeConfig.aiMatchBatchSize] 条一次请求，分批判；
 * - **候选先给窄的**：系统提示里只塞**本季度在播**的番；不够用时模型自己调 `search_series`
 *   工具去整个库检索。判完再调 `decide` 一次性交回结果（`seriesId` 省略 = 匹配不到）。
 *
 * **成本闸门**：整轮累计 token 超过 [AppSettings.AnimeConfig.aiMatchTokenLimit] 就抛
 * [TokenBudgetExceeded] 硬止损，避免模型反复检索把 token 烧穿；同时每一轮的 `usage` 都记进
 * 全局台账 [TokenLedger]（与视觉打标同一本账，仪表盘 / `sys_token_usage` 可见）。
 *
 * 落库口径（这是「不再重复匹配」的关键）：
 * - 命中 → [MATCH_MATCHED]；明确判「匹配不到」→ [MATCH_AI_UNMATCHED]（下次不再捞）；
 * - 整批请求 / 解析失败或 token 超限 → 原地不动（仍是 [MATCH_UNMATCHED]），下轮还能再试。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class AnimeMatchAiService(
    settings: AppSettings,
    private val seriesRepo: AnimeSeriesRepository,
    private val aliasRepo: AnimeSeriesAliasRepository,
    private val releaseRepo: AnimeReleaseRepository,
    private val ledger: TokenLedger,
) {

    private val log = Log.of("Anime")
    private val config = settings.anime
    private val llm = settings.llm
    private val client = ChatClient(settings.llm)

    /** 全局台账里的 provider 标签（按模型区分，与视觉打标的 `…@vision` 分开记）。 */
    private val provider: String = "${llm.model}@anime-match"

    /** 「匹配所有未识别」：捞全部 [MATCH_UNMATCHED] 走一轮 AI 匹配，返回新增命中条数。 */
    suspend fun rematchAll(exec: QueryExecutor): Int {
        val pending = releaseRepo.findAllByMatchState(exec, MATCH_UNMATCHED, UNMATCHED_LIMIT)
            .getOrElse { throw ApiException(500, "读取未识别发布失败") }
        if (pending.isEmpty()) return 0
        return rematch(exec, pending).size
    }

    /**
     * 对一批**未识别**发布做 AI 匹配；返回 `releaseId → seriesId`（仅本轮新增命中的）。
     *
     * 传进来的应当都是 [MATCH_UNMATCHED]；命中改 `matched`、判定匹配不到的改 `ai-unmatched`。
     * token 超过上限时提前收手（已判的部分照常落库）。
     */
    suspend fun rematch(exec: QueryExecutor, releases: List<AnimeReleaseEntity>): Map<Long, Long> {
        if (releases.isEmpty()) return emptyMap()
        if (llm.apiKey.isBlank()) {
            log.warn { "未配置 llm.apiKey，跳过 AI 匹配" }
            return emptyMap()
        }
        val seasons = loadSeasonCandidates(exec)
        if (seasons.isEmpty()) {
            log.warn { "番剧目录为空，跳过 AI 匹配" }
            return emptyMap()
        }

        val meter = TokenMeter(config.aiMatchTokenLimit)
        val matched = LinkedHashMap<Long, Long>()
        val batch = config.aiMatchBatchSize.coerceAtLeast(1)
        for (chunk in releases.chunked(batch)) {
            val decisions: Map<Long, Long?> = try {
                matchChunk(exec, chunk, seasons, meter)
            } catch (e: TokenBudgetExceeded) {
                log.warn { "AI 匹配达 token 上限（${e.used}/${e.limit}），提前收手" }
                break
            } catch (e: Throwable) {
                log.warn(e) { "AI 匹配失败（${chunk.size} 条），本轮跳过" }
                continue
            }
            for (release in chunk) {
                val picked = decisions[release.id]
                if (picked != null && seriesRepo.findOneById(exec, picked).getOrNull() != null) {
                    releaseRepo.executeUpdateSeriesId(exec, release.id, picked, MATCH_MATCHED)
                    matched[release.id] = picked
                } else {
                    releaseRepo.executeUpdateMatchState(exec, release.id, MATCH_AI_UNMATCHED)
                }
            }
        }
        if (matched.isNotEmpty()) log.info { "AI 兜底匹配新增 ${matched.size} 条" }
        return matched
    }

    /** 判一批：[系统提示给本季度候选] + [用户消息列这批标题]，模型可检索，最后调 `decide` 交卷。 */
    private suspend fun matchChunk(
        exec: QueryExecutor,
        chunk: List<AnimeReleaseEntity>,
        candidates: List<Candidate>,
        meter: TokenMeter,
    ): Map<Long, Long?> {
        var captured: JsonObject? = null
        val spec = client.prompt()
            .system(systemPrompt(candidates))
            .user(userPrompt(chunk))
            .tools(searchTool(exec), decideTool)
            .toolChoice(ToolChoice.Auto)
            .onUsage { usage ->
                // 先记全局台账（即便随后超限抛错中断，这一轮的消耗也已入账），再累入本次硬上限
                ledger.record(provider, usage)
                meter.add(usage)
            }
            .inspect {
                onExit { event ->
                    if (event.call.name == DECIDE_TOOL) {
                        captured = event.arguments
                        event.throwIt(DecisionCaptured())
                    }
                }
            }
        try {
            spec.text()
        } catch (_: DecisionCaptured) {
            // 预期：decide 已把判定交齐，主动中断后续轮次
        }
        val args = captured ?: throw ApiException(502, "大模型未调用 decide 工具")
        return parseDecisions(args, chunk)
    }

    /** 本季度候选：在播番 + 别名（每轮读一次，量级小）。 */
    private suspend fun loadSeasonCandidates(exec: QueryExecutor): List<Candidate> =
        decorate(exec, seriesRepo.findAllByAlive(exec).getOrElse { emptyList() }.filter { it.status == STATUS_AIRING })

    /** 给一批番补上别名，转成提示词用的候选。 */
    private suspend fun decorate(exec: QueryExecutor, series: List<AnimeSeriesEntity>): List<Candidate> {
        if (series.isEmpty()) return emptyList()
        val aliasesBySeries = aliasesBySeries(exec)
        return series.map { s ->
            Candidate(s.id, s.title, s.titleOriginal, aliasesBySeries[s.id].orEmpty().map { it.alias })
        }
    }

    /** 检索工具的实现：按关键词在库里找番，返回 `id | 展示名 / 原名 / 别名` 若干行。 */
    private suspend fun searchSeries(exec: QueryExecutor, keyword: String): String {
        val like = "%$keyword%"
        val found = LinkedHashMap<Long, AnimeSeriesEntity>()
        seriesRepo.findAllByTitleOrOriginalLike(exec, like, SEARCH_LIMIT).getOrElse { emptyList() }
            .forEach { found[it.id] = it }
        for (alias in aliasRepo.findAllByAliasLike(exec, like, SEARCH_LIMIT).getOrElse { emptyList() }) {
            if (found.containsKey(alias.seriesId)) continue
            seriesRepo.findOneById(exec, alias.seriesId).getOrNull()?.let { found[it.id] = it }
        }
        if (found.isEmpty()) return "没有检索到「$keyword」相关的番剧"
        val aliasesBySeries = aliasesBySeries(exec)
        return found.values.joinToString("\n") {
            renderCandidate(it.id, it.title, it.titleOriginal, aliasesBySeries[it.id].orEmpty().map { a -> a.alias })
        }
    }

    /** 别名按系列分组（全表，量级小）。 */
    private suspend fun aliasesBySeries(exec: QueryExecutor): Map<Long, List<AnimeSeriesAliasEntity>> =
        aliasRepo.findAllByAlive(exec).getOrElse { emptyList() }.groupBy { it.seriesId }

    /** 解析 `decide` 入参：`decisions[i].seriesId`（缺 = 匹配不到）→ releaseId → seriesId?。 */
    private fun parseDecisions(args: JsonObject, chunk: List<AnimeReleaseEntity>): Map<Long, Long?> {
        val array = args["decisions"] as? JsonArray ?: throw ApiException(502, "大模型返回缺少 decisions")
        val out = LinkedHashMap<Long, Long?>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val index = (obj["i"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull ?: continue
            val release = chunk.getOrNull(index) ?: continue
            out[release.id] = (obj["seriesId"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
        }
        if (out.isEmpty()) throw ApiException(502, "大模型没有给出任何判定")
        return out
    }

    private fun systemPrompt(candidates: List<Candidate>): String = buildString {
        appendLine("你是番组发布条目的匹配助手：为每条 RSS 发布标题找到对应的番剧。")
        appendLine("下面先给你**本季度在播**的候选表；表里没有合适的，就用 search_series 工具去整个番剧库检索。")
        appendLine("步骤：")
        appendLine("1. 逐条看标题，先在候选表里找；")
        appendLine("2. 找不到就调用 search_series 检索（可用番名片段 / 日文名 / 别名做关键词）；")
        appendLine("3. 全部判完，调用 decide 工具**一次性**提交结果（i = 条目序号，匹配不到就省略 seriesId）。")
        appendLine("红线：")
        appendLine("- seriesId 必须是候选表或检索结果里出现过的数字 id，绝不编造；")
        appendLine("- 拿不准就判「匹配不到」，宁缺毋滥，错配比漏配更糟；")
        appendLine("- 注意续作 / 多季 / 剧场版：季数与副标题要对得上；字幕组、分辨率、编码、简繁、集数等是噪音。")
        appendLine()
        appendLine("候选表（本季度在播；格式：id | 展示名 / 原名 / 别名）：")
        for (c in candidates) appendLine(renderCandidate(c.id, c.title, c.original, c.aliases))
    }

    private fun userPrompt(chunk: List<AnimeReleaseEntity>): String = buildString {
        appendLine("待匹配发布（序号从 0 起，[组名] 是发布组）：")
        chunk.forEachIndexed { index, release ->
            append('#').append(index).append(' ')
            if (release.groupName.isNotBlank()) append('[').append(release.groupName).append("] ")
            appendLine(release.title)
        }
    }

    /** 一行候选：`id | 展示名 / 原名 / 别名…`。 */
    private fun renderCandidate(id: Long, title: String, original: String?, aliases: List<String>): String = buildString {
        append(id).append(' ').append('|').append(' ').append(title)
        val extra = (listOfNotNull(original) + aliases).filter { it.isNotBlank() }.distinct().take(ALIAS_LIMIT)
        if (extra.isNotEmpty()) append(" / ").append(extra.joinToString(" / "))
    }

    /** 候选表 / 检索结果里的一条番。 */
    private data class Candidate(val id: Long, val title: String, val original: String?, val aliases: List<String>)

    /** `search_series` 工具：模型在候选表之外按关键词检索番剧库。 */
    private fun searchTool(exec: QueryExecutor): ChatTool = object : ChatTool {
        override val name: String = SEARCH_TOOL
        override val description: String = "在番剧库里按关键词检索（命中标题 / 原名 / 别名），返回若干条 `id | 名称`，供你在本季度候选表之外继续找。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("keyword") {
                    put("type", "string")
                    put("description", "检索关键词，番名片段即可（如「青之箱」）")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("keyword")) }
        }
        override suspend fun call(arguments: JsonObject): String = runCatching {
            val keyword = (arguments["keyword"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (keyword.isEmpty()) "请给出 keyword 参数" else searchSeries(exec, keyword)
        }.getOrElse { "检索失败：${it.message}" }
    }

    /** `decide` 工具：模型一次性交回全部判定；真实解析在 [parseDecisions]，这里只回执。 */
    private val decideTool: ChatTool = object : ChatTool {
        override val name: String = DECIDE_TOOL
        override val description: String = "一次性提交所有发布条目的匹配判定；匹配不到的条目省略其 seriesId。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("decisions") {
                    put("type", "array")
                    put("description", "每条发布的判定，按序号一一对应")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("i") {
                                put("type", "integer")
                                put("description", "发布条目序号（从 0 起）")
                            }
                            putJsonObject("seriesId") {
                                put("type", "integer")
                                put("description", "匹配到的番剧 id；匹配不到则省略该字段")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("i")) }
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("decisions")) }
        }

        override suspend fun call(arguments: JsonObject): String =
            "已记录 ${(arguments["decisions"] as? JsonArray)?.size ?: 0} 条判定"
    }

    /** 判定已收齐后用来中断对话轮次的信号。 */
    private class DecisionCaptured : RuntimeException("decide captured")

    private companion object {
        const val DECIDE_TOOL: String = "decide"
        const val SEARCH_TOOL: String = "search_series"

        /** 每条候选最多带几个别名进提示词（控体量）。 */
        const val ALIAS_LIMIT: Int = 6

        /** 检索工具单次返回的候选上限。 */
        const val SEARCH_LIMIT: Long = 15L

        /** 一次「匹配所有未识别」最多处理的发布条数。 */
        const val UNMATCHED_LIMIT: Long = 500L
    }
}

/**
 * 累计一次 AI 匹配的 token 用量；一超硬上限就抛 [TokenBudgetExceeded] 止损。
 *
 * 从 `onUsage` 回调里抛出的异常会一路冒泡、中断整轮请求 —— 正是我们要的「别再烧了」。
 */
private class TokenMeter(private val limit: Long) {

    /** 已累计的 token 数。 */
    var used: Long = 0L
        private set

    fun add(usage: ChatUsage) {
        used += if (usage.totalTokens > 0) usage.totalTokens.toLong() else (usage.promptTokens + usage.completionTokens).toLong()
        if (limit > 0 && used > limit) throw TokenBudgetExceeded(used, limit)
    }
}

/** token 用量触及硬上限；[AnimeMatchAiService.rematch] 据此提前收手。 */
private class TokenBudgetExceeded(val used: Long, val limit: Long) : RuntimeException("AI 匹配 token 超限：$used > $limit")
