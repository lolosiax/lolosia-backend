package moe.lolosia.web.controller

import kotlinx.serialization.Serializable
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.SysRoleRepository
import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository
import moe.lolosia.web.dto.RawJson
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
 * 路由插件与基础设施的验证控制器：覆盖 @Controller / @GET / @POST / 按类型自动注入 /
 * @Path / @Query / @Body / @Form / @FilePart，以及数据层与静态资源的探针接口。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object DemoController {

    @Serializable
    data class EchoReq(val name: String, val times: Int = 1)

    @Serializable
    data class EchoResp(val items: List<String>)

    @Serializable
    data class RouteInfo(val path: String, val id: String, val verbose: Boolean?)

    @Serializable
    data class UploadInfo(val field: String?, val fileName: String?, val size: Int, val note: String?)

    @GET("/ping")
    fun ping(): String = "pong"

    /** 按类型自动注入：`ctx: Context` 没有任何注解，KSP 生成 `call.inject<Context>()`。 */
    @GET("/whoami")
    fun whoami(ctx: Context): String = "uri=${ctx.call.request.local.uri}"

    /** @Path / @Query 的名称可省略，默认取形参名（`id` → `{id}`，`verbose` → `?verbose=`）。 */
    @GET("/user/get/{id}")
    fun getUser(ctx: Context, @Path id: String, @Query verbose: Boolean?): RouteInfo =
        RouteInfo(
            path = ctx.call.request.local.uri,
            id = id,
            verbose = verbose,
        )

    @POST("/echo")
    fun echo(@Body req: EchoReq): EchoResp =
        EchoResp(List(req.times.coerceIn(0, 10)) { "${req.name}#$it" })

    /** 表单字段 + 文件（multipart）。 */
    @POST("/upload")
    fun upload(@Form("note") note: String?, @FilePart("file") file: UploadedFile): UploadInfo =
        UploadInfo(
            field = file.fieldName,
            fileName = file.fileName,
            size = file.bytes.size,
            note = note,
        )

    /** 已是 JSON 文本的响应体：声明 `application/json`，故仍会被信封包成 `{code,msg,data}`（对比 [ping]）。 */
    @GET("/raw")
    fun raw(): RawJson = RawJson("""{"name":"lolosia","times":3}""")

    /** 路径参数用显式名称（与形参名不同）。 */
    @GET("/explicit/{uid}")
    fun explicit(@Path("uid") userId: String): String = "userId=$userId"

    /** 生效配置（口令不外泄）。 */
    @GET("/config")
    fun config(config: AppSettings): String = buildString {
        append("port=").append(config.server.port)
        append(", host=").append(config.server.host)
        append(", logLevel=").append(config.logLevel)
        append(", db=").append(config.datasource.url)
        append(", dbUser=").append(config.datasource.username)
    }

    @GET("/db/ping")
    suspend fun dbPing(db: Database): String = "db=${db.ping()}"

    /** 写一条带过期时间的缓存。 */
    @GET("/cache/put")
    suspend fun cachePut(cache: CacheManager, @Query path: String, @Query tag: String, @Query value: String): String =
        cache[path].put(tag, value)

    /** 取未过期的缓存值，并附上过期时刻。 */
    @GET("/cache/get")
    suspend fun cacheGet(cache: CacheManager, @Query path: String, @Query tag: String): String {
        val row = cache[path].find(tag) ?: return "<null>"
        return "${row.value} (expiresAt=${row.expiresAt})"
    }

    /** 新建会话，返回会话 id；带 `userId` 时一并写入会话。 */
    @GET("/session/new")
    suspend fun sessionNew(sessions: SessionManager, @Query userId: String?): String {
        val session = sessions.create()
        userId?.let { session.set(SessionMap.KEY_USER_ID, it) }
        sessions.save(session)
        return session.id
    }

    /** 按 id 读回会话内容（内存或库）。 */
    @GET("/session/get/{id}")
    suspend fun sessionGet(sessions: SessionManager, @Path id: String): String =
        sessions.find(id)?.toJson() ?: "<null>"

    @GET("/db/roles")
    suspend fun dbRoles(db: Database, roles: SysRoleRepository): String =
        roles.findAllByDeletedFalse(db.driver).getOrThrow().joinToString("\n") { "${it.id} ${it.type} ${it.roleName}" }

    /**
     * 用户列表：
     * - 带 `ids`（逗号分隔）→ 逐个 `findOneById`；
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
