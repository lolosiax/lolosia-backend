package moe.lolosia.web.controller

import cn.ktorfitx.server.annotation.Attribute
import cn.ktorfitx.server.annotation.Body
import cn.ktorfitx.server.annotation.Controller
import cn.ktorfitx.server.annotation.POST
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import moe.lolosia.web.dto.response.toJsonObject
import moe.lolosia.web.service.EventBusSSEService
import moe.lolosia.web.util.bundle.bundleScope
import moe.lolosia.web.util.ebean.toUuid
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.SSEContext
import moe.lolosia.web.util.sse.EventSource
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 事件总线 SSE 控制器。
 * - POST /api/sse（x-upgrade: event-source）→ 中间件返回会话ID，此路由在第二步 eventSourceId 连接时执行
 * - POST /api/sse/registry → 登录后将 SSE 连接绑定到用户
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
@Controller("/api")
object EventBusSSEController : KoinComponent {

    private val service: EventBusSSEService by inject()

    @POST("/sse")
    suspend fun connect(
        @Attribute("ctx") ctx: Context,
        @Attribute("sse") source: EventSource,
    ): JsonObject {
        val ctx1 = SSEContext.of(ctx)
        // 业务协程注册连接并等待关闭；HTTP 响应由中间件替换为 SSE 流
        CoroutineScope(Dispatchers.Default).launch {
            service.connect(ctx1, source)
        }
        return bundleScope {
            "code" set 200
        }.toJsonObject()
    }

    @Serializable
    data class RegisterUserFn(val sseId: String)

    @POST("/sse/registry")
    suspend fun registerUser(@Attribute("ctx") ctx: Context, @Body params: RegisterUserFn): JsonObject =
        service.registerUser(ctx, params.sseId.toUuid()).toJsonObject()
}
