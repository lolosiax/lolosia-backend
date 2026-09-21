package moe.lolosia.web.controller

import kotlinx.serialization.Serializable
import moe.lolosia.web.config.AppConfig
import moe.lolosia.web.db.Database
import moe.lolosia.web.db.SysRoleRepository
import moe.lolosia.web.db.SysUserRepository
import moe.lolosia.web.db.ViewUserRoleRepository
import moe.lolosia.web.service.CacheManager
import moe.lolosia.web.service.SessionManager
import moe.lolosia.web.service.SessionMap
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.FilePart
import moe.lolosia.web.util.router.Form
import moe.lolosia.web.util.router.GET
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.router.Path
import moe.lolosia.web.util.router.Query
import moe.lolosia.web.util.router.UploadedFile
import moe.lolosia.web.util.session.Context

/**
 * 自研路由插件的验证用控制器：覆盖 @Controller / @GET / @POST /
 * 按类型自动注入 / @Path / @Query / @Body / @Form / @FilePart。
 *
 * 注意：写法与旧 ktorfitx 控制器一致，仅 import 包名不同。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class EchoReq(val name: String, val times: Int = 1)

@Serializable
data class EchoResp(val items: List<String>)

@Serializable
data class RouteInfo(val path: String, val id: String, val verbose: Boolean?)

@Serializable
data class UploadInfo(val field: String?, val fileName: String?, val size: Int, val note: String?)

@Controller("/api")
object DemoController {

    /** 最简单：无参数、返回 String */
    @GET("/ping")
    fun ping(): String = "pong"

    /**
     * 按类型自动注入：`ctx: Context` 没有任何注解，
     * KSP 检测到 Context 标注了 @Injectable 后生成 `call.inject<Context>()`。
     */
    @GET("/whoami")
    fun whoami(ctx: Context): String = "uri=${ctx.call.request.local.uri}"

    /** @Path / @Query 的名称可省略，默认取形参名（`id` → `{id}`，`verbose` → `?verbose=`） */
    @GET("/user/get/{id}")
    fun getUser(ctx: Context, @Path id: String, @Query verbose: Boolean?): RouteInfo =
        RouteInfo(
            path = ctx.call.request.local.uri,
            id = id,
            verbose = verbose,
        )

    /** JSON 请求体 */
    @POST("/echo")
    fun echo(@Body req: EchoReq): EchoResp =
        EchoResp(List(req.times.coerceIn(0, 10)) { "${req.name}#$it" })

    /** 表单字段 + 文件（multipart） */
    @POST("/upload")
    fun upload(@Form("note") note: String?, @FilePart("file") file: UploadedFile): UploadInfo =
        UploadInfo(
            field = file.fieldName,
            fileName = file.fileName,
            size = file.bytes.size,
            note = note,
        )

    /** 路径参数用显式名称（与形参名不同） */
    @GET("/explicit/{uid}")
    fun explicit(@Path("uid") userId: String): String = "userId=$userId"

    /**
     * 生效配置（口令不外泄）：验证「配置文件/环境变量生效」+「Koin → 路由按类型注入」整条链路。
     * 服务实例从 0.6 起按同样方式注入。
     */
    @GET("/config")
    fun config(config: AppConfig): String = buildString {
        append("port=").append(config.server.port)
        append(", host=").append(config.server.host)
        append(", logLevel=").append(config.logLevel)
        append(", db=").append(config.datasource.url)
        append(", dbUser=").append(config.datasource.username)
    }

    /** 数据访问探针（P0-0.6 第一步）：验证 sqlx4k 的 FFI 驱动在 native 上确实能连库执行查询。 */
    @GET("/db/ping")
    suspend fun dbPing(db: Database): String = "db=${db.ping()}"

    /** 缓存读写（P0-0.6 验证）：写一条带过期时间的缓存。 */
    @GET("/cache/put")
    suspend fun cachePut(cache: CacheManager, @Query path: String, @Query tag: String, @Query value: String): String =
        cache[path].put(tag, value)

    /** 缓存读取（P0-0.6 验证）：取未过期的缓存值，并附上过期时刻（`Instant` 按 UTC 打印，便于核对时区）。 */
    @GET("/cache/get")
    suspend fun cacheGet(cache: CacheManager, @Query path: String, @Query tag: String): String {
        val row = cache[path].find(tag) ?: return "<null>"
        return "${row.value} (expiresAt=${row.expiresAt})"
    }

    /** 新建会话（P0-0.6 验证）：返回会话 id；带 `userId` 时一并写入会话。 */
    @GET("/session/new")
    suspend fun sessionNew(sessions: SessionManager, @Query userId: String?): String {
        val session = sessions.create()
        userId?.let { session.set(SessionMap.KEY_USER_ID, it) }
        sessions.save(session)
        return session.id
    }

    /** 取会话（P0-0.6 验证）：按 id 读回会话内容（内存或库）。 */
    @GET("/session/get/{id}")
    suspend fun sessionGet(sessions: SessionManager, @Path id: String): String =
        sessions.find(id)?.toJson() ?: "<null>"

    /** 角色列表（P0-0.6 验证）：`sys_role` 全量（走生成的 RowMapper，含 Instant 解码）。 */
    @GET("/db/roles")
    suspend fun dbRoles(db: Database, roles: SysRoleRepository): String =
        roles.findAllByDeletedFalse(db.driver).getOrThrow().joinToString("\n") { "${it.id} ${it.type} ${it.roleName}" }

    /**
     * 用户列表（P0-0.6 验证）：
     * - 带 `ids`（逗号分隔）→ 逐个 `findOneById`（`@Query` 不支持 `IN (列表)`，见 §2-13）；
     * - 否则走 `view_user_role` 的分页查询（默认每页 5 条）。
     */
    @GET("/db/users")
    suspend fun dbUsers(
        db: Database,
        users: SysUserRepository,
        view: ViewUserRoleRepository,
        @Query ids: String?,
        @Query keys: String?,
        @Query pageNo: Int?,
        @Query pageSize: Int?,
    ): String {
        if (!ids.isNullOrBlank()) {
            val found = ids.split(',').mapNotNull { users.findOneById(db.driver, it.trim()).getOrThrow() }
            return "by-id hit ${found.size}: " + found.joinToString(", ") { "${it.userName}/${it.realName}" }
        }
        val page = pageNo ?: 1
        val size = pageSize ?: 5
        val pattern = "%${keys.orEmpty()}%"
        val total = view.countByKeys(db.driver, pattern).getOrThrow()
        val rows = view.findAllByKeys(db.driver, pattern, size, (page - 1) * size).getOrThrow()
        return "total=$total\n" + rows.joinToString("\n") { "${it.userName} / ${it.realName} / ${it.roleName}(${it.type})" }
    }
}
