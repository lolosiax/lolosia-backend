package moe.lolosia.web.service.dav

import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * WebDAV 锁管理（内存态、不持久化）。
 *
 * Windows 的 WebDAV 客户端（WebClient）把「可写挂载」当作需要 Class 2（LOCK/UNLOCK）能力，
 * 因此必须应答 LOCK/UNLOCK。这里**只发令牌、记录有效期，不做真正互斥**：单用户私有盘，
 * 并发保护没有实际意义，客户端拿到令牌后就能正常读写。
 *
 * @author 洛洛希雅Lolosia
 */
class DavLockManager {

    private val locks = mutableMapOf<String, Long>()

    /** 新建一把锁，返回 `opaquelocktoken:` 令牌。 */
    fun lock(timeoutSeconds: Long): String {
        prune()
        val token = "opaquelocktoken:${Uuid.random()}"
        locks[token] = expiry(timeoutSeconds)
        return token
    }

    /** 刷新已有锁；令牌不存在返回 `null`（视为新建）。 */
    fun refresh(token: String, timeoutSeconds: Long): String? {
        prune()
        if (!locks.containsKey(token)) return null
        locks[token] = expiry(timeoutSeconds)
        return token
    }

    fun unlock(token: String): Boolean {
        prune()
        return locks.remove(token) != null
    }

    private fun expiry(timeoutSeconds: Long): Long =
        Clock.System.now().toEpochMilliseconds() + timeoutSeconds * 1000

    private fun prune() {
        val now = Clock.System.now().toEpochMilliseconds()
        locks.entries.removeAll { it.value <= now }
    }
}
