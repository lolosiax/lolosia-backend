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

/** 取数中继：查询远端已备好的封面清单（`data.{total, ready, ids}`）。 */
const val ANIME_RELAY_COVER_STATUS_PATH: String = "/anime/bangumi/covers/status"

/** 取数中继：按番 id 取封面原图（`GET <此前缀>/<bangumiId>.jpg`，直接返回图片字节）。 */
const val ANIME_RELAY_COVER_PATH: String = "/anime/bangumi/covers"

/** 封面回源落盘目录（`FileStore` 相对路径，根 = `work`）；对应 URL 前缀见 [ANIME_COVER_URL_PREFIX]。 */
const val ANIME_COVER_STORE_DIR: String = "resources/anime/cover"

/** 本地封面经由 `/res/resources` 暴露的 URL 前缀（`anime_series.cover` 落到本地后就长这样）。 */
const val ANIME_COVER_URL_PREFIX: String = "/res/resources/anime/cover/"

/** 该 `cover` 是否已是本地回源路径（而非 Bangumi 原始 URL）。 */
internal fun isLocalAnimeCover(cover: String?): Boolean = cover?.startsWith(ANIME_COVER_URL_PREFIX) == true

/**
 * 封面**缩略图**缓存的根目录（`FileStore`/okio 相对路径，根 = 启动目录）。
 *
 * 大图仍留在 [ANIME_COVER_STORE_DIR]，这里只缓存缩小的 WebP（档位 + 元数据，键 = SHA-1），
 * 复用文件管理器那套 `ThumbnailCache` 机制（见 `service/files/ThumbnailCache.kt`）。
 */
const val ANIME_COVER_CACHE_ROOT: String = "work/.cache/anime/cover"

/** 封面缩略图缓存版本：算法/规格变更时递增，旧缓存整体失效（目录名带版本）。 */
const val ANIME_COVER_CACHE_VERSION: Int = 1

/** 封面缩略图下发路由（公开，同 `/res/resources`：`<img>` 没法带鉴权头）。`?id={bangumiId}&size={档位}`。 */
const val ANIME_COVER_THUMB_ROUTE: String = "/res/anime-cover"

/** 封面回源时，两张图之间的间隔（毫秒）：别把中继/图床打得太急。 */
const val ANIME_COVER_FETCH_DELAY_MILLIS: Long = 1_000L

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
