package moe.lolosia.web.util.llm

/**
 * 大模型对话入口门面（对标 Spring AI 的 `ChatClient`）。
 *
 * 用法：
 * ```
 * chatClient.prompt()
 *     .system("…").user("…").image(bytes, "image/png")
 *     .memory(memory, convId).tools(a, b)
 *     .inspect { onEntry { … }; onExit { … } }
 *     .stream()
 * ```
 */
class ChatClient(val settings: LlmSettings) {

    /** 开始构建一次请求。 */
    fun prompt(): ChatRequestSpec = ChatRequestSpec(settings)

    companion object {
        /** 用配置创建客户端。 */
        fun of(settings: LlmSettings): ChatClient = ChatClient(settings)
    }
}
