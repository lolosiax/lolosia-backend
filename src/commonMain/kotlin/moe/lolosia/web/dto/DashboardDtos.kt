package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 仪表板接口的 DTO（`controller/DashboardController.kt`）。
 *
 * 三个接口各管一块：
 * - `dashboard/system`：系统状态环（CPU / 内存 / 存储的最新值），来自内存环形缓冲，最轻、可高频轮询；
 * - `dashboard/metrics`：实时监控曲线（按时间范围取序列）；
 * - `dashboard/overview`：媒体卡片 + 访问排行（来自 `media_*` 与 `sys_access_stat`）。
 *
 * 时间字段一律是 **epoch 毫秒**（由前端格式化）；图片只给**路径 + 签名**，前端自己拼缩略图直链。
 *
 * @author 洛洛希雅Lolosia
 */

/** 系统状态的一个环形指标。 */
@Serializable
data class DashboardVital(
    /** `cpu` / `mem` / `disk`。 */
    val key: String,
    val label: String,
    /** 百分比（0~100）。 */
    val value: Double = 0.0,
    /** 展示用补充（如 `3.2 / 8.0 GB`、`4 核`）；没有为空串。 */
    val detail: String = "",
)

/**
 * 系统状态卡片。
 *
 * [available] 为 `false` 表示**本平台不采集**（Windows 目标），此时前端应把系统卡片整体隐藏或提示，
 * 而不是显示一堆 0。
 */
@Serializable
data class DashboardSystem(
    val available: Boolean = false,
    /** 最近一次采样时刻（epoch 毫秒）；没有为 0。 */
    val updatedAt: Long = 0,
    val vitals: List<DashboardVital> = emptyList(),
)

/** 一条曲线（一个指标下的一个系列）。 */
@Serializable
data class DashboardSeries(
    val name: String,
    /** 建议配色（前端可忽略）。 */
    val color: String = "",
    val data: List<Double> = emptyList(),
)

/** 实时监控里的一个指标（含一或两条曲线）。 */
@Serializable
data class DashboardMetric(
    /** `cpu` / `mem` / `traffic` / `diskio`。 */
    val key: String,
    val label: String,
    /** `%` 或 `B/s`。 */
    val unit: String,
    val series: List<DashboardSeries> = emptyList(),
)

/** 实时监控曲线集合：[at] 是各采样点的时间戳（epoch 毫秒），各 [DashboardSeries.data] 与之一一对应。 */
@Serializable
data class DashboardMetrics(
    /** 回显生效的时间范围（`1m` / `10m` / `1h` / `1d` / `1w`）。 */
    val range: String,
    val at: List<Long> = emptyList(),
    val metrics: List<DashboardMetric> = emptyList(),
)

/** 排行的通用一行：[key] 是 IP 或 Host。 */
@Serializable
data class DashboardRankRow(
    val key: String,
    val count: Long = 0,
)

/** 文件行（近期活跃 / 最近新增）。 */
@Serializable
data class DashboardFileRow(
    val name: String,
    val path: String,
    /** 小写扩展名（不含点）。 */
    val ext: String = "",
    /** `image` / `video` / `audio` / `other`。 */
    val kind: String = "other",
    val size: Long = 0,
    /** 源文件修改时间（epoch 毫秒）。 */
    val mtime: Long = 0,
    /** 代表标签的展示名；没有为空串。 */
    val tag: String = "",
    /** 直链签名（取缩略图用）。 */
    val exp: Long = 0,
    val sig: String = "",
)

/** 标签行（最活跃的标签）。 */
@Serializable
data class DashboardTagRow(
    val namespace: String,
    val name: String,
    /** 被引用的资产数。 */
    val count: Long = 0,
)

/** 图片卡片（为你推荐 / 受欢迎）。 */
@Serializable
data class DashboardPhotoRow(
    val name: String,
    val path: String,
    /** 展示标题（取文件名去扩展名）。 */
    val title: String = "",
    /** 代表标签的展示名；没有为空串。 */
    val tag: String = "",
    /** 热度：该图身上的标签数（`受欢迎` 的排序依据，没有真实点赞数据）。 */
    val likes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    /** 8×8 采样（每像素 4 位十六进制，RGB565 大端），加载前的占位。 */
    val sample: String = "",
    val exp: Long = 0,
    val sig: String = "",
)

/** 媒体卡片 + 访问排行的汇总（首屏一次拉取）。 */
@Serializable
data class DashboardOverview(
    val ipRank: List<DashboardRankRow> = emptyList(),
    val originRank: List<DashboardRankRow> = emptyList(),
    val activeFiles: List<DashboardFileRow> = emptyList(),
    val newFiles: List<DashboardFileRow> = emptyList(),
    val hotTags: List<DashboardTagRow> = emptyList(),
    val recommended: List<DashboardPhotoRow> = emptyList(),
    val popular: List<DashboardPhotoRow> = emptyList(),
    val generatedAt: Long = 0,
)
