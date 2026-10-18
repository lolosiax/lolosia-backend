package moe.lolosia.web.ui.router

/**
 * 一条路由：页面 id、标题、承载方式与 RML 文档路径。
 *
 * 页面代码只写一份，Windows / Android 共用同一份 RML。
 *
 * @author 洛洛希雅Lolosia
 */
data class Route(
    val id: String,
    val title: String,
    val layout: LayoutType,
    val document: String,
    val icon: String? = null,
)
