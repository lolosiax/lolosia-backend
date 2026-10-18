package moe.lolosia.web.util.session

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.ktor.server.application.*
import io.ktor.util.*
import moe.lolosia.web.api.SessionIdProvider
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.service.SessionMap
import moe.lolosia.web.service.permission.Permissions
import moe.lolosia.web.util.router.Injectable

/**
 * 请求上下文：**一次请求一个**，请求结束随 `call` 一起释放。
 *
 * 控制器形参声明 `ctx: Context` 即可按类型自动注入（见 [Injectable]）。它是**逐请求**提供的
 * （`requestScoped = true`）：注册在 Bootstrap 里、由 `call.attributes` 取，不挂在 DI 容器上。
 *
 * 能力分四面（对齐旧 JVM 版的 `Context`，见 `doc/native-migration-plan.md`）：
 * - **请求面** [call]：HTTP 原始上下文。**服务层禁止使用** —— 需要 IP / UA / 请求头的场景由控制器
 *   抽成普通参数传下去（旧版 `LoginService.captcha` 直接读 `ctx.clientAddressString` 是反面教材）；
 * - **用户面** [session] / [userId] / [user]：登录态与当前用户，服务层可自由使用；
 * - **执行面** [db] / [transaction]：语句执行句柄与事务边界，服务层传它去调仓库方法；
 * - **权限面** [hasPermission]：接口级授权，`@Permission` 的落点（模型见 `doc/rbac-design.md`）。
 *
 * [data] 是请求期共享数据区，等价于旧版把 `tenantId` / `examId` 之类挂在 `ctx` 上逐层传的做法。
 *
 * 会话由鉴权拦截器解析后放进 `call.attributes`（键 [SessionKey]），这里的属性都是**同步**读取，
 * 不需要 suspend —— 与旧 JVM 版「懒解析」的差别只是把解析时机提前到了拦截器里。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable(requestScoped = true)
interface Context : SessionIdProvider {
    /** 请求面：HTTP 原始上下文（仅控制器可用，服务层禁止使用）。 */
    val call: ApplicationCall

    /** 请求期共享数据：跨层传临时数据用，请求结束即释放。 */
    val data: Attributes

    /** 用户面：当前会话；未携带有效 `Authorization` 时为 `null`。 */
    val session: SessionMap? get() = call.attributes.getOrNull(SessionKey)

    /** 用户面：会话 id（前端拿它当 token）。 */
    override val sessionId: String? get() = session?.id

    /** 用户面：当前登录用户 id；会话里没写用户时（如匿名会话）为 `null`。 */
    val userId: String? get() = session?.userId

    /** 用户面：取登录用户 id，取不到按「未登录」处理（401）。 */
    fun requireUserId(): String =
        userId ?: throw ApiException(401, "身份认证失败，请重新登录")

    /** 用户面：当前用户；实体与角色在**一次请求内只查一次**（见 [UserInfo]）。 */
    val user: UserInfo

    /** 执行面：语句执行句柄（连接池）。调仓库方法时传它。 */
    val db: QueryExecutor

    /** 执行面：把 [block] 内的语句放进同一事务，抛异常则回滚。 */
    suspend fun <T> transaction(block: suspend (Transaction) -> T): T

    /**
     * 权限面：当前用户是否拥有 [nodes] 中的**任一**权限。
     *
     * `@Permission` 生成的路由校验最终落到这里（见 `RouterProcessor`）。判定口径：合并
     * 「组链（含继承的祖先组）+ 用户直授」的规则 → 命中的规则里**负号优先** → 都没命中即拒绝；
     * 拥有者组（`owner`）直接放行。规则在一次请求内只解析一次（见 [UserInfo.permissionRules]）。
     */
    suspend fun hasPermission(vararg nodes: String): Boolean {
        if (nodes.isEmpty()) return true
        if (user.isOwner()) return true
        val rules = user.permissionRules()
        return nodes.any { Permissions.granted(rules, it) }
    }
}

/** 已解析会话在 `call.attributes` 中的键（由鉴权拦截器写入）。 */
val SessionKey: AttributeKey<SessionMap> = AttributeKey("session")
