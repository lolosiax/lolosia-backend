package moe.lolosia.web.ui.event

/** 一次 HTTP 调用的结果（EventBus 的握手与绑定只需要状态码和文本）。 */
data class HttpResult(val status: Int, val body: String)

/**
 * EventBus 的底层传输：只负责发 HTTP、读行，不关心 SSE 协议。
 *
 * 各平台实现这一层即可接入同一套事件总线逻辑：
 * - Android：`HttpURLConnection`；
 * - Windows / Linux：Ktor 或其他 HTTP 客户端；
 * - 测试：可直接用内存实现。
 *
 * 读流是阻塞/挂起直到连接关闭或 [close]；[onLine] 在传输层线程上逐行回调，
 * 调用方不得在其中做重活或直接碰 UI。
 *
 * @author 洛洛希雅Lolosia
 */
interface EventStreamTransport {

    /** 发一个 JSON POST；非 2xx 也要返回响应体，便于调用方记录服务端错误。 */
    suspend fun postJson(
        baseUrl: String,
        path: String,
        token: String?,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult

    /** 打开 `text/event-stream` 并逐行读取，直到连接关闭、出错或 [close] 被调用。 */
    suspend fun openEventStream(
        baseUrl: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit,
    )

    /** 强制断开进行中的流（让阻塞读立刻返回）。 */
    fun close()
}
