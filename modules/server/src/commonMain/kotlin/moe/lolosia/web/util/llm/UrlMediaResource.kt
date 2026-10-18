package moe.lolosia.web.util.llm

/** 外链图片，解析为原样 URL。 */
class UrlMediaResource(private val url: String) : MediaResource {

    override suspend fun resolve(): ContentPart = ContentPart.Image(url)
}
