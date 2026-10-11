package moe.lolosia.web.controller

import moe.lolosia.web.dto.DashboardMetrics
import moe.lolosia.web.dto.DashboardOverview
import moe.lolosia.web.dto.DashboardSystem
import moe.lolosia.web.service.dashboard.DASHBOARD_READ
import moe.lolosia.web.service.dashboard.DashboardService
import moe.lolosia.web.service.dashboard.SystemMetricService
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.GET
import moe.lolosia.web.util.router.Permission
import moe.lolosia.web.util.router.Query
import moe.lolosia.web.util.session.Context

/**
 * 仪表板接口：系统状态、实时监控曲线、媒体卡片与访问排行。
 *
 * 三个接口拆开是为了让前端按各自的刷新节奏取：
 * - `dashboard/system` 最轻（只读内存里最近一次采样），可以秒级轮询；
 * - `dashboard/metrics?range=` 拉曲线，切换时间范围时才需要；
 * - `dashboard/overview` 最重（媒体与访问排行），首屏拉一次即可。
 *
 * 整个控制器只要求 [DASHBOARD_READ]（默认给 admin 及以上，见 `permissions.yaml` 的 `+lolosia.*`）。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
@Permission(DASHBOARD_READ)
object DashboardController {

    /** 系统状态卡片：CPU / 内存 / 存储的最近一次采样。平台不支持时 `available = false`。 */
    @GET("/dashboard/system")
    suspend fun system(metric: SystemMetricService): DashboardSystem = metric.system()

    /**
     * 实时监控曲线。`range` 取 `1m` / `10m` / `1h` / `1d` / `1w`（未知值回退 `1m`）；
     * 前三个走内存环形缓冲、后两个走数据库，见 [SystemMetricService.metrics]。
     */
    @GET("/dashboard/metrics")
    suspend fun metrics(ctx: Context, metric: SystemMetricService, @Query range: String?): DashboardMetrics =
        metric.metrics(ctx, range)

    /** 首屏汇总：访问排行 + 近期活跃 / 最近新增 / 最活跃标签 / 为你推荐 / 受欢迎。 */
    @GET("/dashboard/overview")
    suspend fun overview(ctx: Context, dashboard: DashboardService): DashboardOverview = dashboard.overview(ctx)
}
