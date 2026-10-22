package moe.lolosia.local.metrics

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import moe.lolosia.web.util.time.nowEpochMs
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen

/**
 * Linux 侧的系统指标采集：直接解析 `/proc`。
 *
 * - `/proc/stat`：第一行 `cpu` 的累计节拍（busy = total - idle - iowait）；
 * - `/proc/meminfo`：`MemTotal` 与 `MemAvailable`（缺失时退回 `MemFree`）；
 * - `/proc/net/dev`：逐网卡累计收发字节（跳过 `lo`）；
 * - `/proc/diskstats`：逐设备累计读写扇区 × 512（跳过 `loop*` / `ram*` 伪设备）。
 *
 * **磁盘容量走 `df -Pk` 兜底**：Kotlin/Native 的 `platform.posix` 未导出 `statfs`/`statvfs`，
 * 起一次 `df` 取挂载点用量，并按 [DISK_CACHE_MS] 低频缓存（采样每 2s 一次，不必每次起进程）。
 *
 * 任意一步解析失败都返回 `null`，不抛异常。
 */
actual fun readSystemMetrics(diskPath: String): SystemMetricsSnapshot? {
    val stat = readText("/proc/stat") ?: return null
    val meminfo = readText("/proc/meminfo") ?: return null
    val netdev = readText("/proc/net/dev") ?: return null
    val diskstats = readText("/proc/diskstats") ?: return null

    val cpu = parseCpu(stat) ?: return null
    val cores = parseCpuCores(stat)
    val mem = parseMem(meminfo) ?: return null
    val net = parseNetDev(netdev)
    val io = parseDiskStats(diskstats)
    val disk = readDiskCapacity(diskPath)

    return SystemMetricsSnapshot(
        cpuBusyTicks = cpu.first,
        cpuTotalTicks = cpu.second,
        memUsedBytes = mem.first,
        memTotalBytes = mem.second,
        diskUsedBytes = disk.first,
        diskTotalBytes = disk.second,
        netInBytes = net.first,
        netOutBytes = net.second,
        diskReadBytes = io.first,
        diskWriteBytes = io.second,
        cpuCoreBusyTicks = cores.first,
        cpuCoreTotalTicks = cores.second,
    )
}

/**
 * 读取一个 `/proc` 伪文件到 EOF。
 *
 * 不能用 `util.fs.readFileBytes`：它靠 `fseek(SEEK_END)` + `ftell` 求长度，而 `/proc` 文件的
 * `st_size` 恒为 0，会被当成空文件直接返回 `null`（整条采样链因此恒判「本机不支持」）。
 */
private fun readText(path: String): String? {
    val file = fopen(path, "rb") ?: return null
    return try {
        readAllText(file)
    } finally {
        fclose(file)
    }
}

/** 把一个已打开的流（文件 / 管道）读到 EOF。 */
@OptIn(UnsafeNumber::class)
private fun readAllText(file: CPointer<FILE>): String {
    val chunk = ByteArray(16 * 1024)
    var data = ByteArray(0)
    while (true) {
        val count = chunk.usePinned { pinned -> fread(pinned.addressOf(0), 1uL, chunk.size.toULong(), file).toLong() }
        if (count <= 0L) break
        val size = count.toInt()
        val offset = data.size
        data = data.copyOf(offset + size)
        chunk.copyInto(data, offset, 0, size)
    }
    return data.decodeToString()
}

// —— 磁盘容量：`df -Pk` 兜底 + 低频缓存 —— //

/** `df` 结果的驻留时长，避免每 2s 采样都起进程。 */
private const val DISK_CACHE_MS = 60_000L

private var diskCachedAtMs = 0L
private var diskCachedUsed = 0L
private var diskCachedTotal = 0L

/**
 * 磁盘容量（已用 / 总量，字节）：`platform.posix` 未导出 `statfs`/`statvfs`，
 * 退而用 `df -Pk` 取挂载点用量，并按 [DISK_CACHE_MS] 缓存；取不到时沿用上次结果。
 */
private fun readDiskCapacity(diskPath: String): Pair<Long, Long> {
    val now = nowEpochMs()
    if (diskCachedAtMs != 0L && now - diskCachedAtMs < DISK_CACHE_MS) return diskCachedUsed to diskCachedTotal
    val parsed = runDf(diskPath) ?: return diskCachedUsed to diskCachedTotal
    diskCachedAtMs = now
    diskCachedUsed = parsed.first
    diskCachedTotal = parsed.second
    return parsed
}

/** 跑一次 `df -Pk <path>`，解析「已用 / 总量（字节）」；失败返回 `null`。 */
@OptIn(UnsafeNumber::class)
private fun runDf(diskPath: String): Pair<Long, Long>? {
    val pipe = popen("df -Pk ${shellQuote(diskPath)} 2>/dev/null", "r") ?: return null
    val output = try {
        readAllText(pipe)
    } finally {
        pclose(pipe)
    }
    // POSIX 输出：「文件系统 1024-块 已用 可用 容量 挂载点」，取最后一行数据行
    val line = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("Filesystem") } ?: return null
    val fields = line.split(' ').filter { it.isNotEmpty() }
    if (fields.size < 3) return null
    val total = fields[1].toLongOrNull()?.times(1024L) ?: return null
    val used = fields[2].toLongOrNull()?.times(1024L) ?: return null
    if (total <= 0L) return null
    return used.coerceAtLeast(0L) to total
}

/** 单引号包裹并转义，供 shell 使用。 */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** CPU：返回「(累计繁忙节拍, 累计总节拍)」。 */
private fun parseCpu(text: String): Pair<Long, Long>? {
    val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
    val fields = line.trim().split(' ').filter { it.isNotEmpty() }
    if (fields.size < 5) return null
    val total = fields.drop(1).sumOf { it.toLongOrNull() ?: 0L }
    val idle = (fields.getOrNull(4)?.toLongOrNull() ?: 0L) + (fields.getOrNull(5)?.toLongOrNull() ?: 0L)
    return (total - idle) to total
}

/** 逐核 CPU：`/proc/stat` 的 `cpu0…` 行，返回「(逐核繁忙节拍, 逐核总节拍)」。 */
private fun parseCpuCores(text: String): Pair<List<Long>, List<Long>> {
    val busy = mutableListOf<Long>()
    val total = mutableListOf<Long>()
    for (line in text.lineSequence()) {
        if (!line.startsWith("cpu") || line.startsWith("cpu ")) continue
        val fields = line.trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size < 5) continue
        val all = fields.drop(1).sumOf { it.toLongOrNull() ?: 0L }
        val idle = (fields.getOrNull(4)?.toLongOrNull() ?: 0L) + (fields.getOrNull(5)?.toLongOrNull() ?: 0L)
        busy += (all - idle).coerceAtLeast(0L)
        total += all
    }
    return busy to total
}

/** 内存：返回「(已用字节, 总字节)」。 */
private fun parseMem(text: String): Pair<Long, Long>? {
    var totalKb = -1L
    var availableKb = -1L
    var freeKb = -1L
    for (line in text.lineSequence()) {
        when {
            line.startsWith("MemTotal:") -> totalKb = digitsOf(line)
            line.startsWith("MemAvailable:") -> availableKb = digitsOf(line)
            line.startsWith("MemFree:") -> freeKb = digitsOf(line)
        }
    }
    if (totalKb <= 0L) return null
    val usedKb = if (availableKb >= 0L) totalKb - availableKb else totalKb - freeKb.coerceAtLeast(0L)
    return (usedKb.coerceAtLeast(0L) * 1024L) to (totalKb * 1024L)
}

/** 网络：逐网卡累计收发字节求和，跳过回环 `lo`。 */
private fun parseNetDev(text: String): Pair<Long, Long> {
    var inBytes = 0L
    var outBytes = 0L
    for (line in text.lineSequence()) {
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val name = line.substring(0, colon).trim()
        if (name.isEmpty() || name == "lo") continue
        val fields = line.substring(colon + 1).trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size < 9) continue
        inBytes += fields[0].toLongOrNull() ?: 0L
        outBytes += fields[8].toLongOrNull() ?: 0L
    }
    return inBytes to outBytes
}

/** 磁盘 IO：逐设备累计读写扇区 × 512 求和。 */
private fun parseDiskStats(text: String): Pair<Long, Long> {
    var read = 0L
    var write = 0L
    for (line in text.lineSequence()) {
        val fields = line.trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size < 10) continue
        val name = fields[2]
        if (name.startsWith("loop") || name.startsWith("ram")) continue
        read += (fields[5].toLongOrNull() ?: 0L) * 512L
        write += (fields[9].toLongOrNull() ?: 0L) * 512L
    }
    return read to write
}

/** 取一行里连续出现的数字拼成的值（`MemTotal: 16384000 kB` → `16384000`）。 */
private fun digitsOf(line: String): Long = line.filter { it.isDigit() }.toLongOrNull() ?: -1L
