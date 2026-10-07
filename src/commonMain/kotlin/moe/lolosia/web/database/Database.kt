package moe.lolosia.web.database

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.mysql.IMySQL
import io.github.smyrgeorge.sqlx4k.mysql.mySQL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 数据访问入口（sqlx4k + MySQL/MariaDB）。
 *
 * sqlx4k 只提供执行原语（`execute` / `fetchAll` / 事务 / `RowMapper`），
 * native 侧底层是 Rust `sqlx` 的 FFI（Maven 产物自带预编译静态库，消费者不需要 Rust 工具链）。
 *
 * **连接池是懒建的**：构造只留配置、不碰网络。真正建池（`mySQL(...)`）会阻塞到连上为止
 * （主机不可达时约 30s 后抛 `PoolTimedOut`），所以它只在 [connect] 里做，并由 [startReconnectLoop]
 * 在后台重试 —— 启动阶段不建池，数据库不可用只记日志，**不会拖垮进程**。
 *
 * 库未就绪时 [driver] 抛 503（[ApiException]），前端据此报错（预期行为）；库恢复后后台重连成功即自动可用。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
class Database(private val config: AppSettings.DatasourceConfig) {

    private val log = Log.of("Database")
    private val mutex = Mutex()

    @Volatile
    private var instance: IMySQL? = null

    @Volatile
    private var lastError: String? = null

    /** 是否已建立连接池。 */
    val connected: Boolean get() = instance != null

    /** 执行句柄；尚未连上时抛 503，由 `StatusPages` 转成带信封的错误响应。 */
    val driver: IMySQL
        get() = instance ?: throw ApiException(503, "数据库暂不可用${lastError?.let { "：$it" } ?: ""}")

    /**
     * 建立连接池（阻塞到连上或超时）。幂等：已就绪直接返回 `true`。
     *
     * 建池前先跑一遍 [MySqlAuthProbe]：sqlx4k 的建池在鉴权失败时会 panic 崩进程，预检把
     * 「主机不可达 / 账号密码或库名有误」这两类**确定性**问题提前挡掉（也就顺带免了那 ~30s 阻塞）；
     * 预检判不了的情形仍交给 sqlx4k 走原流程。
     *
     * 失败只记录原因并返回 `false`，不抛出 —— 让调用方（启动检查 / 后台重连）自己决定节奏。
     */
    suspend fun connect(): Boolean {
        if (instance != null) return true
        return mutex.withLock {
            if (instance != null) return@withLock true

            when (val probe = MySqlAuthProbe.probe(config)) {
                is MySqlAuthProbe.Result.Ok -> Unit

                is MySqlAuthProbe.Result.Unreachable -> {
                    lastError = probe.message
                    log.warn { "数据库不可达（$RECONNECT_INTERVAL 后重试）：${config.url} —— ${probe.message}" }
                    return@withLock false
                }

                is MySqlAuthProbe.Result.Denied -> {
                    lastError = probe.message
                    log.error { "数据库鉴权失败，已跳过建池（$RECONNECT_INTERVAL 后重试）：${config.url} —— ${probe.message}" }
                    return@withLock false
                }

                is MySqlAuthProbe.Result.Inconclusive ->
                    log.warn { "数据库预检无法判定（${probe.message}），交由 sqlx4k 直接建池" }
            }

            runCatching {
                mySQL(
                    url = config.url,
                    username = config.username,
                    password = config.password,
                    options = ConnectionPool.Options(maxConnections = config.maxConnections),
                )
            }.fold(
                onSuccess = {
                    instance = it
                    lastError = null
                    log.info { "数据库已连接：${config.url}" }
                    true
                },
                onFailure = {
                    lastError = it.message ?: it.toString()
                    log.error(it) { "数据库连接失败（$RECONNECT_INTERVAL 后重试）：${config.url}" }
                    false
                },
            )
        }
    }

    /**
     * 后台重连：启动即试一次，失败则每 [interval] 再试，直到连上或作用域取消。
     * 挂在应用作用域上，保证「数据库重启后服务自动恢复」。
     */
    fun startReconnectLoop(scope: CoroutineScope, interval: Duration = RECONNECT_INTERVAL) {
        scope.launch {
            while (isActive && instance == null) {
                if (connect()) break
                delay(interval)
            }
        }
    }

    /** 连通性探测（运维 / 冒烟用）：返回「服务端版本 / 当前库名 / 会话时区 / 库端 now()」。 */
    suspend fun ping(): String {
        val rs = driver
            .fetchAll(
                "select version() as version, database() as db, @@session.time_zone as tz, now() as db_now"
            )
            .getOrThrow()
        val row = rs.rows.firstOrNull() ?: error("数据库没有返回结果")
        return "${row.get("version").asString()} / ${row.get("db").asString()} / " +
            "tz=${row.get("tz").asString()} / now=${row.get("db_now").asString()}"
    }

    private companion object {
        private val RECONNECT_INTERVAL: Duration = 10.seconds
    }
}
