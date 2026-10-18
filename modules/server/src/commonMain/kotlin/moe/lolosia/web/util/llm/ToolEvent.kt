package moe.lolosia.web.util.llm

import kotlinx.serialization.json.JsonObject

/**
 * 一次工具调用的拦截事件，由 [ToolInspector] 的处理器接收。
 *
 * [throwable] 非 null 时，循环会把它抛出以中断整条对话流。
 */
sealed class ToolEvent(val call: ToolCall, val tool: ChatTool) {

    /** 处理器要求抛出的异常。 */
    var throwable: Throwable? = null

    /** 请求循环抛出 [e]，中断本次对话。 */
    fun throwIt(e: Throwable) {
        throwable = e
    }

    /** 调用前事件。 */
    class Entry(
        call: ToolCall,
        tool: ChatTool,
        /** 已解析的入参；处理器可原地改写，循环用改写后的值调用工具。 */
        var arguments: JsonObject,
    ) : ToolEvent(call, tool) {

        private var cancelReason: String? = null

        /** 取消调用；[reason] 作为工具结果回给模型（模型可据此自纠）。 */
        fun cancel(reason: String) {
            cancelReason = reason
        }

        /** 非 null 表示已取消，值为回给模型的结果。 */
        val cancelled: String? get() = cancelReason
    }

    /** 调用后事件。 */
    class Exit(
        call: ToolCall,
        tool: ChatTool,
        /** 实际用于调用的入参。 */
        val arguments: JsonObject,
        /** 工具返回值；处理器可改写。 */
        var result: String?,
        /** 工具抛出的异常；正常时为 null。 */
        val error: Throwable?,
    ) : ToolEvent(call, tool) {

        /** 工具是否执行失败。 */
        val failed: Boolean get() = error != null
    }
}
