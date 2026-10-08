package moe.lolosia.web.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.sse.EventSource
import moe.lolosia.web.util.sse.KtorEventSource
import kotlin.uuid.Uuid

/**
 * SSE 连接注册表 + 连接生命周期。
 *
 * 一条连接的三步：
 * 1. `POST /api/sse`（带头 `x-upgrade`）→ [createId] 分配标识，交给前端；
 * 2. `GET /api/sse?eventSourceId=<id>` → [open] 建流、注册、发首帧，并后台等待关闭后注销；
 * 3. `POST /api/sse/registry` → [bind] 把连接绑到登录用户（浏览器原生 `EventSource`
 *    不能带自定义头，所以建流请求永远是匿名的，登录态只能事后补绑）。
 *
 * 注册表用 [Mutex] 保护：请求分散在多个协程里，共享可变 Map 在 Kotlin/Native 上必须串行访问
 * （与 [SessionManager] 同一套做法）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class SseService {

    private val log = Log.of("Sse")
    private val mutex = Mutex()

    /** 活跃连接：id → 事件源。 */
    private val sources = mutableMapOf<String, EventSource>()

    /** 已绑定的连接：id → 用户 id（未登录或未注册的连接不在表里）。 */
    private val owners = mutableMapOf<String, String>()

    /** 连接生命周期协程（与请求协程解耦：请求返回后连接仍要活着）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 分配一个新的连接标识。 */
    fun createId(): String = Uuid.random().toString()

    /**
     * 建立连接：注册到表里、推一帧 `status` 首帧，并在后台等它关闭后注销。
     *
     * 立即返回事件源，供调用方放进 `call.attributes`（见 `SseSourceKey`）交给发送管道。
     */
    fun open(id: String): KtorEventSource {
        val source = KtorEventSource(id)
        scope.launch {
            mutex.withLock { sources[id] = source }
            log.info { "SSE 连接已建立：$id" }
            source.send("status", JsonPrimitive("connect successful"))
            source.await()
            mutex.withLock {
                sources.remove(id)
                owners.remove(id)
            }
            log.info { "SSE 连接已关闭：$id" }
        }
        return source
    }

    /**
     * 把已建立的连接绑到用户：登录后前端调 `/sse/registry` 补登录态。
     *
     * 标识对不上（连接已断开）时静默忽略 —— 前端在登录页也会调它，那里本来就没有登录态。
     */
    suspend fun bind(id: String, userId: String) {
        mutex.withLock { if (sources.containsKey(id)) owners[id] = userId }
    }

    /**
     * 向**通过 [allow] 的连接**广播一个事件。
     *
     * [allow] 收到的是连接绑定的用户 id；**未绑定用户的连接（登录页那种匿名连接）一律不推**。
     * 同一个用户只判定一次，判定抛异常按「不放行」处理 —— 推送出问题不该影响业务协程。
     *
     * 先在锁内取快照、再到锁外逐条 [EventSource.send]：`send` 内部是 `trySend`，队列满或连接已
     * 关闭时静默丢弃，不会反压业务协程，所以持锁发送只是徒增竞争。
     */
    suspend fun broadcast(event: String, data: JsonElement? = null, allow: suspend (userId: String) -> Boolean) {
        val targets = mutex.withLock {
            sources.entries.mapNotNull { (id, source) -> owners[id]?.let { it to source } }
        }
        val decided = mutableMapOf<String, Boolean>()
        for ((userId, source) in targets) {
            val allowed = decided[userId] ?: runCatching { allow(userId) }.getOrDefault(false)
            decided[userId] = allowed
            if (allowed) source.send(event, data)
        }
    }
}
