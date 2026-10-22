package moe.lolosia.web.generated

import io.ktor.server.application.*
import io.ktor.server.routing.*
import moe.lolosia.web.dto.PermissionCatalogEntry
import moe.lolosia.web.util.llm.ChatTool
import org.koin.core.Koin
import org.koin.core.module.Module

/**
 * 生成注册表的 `expect` 声明（`actual` 由 router-ksp 生成到各目标源集）。
 *
 * KSP2 在 KMP 下没有 common 编译单元，生成物只能落到各目标源集，common 代码看不到；
 * 故这里手写签名、各目标生成 `actual`，让 common 的启动流程可以直接调用。
 * 二者由**编译器强制配对**：处理器改了签名而这里没跟上（或反之）会直接编译报错，不会静默漂移。
 *
 * 除 [repositoryModules]（引用 sqlx4k 在各目标生成的 `*Impl`）外，其余注册表的函数体只引用
 * common 声明，落在目标源集仅仅受 KSP 输出位置所限。
 *
 * @author 洛洛希雅Lolosia
 */

/** 各 `@Repository` 接口 → sqlx4k 生成实现的 Koin 绑定。 */
expect fun repositoryModules(): List<Module>

/** 各 `@Service` 类型的 Koin 单例绑定（构造器注入）。 */
expect fun serviceModule(): Module

/** 把容器实例接进路由的按类型注入。 */
expect fun Koin.registerInjectables()

/** 批量注册全部注解式控制器的路由。 */
expect fun Routing.generatedRoutes()

/** 全部 `@Permission` 节点的目录（编译期扫描生成，供管理界面做权限选择器）。 */
expect fun generatedPermissions(): List<PermissionCatalogEntry>

/** 按 `@Interceptor(order)` 升序注册全部中间件。 */
expect fun Application.configureInterceptors(koin: Koin)

/** 全部 `@Tool` 方法生成的对话工具表（见 `util/llm`）。 */
expect fun generatedTools(): List<ChatTool>
