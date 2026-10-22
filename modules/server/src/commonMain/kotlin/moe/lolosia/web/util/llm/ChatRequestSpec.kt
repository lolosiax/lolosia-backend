package moe.lolosia.web.util.llm

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import moe.lolosia.web.api.*
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.util.log.*
import kotlin.time.Duration.Companion.seconds

private val log: Logger = Log.of("Chat")

/**
 * 一次对话请求的链式构建器（对标 Spring AI 的 `ChatClientRequestSpec`）。
 *
 * 通过 [ChatClient.prompt] 获取；配置系统 / 用户提示词、图片、工具、记忆与拦截器后，
 * 由 [stream] 触发（冷流，收集才开始发请求）。
 */
class ChatRequestSpec internal constructor(
    private val settings: LlmSettings,
) {

    private var systemPrompt: String? = null
    private val userParts = mutableListOf<ContentPart>()
    private val mediaResources = mutableListOf<MediaResource>()
    private val extraMessages = mutableListOf<ChatMessage>()
    private val tools = mutableListOf<ChatTool>()
    private var toolChoice: ToolChoice = ToolChoice.Auto
    private var memory: ChatMemory? = null
    private var conversationId: String = ""
    private var inspector: ToolInspector? = null
    private var usageHandler: (suspend (ChatUsage) -> Unit)? = null

    /** 设置系统提示词。 */
    fun system(text: String): ChatRequestSpec = apply { systemPrompt = text }

    /** 追加一段用户文本。 */
    fun user(text: String): ChatRequestSpec = apply { userParts += ContentPart.Text(text) }

    /** 追加一张用户图片（字节 + MIME）；[detail] 为 `low` / `original` 等。 */
    fun image(bytes: ByteArray, mime: String, detail: String? = null): ChatRequestSpec =
        apply { userParts += ContentPart.Image(dataUrl(bytes, mime), detail) }

    /** 追加一张用户图片（外链或 `data:` URL）；[detail] 为 `low` / `original` 等。 */
    fun imageUrl(url: String, detail: String? = null): ChatRequestSpec =
        apply { userParts += ContentPart.Image(url, detail) }

    /** 追加一个多模态输入源（延迟解析），见 [MediaResource]。 */
    fun media(resource: MediaResource): ChatRequestSpec = apply { mediaResources += resource }

    /** 追加显式历史消息（记忆接口之外的逃生口）。 */
    fun messages(list: List<ChatMessage>): ChatRequestSpec = apply { extraMessages += list }

    /** 注册可被模型调用的工具。 */
    fun tools(vararg tools: ChatTool): ChatRequestSpec = apply { this.tools += tools }

    /** 设置工具选择策略。 */
    fun toolChoice(choice: ToolChoice): ChatRequestSpec = apply { toolChoice = choice }

    /** 绑定会话记忆；对话结束或报错中断时由本请求回写。 */
    fun memory(memory: ChatMemory, conversationId: String): ChatRequestSpec = apply {
        this.memory = memory
        this.conversationId = conversationId
    }

    /** 注册工具调用拦截器。 */
    fun inspect(block: ToolInspector.() -> Unit): ChatRequestSpec = apply {
        val inspector = this.inspector ?: ToolInspector().also { this.inspector = it }
        inspector.block()
    }

    /**
     * 注册 token 用量回调：请求开 `stream_options.include_usage`，末尾帧带回 `usage` 时触发。
     *
     * 用于成本台账（见 `service/media/TokenLedger`）；不改动「流式、不阻塞」的既有形态。
     */
    fun onUsage(block: suspend (ChatUsage) -> Unit): ChatRequestSpec = apply { usageHandler = block }

    /**
     * 流式对话：逐段产出模型文本增量。
     *
     * 冷流、单收集者语义；取消收集即取消底层请求。工具调用轮次不会以文本产出，
     * 只通过 [inspect] 注册的处理器暴露。
     */
    fun stream(): Flow<String> = flow {
        val userMessage = ChatMessage.User(userParts + mediaResources.map { it.resolve() })
        val memory = this@ChatRequestSpec.memory
        val history = buildList {
            if (memory != null) addAll(memory.load(conversationId))
            addAll(extraMessages)
            systemPrompt?.let { add(ChatMessage.System(it)) }
            add(userMessage)
        }
        val recorded = mutableListOf<ChatMessage>(userMessage)
        try {
            converse(history.toMutableList(), recorded, this)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            persist(memory, recorded)
            throw e
        }
        persist(memory, recorded)
    }

    /** 阻塞语义的便捷入口：收集整条流并拼接。 */
    suspend fun text(): String {
        val builder = StringBuilder()
        stream().collect { builder.append(it) }
        return builder.toString()
    }

    private suspend fun persist(memory: ChatMemory?, recorded: List<ChatMessage>) {
        if (memory == null || recorded.isEmpty()) return
        runCatching { memory.append(conversationId, recorded) }
            .onFailure { log.warn(it) { "记忆回写失败：$conversationId" } }
    }

    /** agent 循环：流式请求一轮，有工具调用就执行并续跑。 */
    private suspend fun converse(
        history: MutableList<ChatMessage>,
        recorded: MutableList<ChatMessage>,
        collector: FlowCollector<String>,
    ) {
        var round = 0
        while (true) {
            val (content, toolCalls) = streamOnce(history, collector)
            if (toolCalls.isEmpty()) {
                val assistant = ChatMessage.Assistant(content.takeIf { it.isNotEmpty() })
                history += assistant
                recorded += assistant
                return
            }
            val assistant = ChatMessage.Assistant(content.takeIf { it.isNotEmpty() }, toolCalls)
            history += assistant
            recorded += assistant
            for (call in toolCalls) {
                val result = invokeTool(call)
                val toolResult = ChatMessage.ToolResult(call.id, call.name, result)
                history += toolResult
                recorded += toolResult
            }
            if (++round >= settings.maxToolRounds) {
                log.warn { "工具调用轮次达到上限 ${settings.maxToolRounds}，停止续跑" }
                return
            }
        }
    }

    /** 发一轮流式请求，产出文本增量并返回本轮完整内容 + 工具调用。 */
    private suspend fun streamOnce(
        history: List<ChatMessage>,
        collector: FlowCollector<String>,
    ): Pair<String, List<ToolCall>> {
        val body = ChatProtocol.buildRequest(settings, history, tools, toolChoice)
        val content = StringBuilder()
        val toolCalls = ToolCallAccumulator()
        var usage: ChatUsage? = null
        try {
            withTimeout(settings.timeoutSeconds.seconds) {
                post<Flow<ByteArray>>(
                    baseUrl = settings.baseUrl.trimEnd('/'),
                    url = CHAT_COMPLETIONS_PATH,
                    body = body,
                    headers = mapOf("Authorization" to "Bearer ${settings.apiKey}"),
                    timeout = settings.timeoutSeconds.seconds,
                    noLog = true,
                ).toEventSourceFrames()
                    .mapNotNull { sseDataLine(it) }
                    .takeWhile { it != STREAM_DONE }
                    .collect { data ->
                        val chunk = chatJson.decodeFromString<ChatCompletionChunk>(data)
                        chunk.usage?.let { usage = it }
                        val delta = chunk.choices.firstOrNull()?.delta ?: return@collect
                        delta.content?.takeIf { it.isNotEmpty() }?.let {
                            content.append(it)
                            collector.emit(it)
                        }
                        delta.toolCalls?.forEach { toolCalls.accept(it) }
                    }
            }
        } catch (e: ApiException) {
            throw ChatException(e.status, e.message ?: "请求失败")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ChatException(502, "调用大模型失败：${e.message}")
        }
        usage?.let { usageHandler?.invoke(it) }
        return content.toString() to toolCalls.build()
    }

    /** 执行一次工具调用，after/before 拦截点均在此处内联。 */
    private suspend fun invokeTool(call: ToolCall): String {
        val tool = tools.firstOrNull { it.name == call.name }
            ?: return "未知工具 ${call.name}；可用工具：${tools.joinToString { it.name }}"
        val arguments = runCatching { chatJson.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }
            .getOrElse { return "工具 ${call.name} 参数解析失败：${it.message}" }

        val entry = ToolEvent.Entry(call, tool, arguments)
        inspector?.before(entry)
        entry.throwable?.let { throw it }
        entry.cancelled?.let { return it }

        val exit = try {
            ToolEvent.Exit(call, tool, entry.arguments, tool.call(entry.arguments), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolEvent.Exit(call, tool, entry.arguments, null, e)
        }
        inspector?.after(exit)
        exit.throwable?.let { throw it }
        return exit.result ?: "工具 ${call.name} 执行失败：${exit.error?.message ?: "未知错误"}"
    }
}
