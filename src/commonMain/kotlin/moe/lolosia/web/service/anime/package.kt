package moe.lolosia.web.service.anime

/**
 * 番组表（anime）的散装常量（公共顶层声明，见 AGENTS.md §1.5）。
 *
 * @author 洛洛希雅Lolosia
 */

/** 浏览番组表 / 关注 / 历史。 */
const val ANIME_READ: String = "lolosia.anime.read"

/** 增删自己的关注。 */
const val ANIME_WRITE: String = "lolosia.anime.write"

/** 维护番剧目录 / 手动映射 / 立即拉取。 */
const val ANIME_ADMIN: String = "lolosia.anime.admin"

/** SSE 事件名：命中的发布组出新资源。 */
const val ANIME_RELEASE_EVENT: String = "anime.release"

/** 采集轮询间隔下限（秒）。 */
const val ANIME_MIN_INTERVAL_SECONDS: Int = 60

/** 周期任务失败后的短重试间隔（毫秒）：失败别干等一整个周期。 */
const val ANIME_RETRY_MILLIS: Long = 60_000L

/** 番剧目录「下次刷新时刻」在 `sys_cache` 里的 path。 */
const val ANIME_CATALOG_CACHE_PATH: String = "/anime/catalog"

/** 番剧目录「下次刷新时刻」的 tag（值 = epoch 毫秒）。 */
const val ANIME_CATALOG_NEXT_REFRESH: String = "nextRefreshAt"

/** 番剧目录连续失败次数的 tag（退避重试用）。 */
const val ANIME_CATALOG_FAIL_COUNT: String = "failCount"

/** 取数中继：通知一次（登记任务，立刻返回，不等待出站）。 */
const val ANIME_RELAY_NOTIFY_PATH: String = "/anime/bangumi/notify"

/** 取数中继：取回结果（`pending` / `ready` / `error`）。 */
const val ANIME_RELAY_RESULT_PATH: String = "/anime/bangumi/result"

/** 取数中继：取结果时还没好，多久再问一次（毫秒）。 */
const val ANIME_RELAY_POLL_MILLIS: Long = 15_000L

/**
 * 把完整 URL 拆成 `baseUrl`（到主机）与 `path`（含 query），供出站 [moe.lolosia.web.api.get] 使用。
 *
 * 无 `/` 时 `path` 取 `/`；非绝对 URL 时 `baseUrl` 即原串。
 */
internal fun splitAbsoluteUrl(url: String): Pair<String, String> {
    val schemeEnd = url.indexOf("://")
    val hostStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
    val slash = url.indexOf('/', hostStart)
    return if (slash < 0) url to "/" else url.substring(0, slash) to url.substring(slash)
}
