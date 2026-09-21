package moe.lolosia.web.service

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlinx.datetime.LocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import moe.lolosia.web.db.Database
import moe.lolosia.web.db.SysCacheRepository
import moe.lolosia.web.db.model.SysCacheEntity
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.time.nowUtc
import moe.lolosia.web.util.time.plusDuration

/**
 * 缓存管理器：以 `path` 分组、`tag` 定位，存在 `sys_cache`。
 *
 * 语义与旧 JVM 版（Ebean）一致：
 * - `put(tag, value, ttl)` 按 tag upsert（查到**未过期**的同 tag 记录就更新，否则插一条新记录）；
 * - `get(tag)` 取该 path 下同 tag 的最新未过期值；
 * - `clear()` 清空整个 path 下的缓存。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
public class CacheManager(private val db: Database, private val repo: SysCacheRepository) {

    public fun accessor(path: String): Accessor = Accessor(path)

    public operator fun get(path: String): Accessor = accessor(path)

    public inner class Accessor(public val path: String) {

        private val ctx: QueryExecutor get() = db.driver

        /** 该 path 下全部未过期缓存（新的在前）。 */
        public suspend fun getAll(): List<SysCacheEntity> = repo.findAllByPath(ctx, path).getOrThrow()

        public suspend fun get(tag: String): String? =
            repo.findOneByPathAndTag(ctx, path, tag).getOrThrow()?.value

        /** 写入缓存并返回写入值；[ttl] 默认 1 天。 */
        public suspend fun put(tag: String, value: String, ttl: Duration = DEFAULT_TTL): String {
            val expiresAt: LocalDateTime = nowUtc().plusDuration(ttl)
            val existing = repo.findOneByPathAndTag(ctx, path, tag).getOrThrow()
            if (existing != null) {
                repo.executeUpdateValueById(ctx, existing.id, value, expiresAt).getOrThrow()
            } else {
                val now = nowUtc()
                repo.executeInsert(ctx, path, tag, value, expiresAt, now, now).getOrThrow()
            }
            return value
        }

        /** 删掉该 tag 的缓存，返回被删掉的值（没有则返回 `null`）。 */
        public suspend fun remove(tag: String): String? {
            val old = get(tag)
            repo.executeDeleteByPathAndTag(ctx, path, tag).getOrThrow()
            return old
        }

        /** 清空该 path 下的全部缓存，返回删除条数。 */
        public suspend fun clear(): Long = repo.executeDeleteByPath(ctx, path).getOrThrow()

        public suspend fun keys(): List<String> = getAll().mapNotNull { it.tag }.distinct()
    }

    public companion object {
        public val DEFAULT_TTL: Duration = 1.days
    }
}
