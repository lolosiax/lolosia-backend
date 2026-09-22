package moe.lolosia.web.controller

import kotlinx.serialization.Serializable
import moe.lolosia.web.config.AppConfig
import moe.lolosia.web.db.Database
import moe.lolosia.web.db.SysRoleRepository
import moe.lolosia.web.db.SysUserRepository
import moe.lolosia.web.db.ViewUserRoleRepository
import moe.lolosia.web.dto.RoleCreateReq
import moe.lolosia.web.dto.RoleUpdateReq
import moe.lolosia.web.dto.UserCreateReq
import moe.lolosia.web.dto.UserUpdateReq
import moe.lolosia.web.service.CacheManager
import moe.lolosia.web.service.SessionManager
import moe.lolosia.web.service.SessionMap
import moe.lolosia.web.service.system.RoleService
import moe.lolosia.web.service.system.UserRoleService
import moe.lolosia.web.service.system.UserService
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
import moe.lolosia.web.util.time.nowEpochMs

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

    /**
     * 服务层自检（P0-0.6 验证用，**临时**）：跑一遍「建用户 → 查 → 改 → 改密码 → 改绑定 → 删」
     * 与「建角色 → 改 → 删」的全链路，逐步报告成败（正式接口与登录态在 0.8 的拦截器链接入）。
     *
     * 测试数据用 `selftest-<时间戳>` 命名，跑完自行清理；失败步骤保留现场便于排查。
     */
    @GET("/svc/selftest")
    suspend fun serviceSelfTest(
        users: UserService,
        roles: RoleService,
        bindings: UserRoleService,
    ): String {
        val report = mutableListOf<String>()

        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                block()
                report += "✓ $name"
            } catch (t: Throwable) {
                report += "✗ $name → ${t.message ?: t}"
            }
        }

        val userName = "selftest-${nowEpochMs()}"
        var userId = ""
        var studentRoleId = 0L
        var tempRoleId = 0L
        var createdAt = ""
        var updatedAt = ""

        step("角色列表（应含 5 个内置角色）") {
            val list = roles.list()
            check(list.size >= 5) { "角色数 ${list.size} < 5" }
            studentRoleId = list.firstOrNull { it.type == "student" }?.id ?: error("没有 student 角色")
        }
        step("角色分页（keys=管理 → 2 条）") {
            val page = roles.page(1, 5, "管理")
            check(page.total >= 2) { "total=${page.total}" }
        }
        step("建用户（唯一用户名 / 邮箱，密码走 bcrypt）") {
            val row = users.create(
                UserCreateReq(
                    userName = userName,
                    password = "old-pass",
                    realName = "自检用户",
                    email = "$userName@example.com",
                    roleId = studentRoleId,
                )
            )
            userId = row.id
            check(row.roleType == "student") { "roleType=${row.roleType}" }
            check(row.roleName != null) { "roleName 为空" }
            check(row.isUse == true) { "isUse=${row.isUse}" }
        }
        step("重复创建同名 → 报「同名用户已存在」") {
            val t = runCatching {
                users.create(UserCreateReq(userName = userName, email = "$userName@example.com", roleId = studentRoleId))
            }.exceptionOrNull()
            check(t is IllegalStateException) { "期望 IllegalStateException，实际 $t" }
        }
        step("详情（角色 id 回读、无 password 字段）") {
            val info = users.info(userId)
            check(info.roleId == studentRoleId) { "roleId=${info.roleId} 期望 $studentRoleId" }
            check(info.createdAt != null) { "createdAt 为空" }
        }
        step("搜索 + 列表（roleName / roleType / updatedAt 都在）") {
            check(users.searching(userName).any { it.id == userId }) { "搜索不到新用户" }
            val row = users.list(1, 10, userName).list.firstOrNull { it.id == userId } ?: error("列表里没有新用户")
            check(row.roleName != null) { "roleName 为空" }
            check(row.updatedAt != null) { "updatedAt 为空" }
            createdAt = row.createdAt.orEmpty()
            updatedAt = row.updatedAt.orEmpty()
        }
        step("改用户（未给的字段保持原值）") {
            users.update(UserUpdateReq(id = userId, realName = "自检用户改", isUse = false))
            val info = users.info(userId)
            check(info.realName == "自检用户改") { "realName=${info.realName}" }
            check(info.isUse == false) { "isUse=${info.isUse}" }
            check(info.userName == userName) { "userName 被误改：${info.userName}" }
        }
        step("改密码（验旧密码 → bcrypt 往返）") {
            users.changePassword(userId, "old-pass", "new-pass")
            val t = runCatching { users.changePassword(userId, "old-pass", "whatever") }.exceptionOrNull()
            check(t is IllegalStateException) { "旧密码应已失效，实际 $t" }
            users.changePassword(userId, "new-pass", "old-pass") // 换回去，便于重复跑
        }
        step("改绑定（roleId 生效并可改回）") {
            val guest = roles.list().firstOrNull { it.type == "guest" } ?: error("没有 guest 角色")
            users.update(UserUpdateReq(id = userId, roleId = guest.id))
            check(bindings.getByUserId(userId).roleId == guest.id) { "绑定没改" }
            users.update(UserUpdateReq(id = userId, roleId = studentRoleId))
        }
        step("角色 建 → 改 → 删（软删）") {
            val created = roles.create(RoleCreateReq(roleName = "自检角色", type = "selftest"))
            tempRoleId = created.id
            check(created.id > 0L) { "自增 id=${created.id}" }
            roles.update(RoleUpdateReq(id = tempRoleId, roleName = "自检角色改"))
            val row = roles.list().firstOrNull { it.id == tempRoleId } ?: error("改完找不到")
            check(row.roleName == "自检角色改") { "roleName=${row.roleName}" }
            check(row.type == "selftest") { "type 被误改：${row.type}" }
            roles.destroy(tempRoleId)
            check(roles.list().none { it.id == tempRoleId }) { "软删后仍在列表里" }
            tempRoleId = 0L
        }
        step("保护规则（super_admin / 使用中的角色不可删）") {
            val superAdmin = roles.list().firstOrNull { it.type == "super_admin" } ?: error("没有 super_admin")
            val t = runCatching { roles.destroy(superAdmin.id) }.exceptionOrNull()
            check(t is IllegalStateException) { "super_admin 应报错，实际 $t" }
            val t2 = runCatching { roles.destroy(studentRoleId) }.exceptionOrNull()
            check(t2 is IllegalStateException) { "使用中的角色应报错，实际 $t2" }
        }
        step("删用户（软删绑定 + 软删用户）") {
            users.delete(listOf(userId))
            check(runCatching { users.info(userId) }.isFailure) { "删完还能查到用户" }
            check(users.list(1, 10, userName).list.none { it.id == userId }) { "删完还在列表里" }
            userId = ""
        }

        report += when {
            userId.isNotEmpty() || tempRoleId != 0L -> "⚠ 有残留测试数据：userId=$userId, roleId=$tempRoleId"
            else -> "已清理干净（用户 selftest-* 软删、自检角色软删）"
        }
        // 时间口径交叉核对：这里是「展示串」（固定 +08:00），对比 /api/db/ping 的库端 now()（UTC）
        report += "新用户时间：createdAt=$createdAt, updatedAt=$updatedAt"
        return report.joinToString("\n")
    }
}
