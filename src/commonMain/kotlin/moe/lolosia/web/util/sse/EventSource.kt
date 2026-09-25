package moe.lolosia.web.util.sse

import kotlinx.serialization.json.JsonElement

/**
 * SSE 事件源：业务侧通过 [send] 推事件，响应管道消费队列并写成 `text/event-stream`。
 *
 * 与「一条 SSE 连接」一一对应：握手拿到 id → 建流时创建 → 客户端断开时 [close]。
 * 业务代码只依赖本接口，不关心帧是怎么写到 socket 上的（见 [SseContent]）。
 *
 * @author 洛洛希雅Lolosia
 */
public interface EventSource {

    /** 连接标识：握手时下发、注册与推送都按它找连接。 */
    public val sseId: String

    /** 连接是否已关闭。 */
    public val isClosed: Boolean

    /**
     * 推一帧事件（队列满或连接已关闭时静默丢弃，不阻塞业务）。
     *
     * @param event SSE 事件名，前端按 `source.addEventListener(event, …)` 监听
     * @param data 事件数据，按 JSON 编码 —— 前端拿到 `e.data` 后会 `JSON.parse`，故必须是合法 JSON
     * @param retry 断线重连间隔（毫秒），即帧里的 `retry:` 字段
     */
    public fun send(event: String = "message", data: JsonElement? = null, retry: Int = DEFAULT_RETRY)

    /** 关闭连接：唤醒 [await] 的等待方，消费方随之收尾。 */
    public fun close()

    /** 挂起直到连接关闭。 */
    public suspend fun await()

    public companion object {
        /** 默认重连间隔，沿用旧版。 */
        public const val DEFAULT_RETRY: Int = 10_000
    }
}
