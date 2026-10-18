package moe.lolosia.web.util.router

/**
 * 表单字段 + 文件项。
 *
 * [fields] 只保留同名字段的**最后一个**值（单值语义）；同名重复出现的字段（如 OSS 上传的
 * `reference`）在 [multiFields] 里按出现顺序完整保留，供 `List<String>` 形参绑定。
 */
class FormData(
    val fields: Map<String, String>,
    val files: List<UploadedFile>,
    val multiFields: Map<String, List<String>> = emptyMap(),
) {
    fun fieldOrNull(name: String): String? = fields[name]
    fun field(name: String): String = required(fields[name], name)

    /** 同名字段的全部取值（按出现顺序）；没有该字段时返回空列表。 */
    fun fieldValues(name: String): List<String> = multiFields[name] ?: emptyList()

    fun fileOrNull(name: String): UploadedFile? =
        files.firstOrNull { it.fieldName == name } ?: files.firstOrNull()

    /** 指定字段名的全部文件项（按出现顺序）。 */
    fun fileValues(name: String): List<UploadedFile> = files.filter { it.fieldName == name }

    companion object {
        val Empty: FormData = FormData(emptyMap(), emptyList(), emptyMap())
    }
}
