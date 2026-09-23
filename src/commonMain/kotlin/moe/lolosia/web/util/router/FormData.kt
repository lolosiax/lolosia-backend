package moe.lolosia.web.util.router

/** 表单字段 + 文件项。 */
public class FormData(
    public val fields: Map<String, String>,
    public val files: List<UploadedFile>,
) {
    public fun fieldOrNull(name: String): String? = fields[name]
    public fun field(name: String): String = required(fields[name], name)
    public fun fileOrNull(name: String): UploadedFile? =
        files.firstOrNull { it.fieldName == name } ?: files.firstOrNull()

    public companion object {
        public val Empty: FormData = FormData(emptyMap(), emptyList())
    }
}
