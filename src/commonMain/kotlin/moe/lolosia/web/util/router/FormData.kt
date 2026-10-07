package moe.lolosia.web.util.router

/** 表单字段 + 文件项。 */
class FormData(
    val fields: Map<String, String>,
    val files: List<UploadedFile>,
) {
    fun fieldOrNull(name: String): String? = fields[name]
    fun field(name: String): String = required(fields[name], name)
    fun fileOrNull(name: String): UploadedFile? =
        files.firstOrNull { it.fieldName == name } ?: files.firstOrNull()

    companion object {
        val Empty: FormData = FormData(emptyMap(), emptyList())
    }
}
