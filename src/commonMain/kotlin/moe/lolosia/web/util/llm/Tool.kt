package moe.lolosia.web.util.llm

/**
 * 把 `object` 里的一个方法标注为可被模型调用的工具。
 *
 * `router-ksp` 的 `ToolProcessor` 据此生成 `ChatTool` 包装（见 `generatedTools()`）：
 * 方法名即工具名（可用 [name] 覆盖），参数按类型推导 JSON Schema。
 *
 * 方法可以是 `suspend` 的 —— 生成的包装在协程里直接调用它，无需阻塞调度桥接。
 *
 * @param name 工具名；留空取方法名
 * @param description 工具描述；留空取方法 KDoc 首行
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Tool(val name: String = "", val description: String = "")
