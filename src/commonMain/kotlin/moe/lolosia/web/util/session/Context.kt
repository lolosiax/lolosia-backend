package moe.lolosia.web.util.session

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.service.SessionMap
import moe.lolosia.web.util.router.Injectable

/**
 * 请求上下文：**一次请求一个**，请求结束随 `call` 一起释放。
 *
 * 控制器形参声明 `ctx: Context` 即可按类型自动注入（见 [Injectable]）。它是**逐请求**提供的
 * （`requestScoped = true`）：注册在 Bootstrap 里、由 `call.attributes` 取，不挂在 DI 容器上。
 *
 * 能力分三面（对齐旧 JVM 版的 `Context`，见 `doc/native-migration-plan.md`）：
 * - **请求面** [call]：HTTP 原始上下文。**服务层禁止使用** —— 需要 IP / UA / 请求头的场景由控制器
 *   抽成普通参数传下去（旧版 `LoginService.captcha` 直接读 `ctx.clientAddressString` 是反面教材）；
 * - **用户面** [session] / [userId] / [user]：登录态与当前用户，服务层可自由使用；
 * - **执行面** [db] / [transaction]：语句执行句柄与事务边界，服务层传它去调仓库方法。
 *
 * [data] 是请求期共享数据区，等价于旧版把 `tenantId` / `examId` 之类挂在 `ctx` 上逐层传的做法。
 *
 * 会话由鉴权拦截器解析后放进 `call.attributes`（键 [SessionKey]），这里的属性都是**同步**读取，
 * 不需要 suspend —— 与旧 JVM 版「懒解析」的差别只是把解析时机提前到了拦截器里。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable(requestScoped = true)
public interface Context {
    /** 请求面：HTTP 原始上下文（仅控制器可用，服务层禁止使用）。 */
    public val call: ApplicationCall

    /** 请求期共享数据：跨层传临时数据用，请求结束即释放。 */
    public val data: Attributes

    /** 用户面：当前会话；未携带有效 `Authorization` 时为 `null`。 */
    public val session: SessionMap? get() = call.attributes.getOrNull(SessionKey)

    /** 用户面：会话 id（前端拿它当 token）。 */
    public val sessionId: String? get() = session?.id

    /** 用户面：当前登录用户 id；会话里没写用户时（如匿名的 `/api/session/new`）为 `null`。 */
    public val userId: String? get() = session?.userId

    /** 用户面：取登录用户 id，取不到按「未登录」处理（401）。 */
    public fun requireUserId(): String =
        userId ?: throw ApiException(401, "身份认证失败，请重新登录")

    /** 用户面：当前用户；实体与角色在**一次请求内只查一次**（见 [UserInfo]）。 */
    public val user: UserInfo

    /** 执行面：语句执行句柄（连接池）。调仓库方法时传它。 */
    public val db: QueryExecutor

    /** 执行面：把 [block] 内的语句放进同一事务，抛异常则回滚。 */
    public suspend fun <T> transaction(block: suspend (Transaction) -> T): T
}

/** 已解析会话在 `call.attributes` 中的键（由鉴权拦截器写入）。 */
public val SessionKey: AttributeKey<SessionMap> = AttributeKey("session")
