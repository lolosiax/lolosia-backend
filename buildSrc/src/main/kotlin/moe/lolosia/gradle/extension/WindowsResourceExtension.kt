package moe.lolosia.gradle.extension

import java.io.File

/**
 * Windows 可执行文件的 PE 资源信息（只对 mingw 目标生效）。
 *
 * 由 buildSrc 生成 `.rc`、经 windres 编译后链入 exe；Explorer「属性 → 详细信息」、
 * 任务管理器与文件版本比较看到的都是这里与工程 `version` 拼出来的字段。
 *
 * 版本号不在这里配 —— 直接取 Gradle 工程的 `version`，免得每次发版手改一处。
 *
 * @author 洛洛希雅Lolosia
 */
open class WindowsResourceExtension {

    /** exe 图标（.ico）。null = 不嵌图标；文件不存在时跳过并告警。 */
    var icon: File? = null

    /** 「公司名」；留空取 [LolosiaExtension.projectName]。 */
    var companyName: String = ""

    /** 「版权」；留空则不写该字段。 */
    var copyright: String = ""

    /** 「文件说明」；留空取 [LolosiaExtension.projectName]。 */
    var fileDescription: String = ""

    fun icon(file: File) {
        icon = file
    }
}
