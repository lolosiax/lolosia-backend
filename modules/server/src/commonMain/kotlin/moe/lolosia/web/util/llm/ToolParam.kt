package moe.lolosia.web.util.llm

/**
 * 描述 [Tool] 方法的参数。
 *
 * @param description 参数描述，进 JSON Schema
 * @param required 是否必填（默认是）；非必填时缺失用零值兜底，可空类型则传 `null`
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class ToolParam(val description: String = "", val required: Boolean = true)
