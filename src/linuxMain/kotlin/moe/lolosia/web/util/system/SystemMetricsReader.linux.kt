package moe.lolosia.web.util.system

import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/**
 * Linux 侧的系统指标采集：直接解析 `/proc`。
 *
 * - `/proc/stat`：第一行 `cpu` 的累计节拍（busy = total - idle - iowait）；
 * - `/proc/meminfo`：`MemTotal` 与 `MemAvailable`（缺失时退回 `MemFree`）；
 * - `/proc/net/dev`：逐网卡累计收发字节（跳过 `lo`）；
 * - `/proc/diskstats`：逐设备累计读写扇区 × 512（跳过 `loop*` / `ram*` 伪设备）。
 *
 * **磁盘容量记为 0（降级）**：Kotlin/Native 的 `platform.posix` 未导出 `statvfs`，
 * 取不到挂载点容量，服务层据此把磁盘占用率算成 0；其余指标不受影响。
 *
 * 任意一步解析失败都返回 `null`，不抛异常。
 */
internal actual fun readSystemMetrics(diskPath: String): SystemMetricsSnapshot? {
    val stat = readText("/proc/stat") ?: return null
    val meminfo = readText("/proc/meminfo") ?: return null
    val netdev = readText("/proc/net/dev") ?: return null
    val diskstats = readText("/proc/diskstats") ?: return null

    val cpu = parseCpu(stat) ?: return null
    val mem = parseMem(meminfo) ?: return null
    val net = parseNetDev(netdev)
    val io = parseDiskStats(diskstats)

    return SystemMetricsSnapshot(
        cpuBusyTicks = cpu.first,
        cpuTotalTicks = cpu.second,
        memUsedBytes = mem.first,
        memTotalBytes = mem.second,
        diskUsedBytes = 0L,
        diskTotalBytes = 0L,
        netInBytes = net.first,
        netOutBytes = net.second,
        diskReadBytes = io.first,
        diskWriteBytes = io.second,
    )
}

/**
 * 读取一个 `/proc` 伪文件到 EOF。
 *
 * 不能用 `util.fs.readFileBytes`：它靠 `fseek(SEEK_END)` + `ftell` 求长度，而 `/proc` 文件的
 * `st_size` 恒为 0，会被当成空文件直接返回 `null`（整条采样链因此恒判「本机不支持」）。
 */
@OptIn(UnsafeNumber::class)
private fun readText(path: String): String? {
    val file = fopen(path, "rb") ?: return null
    try {
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
    } finally {
        fclose(file)
    }
}

/** CPU：返回「(累计繁忙节拍, 累计总节拍)」。 */
private fun parseCpu(text: String): Pair<Long, Long>? {
    val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
    val fields = line.trim().split(' ').filter { it.isNotEmpty() }
    if (fields.size < 5) return null
    val total = fields.drop(1).sumOf { it.toLongOrNull() ?: 0L }
    val idle = (fields.getOrNull(4)?.toLongOrNull() ?: 0L) + (fields.getOrNull(5)?.toLongOrNull() ?: 0L)
    return (total - idle) to total
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
