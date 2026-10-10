package moe.lolosia.web.controller

import kotlinx.serialization.Serializable
import moe.lolosia.web.service.dashboard.AccessStatService
import moe.lolosia.web.service.dashboard.DashboardQueryService
import moe.lolosia.web.service.dashboard.SystemMetricService
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.GET
import moe.lolosia.web.util.router.Permission
import moe.lolosia.web.util.router.Query
import moe.lolosia.web.util.session.Context

/**
 * 仪表盘只读接口：系统概览、指标曲线、综合概览。
 *
 * 三个接口都挂 `lolosia.dashboard.read`（`admin` 及以上由 `+lolosia.*` 覆盖）。
 * DTO 作为本 `object` 的内部类；出参信封由 `EnvelopeInterceptor` 统一处理。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object DashboardController {

    /** 系统概览：占用率 / 容量 / 运行时长。 */
    @Serializable
    data class DashboardSystemDto(
        /** 本机能否采集（Windows 恒为 `false`）。 */
        val available: Boolean = false,
        /** CPU 占用率（0~100）。 */
        val cpu: Double = 0.0,
        /** 内存占用率（0~100）。 */
        val mem: Double = 0.0,
        val memUsed: Long = 0L,
        val memTotal: Long = 0L,
        /** 磁盘占用率（0~100）。 */
        val disk: Double = 0.0,
        val diskUsed: Long = 0L,
        val diskTotal: Long = 0L,
        /** 进程运行时长（毫秒）。 */
        val uptimeMs: Long? = null,
    )

    /** 一条曲线。 */
    @Serializable
    data class DashboardSeriesDto(
        val key: String,
        val name: String,
        val points: List<Double> = emptyList(),
    )

    /** 指标曲线集合。 */
    @Serializable
    data class DashboardMetricsDto(
        val range: String = "1h",
        val available: Boolean = false,
        val series: List<DashboardSeriesDto> = emptyList(),
    )

    /** top-ip 一行。 */
    @Serializable
    data class DashboardIpRow(
        val ip: String,
        /** 归属地（当前不解析，恒为 `null`）。 */
        val region: String? = null,
        val hits: Long = 0L,
    )

    /** top-host 一行。 */
    @Serializable
    data class DashboardOriginRow(val host: String, val hits: Long = 0L)

    /** 热门标签一行。 */
    @Serializable
    data class DashboardTagRow(val namespace: String, val name: String, val count: Long = 0L)

    /** 文件卡片一行。 */
    @Serializable
    data class DashboardFileRow(
        val path: String,
        val name: String,
        val size: Long = 0L,
        val mtime: Long = 0L,
        val kind: String? = null,
        /** 像素宽度 / 高度（未探测为 `null`）；前端据此判断横竖屏排版。 */
        val width: Int? = null,
        val height: Int? = null,
        /** 直链签名（`files/thumb` 用），语义同 `FileEntry.exp` / `FileEntry.sig`。 */
        val exp: Long = 0L,
        val sig: String = "",
    )

    /** 综合概览。 */
    @Serializable
    data class DashboardOverviewDto(
        val topIps: List<DashboardIpRow> = emptyList(),
        val origins: List<DashboardOriginRow> = emptyList(),
        val recentAdded: List<DashboardFileRow> = emptyList(),
        val recentActive: List<DashboardFileRow> = emptyList(),
        val hotTags: List<DashboardTagRow> = emptyList(),
        val recommended: List<DashboardFileRow> = emptyList(),
        val popular: List<DashboardFileRow> = emptyList(),
    )

    /** 系统概览。 */
    @GET("/dashboard/system")
    @Permission("lolosia.dashboard.read")
    suspend fun system(ctx: Context, metrics: SystemMetricService, stat: AccessStatService): DashboardSystemDto {
        val latest = metrics.latest(ctx) ?: return DashboardSystemDto(available = false)
        return DashboardSystemDto(
            available = true,
            cpu = latest.cpu,
            mem = latest.mem,
            memUsed = latest.memUsed,
            memTotal = latest.memTotal,
            disk = latest.disk,
            diskUsed = latest.diskUsed,
            diskTotal = latest.diskTotal,
            uptimeMs = latest.uptimeMs,
        )
    }

    /** 指标曲线（六条：CPU / 内存 / 入站 / 出站 / 读 / 写）。 */
    @GET("/dashboard/metrics")
    @Permission("lolosia.dashboard.read")
    suspend fun metrics(ctx: Context, metrics: SystemMetricService, @Query range: String?): DashboardMetricsDto {
        val window = range ?: "1h"
        val curves = metrics.curve(ctx, window)
        val series = SERIES.mapIndexed { index, (key, name) ->
            DashboardSeriesDto(key = key, name = name, points = curves.getOrElse(index) { emptyList() })
        }
        return DashboardMetricsDto(range = window, available = curves.any { it.isNotEmpty() }, series = series)
    }

    /** 综合概览：访问来源、最近文件、热门标签与推荐。 */
    @GET("/dashboard/overview")
    @Permission("lolosia.dashboard.read")
    suspend fun overview(
        ctx: Context,
        query: DashboardQueryService,
        stat: AccessStatService,
    ): DashboardOverviewDto = DashboardOverviewDto(
        topIps = stat.topIps(ctx, TOP_LIMIT).map { DashboardIpRow(ip = it.first, region = null, hits = it.second) },
        origins = stat.topHosts(ctx, TOP_LIMIT).map { DashboardOriginRow(host = it.first, hits = it.second) },
        recentAdded = query.recentAdded(ctx, FILE_LIMIT),
        recentActive = query.recentActive(ctx, FILE_LIMIT),
        hotTags = query.hotTags(ctx, TAG_LIMIT),
        recommended = query.recommended(ctx, FILE_LIMIT),
        popular = query.popular(ctx, FILE_LIMIT),
    )

    private const val TOP_LIMIT: Int = 10
    private const val TAG_LIMIT: Int = 12
    private const val FILE_LIMIT: Int = 8

    /** 曲线 key / 展示名，顺序与 `SystemMetricService.curve` 返回的六条一一对应。 */
    private val SERIES: List<Pair<String, String>> = listOf(
        "cpu" to "CPU",
        "mem" to "内存",
        "netIn" to "入站",
        "netOut" to "出站",
        "read" to "磁盘读",
        "write" to "磁盘写",
    )
}
