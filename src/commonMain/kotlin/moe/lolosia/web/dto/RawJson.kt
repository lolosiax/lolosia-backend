package moe.lolosia.web.dto

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent

/**
 * 已经拼好的 JSON 文本响应体，声明自己为 `application/json`。
 *
 * 控制器直接返回 `String` 时，内容协商会按 `text/plain` 发出，信封中间件（按 MIME 判定）
 * 因此不会包装；需要输出纯文本形式的 JSON 且仍要信封时，返回本类型即可。
 *
 * @param json 完整的 JSON 文本
 * @author 洛洛希雅Lolosia
 */
public class RawJson(public val json: String) : OutgoingContent.ByteArrayContent() {
    override val contentType: ContentType = ContentType.Application.Json

    override fun bytes(): ByteArray = json.encodeToByteArray()
}
