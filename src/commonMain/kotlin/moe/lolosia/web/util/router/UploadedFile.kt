package moe.lolosia.web.util.router

/** multipart / urlencoded 请求里读出的文件项。 */
class UploadedFile(
    val fieldName: String,
    val fileName: String?,
    val contentType: String?,
    val bytes: ByteArray,
)
