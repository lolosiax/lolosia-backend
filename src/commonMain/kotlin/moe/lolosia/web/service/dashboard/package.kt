package moe.lolosia.web.service.dashboard

/**
 * 仪表板的散装常量（公共顶层声明，见 AGENTS.md §1.5）。
 *
 * @author 洛洛希雅Lolosia
 */

/** 读仪表板：系统状态 / 实时监控曲线 / 媒体卡片与访问排行。默认只给 admin 及以上（`+lolosia.*`）。 */
const val DASHBOARD_READ: String = "lolosia.dashboard.read"
