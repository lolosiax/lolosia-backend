package moe.lolosia.web.service

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.ktor.http.HttpHeaders
import io.ktor.server.request.ApplicationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.SessionRepository
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.time.nowEpochMs

/**
 * 会话管理器：内存缓存 + 定期落库。
 *
 * - 内存里保留活跃会话，每 [AUTO_SAVE_INTERVAL_MS] 把有变更的会话写回 `sys_sessions`；
 * - 超过 [IDLE_RELEASE_MS] 未访问的会话从内存释放（库里仍有，下次访问再读回）；
 * - 会话整份以 JSON 存在 `data` 列，键值见 [SessionMap]。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
public class SessionManager(private val db: Database, private val repo: SessionRepository) {

    private val log = Log.of("Session")
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, SessionMap>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val ctx: QueryExecutor get() = db.driver

    init {
        scope.launch {
            while (isActive) {
                delay(AUTO_SAVE_INTERVAL_MS)
                runCatching { saveAll() }.onFailure { log.error(it) { "自动保存会话失败" } }
            }
        }
    }

    /** 当前内存中的会话数。 */
    public val size: Int get() = sessions.size

    /** 新建一个会话（随机 UUID）。 */
    public suspend fun create(): SessionMap = getOrCreate(Uuid.random().toString())

    /** 取会话：内存 → 库 → 新建。命中都会刷新「最后访问时间」。 */
    public suspend fun getOrCreate(id: String): SessionMap {
        find(id)?.let { return it }
        val created = SessionMap.of(id)
        mutex.withLock { sessions[id] = created }
        save(created)
        return created
    }

    /** 取会话；内存和库里都没有时返回 `null`。 */
    public suspend fun find(id: String): SessionMap? {
        mutex.withLock { sessions[id] }?.let { return it.touch() }
        val entity = repo.findOneById(ctx, id).getOrThrow() ?: return null
        val restored = SessionMap.fromJson(id, entity.data)
        if (restored == null) {
            log.warn { "会话 $id 的数据无法解析，已丢弃" }
            repo.executeDeleteById(ctx, id).getOrThrow()
            return null
        }
        val result = restored.touch()
        mutex.withLock { sessions[id] = result }
        return result
    }

    /** 从 `Authorization: Bearer <uuid>` 解析会话；缺头或格式不对返回 `null`。 */
    public suspend fun mySession(request: ApplicationRequest): SessionMap? {
        val header = request.headers[HttpHeaders.Authorization] ?: return null
        val token = header.substringAfter(' ', "").ifEmpty { header }
        if (!UUID_REGEX.matches(token)) return null
        return find(token)
    }

    /** 删除会话（内存 + 库）。 */
    public suspend fun remove(id: String) {
        mutex.withLock { sessions.remove(id) }
        repo.executeDeleteById(ctx, id).getOrThrow()
    }

    /** 写回单个会话（不存在则插入）。 */
    public suspend fun save(session: SessionMap) {
        val json = session.toJson()
        val now = Clock.System.now()
        val existing = repo.findOneById(ctx, session.id).getOrThrow()
        if (existing == null) {
            repo.executeInsert(ctx, session.id, json, now, now).getOrThrow()
        } else {
            repo.executeUpdateDataById(ctx, session.id, json, now).getOrThrow()
        }
        session.set(KEY_LAST_MANAGER_ACCESS, session.lastAccess)
    }

    /**
     * 把有变更的会话写回库，并释放长时间未访问的内存副本。
     * 由后台协程每 [AUTO_SAVE_INTERVAL_MS] 调一次，可在关服前手动调用。
     */
    public suspend fun saveAll() {
        val snapshot = mutex.withLock { sessions.toMap() }
        if (snapshot.isEmpty()) return

        val now = nowEpochMs()
        val changed = snapshot.values.filter { it.getLong(KEY_LAST_MANAGER_ACCESS) != it.lastAccess }
        changed.forEach { save(it) }
        if (changed.isNotEmpty()) log.info { "自动保存了 ${changed.size} 个会话" }

        val expired = snapshot.values.filter { now - it.lastAccess > IDLE_RELEASE_MS }.map { it.id }
        if (expired.isNotEmpty()) {
            mutex.withLock { expired.forEach { sessions.remove(it) } }
            log.info { "释放了 ${expired.size} 个闲置会话的内存副本" }
        }
    }

    public companion object {
        public const val AUTO_SAVE_INTERVAL_MS: Long = 10_000L
        public const val IDLE_RELEASE_MS: Long = 5 * 60_000L

        private const val KEY_LAST_MANAGER_ACCESS: String = "session:lastManagerAccess"

        private val UUID_REGEX: Regex = run {
            val x = "[0-9a-fA-F]"
            Regex("^$x{8}(-$x{4}){4}$x{8}$")
        }
    }
}
