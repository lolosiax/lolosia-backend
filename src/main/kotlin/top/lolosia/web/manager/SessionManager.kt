package top.lolosia.web.manager

import com.fasterxml.jackson.databind.json.JsonMapper
import top.lolosia.web.model.session.SessionEntity
import top.lolosia.web.model.session.query.QSessionEntity
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.bundle.bundleScope
import top.lolosia.web.util.bundle.readBundle
import top.lolosia.web.util.ebean.toUuid
import top.lolosia.web.util.session.Context
import top.lolosia.web.util.session.WebExchangeContext
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.server.ServerWebExchange
import java.util.*

@Service
class SessionManager : DisposableBean, CoroutineScope {
    override val coroutineContext get() = Dispatchers.Default
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)
    private val mSessions = mutableMapOf<UUID, Bundle>()
    private val mDatabase by lazy { SessionEntity.database }

    @Autowired
    private lateinit var mapper: JsonMapper

    fun get() = get(UUID.randomUUID().toString())
    operator fun get(sessionId: String) = get(sessionId.toUuid())
    operator fun get(sessionId: UUID): Bundle {
        var data = mSessions[sessionId] ?: findDatabase(sessionId)
        if (data.isNullOrEmpty()) {
            data = bundleScope {
                "sessionId" set sessionId
            }
            mSessions[sessionId] = data
            SessionEntity().apply {
                this.id = sessionId
                this.data = mapper.writeValueAsString(data)
                this.save()
            }
        }
        data["session:lastAccess"] = Date().time
        return data
    }

    fun remove(sessionId: String) = remove(sessionId.toUuid())
    fun remove(sessionId: UUID) {
        synchronized(mSessions) {
            mSessions.remove(sessionId)
        }
        QSessionEntity().apply {
            id.eq(sessionId)
            delete()
        }
    }

    val size get() = mSessions.size

    fun getUserSessions(userId: UUID) = getUserSessions(userId.toString())
    fun getUserSessions(userId: String): Map<UUID, Bundle> {
        synchronized(mSessions) {
            return mSessions.filterValues { it["userId"] == userId }
        }
    }

    fun mySession(context: Context): Bundle? {
        return if (context is WebExchangeContext) mySession(context.exchange)
        else mySession(context.sessionId)
    }

    fun mySession(exchange: ServerWebExchange): Bundle? = mySession(exchange.request)

    /**
     * 获取本次请求的Session
     */
    fun mySession(req: ServerHttpRequest): Bundle? {
        var auth = req.headers.getFirst(HttpHeaders.AUTHORIZATION) ?: return null
        if (auth.contains(' ')) auth = auth.split(' ', limit = 2)[1]

        val regex = let {
            val x = "[0-9a-f]"
            Regex("^$x{8}(-$x{4}){4}$x{8}\$")
        }

        if (!regex.matches(auth)) return null
        return mySession(auth)
    }

    private fun mySession(sessionId: String) = mySession(sessionId.toUuid())
    private fun mySession(sessionId: UUID): Bundle? {
        return if (sessionId in this) return this[sessionId]
        else null
    }

    operator fun contains(key: String) = contains(key.toUuid())
    operator fun contains(key: UUID): Boolean {
        if (mSessions.containsKey(key)) return true
        return findDatabase(key) != null
    }

    /**
     * 从数据库查找一个session。
     * 若找到，返回它；若找不到或解析失败，返回null
     * @param sessionId SessionID
     */
    private fun findDatabase(sessionId: UUID): Bundle? {
        val entity = QSessionEntity().run {
            id.eq(sessionId)
            deleted.ne(true)
            findOne()
        } ?: return null
        return try {
            val data = mapper.readBundle(entity.data)
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
        val obj = QSessionEntity().run {
            id.eq(sessionId)
            findOne()
        } ?: SessionEntity().apply {
            id = sessionId
        }
        obj.data = mapper.writeValueAsString(session)
        obj.save()
    }

    fun saveAll() {
        if (mSessions.isEmpty()) return
        var saveCounts = 0
        val needRelease = mutableSetOf<UUID>()
        val now = Date().time
        val entities = QSessionEntity().run {
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
        mDatabase.saveAll(entities.values)
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

    private var saveTimerJob: Job? = null

    @Scheduled(fixedDelay = 10000)
    private fun saveTimer() {
        if (saveTimerJob != null) return
        saveTimerJob = launch(Dispatchers.IO) {
            try {
                saveAll()
            } catch (e: Throwable) {
                logger.error("自动保存Session信息时发生错误", e)
            } finally {
                saveTimerJob = null
            }
        }
    }

    override fun destroy() {
        try {
            // 正在保存数据时等待其完成后再保存一遍
            val job = saveTimerJob
            if (job != null) runBlocking {
                job.join()
            }
            // 不在此时操作。
            //saveAll()
        } catch (e: Throwable) {
            logger.error("保存Session信息时发生错误", e)
        }
    }
}