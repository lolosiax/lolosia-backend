package moe.lolosia.web.service

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.model.SysCacheEntity
import moe.lolosia.web.database.repository.SysCacheRepository
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 缓存管理器：以 `path` 分组、`tag` 定位，存在 `sys_cache`。
 *
 * - `put(tag, value, ttl)` 按 tag upsert（查到**未过期**的同 tag 记录就更新，否则插一条新记录）；
 * - `get(tag)` 取该 path 下同 tag 的最新未过期值；
 * - `clear()` 清空整个 path 下的缓存。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
@OptIn(ExperimentalAtomicApi::class)
class CacheManager(private val db: Database, private val repo: SysCacheRepository) {

    /** 自进程启动以来的命中 / 未命中计数（供仪表盘展示命中率）。 */
    private val hits = AtomicLong(0L)
    private val misses = AtomicLong(0L)

    /** 缓存访问统计。 */
    data class CacheStats(val hits: Long, val misses: Long, val hitRate: Double)

    /** 取累计命中统计；命中率在无访问时记为 0。 */
    fun stats(): CacheStats {
        val hit = hits.load()
        val miss = misses.load()
        val total = hit + miss
        return CacheStats(hit, miss, if (total > 0L) hit.toDouble() / total * 100.0 else 0.0)
    }

    fun accessor(path: String): Accessor = Accessor(path)

    operator fun get(path: String): Accessor = accessor(path)

    inner class Accessor(val path: String) {

        private val ctx: QueryExecutor get() = db.driver

        /** 该 path 下全部未过期缓存（新的在前）。 */
        suspend fun getAll(): List<SysCacheEntity> = repo.findAllByPath(ctx, path).getOrThrow()

        /** 取该 tag 的未过期缓存行（`null` = 不存在或已过期）；顺带记一次命中 / 未命中。 */
        suspend fun find(tag: String): SysCacheEntity? =
            repo.findOneByPathAndTag(ctx, path, tag).getOrThrow().also {
                if (it != null) hits.addAndFetch(1L) else misses.addAndFetch(1L)
            }

        suspend fun get(tag: String): String? = find(tag)?.value

        /** 写入缓存并返回写入值；[ttl] 默认 1 天。 */
        suspend fun put(tag: String, value: String, ttl: Duration = DEFAULT_TTL): String {
            val expiresAt = Clock.System.now() + ttl
            val existing = repo.findOneByPathAndTag(ctx, path, tag).getOrThrow()
            if (existing != null) {
                repo.executeUpdateValueById(ctx, existing.id, value, expiresAt).getOrThrow()
            } else {
                val now = Clock.System.now()
                repo.executeInsert(ctx, path, tag, value, expiresAt, now, now).getOrThrow()
            }
            return value
        }

        /** 删掉该 tag 的缓存，返回被删掉的值（没有则返回 `null`）。 */
        suspend fun remove(tag: String): String? {
            val old = get(tag)
            repo.executeDeleteByPathAndTag(ctx, path, tag).getOrThrow()
            return old
        }

        /** 清空该 path 下的全部缓存，返回删除条数。 */
        suspend fun clear(): Long = repo.executeDeleteByPath(ctx, path).getOrThrow()

        suspend fun keys(): List<String> = getAll().mapNotNull { it.tag }.distinct()
    }

    companion object {
        val DEFAULT_TTL: Duration = 1.days
    }
}
