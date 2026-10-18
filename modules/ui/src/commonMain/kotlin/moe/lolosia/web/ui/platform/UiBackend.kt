package moe.lolosia.web.ui.platform

/**
 * 视图层后端：窗口、文档、数据模型、动作与帧循环。
 *
 * 实现只做胶水，不持有业务状态；Kotlin 页面逻辑通过本接口驱动 RmlUi。
 *
 * 模型字段必须在 [loadDocument] 之前绑定；切换文档时字段会保留。
 *
 * @author 洛洛希雅Lolosia
 */
interface UiBackend {

    fun create(title: String, width: Int, height: Int): Boolean

    fun loadFont(filePath: String, family: String): Boolean

    /** 从 classpath 资源加载 RML 文档。 */
    fun loadDocument(resourcePath: String): Boolean

    /** 直接加载 RML 文本（动态列表等生成页面）。 */
    fun loadDocumentText(rml: String): Boolean

    fun bindString(name: String, value: String): Boolean

    fun bindInt(name: String, value: Int): Boolean

    fun bindBool(name: String, value: Boolean): Boolean

    fun setString(name: String, value: String): Boolean

    fun setInt(name: String, value: Int): Boolean

    fun setBool(name: String, value: Boolean): Boolean

    fun getString(name: String): String?

    fun getInt(name: String): Int?

    fun getBool(name: String): Boolean?

    fun setActionHandler(handler: (String) -> Unit)

    fun frame(): FrameResult

    /** 唤醒阻塞中的帧循环；后台线程改完状态后调用。 */
    fun wake(): Boolean

    /** 最近一次失败的详情，供宿主记录日志。 */
    fun lastError(): String

    fun destroy()
}
