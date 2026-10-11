package moe.lolosia.web.util.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 用户消息里的一段内容，对应 OpenAI content 数组的元素。 */
@Serializable
sealed interface ContentPart {

    /** 纯文本。 */
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart

    /** 图片；[url] 为 http(s) 链接或 `data:<mime>;base64,…`。 */
    @Serializable
    @SerialName("image_url")
    data class Image(val url: String) : ContentPart
}
