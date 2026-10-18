# 原生 ChatClient 设计：多模态 + suspend 工具 + inspect 拦截 + 记忆

> 状态：**P1–P7 已实现**（落 `util/llm/`；与本文的有意偏差见 §10）。
> 规范约定见 [AGENTS.md](../AGENTS.md)；`api/`（对外 HTTP 客户端）与 `interceptor/` 的写法照旧遵循它。
> 对标实现（Spring AI，JVM）在隔壁 `med-agent` 的 `util/llm/`，仅作参照，不照搬其复杂度。

---

## 0. 结论速览

1. 从零实现一个 **OpenAI 兼容** 的 `ChatClient`，API 形状**照抄 Spring AI 的链式调用**：
   `prompt().system(..).user(..).image(..).tools(..).memory(..).inspect { onEntry{}; onExit{} }.stream()`，终点是 `Flow<String>`。
2. **不做反射、不引第三方 AI 依赖**：消息模型、工具 schema、SSE 增量解析、agent 工具循环全部自研，落 `moe.lolosia.web.llm` 顶级包（与 `api/` 同级，见 §7）。
3. **工具是 native 的 `suspend`**：`ChatTool.call(arguments)` 声明为挂起函数；工具调用发生在**我们自己的循环**里，所以无需 Spring 那套「把 ToolCallback 包一层再靠阻塞调度跑协程」的绕路（§5.3）。
4. **`inspect` 拦截器是头等公民**：`onEntry` / `onExit` 的处理器本身就是 `suspend` 的 —— 可以在「工具执行前」做异步校验、改写、取消或抛错，这是 Spring 版本做不到的（§5.3）。
5. **记忆是一个独立接口** `ChatMemory`（`load` / `append`，均 `suspend`）：默认给内存窗口实现，持久化实现由业务侧按 `@Repository` 自建（§5.4）。
6. 多模态走 OpenAI 的 content 数组：文本片 + `image_url`（`data:` base64）片（§5.1）。
7. 传输**复用既有 `api/` 的 Ktor 客户端**（`post<Flow<ByteArray>>`，不做聚合、无 14s 超时），SSE 分帧逻辑提升为 `internal` 复用（§5.6）。
8. 配置进唯一入口 `AppSettings.llm`（§6）；密钥不入库（[AGENTS.md](../AGENTS.md) §9）。

---

## 1. 目标与非目标

### 目标

- 提供与 Spring AI `ChatClient` 等价的调用体验，让从 `med-agent` 迁过来的代码**只改 import / 极少量签名**。
- 输入三要素齐备：**系统提示词**、**用户提示词**（可带图）、**图片/URL 多模态**。
- 可传入**记忆接口**（会话历史），也可传显式 `messages` 逃生口。
- `tools(...)` 接收**可 suspend** 的工具；`@Tool` 注解作为可选的人体工学层。
- `inspect { onEntry { } ; onExit { } }` 对每次工具调用做**可挂起的**前后拦截，支持：读取入参、写回出参、取消（改回一段文本给模型）、抛异常中断。
- 输出统一为 `Flow<String>`（内容增量 token 流）。
- 纯 Kotlin/Native：无 JVM、无反射、无新增外部依赖。

### 非目标（本期不做）

- **不做** streaming 之外的 `call()` 聚合形态（需要时在 `Flow` 上 `.joinToString()` 即可）。
- **不做** 官方 SDK 的可选能力（函数并行调用、结构化输出 JSON mode、embedding、rerank、多模态生成）。
- **不做** provider 专有协议（Anthropic Messages、Gemini……）；只做 OpenAI `/chat/completions` 兼容面。
- **不做** 会话的持久化实现（只定接口）；**不做** 前端 SSE 转发（见 §5.7 末尾）。
- **不做** 自动重试 / 断线续传（沿用 `api/` 的既有能力，不额外加）。

---

## 2. 对标：Spring AI 用法 → 本方案用法

`med-agent` 现有写法（见其 `ProtocolEditManager.kt`）：

```java
var spec = deepseekClient.prompt()
    .system(system)
    .messages(messages)      // 手写历史
    .tools(*toolSet);        // ToolCallback / @Tool 方法
spec = spec.inspect {
    onEntry { event -> /* 校验 toolInput，坏就 event.cancel("…") */ }
};
spec.stream().content().asFlow().collect { token -> broadcast(token) }
```

本方案目标写法：

```kotlin
chatClient.prompt()
    .system(system)
    .user(prompt)
    .image(bytes, "image/png")   // 可选，多模态
    .memory(memory, convId)      // 可选，替代 .messages(手写历史)
    .tools(toolA, toolB)         // ChatTool（suspend）
    .inspect {
        onEntry { e -> /* 现在可以 suspend：异步校验、读库、调外部 */ }
        onExit  { e -> /* 读取工具结果，必要时改写 */ }
    }
    .stream()                    // Flow<String>
```

| Spring AI | 本方案 | 说明 |
| --- | --- | --- |
| `ChatClient.builder(model).build()` | `ChatClient.of(settings, client)` | 只依赖 `AppSettings.llm` + 共享 `HttpClient` |
| `.system(..)` / `.user(..)` | 同名 | 用户消息内部是 parts 列表，支持图文混排 |
| `.media(MimeType, Resource)` | `.image(bytes, mime)` / `.imageUrl(url)` | 统一进 content 数组 |
| `.messages(List<Message>)` | `.messages(..)`（逃生口）/ `.memory(..)` | 记忆接口是推荐路径 |
| `.tools(Object...)` | `.tools(vararg ChatTool)` | 工具是 `suspend` |
| `.inspect { onEntry/onExit }` | 同名，处理器 `suspend` | 原生循环内联，无回调包装 |
| `.stream().content().asFlow()` | `.stream()` | 直接就是 `Flow<String>` |

---

## 3. 总体架构

```
                ┌─────────────── 调用方（service / controller）───────────────┐
                │  chatClient.prompt()...inspect{...}.stream() : Flow<String> │
                └───────────────────────────┬────────────────────────────────┘
                                            │
        ┌───────────────────────────────────▼───────────────────────────────────┐
        │ ChatClient / ChatRequestSpec （链式构建，等价 Spring 的 RequestSpec） │
        └───────┬───────────────────┬───────────────────┬───────────────┬───────┘
                │                   │                   │               │
         ChatMessage 模型      ChatTool 抽象        ChatMemory 接口   ToolInspector
      (system/user/assistant/  (suspend call +      (suspend load/    (onEntry/onExit,
       tool + ContentPart)      JSON schema)          append)          suspend 处理器)
                │                   │                   │               │
        ┌───────▼───────────────────▼───────────────────▼───────────────▼───────┐
        │                         Agent 循环（ChatSession）                       │
        │  load 记忆 → 流式请求一轮 → 有 tool_calls 就执行（过 inspect）→ 回填 → 续              │
        └───────────────────────────────┬────────────────────────────────────────┘
                                        │  逐 token emit
        ┌───────────────────────────────▼────────────────────────────────────────┐
        │ 传输层：复用 api/ 的 HttpClient；解析 SSE(chat.completion.chunk) → delta  │
        └────────────────────────────────────────────────────────────────────────┘
```

分层职责：

- **协议层** `ChatProtocol`：OpenAI 请求/响应 DTO（`@Serializable`），消息 ↔ wire 的双向映射。
- **流层** `ChatStreamParser`：把 `Flow<ByteArray>` 的 SSE 帧切成 `ChatChunk`，并把碎片化的 `tool_calls` 按 `index` 累积成完整调用。
- **会话层** `ChatSession`：跑 agent 工具循环，产出 `Flow<String>`。
- **门面层** `ChatClient` / `ChatRequestSpec`：对外链式 API。

---

## 4. 公开 API

```kotlin
// 顶层门面
class ChatClient(private val settings: LlmSettings, private val http: HttpClient = apiClient) {
    fun prompt(): ChatRequestSpec = ChatRequestSpec(settings, http)
    companion object { fun of(settings: LlmSettings): ChatClient = ChatClient(settings) }
}

class ChatRequestSpec internal constructor(
    private val settings: LlmSettings,
    private val http: HttpClient,
) {
    fun system(text: String): ChatRequestSpec                    // 系统提示词
    fun user(text: String): ChatRequestSpec                      // 用户文本
    fun image(bytes: ByteArray, mime: String): ChatRequestSpec   // 用户图片（base64 data URL）
    fun imageUrl(url: String): ChatRequestSpec                   // 用户图片（外链）
    fun messages(list: List<ChatMessage>): ChatRequestSpec       // 逃生口：显式历史
    fun memory(memory: ChatMemory, conversationId: String): ChatRequestSpec
    fun tools(vararg tools: ChatTool): ChatRequestSpec
    fun toolChoice(choice: ToolChoice): ChatRequestSpec          // auto / none / required / 指定名

    fun inspect(block: ToolInspector.Spec.() -> Unit): ChatRequestSpec

    fun stream(): Flow<String>                                   // 冷流；collect 才发请求
    suspend fun text(): String                                   // stream().joinToString("")
}
```

约定：

- DSL 方法**返回自身**（可变 builder），与 Spring 一致；`stream()` 是终点，可多次 collect（每次都是全新的独立会话）。
- `stream()` 是**冷流**、**单收集者**语义；取消收集即取消底层 HTTP 请求。
- `inspect { }` 必须在 `stream()` 之前调用，绑定到本次请求。

---

## 5. 关键机制

### 5.1 多模态消息模型

```kotlin
@Serializable
sealed interface ChatMessage {
    val role: String
}

// system
@Serializable @SerialName("system")
data class SystemMessage(val text: String) : ChatMessage { override val role get() = "system" }

// user：parts 列表，文本 / 图片混排
@Serializable @SerialName("user")
data class UserMessage(val parts: List<ContentPart>) : ChatMessage { override val role get() = "user" }

// assistant：可能只带文本，可能带 tool_calls（流式回填后）
@Serializable @SerialName("assistant")
data class AssistantMessage(val text: String?, val toolCalls: List<ToolCall> = emptyList()) : ChatMessage {
    override val role get() = "assistant"
}

// tool：工具执行结果
@Serializable @SerialName("tool")
data class ToolResultMessage(val toolCallId: String, val name: String, val content: String) : ChatMessage {
    override val role get() = "tool"
}

@Serializable
sealed interface ContentPart
@Serializable @SerialName("text")
data class TextPart(val text: String) : ContentPart
@Serializable @SerialName("image_url")
data class ImagePart(val url: String) : ContentPart          // data:image/png;base64,xxx 或 http(s)://

data class ToolCall(val id: String, val name: String, val arguments: String)  // arguments 是 JSON 字符串
```

wire 映射要点：

- `SystemMessage` / 纯文本 `UserMessage`（只有一个 `TextPart`）→ `content` 直接给**字符串**（兼容性最好）。
- 含图片的 `UserMessage` → `content` 给**数组**：
  `[{"type":"text","text":"…"},{"type":"image_url","image_url":{"url":"data:image/png;base64,…"}}]`。
- 图片编码用 `kotlin.io.encoding.Base64`（K/N stdlib 自带；如仍标 `@ExperimentalEncodingApi` 就本地 `@OptIn`）。**不做**本地缩放/转码 —— 交给调用方传合适尺寸的字节（避免在板子上做重活）。

### 5.2 工具抽象（native suspend）

基础接口（手写工具与 KSP 生成工具**都落到它**）：

```kotlin
interface ChatTool {
    val name: String
    val description: String
    /** JSON Schema（object），描述入参。 */
    val parameters: JsonObject
    /** 执行工具；arguments 已由协议层反序列化成 JsonObject。 */
    suspend fun call(arguments: JsonObject): String
}
```

`.tools(...)` 接收 `vararg ChatTool`。手写一个工具：

```kotlin
val currentTime = tool(
    name = "get_time",
    description = "获取当前时间",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    },
) { currentLocal().toString() }          // 这里就是 suspend lambda
```

`@Tool` 注解层（**可选、建议二期**）：标在 `object` 的 `suspend` 函数上，由 `router-ksp` 新增一个 `ToolProcessor` 编译期生成 `ChatTool` 包装，schema 从形参类型推导（`String/Int/Long/Double/Boolean/枚举`，用 `@ToolParam(description, required)` 补描述）。

- 生成物沿用仓库既有 KSP 约定（见 [AGENTS.md](../AGENTS.md) §2）：commonMain 的 `generated/GeneratedRegistries.kt` 加 `expect fun generatedTools(): List<ChatTool>`，各目标生成 `actual`，由编译器强制配对。
- 为什么分两期：schema 推导 + KSP 是本方案里唯一有「代码生成复杂度」的部分，先用手写 `ChatTool` 把整条链路跑通，再上注解糖；避免一次性引入过多变量。
- **注意**：[AGENTS.md](../AGENTS.md) §1.4 要求「一个 `.kt` 一个顶层类型」，所以 `@Tool` / `@ToolParam` 各自独立成文件（`router/Annotations.kt` 的集中写法是唯一豁免，不适用于 llm 域）。

### 5.3 `inspect` 拦截：为什么原生不绕

**Spring 版为什么绕**（见 `med-agent` 的 `ToolCallbackInspector.kt` + `MergedToolCallback.java`）：

- 框架持有 `ToolCallback` 列表，要拦截只能**把每个 callback 包一层** `MergedToolCallback`，再靠反射式的 `as DefaultChatClientRequestSpec` 回填 `toolCallbacks`；
- `ToolCallback.call()` 是**同步**方法，而回调里想跑协程（DB/网络）只能靠 `runBlocking` 之类的桥接（其 `callOtherDispatcher` 里那段被注释掉的 `runBlocking` 就是痕迹）。
- 事件总线用的是自定义 `EventHandle`，还要 `javaThrow` 才能把 Kotlin 异常从 Java 层带出去。

**本方案为什么简单**：agent 循环是**我们自己写的**，工具调用点就在循环里，拦截天然是普通函数调用，处理器能直接是 `suspend`：

```kotlin
class ToolInspector {
    private val entry = mutableListOf<suspend (ToolEvent.Entry) -> Unit>()
    private val exit = mutableListOf<suspend (ToolEvent.Exit) -> Unit>()
    fun onEntry(h: suspend (ToolEvent.Entry) -> Unit) { entry += h }
    fun onExit(h: suspend (ToolEvent.Exit) -> Unit) { exit += h }

    suspend fun before(e: ToolEvent.Entry) { entry.forEach { it(e) } }
    suspend fun after(e: ToolEvent.Exit) { exit.forEach { it(e) } }
}
```

循环里的调用点：

```kotlin
suspend fun invoke(tc: ToolCall, tool: ChatTool, inspector: ToolInspector?): String {
    val args = parseArgs(tc.arguments)
    val entry = ToolEvent.Entry(tc, tool, tc.arguments, args)
    inspector?.before(entry)                 // ← 可 suspend：异步校验 / 读库 / 调风控
    entry.throwable?.let { throw it }        // 处理器要求中断
    entry.cancelReason?.let { return it }    // 处理器取消：把这段话当工具结果喂回模型

    val result = try {
        tool.call(args)                      // ← 原生 suspend
    } catch (e: Throwable) {
        val exit = ToolEvent.Exit(tc, tool, tc.arguments, null, e)
        inspector?.after(exit)
        exit.throwable?.let { throw it }
        return "工具执行异常：${e.message}"
    }

    val exit = ToolEvent.Exit(tc, tool, tc.arguments, result, null)
    inspector?.after(exit)                   // ← 可 suspend：落库、改写结果、埋点
    exit.throwable?.let { throw it }
    return exit.result ?: result
}
```

`ToolEvent.Entry` / `Exit` 字段（对齐 Spring 的 `EntryEvent` / `ExitEvent` 语义，但用 Kotlin 数据类）：

| 字段 | 说明 |
| --- | --- |
| `call` | 本次 `ToolCall`（id / name / 原始 arguments 串） |
| `tool` | 命中的 `ChatTool` |
| `arguments` | 已解析的 `JsonObject`（Entry 可**原地改写**，循环用改写后的值调用） |
| `cancel(reason)` | 取消调用，`reason` 作为工具结果返回给模型（模型据此自纠） |
| `throwIt(e)` | 抛异常中断整条流 |
| `result`（仅 Exit） | 工具返回值，可改写 |
| `error`（仅 Exit） | 执行抛出的异常 |

处理器按**注册顺序**串行执行；同一 `Entry` 上「先 `throwIt` 优先于 `cancel`」——与 Spring 版一致。

### 5.4 记忆接口

```kotlin
interface ChatMemory {
    suspend fun load(conversationId: String): List<ChatMessage>
    suspend fun append(conversationId: String, messages: List<ChatMessage>)
}
```

- **系统提示词不进记忆**：每次请求由 `.system(..)` 现给（与 Spring 的 `defaultSystem` 覆盖语义一致），记忆只存 system 之外的轮次。
- 默认提供两个实现：
  - `InMemoryChatMemory(maxMessages)`：`Mutex` 保护的 `Map<String, List<ChatMessage>>`（K/N 上共享可变 Map 必须串行访问，见 `SseService` / `SessionManager` 的同一做法）。
  - `WindowChatMemory(delegate, maxMessages)`：在委托之上做**滑动窗口**，裁旧时保证「`assistant(tool_calls)` 与其配对的若干 `tool` 结果」不被拆散。
- **持久化**由业务侧实现（`@Repository` + sqlx4k，或复用 `sys_*` 风格的表），本方案不建表、不碰 DB。参考 `med-agent` 里那套 `loadMessages` / `persistMessage`，但那属于**业务记忆实现**，不属客户端本体。
- `ChatMemory` 与显式 `.messages(..)` 可共存：最终消息 = `memory.load(id)` + `.messages(..)` 追加 + 本轮 system/user。

### 5.5 Agent 循环与 `Flow<String>`

```kotlin
fun stream(): Flow<String> = channelFlow {
    val history = buildList {
        memory?.let { addAll(it.load(conversationId)) }
        explicitMessages.forEach { add(it) }
        system?.let { add(SystemMessage(it)) }
        add(UserMessage(userParts))
    }
    val pending = history.drop(history.indexOfFirst { it is SystemMessage }.coerceAtLeast(0)) // 本轮需回写记忆的部分

    var round = 0
    while (true) {
        val (content, toolCalls) = streamOnce(history, tools) { token -> send(token) }     // 逐 token 下发
        if (toolCalls.isEmpty()) break
        if (++round > settings.maxToolRounds) break

        history += AssistantMessage(content.ifEmpty { null }, toolCalls)
        for (tc in toolCalls) {
            val tool = tools.firstOrNull { it.name == tc.name }
            val result = if (tool == null) "未知工具：${tc.name}" else invoke(tc, tool, inspector)
            history += ToolResultMessage(tc.id, tc.name, result)
        }
    }
    memory?.append(conversationId, pending)   // 本轮新增轮次回写（在 finally 里更稳，见下）
}
```

要点与不变量：

- **只 emit 内容 token**：`delta.content` 原样 `send`；`delta.tool_calls` 只累积、不下发。因此 `Flow<String>` 与 Spring 的 `stream().content().asFlow()` 语义一致。
- **一次 `streamOnce` 一帧**：一个 HTTP SSE 请求 = 一轮；`finish_reason == "tool_calls"` 说明要执行工具再续，`"stop"` 则结束。
- **回写时机**：推荐在每个轮次结束后 `memory.append(本轮新增)`，并在 `finally` / `onCompletion` 兜底回写，保证取消/异常也不丢已产生的轮次（是否「取消即丢弃」由业务在 `ChatMemory` 实现里定，接口不强制）。
- **并发**：整条流跑在收集者的协程里；工具 `suspend` 天然让出线程。**不**引入全局 scope（与 [AGENTS.md](../AGENTS.md) §2 一致，不碰全局容器/作用域）。
- `maxToolRounds` 兜底防止模型无限调工具（默认 8，可配）。

### 5.6 流式协议解析

请求体（示意）：

```json
{
  "model": "…",
  "messages": [ … ],
  "tools": [{"type":"function","function":{"name":"…","description":"…","parameters":{…}}}],
  "tool_choice": "auto",
  "stream": true
}
```

`streamOnce` 复用 `api/` 的客户端拿**裸字节流**（不聚合、不套 14s 超时）：

```kotlin
val flow: Flow<ByteArray> = post<Flow<ByteArray>>(
    baseUrl = settings.baseUrl,          // 绝对地址，见 §6
    url = "/chat/completions",
    body = requestJson,
    headers = mapOf("Authorization" to "Bearer ${settings.apiKey}"),
    timeout = settings.timeout,          // 见下方说明
)
```

> `bytes()`（`dispatch` 里 `Flow` 分支）本身**不包** `withTimeout`，长流不会被 14s 默认超时掐断 —— 这正是选它的原因。

SSE 分帧：`api/package.kt` 里已有私有的 `toEventSourceFrames()` / `indexOfFrameEnd()`，本方案**将其可见性提升为 `internal`** 后直接复用（唯一一处对既有文件的改动）。

增量解析 `ChatStreamParser`：

- 每帧 `data: {json}`，末帧 `data: [DONE]` 结束；空行分帧，`:` 开头为心跳注释，丢弃。
- `choices[0].delta.content` → 下发。
- `choices[0].delta.tool_calls[]`：按 `index` 聚合（`id` / `function.name` 只在首片出现，`function.arguments` 是**碎片**，需字符串拼接）。一轮结束时组装成 `List<ToolCall>`。
- 非 2xx：读 body 里的 `{"error":{"message":…}}` → 抛 `ChatException`（复用 `ApiException` 或新类型，见 §10）。
- **不做** `stream_options.include_usage`（用量统计非目标）。

### 5.7 与前端 SSE 的关系（边界）

本方案交付物到 `Flow<String>` 为止。若控制器要把它转发给浏览器，走既有的 `SseService` / `KtorEventSource` / `SseContent`（`util/sse/`）：`collect { sse.send("message", JsonPrimitive(it)) }`。**这一步属于业务控制器**，不在客户端职责内，也不改 SSE 基础设施。

---

## 6. 配置

按 [AGENTS.md](../AGENTS.md) §7「配置只有一个入口」，加进 `AppSettings`（`@Serializable`）的一个嵌套类：

```kotlin
@Serializable
data class LlmSettings(
    /** OpenAI 兼容服务的根地址（含 /v1 前缀按各家实际填），如 https://api.example.com/v1 */
    val baseUrl: String = "",
    /** 密钥；留空则不启用。**不入库**（见 AGENTS §9）。 */
    val apiKey: String = "",
    /** 默认模型名。 */
    val model: String = "",
    /** 采样温度，null 表示不发该字段。 */
    val temperature: Double? = null,
    /** 工具循环轮次上限。 */
    val maxToolRounds: Int = 8,
    /** 单次请求的整体超时（秒）。 */
    val timeoutSeconds: Int = 120,
)
```

- 加到 `AppSettings` 顶层字段：`val llm: LlmSettings = LlmSettings()`；如需环境变量覆盖，在 `SettingsLoader` 的 `envOverrides` 里补（照现有字段的写法）。
- `ChatClient.of(settings.llm)` 由调用方在服务里构建（也可经 `@Service` 单例持有，构造注入 `AppSettings`）。
- **密钥日志脱敏**：`api/package.kt` 的请求日志会打印 body（不含 header），但要确保 `Authorization` 头**永远不进日志**（当前 `send()` 不记录 header，符合要求；实现时别新增 header 日志）。

---

## 7. 文件落点

落 **`moe.lolosia.web.util.llm`**（与 `med-agent` 的 `util/llm/` 对齐；本仓库暂未拆包，先放这里）。

> 说明：它同时具备「出网基础设施 + 自有协议 DTO」与「业务工具」两面，按 [AGENTS.md](../AGENTS.md) §1.6 的严格口径
> 更像顶级包（如 `api/`）；但当前先与 `med-agent` 路径保持一致，落 `util/llm/`，将来拆包时整目录平移即可。

```
src/commonMain/kotlin/moe/lolosia/web/llm/
  ChatClient.kt          门面：prompt() → ChatRequestSpec
  ChatRequestSpec.kt     链式构建 + stream()/text()
  ChatSession.kt         agent 工具循环（streamOnce + invoke）
  ChatMessage.kt         sealed 消息模型
  ContentPart.kt         text / image_url 片
  ToolCall.kt            单次工具调用
  ChatTool.kt            工具接口 + tool(name){ } 构建器
  ToolInspector.kt       onEntry/onExit（suspend 处理器）
  ToolEvent.kt           Entry / Exit 事件
  ChatMemory.kt          记忆接口
  InMemoryChatMemory.kt  内存实现
  WindowChatMemory.kt    滑动窗口包装
  ChatProtocol.kt        OpenAI 请求/响应 @Serializable DTO
  ChatStreamParser.kt    SSE 帧 → ChatChunk（tool_calls 按 index 聚合）
  ChatException.kt       协议/HTTP 错误
  LlmSettings.kt         若不想塞进 AppSettings 的嵌套类，可独立；否则随 AppSettings 内部类
  ToolChoice.kt          auto / none / required / 指定名
  package.kt             tool(..) 构建器等散装声明
```

`@Tool` / `@ToolParam`（二期）各自独立文件，另加 `router-ksp` 的 `ToolProcessor` + `GeneratedRegistries.kt` 里的 `expect fun generatedTools()`，以及 `META-INF/services` 里注册 `ToolProcessorProvider`。

一个 `.kt` 一个顶层类型（[AGENTS.md](../AGENTS.md) §1.4）已体现在上表（`ToolInspector.Spec` 等作为嵌套类随宿主）。

---

## 8. 分期实施

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| P1 传输与协议 | `api/package.kt` 提升分帧函数可见性；`ChatProtocol` / `ChatStreamParser` / `ChatException` | 能把 `Flow<ByteArray>` 解析成 `(content, toolCalls)` |
| P2 消息与多模态 | `ChatMessage` / `ContentPart` / wire 双向映射 | 文本 + 图片请求可发 |
| P3 工具 | `ChatTool` + `tool(..)` 构建器 | 手写 suspend 工具可被调用 |
| P4 inspect | `ToolInspector` / `ToolEvent` + 循环内联调用 | `onEntry/onExit` 生效（取消/抛错/改写） |
| P5 记忆 | `ChatMemory` + 两个实现 | 多轮对话可用 |
| P6 门面 | `ChatClient` / `ChatRequestSpec` + `AppSettings.llm` | `prompt()...stream(): Flow<String>` 全通 |
| P7（可选）注解糖 | `@Tool` / `ToolParam` + `ToolProcessor` + `generatedTools()` | 注解即得工具 |
| P8（可选）业务 | DB 版 `ChatMemory`；控制器把流转发到 SSE | 端到端 |

---

## 9. 验证

- 编译自检（[AGENTS.md](../AGENTS.md) §9）：

```powershell
gradlew compileKotlinMingwX64 compileKotlinLinuxArm64 compileKotlinLinuxX64
```

- 冒烟（无自动化测试，靠临时脚本）：本机 `runDebugExecutableMingwX64` 起后端后，用一个临时 `main` / 探针控制器：
  1. 纯文本 `prompt().user("hi").stream()` 能逐 token 收完；
  2. 带图 `image(..)` 请求模型能描述图片；
  3. 注册一个 `suspend` 假工具，验证 `onEntry` 里 `cancel("…")` 能把这段话回给模型、`throwIt(e)` 能中断流；
  4. 带 `InMemoryChatMemory` 连续两轮，第二轮能"记得"第一轮；
  5. 取消收集（`withTimeout` 包住 collect）时底层请求确实中止。
- 真机（板子/云主机）只验一次：`linkReleaseExecutableLinuxArm64` 能链上、单请求跑通即可（无新增 C 依赖，链接风险低）。

---

## 10. 决策记录（原开放问题）

1. **包名**：落 `util/llm/`（与 `med-agent` 对齐；本仓库暂未拆包）。
2. **`@Tool` 注解层**：本期一并实现（非可选）——`@Tool` / `@ToolParam` + `router-ksp` 的 `ToolProcessor` → `generatedTools()`。
   与本文的偏差：`git` 分两轮提交（基础版 → 注解层）；schema 只支持标量参数（String / Int / Long / Double / Float / Boolean / 枚举，含可空）。
3. **多模态粒度**：抽象为 `MediaResource`（对标 Spring 的 `Resource`），内置 `BytesMediaResource` / `UrlMediaResource` / `FileSystemMediaResource` 三个实现；未接媒体库 `quick_id`（需要时再加一个实现即可）。
4. **记忆回写时机**：整轮对话**正常结束**或**报错中断**时回写一次；用户主动取消（`CancellationException`）不回写。
5. **错误类型**：新增 `ChatException : ApiException`（因此把 `ApiException` 放开为 `open`），可走既有 `StatusPages` → 信封。
6. **模型/服务商**：默认 `baseUrl = https://api.deepseek.com`、`model = deepseek-flash`；密钥经 `KTOR_LLM_API_KEY` 注入，不入库。
