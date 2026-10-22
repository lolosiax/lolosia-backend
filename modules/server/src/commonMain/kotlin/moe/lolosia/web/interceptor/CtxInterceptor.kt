package moe.lolosia.web.interceptor

import io.ktor.server.application.*
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository
import moe.lolosia.web.service.permission.PermissionResolver
import moe.lolosia.web.util.session.ApplicationCallContext
import moe.lolosia.web.util.session.ContextKey

/**
 * 第 2 层：为每个请求创建请求上下文并放入 `call.attributes`，控制器形参写 `ctx: Context` 即得。
 *
 * 形参即依赖：生成器会把它们按类型从容器取出来（`koin.get(...)`），所以这里声明容器里的类型
 * （`Database`）而不是驱动类型。
 *
 * 只把 [Database] 本体交给上下文、**不提前取驱动**：数据库未就绪时由真正读库的处理路径抛 503，
 * 静态资源、SSE 这类不碰库的请求不受影响。
 *
 * @author 洛洛希雅Lolosia
 */
@Interceptor(-5)
fun Application.installCtxInterceptor(
    db: Database,
    users: SysUserRepository,
    bindings: ViewUserRoleRepository,
    resolver: PermissionResolver,
) {
    intercept(ApplicationCallPipeline.Call) {
        call.attributes.put(
            ContextKey,
            ApplicationCallContext(call, db, users, bindings, resolver),
        )
        proceed()
    }
}
