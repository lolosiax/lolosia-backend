package moe.lolosia.web.util.platform

import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.linux.statvfs
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Linux 下的系统指标采集：读 `/proc/stat`、`/proc/meminfo`、`/proc/net/dev`、`/proc/diskstats`，
 * 根分区容量走 `statvfs("/")`。
 *
 * 速率类指标（网络 / 磁盘 IO）是**累计计数器**，只能用相邻两次采样的差分除时间间隔得到，
 * 所以这个对象持有上一次的读数与时间标记；首次采样时这些速率为 0。
 *
 * 设备过滤：`/proc/diskstats` 里整盘与分区并列，直接求和会重复计数，因此只认整盘（无分区后缀）
 * 的设备名。`/proc/net/dev` 同理跳过回环 `lo`。
 *
 * @author 洛洛希雅Lolosia
 */
@OptIn(ExperimentalForeignApi::class)
actual object SystemMetricsReader {

    actual val supported: Boolean = true

    /** 整盘设备名（排除分区，如 `sda1` / `nvme0n1p1`）。 */
    private val wholeDisk: Regex = Regex("^(sd[a-z]+|hd[a-z]+|vd[a-z]+|nvme\\d+n\\d+|mmcblk\\d+)$")

    private var lastCpuTotal = 0.0
    private var lastCpuIdle = 0.0
    private var lastNetIn = 0.0
    private var lastNetOut = 0.0
    private var lastDiskRead = 0.0
    private var lastDiskWrite = 0.0
    private var lastMark: TimeMark? = null

    actual fun sample(): SystemMetricsSnapshot? {
        val stat = readProc("/proc/stat") ?: return null
        val meminfo = readProc("/proc/meminfo") ?: return null
        val netdev = readProc("/proc/net/dev") ?: return null
        val diskstats = readProc("/proc/diskstats") ?: return null

        val (cpu, cores) = parseCpu(stat)
        val (memUsed, memTotal, memPercent) = parseMem(meminfo)
        val (diskUsed, diskTotal, diskPercent) = parseDisk()

        val (netInRaw, netOutRaw) = parseNet(netdev)
        val (diskReadRaw, diskWriteRaw) = parseDiskIo(diskstats)

        val seconds = (lastMark?.elapsedNow()?.inWholeMilliseconds ?: 0L) / 1000.0
        val netIn = rate(netInRaw, lastNetIn, seconds)
        val netOut = rate(netOutRaw, lastNetOut, seconds)
        val diskRead = rate(diskReadRaw, lastDiskRead, seconds)
        val diskWrite = rate(diskWriteRaw, lastDiskWrite, seconds)

        lastNetIn = netInRaw
        lastNetOut = netOutRaw
        lastDiskRead = diskReadRaw
        lastDiskWrite = diskWriteRaw
        lastMark = TimeSource.Monotonic.markNow()

        return SystemMetricsSnapshot(
            cpu = cpu,
            mem = memPercent,
            disk = diskPercent,
            netIn = netIn,
            netOut = netOut,
            diskRead = diskRead,
            diskWrite = diskWrite,
            memUsed = memUsed,
            memTotal = memTotal,
            diskUsed = diskUsed,
            diskTotal = diskTotal,
            cores = cores,
        )
    }

    /** CPU：`cpu  user nice system idle iowait irq softirq steal …` 的 jiffies 差分。 */
    private fun parseCpu(stat: String): Pair<Double, Int> {
        var percent = 0.0
        var cores = 0
        var total = 0.0
        var idle = 0.0
        for (line in stat.lineSequence()) {
            if (!line.startsWith("cpu")) continue
            if (line.getOrNull(3)?.isDigit() == true) {
                cores++
                continue
            }
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 9) continue
            val values = fields.drop(1).take(8).mapNotNull { it.toDoubleOrNull() }
            if (values.size < 8) continue
            total = values.sum()
            idle = values[3] + values[4] // idle + iowait
        }
        if (lastCpuTotal > 0.0) {
            val deltaTotal = total - lastCpuTotal
            val deltaIdle = idle - lastCpuIdle
            if (deltaTotal > 0.0) percent = ((deltaTotal - deltaIdle) / deltaTotal * 100.0).coerceIn(0.0, 100.0)
        }
        lastCpuTotal = total
        lastCpuIdle = idle
        return percent to cores.coerceAtLeast(1)
    }

    /** 内存：`MemTotal` 与 `MemAvailable`（单位 kB）。 */
    private fun parseMem(meminfo: String): Triple<Long, Long, Double> {
        var totalKb = 0L
        var availableKb = 0L
        for (line in meminfo.lineSequence()) {
            when {
                line.startsWith("MemTotal:") -> totalKb = firstLong(line)
                line.startsWith("MemAvailable:") -> availableKb = firstLong(line)
            }
        }
        val total = totalKb * 1024
        val available = availableKb * 1024
        val used = (total - available).coerceAtLeast(0)
        val percent = if (total > 0) used.toDouble() / total * 100.0 else 0.0
        return Triple(used, total, percent)
    }

    /** 根分区容量：`statvfs("/")`。 */
    private fun parseDisk(): Triple<Long, Long, Double> = memScoped {
        val st = alloc<statvfs>()
        if (statvfs("/", st.ptr) != 0) return@memScoped Triple(0L, 0L, 0.0)
        val fragment = st.f_frsize.toDouble()
        val total = st.f_blocks.toDouble() * fragment
        val free = st.f_bavail.toDouble() * fragment
        val used = (total - free).coerceAtLeast(0.0)
        val percent = if (total > 0) used / total * 100.0 else 0.0
        Triple(used.toLong(), total.toLong(), percent)
    }

    /** 网络：各网卡的累计收发字节（跳过回环）。 */
    private fun parseNet(netdev: String): Pair<Double, Double> {
        var rx = 0.0
        var tx = 0.0
        for (line in netdev.lineSequence()) {
            if (!line.contains(':')) continue
            val name = line.substringBefore(':').trim()
            if (name == "lo") continue
            val nums = line.substringAfter(':').trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            if (nums.size < 16) continue
            rx += nums[0]
            tx += nums[8]
        }
        return rx to tx
    }

    /** 磁盘 IO：整盘累计扇区数 × 512 字节。 */
    private fun parseDiskIo(diskstats: String): Pair<Double, Double> {
        var read = 0.0
        var write = 0.0
        for (line in diskstats.lineSequence()) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 14) continue
            if (!wholeDisk.matches(fields[2])) continue
            read += fields[5].toDoubleOrNull() ?: 0.0
            write += fields[9].toDoubleOrNull() ?: 0.0
        }
        return read * 512.0 to write * 512.0
    }

    /** 累计值差分求速率；计数器回绕或清零时记 0，避免出现负值。 */
    private fun rate(current: Double, previous: Double, seconds: Double): Double =
        if (seconds <= 0.0 || current < previous) 0.0 else (current - previous) / seconds

    private fun firstLong(line: String): Long =
        line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L

    private fun readProc(path: String): String? =
        runCatching { FileSystem.SYSTEM.read(path.toPath()) { readUtf8() } }.getOrNull()
}
