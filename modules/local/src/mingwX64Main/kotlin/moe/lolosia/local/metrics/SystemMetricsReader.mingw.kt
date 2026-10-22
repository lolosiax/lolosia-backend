package moe.lolosia.local.metrics

import kotlinx.cinterop.*
import platform.posix.getenv
import platform.windows.*

/**
 * Windows 侧的系统指标采集：全部走**未提权**可用的 Win32 / NT API，解析失败一律返回 `null` 或计 0。
 *
 * - CPU：`GetSystemTimes` 的 idle / kernel / user 累计 100ns 节拍（kernel 含 idle，故忙 = total - idle）；
 * - 内存：`GlobalMemoryStatusEx` 的物理内存总量与可用量（已用 = 总量 - 可用）；
 * - 磁盘容量：`GetDiskFreeSpaceW` 取系统盘（`SystemDrive`）的簇数 × 簇大小；
 * - 网络：动态加载 `iphlpapi.dll` 的 `GetIfTable`，逐网卡累计收发字节、跳过回环，
 *   并按物理地址去重（同一网卡会被 Windows 以多行别名返回，否则吞吐被成倍放大）；
 * - 磁盘读写：动态加载 `ntdll.dll` 的 `NtQuerySystemInformation(SystemPerformanceInformation)`，
 *   取系统累计 IO 传输字节（无需开启 diskperf 计数、无需管理员）。
 *
 * `iphlpapi` / `ntdll` 不随 platform.windows 暴露，故用 `LoadLibraryA` + `GetProcAddress` 运行期解析，
 * 结构体按已知布局从缓冲区手工取值（MIB_IFROW 固定 860 字节、dwInOctets / dwOutOctets 在固定偏移）。
 *
 * @param diskPath 该平台忽略（Linux 用挂载点，Windows 固定统计系统盘）。
 */
@Suppress("UNUSED_PARAMETER")
actual fun readSystemMetrics(diskPath: String): SystemMetricsSnapshot? {
    val cpu = readCpu() ?: return null
    val cores = readCpuCores()
    val mem = readMem() ?: return null
    val disk = readDisk()
    val net = readNet()
    val io = readDiskIo()
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

/** FILETIME（两个 32 位字段）折成 64 位累计值。 */
private fun FILETIME.ticks(): Long =
    ((dwHighDateTime.toLong() and 0xFFFFFFFFL) shl 32) or (dwLowDateTime.toLong() and 0xFFFFFFFFL)

/** CPU：返回「(累计繁忙节拍, 累计总节拍)」，单位 100ns。 */
private fun readCpu(): Pair<Long, Long>? = memScoped {
    val idle = alloc<FILETIME>()
    val kernel = alloc<FILETIME>()
    val user = alloc<FILETIME>()
    if (GetSystemTimes(idle.ptr, kernel.ptr, user.ptr) == 0) return@memScoped null
    val idleTicks = idle.ticks()
    val total = kernel.ticks() + user.ticks()
    (total - idleTicks).coerceAtLeast(0L) to total
}

/**
 * 逐核 CPU：`NtQuerySystemInformation(SystemProcessorPerformanceInformation)` 每核返回
 * idle / kernel / user 累计 100ns 节拍（kernel 含 idle），返回「(逐核繁忙节拍, 逐核总节拍)」。
 *
 * 缓冲按 `GetSystemInfo` 的逻辑处理器数 × [SYSTEM_PROCESSOR_INFO_SIZE] 申请；调用失败时返回空。
 */
private fun readCpuCores(): Pair<List<Long>, List<Long>> {
    val module = LoadLibraryA("ntdll.dll") ?: return emptyList<Long>() to emptyList()
    try {
        val address = GetProcAddress(module, "NtQuerySystemInformation") ?: return emptyList<Long>() to emptyList()
        val query = address.reinterpret<CFunction<(Int, COpaquePointer?, Int, CPointer<DWORDVar>?) -> Int>>()
        return memScoped {
            val info = alloc<SYSTEM_INFO>()
            GetSystemInfo(info.ptr)
            val count = info.dwNumberOfProcessors.toInt().coerceIn(1, MAX_CPU_CORES)
            val size = count * SYSTEM_PROCESSOR_INFO_SIZE
            val buffer = allocArray<ByteVar>(size)
            val returned = alloc<DWORDVar>()
            if (query(SYSTEM_PROCESSOR_PERFORMANCE_CLASS, buffer, size, returned.ptr) != 0) {
                return@memScoped emptyList<Long>() to emptyList()
            }
            val raw = buffer.readBytes(size)
            val busy = ArrayList<Long>(count)
            val total = ArrayList<Long>(count)
            for (index in 0 until count) {
                val base = index * SYSTEM_PROCESSOR_INFO_SIZE
                val idle = leU64(raw, base)
                val kernel = leU64(raw, base + 8)
                val user = leU64(raw, base + 16)
                val all = kernel + user
                busy += (all - idle).coerceAtLeast(0L)
                total += all
            }
            busy to total
        }
    } finally {
        FreeLibrary(module)
    }
}

/** 内存：返回「(已用字节, 总字节)」。 */
private fun readMem(): Pair<Long, Long>? = memScoped {
    val status = alloc<MEMORYSTATUSEX>()
    status.dwLength = sizeOf<MEMORYSTATUSEX>().toUInt()
    if (GlobalMemoryStatusEx(status.ptr) == 0) return@memScoped null
    val total = status.ullTotalPhys.toLong()
    val available = status.ullAvailPhys.toLong()
    (total - available).coerceAtLeast(0L) to total
}

/** 磁盘容量：返回「(已用字节, 总字节)」；取系统盘，取不到回 0。 */
private fun readDisk(): Pair<Long, Long> = memScoped {
    val root = diskRoot()
    val rootW = allocArray<UShortVar>(root.length + 1)
    root.forEachIndexed { index, ch -> rootW[index] = ch.code.toUShort() }
    rootW[root.length] = 0u
    val sectorsPerCluster = alloc<DWORDVar>()
    val bytesPerSector = alloc<DWORDVar>()
    val freeClusters = alloc<DWORDVar>()
    val totalClusters = alloc<DWORDVar>()
    if (GetDiskFreeSpaceW(rootW, sectorsPerCluster.ptr, bytesPerSector.ptr, freeClusters.ptr, totalClusters.ptr) == 0) {
        return@memScoped 0L to 0L
    }
    val unit = sectorsPerCluster.value.toLong() * bytesPerSector.value.toLong()
    val totalBytes = totalClusters.value.toLong() * unit
    val usedBytes = (totalClusters.value.toLong() - freeClusters.value.toLong()).coerceAtLeast(0L) * unit
    usedBytes to totalBytes
}

/** 系统盘根路径（`SystemDrive` 环境变量，缺省 `C:\`）。 */
private fun diskRoot(): String {
    val drive = getenv("SystemDrive")?.toKString()
    return if (drive.isNullOrBlank()) "C:\\" else "$drive\\"
}

/** 网络：返回「(累计入站字节, 累计出站字节)」，逐网卡求和（按物理地址去重）、跳过回环。 */
private fun readNet(): Pair<Long, Long> {
    val module = LoadLibraryA("iphlpapi.dll") ?: return 0L to 0L
    try {
        val address = GetProcAddress(module, "GetIfTable") ?: return 0L to 0L
        val getIfTable = address.reinterpret<CFunction<(COpaquePointer?, CPointer<DWORDVar>?, Int) -> UInt>>()
        return memScoped {
            val size = alloc<DWORDVar>()
            // 先以空缓冲问一次所需大小（返回 ERROR_INSUFFICIENT_BUFFER 并写入大小）
            getIfTable(null, size.ptr, 1)
            val bytes = size.value.toInt()
            if (bytes <= MIB_IFTABLE_HEADER) return@memScoped 0L to 0L
            val buffer = allocArray<ByteVar>(bytes)
            if (getIfTable(buffer, size.ptr, 1) != 0u) return@memScoped 0L to 0L
            val raw = buffer.readBytes(bytes)
            val entries = leU32(raw, 0)
            var inbound = 0L
            var outbound = 0L
            // Windows 会为同一块网卡（同一物理地址）返回多行「别名」（WFP / 网络隔离层），
            // 逐行相加会把同一份流量重复计 N 次（实测某机 12 次，吞吐被放大一个数量级）。
            // 按物理地址去重，同一网卡只累加一次。
            val seen = HashSet<Long>()
            for (index in 0 until entries.toInt()) {
                val base = MIB_IFTABLE_HEADER + index * MIB_IFROW_SIZE
                if (leU32(raw, base + MIB_IFROW_TYPE) == IF_TYPE_SOFTWARE_LOOPBACK) continue
                val physLen = leU32(raw, base + MIB_IFROW_PHYS_ADDR_LEN).toInt().coerceIn(0, MIB_IFROW_PHYS_ADDR_MAX)
                if (physLen > 0 && !seen.add(readPhysAddr(raw, base + MIB_IFROW_PHYS_ADDR, physLen))) continue
                inbound += leU32(raw, base + MIB_IFROW_IN_OCTETS).toLong()
                outbound += leU32(raw, base + MIB_IFROW_OUT_OCTETS).toLong()
            }
            inbound to outbound
        }
    } finally {
        FreeLibrary(module)
    }
}

/** 磁盘读写：返回「(累计读字节, 累计写字节)」，走 NT 系统性能信息。 */
private fun readDiskIo(): Pair<Long, Long> {
    val module = LoadLibraryA("ntdll.dll") ?: return 0L to 0L
    try {
        val address = GetProcAddress(module, "NtQuerySystemInformation") ?: return 0L to 0L
        val query = address.reinterpret<CFunction<(Int, COpaquePointer?, Int, CPointer<DWORDVar>?) -> Int>>()
        return memScoped {
            val buffer = allocArray<ByteVar>(SYSTEM_PERFORMANCE_SIZE)
            val returned = alloc<DWORDVar>()
            // SystemPerformanceInformation = 2；成功返回 STATUS_SUCCESS(0)
            if (query(SYSTEM_PERFORMANCE_INFO_CLASS, buffer, SYSTEM_PERFORMANCE_SIZE, returned.ptr) != 0) {
                return@memScoped 0L to 0L
            }
            val raw = buffer.readBytes(SYSTEM_PERFORMANCE_SIZE)
            leU64(raw, SYSTEM_PERFORMANCE_IO_READ) to leU64(raw, SYSTEM_PERFORMANCE_IO_WRITE)
        }
    } finally {
        FreeLibrary(module)
    }
}

/** 小端 32 位取值。 */
private fun leU32(bytes: ByteArray, offset: Int): UInt {
    var value = 0L
    for (index in 0 until 4) value = value or ((bytes[offset + index].toLong() and 0xFFL) shl (8 * index))
    return value.toUInt()
}

/** 小端 64 位取值。 */
private fun leU64(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) value = value or ((bytes[offset + index].toLong() and 0xFFL) shl (8 * index))
    return value
}

/** 把物理地址（`bPhysAddr`，最多 [MIB_IFROW_PHYS_ADDR_MAX] 字节）折成一个去重用的 64 位键。 */
private fun readPhysAddr(bytes: ByteArray, offset: Int, len: Int): Long {
    var value = 0L
    for (index in 0 until len) value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
    return value
}

/** `MIB_IFTABLE` 头部（dwNumEntries）字节数。 */
private const val MIB_IFTABLE_HEADER: Int = 4

/** 单个 `MIB_IFROW` 的固定字节数。 */
private const val MIB_IFROW_SIZE: Int = 860

/** `MIB_IFROW.dwType` 在行内偏移。 */
private const val MIB_IFROW_TYPE: Int = 516

/** `MIB_IFROW.dwPhysAddrLen` 在行内偏移。 */
private const val MIB_IFROW_PHYS_ADDR_LEN: Int = 528

/** `MIB_IFROW.bPhysAddr` 在行内偏移。 */
private const val MIB_IFROW_PHYS_ADDR: Int = 532

/** `MAXLEN_PHYSADDR`，物理地址最大字节数。 */
private const val MIB_IFROW_PHYS_ADDR_MAX: Int = 8

/** `MIB_IFROW.dwInOctets` 在行内偏移。 */
private const val MIB_IFROW_IN_OCTETS: Int = 552

/** `MIB_IFROW.dwOutOctets` 在行内偏移。 */
private const val MIB_IFROW_OUT_OCTETS: Int = 576

/** `IF_TYPE_SOFTWARE_LOOPBACK`，回环网卡类型。 */
private const val IF_TYPE_SOFTWARE_LOOPBACK: UInt = 24u

/** `SystemPerformanceInformation` 类别号。 */
private const val SYSTEM_PERFORMANCE_INFO_CLASS: Int = 2

/** `SYSTEM_PERFORMANCE_INFORMATION` 缓冲大小（结构体约 312 字节，取 1024 留余量）。 */
private const val SYSTEM_PERFORMANCE_SIZE: Int = 1024

/** `SYSTEM_PERFORMANCE_INFORMATION.IoReadTransferCount` 偏移。 */
private const val SYSTEM_PERFORMANCE_IO_READ: Int = 8

/** `SYSTEM_PERFORMANCE_INFORMATION.IoWriteTransferCount` 偏移。 */
private const val SYSTEM_PERFORMANCE_IO_WRITE: Int = 16

/** `SystemProcessorPerformanceInformation` 类别号。 */
private const val SYSTEM_PROCESSOR_PERFORMANCE_CLASS: Int = 8

/** 单个 `SYSTEM_PROCESSOR_PERFORMANCE_INFORMATION` 的固定字节数（x64，含尾部对齐）。 */
private const val SYSTEM_PROCESSOR_INFO_SIZE: Int = 48

/** 逐核缓冲的核数上限。 */
private const val MAX_CPU_CORES: Int = 64
