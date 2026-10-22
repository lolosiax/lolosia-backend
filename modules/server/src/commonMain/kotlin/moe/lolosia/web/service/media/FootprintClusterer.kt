package moe.lolosia.web.service.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 足迹地图的聚类算法：**纯函数、无 IO**，输入是一批点，输出是若干簇。
 *
 * 两种"x-y 聚类"共用同一套心智，只是 x/y 的含义不同：
 * - [spatial]：x/y 是**平面米制坐标**（等距圆柱投影），聚出来的是地理热点；
 * - [temporal]：x 是**时间轴**（毫秒），聚出来的是"一次拍摄会话"。
 *
 * 空间部分用 **DBSCAN**（[epsMeters] 邻域 + [minPts] 密度阈值）：它能自动决定簇数、把稀疏的
 * 孤立点当噪声丢掉 —— 这正是"热点"想要的行为，不必预设"有几个地点"。邻域查询挂在一张
 * 边长 = eps 的网格上，把朴素 O(N²) 降到与局部密度成正比。
 *
 * 投影只在**局部**成立：以全库均值纬度做等距圆柱（个人相册跨度有限，误差可忽略）。
 * 若将来要支持跨大洲的库，把投影换成球面距离即可，接口不变。
 *
 * @author 洛洛希雅Lolosia
 */
object FootprintClusterer {

    /** 每度对应的米数（WGS84 平均）。 */
    private const val METERS_PER_DEGREE: Double = 111_320.0

    /** 聚类的输入点（下标即它在调用方列表里的位置）。 */
    data class Point(val lat: Double, val lon: Double, val takenAt: Long)

    /** 一个空间簇。 */
    data class Cluster(
        val lat: Double,
        val lon: Double,
        val count: Int,
        /** 成员到中心的最大距离（米）。 */
        val radiusM: Double,
        /** 最早 / 最晚拍摄时刻（毫秒）；都没有拍摄时间时为 0。 */
        val firstAt: Long,
        val lastAt: Long,
        /** 代表点在输入列表里的下标（离中心最近的那一个）。 */
        val representative: Int,
    )

    /** 一个时间簇（拍摄会话）。 */
    data class Period(
        val startAt: Long,
        val endAt: Long,
        val count: Int,
        /** 代表点在输入列表里的下标。 */
        val representative: Int,
    )

    /**
     * 空间聚类（DBSCAN）。
     *
     * @param epsMeters 邻域半径（米），须为正。
     * @param minPts 成为核心点所需的最少邻居数（含自身）；小于它的点当噪声丢弃。
     */
    fun spatial(points: List<Point>, epsMeters: Double, minPts: Int): List<Cluster> {
        val n = points.size
        if (n == 0) return emptyList()

        val eps = epsMeters.coerceAtLeast(1.0)
        val minPts = minPts.coerceAtLeast(1)
        val eps2 = eps * eps

        // 等距圆柱投影：以均值纬度定 x 方向的伸缩，全局共用一个坐标系
        val lat0 = points.sumOf { it.lat } / n
        val kx = cos(lat0 * PI / 180.0) * METERS_PER_DEGREE
        val xs = DoubleArray(n) { points[it].lon * kx }
        val ys = DoubleArray(n) { points[it].lat * METERS_PER_DEGREE }

        // 网格索引：cell = eps，邻域查询只看 3×3 个格子
        val grid = HashMap<Long, MutableList<Int>>(n * 2)
        val cellX = IntArray(n)
        val cellY = IntArray(n)
        for (i in 0 until n) {
            val cx = floor(xs[i] / eps).toInt()
            val cy = floor(ys[i] / eps).toInt()
            cellX[i] = cx
            cellY[i] = cy
            grid.getOrPut(key(cx, cy)) { ArrayList() }.add(i)
        }

        fun neighborsWithin(i: Int): MutableList<Int> {
            val out = ArrayList<Int>()
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val bucket = grid[key(cellX[i] + dx, cellY[i] + dy)] ?: continue
                    for (j in bucket) {
                        val ddx = xs[j] - xs[i]
                        val ddy = ys[j] - ys[i]
                        if (ddx * ddx + ddy * ddy <= eps2) out.add(j)
                    }
                }
            }
            return out
        }

        val visited = BooleanArray(n)
        val clusterOf = IntArray(n) { -1 }
        val clusters = ArrayList<Cluster>()

        for (i in 0 until n) {
            if (visited[i]) continue
            visited[i] = true
            val seeds = neighborsWithin(i)
            if (seeds.size < minPts) continue // 噪声点

            val cid = clusters.size
            val members = ArrayList<Int>(seeds.size)
            clusterOf[i] = cid
            members.add(i)

            var cursor = 0
            while (cursor < seeds.size) {
                val j = seeds[cursor++]
                if (!visited[j]) {
                    visited[j] = true
                    val more = neighborsWithin(j)
                    if (more.size >= minPts) seeds.addAll(more)
                }
                if (clusterOf[j] == -1) {
                    clusterOf[j] = cid
                    members.add(j)
                }
            }
            clusters.add(buildCluster(points, xs, ys, members, kx))
        }
        return clusters
    }

    /**
     * 时间聚类：按相邻拍摄时刻的间隔切段，超过 [gapMillis] 就是新的一段。
     *
     * @param points 须**已按时间升序**（调用方的 SQL 已 `order by`）；无拍摄时间（0）的点被跳过。
     */
    fun temporal(points: List<Point>, gapMillis: Long): List<Period> {
        val gap = gapMillis.coerceAtLeast(1L)
        val timed = points.withIndex().filter { it.value.takenAt > 0 }
        if (timed.isEmpty()) return emptyList()

        val periods = ArrayList<Period>()
        var startPos = 0
        for (k in 1..timed.size) {
            val broken = k == timed.size || timed[k].value.takenAt - timed[k - 1].value.takenAt > gap
            if (!broken) continue
            val count = k - startPos
            periods.add(
                Period(
                    startAt = timed[startPos].value.takenAt,
                    endAt = timed[k - 1].value.takenAt,
                    count = count,
                    representative = timed[startPos + count / 2].index,
                )
            )
            startPos = k
        }
        return periods
    }

    /** 汇总一个空间簇：中心取成员质心，半径取成员到中心的最大距离，代表图取离中心最近的一张。 */
    private fun buildCluster(points: List<Point>, xs: DoubleArray, ys: DoubleArray, members: List<Int>, kx: Double): Cluster {
        var sumX = 0.0
        var sumY = 0.0
        for (m in members) {
            sumX += xs[m]
            sumY += ys[m]
        }
        val cx = sumX / members.size
        val cy = sumY / members.size

        var maxDist2 = 0.0
        var best = members.first()
        var bestDist2 = Double.MAX_VALUE
        var firstAt = 0L
        var lastAt = 0L
        for (m in members) {
            val dx = xs[m] - cx
            val dy = ys[m] - cy
            val d2 = dx * dx + dy * dy
            if (d2 > maxDist2) maxDist2 = d2
            if (d2 < bestDist2) {
                bestDist2 = d2
                best = m
            }
            val t = points[m].takenAt
            if (t > 0) {
                if (firstAt == 0L || t < firstAt) firstAt = t
                if (t > lastAt) lastAt = t
            }
        }

        // 反投影回经纬度：y 直接除常数；x 必须用与正投影**同一个** kx 还原，
        // 否则经度会带一个 cos(lat0)/cos(repLat) 的系数偏差（跨纬度相册里能让整簇经度漂移上百度）。
        val lat = cy / METERS_PER_DEGREE
        return Cluster(
            lat = lat,
            lon = cx / kx,
            count = members.size,
            radiusM = sqrt(maxDist2),
            firstAt = firstAt,
            lastAt = lastAt,
            representative = best,
        )
    }

    /** 把 (cellX, cellY) 压成一个 long 当哈希键；cellY 可能为负，先转无符号再拼。 */
    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
}
