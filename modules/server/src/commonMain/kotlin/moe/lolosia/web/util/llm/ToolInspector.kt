package moe.lolosia.web.util.llm

/**
 * 工具调用拦截器：注册的工具前后处理器按注册顺序串行执行。
 *
 * 处理器本身是**挂起函数**，因此可以在工具执行前做异步校验、读库、调外部服务，
 * 也可以在执行后改写结果、落库、埋点 —— 这是原生实现相对 JVM 侧回调包装的便利之处。
 */
class ToolInspector {

    private val entryHandlers = mutableListOf<suspend (ToolEvent.Entry) -> Unit>()
    private val exitHandlers = mutableListOf<suspend (ToolEvent.Exit) -> Unit>()

    /** 注册调用前处理器。 */
    fun onEntry(handler: suspend (ToolEvent.Entry) -> Unit) {
        entryHandlers += handler
    }

    /** 注册调用后处理器。 */
    fun onExit(handler: suspend (ToolEvent.Exit) -> Unit) {
        exitHandlers += handler
    }

    internal suspend fun before(event: ToolEvent.Entry) {
        entryHandlers.forEach { it(event) }
    }

    internal suspend fun after(event: ToolEvent.Exit) {
        exitHandlers.forEach { it(event) }
    }
}
