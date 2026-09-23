package moe.lolosia.web.util.session

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.mysql.IMySQL
import io.ktor.server.application.ApplicationCall
import io.ktor.util.Attributes
import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository

/**
 * [Context] 的唯一实现：把一次请求要用到的东西挂在一起。
 *
 * 由 `installCtxInterceptor` 逐请求构造（形参由容器注入），所以这里能拿到单例的驱动与仓库。
 *
 * @author 洛洛希雅Lolosia
 */
internal class ApplicationCallContext(
    override val call: ApplicationCall,
    private val driver: IMySQL,
    users: SysUserRepository,
    bindings: ViewUserRoleRepository,
) : Context {

    /** 请求级共享数据不跨线程（一次请求只有一个协程写它），故不开并发保护。 */
    override val data: Attributes = Attributes(concurrent = false)

    override val user: UserInfo = UserInfo(this, users, bindings)

    override val db: QueryExecutor get() = driver

    override suspend fun <T> transaction(block: suspend (Transaction) -> T): T = driver.transaction(block)
}
