package moe.lolosia.web.util.llm

import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * 本地文件，读取字节后编成 base64 data URL。对标 Spring 的 `FileSystemResource`。
 *
 * @param path 绝对路径
 * @param mime 显式 MIME；为 `null` 时按扩展名推断（见 [mimeOf]）
 * @param fileSystem 读取用的文件系统，默认系统文件系统
 */
class FileSystemMediaResource(
    private val path: String,
    private val mime: String? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : MediaResource {

    override suspend fun resolve(): ContentPart {
        val bytes = fileSystem.read(path.toPath()) { readByteArray() }
        return ContentPart.Image(dataUrl(bytes, mime ?: mimeOf(path)))
    }
}
