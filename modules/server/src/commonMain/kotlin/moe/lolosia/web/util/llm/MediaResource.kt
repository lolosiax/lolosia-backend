package moe.lolosia.web.util.llm

/**
 * 多模态输入源。对标 Spring 的 `Resource`：把「文本之外的输入」（图片等）抽象成
 * 一个可延迟解析的对象，发请求时才 [resolve] 成 [ContentPart]。
 */
interface MediaResource {

    /** 解析成本次请求可用的内容片段。 */
    suspend fun resolve(): ContentPart
}
