package moe.lolosia.web.util.llm

/** 工具选择策略。 */
sealed interface ToolChoice {

    /** 由模型自行决定（默认）。 */
    data object Auto : ToolChoice

    /** 禁止调用工具。 */
    data object None : ToolChoice

    /** 强制调用工具。 */
    data object Required : ToolChoice

    /** 强制调用指定名字的工具。 */
    data class Named(val name: String) : ToolChoice
}
