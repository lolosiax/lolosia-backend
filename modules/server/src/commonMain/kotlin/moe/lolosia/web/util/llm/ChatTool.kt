package moe.lolosia.web.util.llm

import kotlinx.serialization.json.JsonObject

/**
 * 可由模型调用的工具。
 *
 * [call] 是挂起函数，工具内部可直接进行网络 / 数据库 / 其他挂起操作，
 * 无需阻塞调度桥接。用 [tool] 构建，或实现本接口。
 */
interface ChatTool {

    /** 工具名，模型据此发起调用。 */
    val name: String

    /** 工具描述，供模型判断何时使用。 */
    val description: String

    /** 入参的 JSON Schema（一个 object）。 */
    val parameters: JsonObject

    /** 执行工具；[arguments] 已由协议层反序列化。 */
    suspend fun call(arguments: JsonObject): String
}
