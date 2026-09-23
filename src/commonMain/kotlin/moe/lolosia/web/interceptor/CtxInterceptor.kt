package moe.lolosia.web.interceptor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository
import moe.lolosia.web.util.session.ApplicationCallContext
import moe.lolosia.web.util.session.ContextKey

/**
 * 第 2 层：为每个请求创建请求上下文并放入 `call.attributes`，控制器形参写 `ctx: Context` 即得。
 *
 * 形参即依赖：生成器会把它们按类型从容器取出来（`koin.get(...)`），所以这里声明容器里的类型
 * （`Database`）而不是驱动类型。
 *
 * @author 洛洛希雅Lolosia
 */
@Interceptor(-5)
public fun Application.installCtxInterceptor(
    db: Database,
    users: SysUserRepository,
    bindings: ViewUserRoleRepository,
) {
    intercept(ApplicationCallPipeline.Call) {
        call.attributes.put(ContextKey, ApplicationCallContext(call, db.driver, users, bindings))
        proceed()
    }
}
