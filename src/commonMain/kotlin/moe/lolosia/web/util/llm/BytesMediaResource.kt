package moe.lolosia.web.util.llm

/** 内存字节 + MIME，解析为 base64 data URL。 */
class BytesMediaResource(private val bytes: ByteArray, private val mime: String) : MediaResource {

    override suspend fun resolve(): ContentPart = ContentPart.Image(dataUrl(bytes, mime))
}
