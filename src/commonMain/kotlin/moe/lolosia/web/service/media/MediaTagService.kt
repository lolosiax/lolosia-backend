package moe.lolosia.web.service.media

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import moe.lolosia.web.database.model.RATING_EXPLICIT
import moe.lolosia.web.database.repository.MediaAssetTagRepository
import moe.lolosia.web.database.repository.MediaTagRepository
import moe.lolosia.web.database.repository.MediaTagRuleRepository
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import kotlin.time.Clock

/**
 * 标签词表的读写：**规则打标** + 按需建标签 + 评级投影。
 *
 * 规则集是**整轮扫描加载一次**的（[loadRules]），不在每个文件上重查库 —— 十万张图就是十万次
 * 查询，机械盘上不可接受。
 *
 * 人工打标走 [attach] / [detach] / [deny]，来源恒为 [SOURCE_MANUAL]；规则打标走 [applyRules]，
 * 来源 [SOURCE_RULE]；[SOURCE_MODEL] 留给 P3 的外发分析。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class MediaTagService(
    private val rules: MediaTagRuleRepository,
    private val tags: MediaTagRepository,
    private val bindings: MediaAssetTagRepository,
    private val classpath: Classpath,
    settings: AppSettings,
) {

    private val log = Log.of("Media")
    private val config = settings.files.media

    /** 内嵌默认规则集；解析失败时为空表（只记日志，不阻断扫描）。 */
    private val fallback: List<MediaRule> by lazy { loadMediaRules(classpath) }

    /**
     * 取当前生效的规则集：库表有规则就以库为准，一条都没有才回退内嵌资源
     * （完全照搬 rbac 的两条来源优先级，见 `doc/media-index-design.md` §4.5）。
     */
    suspend fun loadRules(exec: QueryExecutor): MediaRuleSet {
        if (!config.rules.enabled) return MediaRuleSet(emptyList())
        val rows = rules.findAllByDeletedFalse(exec).getOrElse {
            log.warn(it) { "读取规则表失败，回退内嵌默认规则集" }
            return MediaRuleSet(fallback)
        }
        if (rows.isEmpty()) return MediaRuleSet(fallback)
        // 库表的规则挂的是 tagId，翻译成与 yaml 一致的「命名空间:名字」
        val keys = tags.findAllByDeletedFalse(exec).getOrElse { emptyList() }
            .associate { it.id to "${it.namespace}:${it.name}" }
        val mapped = rows.filter { it.enabled }.mapNotNull { row ->
            val tag = row.tagId?.let { keys[it] }
            if (tag == null && row.rating == null) return@mapNotNull null
            MediaRule(row.matchType, row.pattern, tag, row.rating, row.priority)
        }
        return MediaRuleSet(mapped)
    }

    /**
     * 应用一批规则：命中的标签逐个关联，并返回规则给出的评级（`null` = 不改评级）。
     *
     * 只写 [SOURCE_RULE] 的行，人工行一个都不动（§7.3）。
     */
    suspend fun applyRules(
        exec: QueryExecutor,
        ruleSet: MediaRuleSet,
        assetId: Long,
        path: String,
        name: String,
        operator: String?,
    ): String? {
        if (ruleSet.size == 0) return null
        for (key in ruleSet.tagsFor(path, name)) {
            val parts = key.split(':', limit = 2)
            if (parts.size != 2) continue
            attach(exec, assetId, parts[0], parts[1], SOURCE_RULE, operator)
        }
        return ruleSet.ratingFor(path, name)
    }

    /**
     * 某个资产当前**生效**的标签（`namespace:name`）。
     *
     * 供外发分析把「已有的打标」当上下文喂给模型（见 `doc/vision-autotag-design.md`）：不分来源
     * （人工 / 规则 / 模型都算），但**跳过否决行** —— 已否决的标签不该再提示模型去沿用。
     */
    suspend fun labelsOf(exec: QueryExecutor, assetId: Long): List<String> {
        val links = bindings.findAllByAssetId(exec, assetId).getOrNull().orEmpty()
        if (links.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (link in links) {
            if (link.negative) continue
            val tag = tags.findOneById(exec, link.tagId).getOrNull() ?: continue
            out.add("${tag.namespace}:${tag.name}")
        }
        return out.toList()
    }

    /** 找一个标签；不存在则按需创建（根级自由标签 `parentId = null` 是合法状态，§7.5）。 */
    suspend fun ensureTag(
        exec: QueryExecutor,
        namespace: String,
        name: String,
        operator: String?,
    ): Long? {
        tags.findOneByNamespaceAndName(exec, namespace, name).getOrNull()?.let { return it.id }
        val now = Clock.System.now()
        // 并发扫描可能同时建同一个标签，唯一键会拦下来 —— 拦下就回查，不当作错误
        runCatching {
            tags.executeInsert(exec, namespace, name, null, null, null, null, now, operator, now, operator).getOrThrow()
        }.onFailure { log.warn(it) { "创建标签失败（可能是并发重复）：$namespace:$name" } }
        return tags.findOneByNamespaceAndName(exec, namespace, name).getOrNull()?.id
    }

    /**
     * 关联一个标签；同一来源已关联过就跳过（幂等，允许同标签有多来源行）。
     *
     * 同来源的行已存在但被**否决**过时顺手把否决位翻正 —— 否则「先否决、再打上」从界面看像是没生效。
     *
     * [provider] 记「是哪个模型打的」（`media_asset_tag.provider`，供换模型重跑区分），
     * 只对模型来源有意义；人工 / 规则传 `null`。
     */
    suspend fun attach(
        exec: QueryExecutor,
        assetId: Long,
        namespace: String,
        name: String,
        source: String,
        operator: String?,
        provider: String? = null,
    ): Long? {
        val tagId = ensureTag(exec, namespace, name, operator) ?: return null
        val existing = bindings.findAllByAssetIdAndTagId(exec, assetId, tagId).getOrNull().orEmpty()
        val same = existing.firstOrNull { it.source == source }
        val now = Clock.System.now()
        if (same != null) {
            if (same.negative) {
                bindings.executeUpdateNegativeByAssetIdAndTagIdAndSource(
                    exec, assetId, tagId, source, false, now, operator,
                ).getOrThrow()
            }
            return tagId
        }
        bindings.executeInsert(exec, assetId, tagId, source, provider, null, false, now, operator, now, operator)
            .getOrThrow()
        return tagId
    }

    /**
     * 人工解除关联：这个资产上这个标签的**所有来源**一起软删，返回受影响行数。
     *
     * 刻意**不按需建**标签 —— 解除是「拿掉」，不该顺手往词表里塞一行。
     *
     * 与 [deny] 的分工：解除只是把关联摘掉，目录规则下次扫描还会再加回来；要挡住才用否决。
     */
    suspend fun detach(
        exec: QueryExecutor,
        assetId: Long,
        namespace: String,
        name: String,
        operator: String?,
    ): Long {
        val tagId = tags.findOneByNamespaceAndName(exec, namespace, name).getOrNull()?.id ?: return 0L
        return bindings.executeSoftDeleteByAssetIdAndTagId(exec, assetId, tagId, Clock.System.now(), operator)
            .getOrThrow()
    }

    /**
     * 人工否决：这张图**明确不是**这个标签（§7.3）。
     *
     * 没有 `manual` 行就补一条带否决位的，有就翻位 —— 之后规则与模型想打这个标签都得先过这一关。
     */
    suspend fun deny(
        exec: QueryExecutor,
        assetId: Long,
        namespace: String,
        name: String,
        operator: String?,
    ): Long? {
        val tagId = ensureTag(exec, namespace, name, operator) ?: return null
        val now = Clock.System.now()
        val existing = bindings.findAllByAssetIdAndTagId(exec, assetId, tagId).getOrNull().orEmpty()
        if (existing.none { it.source == SOURCE_MANUAL }) {
            bindings.executeInsert(
                exec, assetId, tagId, SOURCE_MANUAL, null, null, true, now, operator, now, operator,
            ).getOrThrow()
            return tagId
        }
        bindings.executeUpdateNegativeByAssetIdAndTagIdAndSource(
            exec, assetId, tagId, SOURCE_MANUAL, true, now, operator,
        ).getOrThrow()
        return tagId
    }

    /**
     * 评级 → 标签投影（§7.2）：列是权威、标签是投影，两者并存只为「按标签检索」能复用。
     *
     * 只有 `explicit` 才有投影；`source` 跟随评级的来源（`manual` / `rule`）。
     */
    suspend fun projectRating(
        exec: QueryExecutor,
        assetId: Long,
        rating: String,
        source: String,
        operator: String?,
    ) {
        if (RatingPolicy.normalize(rating) != RATING_EXPLICIT) return
        attach(exec, assetId, NAMESPACE_META, TAG_EXPLICIT, source, operator)
    }
}
