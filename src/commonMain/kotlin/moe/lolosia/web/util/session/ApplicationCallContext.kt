package moe.lolosia.web.util.session

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.ktor.server.application.*
import io.ktor.util.*
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository

/**
 * [Context] 的唯一实现：把一次请求要用到的东西挂在一起。
 *
 * 由 `installCtxInterceptor` 逐请求构造（形参由容器注入），所以这里能拿到单例的数据库入口与仓库。
 *
 * 持的是 [Database] 本体（而不是驱动）：驱动**按需取**，数据库不可用时抛 503 只影响真正读库的
 * 处理路径，静态资源 / SSE 等不碰库的请求照常服务。
 *
 * @author 洛洛希雅Lolosia
 */
internal class ApplicationCallContext(
    override val call: ApplicationCall,
    private val database: Database,
    users: SysUserRepository,
    bindings: ViewUserRoleRepository,
) : Context {

    /** 请求级共享数据不跨线程（一次请求只有一个协程写它），故不开并发保护。 */
    override val data: Attributes = Attributes(concurrent = false)

    override val user: UserInfo = UserInfo(this, users, bindings)

    override val db: QueryExecutor get() = database.driver

    override suspend fun <T> transaction(block: suspend (Transaction) -> T): T = database.driver.transaction(block)
}
