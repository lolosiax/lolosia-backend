package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 本地服务的协议帧：请求、响应、事件共用一种形状。
 *
 * - 请求：`id` + `method` + `params`
 * - 响应：`id` + `result` 或 `error`
 * - 事件：`event` + `data`，没有 `id`
 *
 * 两种传输（进程内 / loopback WebSocket）都收发这个结构，行为因此保持一致。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class LocalFrame(
    /** 请求 / 响应关联 id；事件为 `null`。 */
    val id: Long? = null,
    /** 请求方法名，点分小写，如 `metrics.get`。 */
    val method: String? = null,
    /** 请求参数；无参数时为 `null`。 */
    val params: kotlinx.serialization.json.JsonObject? = null,
    /** 成功响应体。 */
    val result: kotlinx.serialization.json.JsonElement? = null,
    /** 失败响应体。 */
    val error: LocalError? = null,
    /** 事件名，如 `job.progress`。 */
    val event: String? = null,
    /** 事件数据。 */
    val data: kotlinx.serialization.json.JsonElement? = null,
)
