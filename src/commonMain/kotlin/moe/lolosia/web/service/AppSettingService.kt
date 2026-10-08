package moe.lolosia.web.service

import kotlinx.coroutines.flow.MutableStateFlow
import moe.lolosia.web.dto.AppSettingDto
import moe.lolosia.web.dto.WebdavSettingDto
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import kotlin.time.Duration.Companion.days

/**
 * 运行时设置：在设置面板里改、立刻生效、重启后仍在。
 *
 * 值存在 `sys_cache`（键 [PATH]），同时内存里留一份 —— 判定侧是在**每个请求**上读的
 * （WebDAV 每解析一段路径都要看一眼），不能每次都查库。[load] 在数据库连上后灌一次。
 *
 * `application.yaml` 里的同名项只是**初值**：库里没有记录时用它，有记录就以库里的为准。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class AppSettingService(private val settings: AppSettings, private val cache: CacheManager) {

    private val log = Log.of("AppSetting")

    /** WebDAV 是否允许访问隐藏项；初值取配置文件。 */
    private val webdavAllowHidden = MutableStateFlow(settings.webdav.allowHidden)

    /** WebDAV 是否允许客户端访问隐藏文件 / 目录（同步读，供路径判定的热路径用）。 */
    val allowHiddenFiles: Boolean get() = webdavAllowHidden.value

    /** 当前设置快照（接口返回值）。 */
    fun snapshot(): AppSettingDto = AppSettingDto(webdav = WebdavSettingDto(allowHidden = allowHiddenFiles))

    /**
     * 把库里的值读回内存。数据库每次（重）连上都会调一次；读不到就保持配置文件里的初值。
     */
    suspend fun load() {
        val stored = runCatching { cache[PATH].get(KEY_ALLOW_HIDDEN) }
            .onFailure { log.warn(it) { "读取运行时设置失败，先用配置文件里的初值" } }
            .getOrNull() ?: return
        webdavAllowHidden.value = stored.toBoolean()
        log.info { "WebDAV 隐藏项访问：${if (webdavAllowHidden.value) "允许" else "拒绝"}（来自数据库）" }
    }

    /** 改「WebDAV 是否允许访问隐藏文件」：先落库，再改内存态。 */
    suspend fun setAllowHiddenFiles(value: Boolean): AppSettingDto {
        cache[PATH].put(KEY_ALLOW_HIDDEN, value.toString(), TTL)
        webdavAllowHidden.value = value
        log.info { "WebDAV 隐藏项访问改为：${if (value) "允许" else "拒绝"}" }
        return snapshot()
    }

    companion object {
        /** `sys_cache` 里的分组名。 */
        const val PATH: String = "app:setting"

        /** 「允许访问隐藏文件」在 `sys_cache` 里的 tag。 */
        const val KEY_ALLOW_HIDDEN: String = "webdav.allowHidden"

        /** 设置不是缓存，不该过期 —— 给一个远长于服务生命周期的 TTL（`sys_cache` 的每条都要带）。 */
        private val TTL = 3650.days
    }
}
