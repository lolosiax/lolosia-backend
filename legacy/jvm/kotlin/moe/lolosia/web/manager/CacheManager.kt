package moe.lolosia.web.manager

import moe.lolosia.web.model.system.SysCacheEntity
import moe.lolosia.web.model.system.query.QSysCacheEntity
import moe.lolosia.web.annotation.Service
import moe.lolosia.web.util.ebean.createModel
import moe.lolosia.web.util.ebean.database
import moe.lolosia.web.util.ebean.delete
import moe.lolosia.web.util.ebean.query
import moe.lolosia.web.util.ebean.transaction
import java.sql.Timestamp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 缓存管理器。
 * 基于数据库表 sys_cache，按 path 分组、tag 定位。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
@Service
class CacheManager {

    fun getCacheAccessor(path: String) = Accessor(path)

    operator fun get(path: String) = getCacheAccessor(path)

    inner class Accessor(val path: String) : MutableMap<String, String> {

        fun query() = query { }

        /** 查询缓存，默认不查询过期的缓存记录。  */
        inline fun query(block: QSysCacheEntity.() -> Unit): QSysCacheEntity {
            val query = query<QSysCacheEntity>()
            query.path.eq(path)
            query.expiresAt.gt(Timestamp(System.currentTimeMillis()))
            block(query)
            return query
        }

        /** 创建一个缓存，默认的过期时间是 1 天 */
        inline fun createModel(block: SysCacheEntity.() -> Unit): SysCacheEntity {
            val entity = createModel<SysCacheEntity>()
            entity.path = path
            block(entity)
            return entity
        }

        /** 创建一个缓存并存入数据库，默认的过期时间是 1 天 */
        inline fun create(block: SysCacheEntity.() -> Unit): SysCacheEntity {
            val neo = createModel(block)
            val old = query().tag.eq(neo.tag).findOne()
            if (old != null) {
                old.value = neo.value
                old.expiresAt = neo.expiresAt
                old.update()
                return old
            } else {
                neo.insert()
                neo.refresh()
                return neo
            }
        }

        /**
         * 添加一个缓存，默认的过期时间是 1 天
         */
        fun put(tag: String, value: String, expiresAt: Duration): String {
            val entity = create {
                this.tag = tag
                this.value = value
                this.expiresAt = Timestamp(System.currentTimeMillis() + expiresAt.inWholeMilliseconds)
            }
            return entity.value
        }

        override val size: Int
            get() = query().findCount()

        override fun containsKey(key: String): Boolean {
            return query().tag.eq(key).exists()
        }

        override fun containsValue(value: String): Boolean {
            return query().value.eq(value).exists()
        }

        override fun get(key: String): String? {
            return query {
                tag.eq(key)
                createdAt.desc()
            }.findOne()?.value
        }

        override fun isEmpty(): Boolean {
            return query().exists()
        }

        override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
            get() = query {
                createdAt.desc()
                tag.asMapKey()
            }.findMap<String>().mapValues { it.value.value }.toMutableMap().entries

        override val keys: MutableSet<String>
            get() = query {
                setDistinct(true)
                select(tag)
            }.findSingleAttributeSet()

        override val values: MutableCollection<String>
            get() = query { select(value) }.findSingleAttributeSet()

        override fun put(key: String, value: String): String {
            return put(key, value, 1.days)
        }

        override fun putAll(from: Map<out String, String>) {
            val entities = from.map {
                createModel {
                    tag = it.key
                    value = it.value
                    expiresAt = Timestamp(System.currentTimeMillis() + 1.days.inWholeMilliseconds)
                }
            }
            database().transaction {
                database().saveAll(entities, it)
            }
        }

        override fun remove(key: String): String? {
            val out = get(key)
            query().tag.eq(key).delete()
            return out
        }

        override fun clear() {
            database().transaction {
                query().delete(it)
            }
        }
    }
}
