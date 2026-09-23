package moe.lolosia.web.util.router

/** multipart / urlencoded 请求里读出的文件项。 */
public class UploadedFile(
    public val fieldName: String,
    public val fileName: String?,
    public val contentType: String?,
    public val bytes: ByteArray,
)
