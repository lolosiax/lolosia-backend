package moe.lolosia.web.controller

import kotlinx.serialization.Serializable
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.dto.RawJson
import moe.lolosia.web.service.SseService
import moe.lolosia.web.util.router.*
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.sse.SSE_ID_PARAM
import moe.lolosia.web.util.sse.SseSourceKey

/**
 * SSE 事件总线：建流与登录绑定（对应前端 `src/api/sse.ts`）。
 *
 * 三步走：
 * - `POST /api/sse`（头 `x-upgrade: event-source`）→ 由 `SseInterceptor` 直接应答标识，**不到这里**；
 * - `GET /api/sse?eventSourceId=<id>` → 本控制器建连接，响应体被换成 `text/event-stream` 流；
 * - `POST /api/sse/registry` → 登录后把连接绑到用户（前端在登录页也会调；那里没有登录态，按空操作处理）。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object SseController {

    @Serializable
    data class RegistryReq(val sseId: String)

    /** 建流：把事件源放进 `call.attributes` 后立即返回，响应体由发送管道换成流（见 [SseInterceptor]）。 */
    @GET("/sse")
    suspend fun connect(ctx: Context, @Query eventSourceId: String?, sse: SseService): RawJson {
        val id = eventSourceId ?: throw ApiException(400, "缺少查询参数 $SSE_ID_PARAM")
        ctx.call.attributes.put(SseSourceKey, sse.open(id))
        return RawJson("""{"code":200}""")
    }

    /** 登录后补绑登录态；未登录（登录页）时什么都没绑，但同样返回成功。 */
    @POST("/sse/registry")
    suspend fun registry(ctx: Context, sse: SseService, @Body req: RegistryReq): MessageResult {
        ctx.userId?.let { sse.bind(req.sseId, it) }
        return MessageResult("success")
    }
}
