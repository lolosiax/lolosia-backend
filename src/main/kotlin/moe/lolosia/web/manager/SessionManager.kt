package moe.lolosia.web.manager

import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.http.HttpHeaders
import io.ktor.server.request.ApplicationRequest
import moe.lolosia.web.annotation.Service
import moe.lolosia.web.model.session.SessionEntity
import moe.lolosia.web.model.session.query.QSessionEntity
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.bundle.bundleScope
import moe.lolosia.web.util.ebean.applyDatabase
import moe.lolosia.web.util.ebean.createModel
import moe.lolosia.web.util.ebean.database
import moe.lolosia.web.util.ebean.query
import moe.lolosia.web.util.ebean.toUuid
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.IWebExchangeContext
import moe.lolosia.web.util.session.SessionMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import java.util.*

/**
 * 会话管理器：内存缓存 + 数据库持久化。
 * 每 10 秒自动保存变更并将 5 分钟未访问的会话释放出内存。
 *
 * 注入风格：无参构造 + 字段注入（`by inject()`）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Service
class SessionManager : KoinComponent, CoroutineScope {
    private val mapper: JsonMapper by inject()
    override val coroutineContext get() = Dispatchers.Default
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    private val mSessions = mutableMapOf<UUID, SessionMapImpl>()

    init {
        launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(10000)
                    saveAll()
                } catch (e: Throwable) {
                    logger.error("自动保存Session信息时发生错误", e)
                }
            }
        }
    }

    fun get() = get(UUID.randomUUID().toString())
    operator fun get(sessionId: String) = get(sessionId.toUuid())
    operator fun get(sessionId: UUID): SessionMap {
        var data = mSessions[sessionId] ?: findDatabase(sessionId)
        if (data.isNullOrEmpty()) {
            data = bundleScope {
                "sessionId" set sessionId.toString()
            }.toSession()
            mSessions[sessionId] = data
            createModel<SessionEntity> {
                this.id = sessionId
                this.data = mapper.writeValueAsString(data)
            }.save()
        }
        data["session:lastAccess"] = Date().time
        return data
    }

    fun remove(sessionId: String) = remove(sessionId.toUuid())
    fun remove(sessionId: UUID) {
        synchronized(mSessions) {
            mSessions.remove(sessionId)
        }
        query<QSessionEntity> {
            id.eq(sessionId)
        }.delete()
    }

    val size get() = mSessions.size

    fun getUserSessions(userId: UUID) = getUserSessions(userId.toString())
    fun getUserSessions(userId: String): Map<UUID, SessionMap> {
        synchronized(mSessions) {
            return mSessions.filterValues { it["userId"] == userId }
        }
    }

    fun mySession(context: Context): SessionMap? {
        return if (context is IWebExchangeContext) mySession(context.call.request)
        else mySession(context.sessionId)
    }

    /**
     * 获取本次请求的Session（从 Authorization: Bearer <uuid> 解析）
     */
    fun mySession(req: ApplicationRequest): SessionMap? {
        var auth = req.headers[HttpHeaders.Authorization] ?: return null
        if (auth.contains(' ')) auth = auth.split(' ', limit = 2)[1]

        val regex = let {
            val x = "[0-9a-f]"
            Regex("^$x{8}(-$x{4}){4}$x{8}\$")
        }

        if (!regex.matches(auth)) return null
        return mySession(auth)
    }

    private fun mySession(sessionId: String) = mySession(sessionId.toUuid())
    private fun mySession(sessionId: UUID): SessionMap? {
        return if (sessionId in this) this[sessionId] else null
    }

    operator fun contains(key: String) = contains(key.toUuid())
    operator fun contains(key: UUID): Boolean {
        if (mSessions.containsKey(key)) return true
        return findDatabase(key) != null
    }

    /**
     * 从数据库查找一个session。
     * 若找到，返回它；若找不到或解析失败，返回null
     */
    private fun findDatabase(sessionId: UUID): SessionMapImpl? {
        val entity = query<QSessionEntity> {
            id.eq(sessionId)
            deleted.ne(true)
        }.findOne() ?: return null
        return try {
            val data = SessionMapImpl(mapper.readValue(entity.data, LinkedHashMap::class.java) as MutableMap<String, Any?>)
            synchronized(mSessions) {
                mSessions[sessionId] = data
            }
            data
        } catch (e: Throwable) {
            logger.error("未能读取${sessionId}的Session信息", e)
            entity.delete()
            null
        }
    }

    fun save(sessionId: String) = save(sessionId.toUuid())
    fun save(sessionId: UUID) {
        val session = mSessions[sessionId] ?: return
        val obj = query<QSessionEntity> {
            id.eq(sessionId)
        }.findOne() ?: createModel<SessionEntity> {
            id = sessionId
        }
        obj.applyDatabase()
        obj.data = mapper.writeValueAsString(session)
        obj.save()
    }

    fun saveAll() {
        if (mSessions.isEmpty()) return
        var saveCounts = 0
        val needRelease = mutableSetOf<UUID>()
        val now = Date().time
        val entities = query<QSessionEntity>().run {
            synchronized(mSessions) {
                id.isIn(mSessions.keys)
                findMap<UUID>()
            }
        }
        synchronized(mSessions) {
            entities += (mSessions.keys - entities.keys).associateBy({ it }) {
                SessionEntity().apply {
                    id = it
                }
            }
            mSessions.forEach { (key, entity) ->
                entities[key]!!.data = mapper.writeValueAsString(entity)
                val lastAccess = entity["session:lastAccess"] as? Long ?: Date().time
                if (lastAccess != entity["session:lastManagerAccess"]) {
                    entity["session:lastManagerAccess"] = lastAccess
                    saveCounts++
                }
                // 查询超时的Session信息，超时时间为5分钟
                if (now - lastAccess > 5 * 60_000) needRelease.add(key)
            }
        }
        database().saveAll(entities.values)
        // 仅在有session需要保存时打印日志
        if (saveCounts > 0) {
            logger.info("Auto saving $saveCounts of sessions.")
        }
        // 释放过时的Session信息，减少内存占用
        if (needRelease.isNotEmpty()) {
            synchronized(mSessions) {
                mSessions -= needRelease
            }
            logger.info("There are ${needRelease.size} sessions that have been released.")
        }
    }

    private fun Bundle.toSession(): SessionMapImpl {
        return SessionMapImpl(this)
    }

    inner class SessionMapImpl private constructor(
        val map: MutableMap<String, Any?>, var2: Boolean
    ) : SessionMap, Map<String, Any?> by map {
        constructor() : this(mutableMapOf())
        constructor(map: Map<String, Any?>) : this(map.toMutableMap(), true)

        val id
            get() = map["sessionId"]?.toString()?.toUuid()
                ?: throw NoSuchElementException("No SessionId found!")

        val userId
            get() = map["userId"]?.toString()?.toUuid()
                ?: throw NoSuchElementException("No UserId found!")

        override operator fun set(key: String, value: Any?) {
            map[key] = value
        }

        override fun remove(key: String): Any? {
            return map.remove(key)
        }
    }
}
