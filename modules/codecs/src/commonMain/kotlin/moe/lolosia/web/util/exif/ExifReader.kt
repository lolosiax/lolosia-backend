package moe.lolosia.web.util.exif

import moe.lolosia.web.util.log.Log
import kotlin.math.abs
import kotlin.time.Instant

/**
 * 自研 EXIF 解析器：从 JPEG / PNG / WebP（以及裸 TIFF）里取出拍摄元数据。
 *
 * 为什么自研：native 上没有 `javax.imageio`，而引 libexif / exiv2 要为三个目标各背一份静态库与
 * cinterop 绑定（exiv2 还是 C++，K/N 侧基本绑不了）。我们要的只是「取几十个标签 + 折算时间」，
 * 几百行 Kotlin 就够（见 `doc/media-index-design.md` §6.2）。
 *
 * **不可信输入口径**（§6.5）：所有偏移读取都先做范围校验，坏数据一律让解析返回 `null`，
 * 绝不因为一张构造过的图把进程带走；目录项数量与文本长度都有上限。
 *
 * 时区折算规则（§6.3）：优先用 `OffsetTimeOriginal`，其次 `OffsetTime`，
 * 都没有时用调用方传入的 [defaultOffsetMin]（配置项 `defaultTimezoneOffset`）；
 * 三者皆无才放弃 `takenAt`，但**墙上时间字符串永远保留**。
 *
 * @author 洛洛希雅Lolosia
 */
object ExifReader {

    /**
     * 解析 [bytes]（文件头即可，几百 KB 足够）。
     *
     * @param defaultOffsetMin EXIF 没写时区时的兜底偏移（分钟，+480 = UTC+8）；为 `null` 且 EXIF 也没写时，
     * `takenAt` 会是 `null`（墙上时间仍然保留在 [ExifFields.takenAtLocal]）
     * @return 没有 EXIF 或数据不可信时返回 `null`
     */
    fun read(bytes: ByteArray, defaultOffsetMin: Int? = null): ExifFields? {
        val base = findTiffBase(bytes) ?: return null
        return runCatching { TiffReader(bytes, base, defaultOffsetMin).parse() }
            .onFailure { log.warn(it) { "EXIF 解析失败（base=$base, 长度=${bytes.size}）" } }
            .getOrNull()
    }

    /** 找出 TIFF 头的绝对偏移（EXIF 的容器外壳有三种，识别成本很低）。 */
    private fun findTiffBase(b: ByteArray): Int? {
        if (b.size < 8) return null
        if (isTiffHeader(b, 0)) return 0
        if (b.u8(0) == 0xFF && b.u8(1) == 0xD8) return findJpegApp1(b)
        if (b.matches(0, PNG_SIGNATURE)) return findPngExif(b)
        if (b.matches(0, RIFF) && b.matches(8, WEBP)) return findWebpExif(b)
        return null
    }

    /** JPEG：从 SOI 之后逐段跳，找带 `Exif\0\0` 的 APP1。 */
    private fun findJpegApp1(b: ByteArray): Int? {
        var i = 2
        while (i + 4 <= b.size) {
            if (b.u8(i) != 0xFF) {
                i++
                continue
            }
            val marker = b.u8(i + 1)
            when {
                marker == 0xFF || marker == 0x00 -> {
                    i++
                    continue
                }

                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> {
                    i += 2
                    continue
                }
                // SOS 之后是压缩数据，不会再有 APP1
                marker == 0xDA -> return null
            }
            val len = b.u16be(i + 2)
            if (len < 2) return null
            if (marker == 0xE1 && b.matches(i + 4, EXIF_HEADER)) return i + 10
            i += 2 + len
        }
        return null
    }

    /** PNG：找 `eXIf` 块。 */
    private fun findPngExif(b: ByteArray): Int? {
        var i = 8
        while (i + 8 <= b.size) {
            val len = b.u32be(i)
            if (len < 0L || len > b.size) return null
            if (b.ascii(i + 4, 4) == "eXIf") return (i + 8).takeIf { isTiffHeader(b, it) }
            i += 12 + len.toInt()
        }
        return null
    }

    /** WebP：RIFF 下逐块跳，找 `EXIF` 块。 */
    private fun findWebpExif(b: ByteArray): Int? {
        var i = 12
        while (i + 8 <= b.size) {
            val fourcc = b.ascii(i, 4)
            val len = b.u32le(i + 4)
            if (len < 0L || len > b.size) return null
            if (fourcc == "EXIF") {
                var offset = i + 8
                if (b.matches(offset, EXIF_HEADER)) offset += 6
                return offset.takeIf { isTiffHeader(b, it) }
            }
            i += 8 + len.toInt() + (len.toInt() and 1)
        }
        return null
    }

    private fun isTiffHeader(b: ByteArray, offset: Int): Boolean {
        if (!b.inRange(offset, 8)) return false
        val order = b.ascii(offset, 2)
        if (order != "II" && order != "MM") return false
        val magic = if (order == "II") b.u16le(offset + 2) else b.u16be(offset + 2)
        return magic == 42
    }

    private val log = Log.of("Exif")
}

/** TIFF 的 IFD 走读器。一次性使用，不共享。 */
private class TiffReader(
    private val b: ByteArray,
    private val base: Int,
    private val defaultOffsetMin: Int?,
) {

    private var little: Boolean = true
    private val entries = ArrayList<ExifEntry>()

    private var make: String? = null
    private var model: String? = null
    private var lens: String? = null
    private var aperture: Double? = null
    private var exposureTime: String? = null
    private var iso: Int? = null
    private var focalLength: Double? = null
    private var orientation: Int? = null
    private var pixelWidth: Int? = null
    private var pixelHeight: Int? = null

    private var dateTimeOriginal: String? = null
    private var dateTimeIfd0: String? = null
    private var offsetOriginal: String? = null
    private var offsetAny: String? = null

    private var gpsLatRef: String? = null
    private var gpsLonRef: String? = null
    private var gpsLatDms: Triple<Double, Double, Double>? = null
    private var gpsLonDms: Triple<Double, Double, Double>? = null

    private var exifIfdPointer: Long? = null
    private var gpsIfdPointer: Long? = null

    fun parse(): ExifFields? {
        little = when (b.ascii(base, 2)) {
            "II" -> true
            "MM" -> false
            else -> return null
        }
        if (!b.inRange(base, 8) || u16(base + 2) != 42) return null
        parseIfd(u32(base + 4), ExifTags.GROUP_IFD0)
        exifIfdPointer?.let { parseIfd(it, ExifTags.GROUP_EXIF) }
        gpsIfdPointer?.let { parseIfd(it, ExifTags.GROUP_GPS) }
        return build()
    }

    /** 解析**一个** IFD（不跟随 next 链：IFD1 是缩略图，我们不要）。 */
    private fun parseIfd(offset: Long, group: String) {
        if (offset <= 0L || offset > MAX_OFFSET) return
        val start = base + offset.toInt()
        if (!b.inRange(start, 2)) return
        // IFD 的条目数是 2 字节 SHORT，其后紧跟 12 字节一条的目录项（TIFF 6.0 §2）
        val count = u16(start)
        if (count <= 0 || count > MAX_ENTRIES_PER_IFD) return
        for (i in 0 until count) {
            if (entries.size >= MAX_ENTRIES_TOTAL) return
            val entry = start + 2 + i * 12
            if (!b.inRange(entry, 12)) return
            val tag = u16(entry)
            val type = u16(entry + 2)
            val values = u32(entry + 4)
            val unit = typeSize(type)
            if (unit == 0 || values <= 0L || values > MAX_VALUES_PER_ENTRY) continue
            val total = unit.toLong() * values
            val valueAt = if (total <= 4L) {
                entry + 8
            } else {
                val pointer = u32(entry + 8)
                if (pointer > MAX_OFFSET) continue
                base + pointer.toInt()
            }
            if (!b.inRange(valueAt, total.toInt())) continue
            promote(group, tag, type, values.toInt(), valueAt, entry + 8)
            append(group, tag, type, values.toInt(), valueAt)
        }
    }

    /** 把我们要筛选 / 排序的字段提升成强类型属性。 */
    private fun promote(group: String, tag: Int, type: Int, count: Int, at: Int, inlineAt: Int) {
        when (group) {
            ExifTags.GROUP_IFD0 -> when (tag) {
                0x010F -> make = text(at, count)
                0x0110 -> model = text(at, count)
                0x0112 -> orientation = longAt(at, type)
                0x0132 -> dateTimeIfd0 = text(at, count)
                0x8769 -> exifIfdPointer = u32(inlineAt)
                0x8825 -> gpsIfdPointer = u32(inlineAt)
            }

            ExifTags.GROUP_EXIF -> when (tag) {
                0x829D -> aperture = rationalAt(at)
                0x829A -> exposureTime = rationalText(at)
                0x8827 -> iso = longAt(at, type)
                0x9003 -> dateTimeOriginal = text(at, count)
                0x9010 -> offsetAny = text(at, count)
                0x9011 -> offsetOriginal = text(at, count)
                0x920A -> focalLength = rationalAt(at)
                0xA002 -> pixelWidth = longAt(at, type)
                0xA003 -> pixelHeight = longAt(at, type)
                0xA434 -> lens = text(at, count)
            }

            ExifTags.GROUP_GPS -> when (tag) {
                0x0001 -> gpsLatRef = text(at, count)
                0x0002 -> gpsLatDms = dmsAt(at)
                0x0003 -> gpsLonRef = text(at, count)
                0x0004 -> gpsLonDms = dmsAt(at)
            }
        }
    }

    /** 长尾项：只作展示，全部转成截断后的文本。 */
    private fun append(group: String, tag: Int, type: Int, count: Int, at: Int) {
        if (tag == ExifTags.TAG_MAKER_NOTE) return
        val value = formatValue(type, count, at)
        if (value.isEmpty()) return
        entries.add(ExifEntry(group, tag, ExifTags.nameOf(group, tag), value))
    }

    private fun formatValue(type: Int, count: Int, at: Int): String {
        if (type == TYPE_ASCII) return text(at, count).orEmpty()
        val shown = minOf(count, MAX_VALUES_SHOWN)
        val unit = typeSize(type)
        val parts = ArrayList<String>(shown)
        for (i in 0 until shown) parts.add(scalarText(type, at + i * unit))
        val joined = parts.joinToString(",")
        return if (count > shown) "$joined,…" else joined
    }

    private fun scalarText(type: Int, at: Int): String = when (type) {
        TYPE_BYTE -> b.u8(at).toString()
        TYPE_SBYTE -> b.u8(at).toByte().toString()
        TYPE_SHORT -> u16(at).toString()
        TYPE_SSHORT -> u16(at).toShort().toString()
        TYPE_LONG -> u32(at).toString()
        TYPE_SLONG -> u32(at).toInt().toString()
        TYPE_RATIONAL, TYPE_SRATIONAL -> rationalText(at).orEmpty()
        else -> "0x" + u32(at).toString(16)
    }

    private fun build(): ExifFields {
        val parts = (dateTimeOriginal ?: dateTimeIfd0)?.let { exifDateParts(it) }
        val offsetMin = parseOffset(offsetOriginal ?: offsetAny) ?: defaultOffsetMin
        return ExifFields(
            cameraMake = make,
            cameraModel = model,
            lens = lens,
            aperture = aperture?.takeIf { it > 0.0 && it < 1000.0 },
            exposureTime = exposureTime,
            iso = iso?.takeIf { it > 0 },
            focalLength = focalLength?.takeIf { it > 0.0 && it < 10000.0 },
            orientation = orientation?.takeIf { it in 1..8 },
            takenAtLocal = parts?.let { localText(it) },
            takenAt = parts?.let { instantOf(it, offsetMin) },
            takenAtOffsetMin = offsetMin,
            pixelWidth = pixelWidth?.takeIf { it > 0 },
            pixelHeight = pixelHeight?.takeIf { it > 0 },
            gps = gps(),
            entries = entries,
        )
    }

    private fun gps(): Gps? {
        val latDms = gpsLatDms ?: return null
        val lonDms = gpsLonDms ?: return null
        val lat = dmsToDegrees(latDms) * if (gpsLatRef.equals("S", ignoreCase = true)) -1.0 else 1.0
        val lon = dmsToDegrees(lonDms) * if (gpsLonRef.equals("W", ignoreCase = true)) -1.0 else 1.0
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        // (0,0) 是「没有定位」而不是「几内亚湾」
        if (lat == 0.0 && lon == 0.0) return null
        return Gps(lat, lon)
    }

    private fun u16(at: Int): Int = if (little) b.u16le(at) else b.u16be(at)

    private fun u32(at: Int): Long = if (little) b.u32le(at) else b.u32be(at)

    /** ASCII 值：读到 NUL 为止，按 UTF-8 解码（有些相机在里面写中文）。 */
    private fun text(at: Int, count: Int): String? {
        if (count <= 0) return null
        val limit = minOf(count, MAX_TEXT_BYTES)
        var end = 0
        while (end < limit && b.u8(at + end) != 0) end++
        if (end == 0) return null
        return b.copyOfRange(at, at + end).decodeToString().trim().ifEmpty { null }
    }

    private fun rationalAt(at: Int): Double? {
        val numerator = u32(at)
        val denominator = u32(at + 4)
        if (denominator == 0L) return null
        return numerator.toDouble() / denominator.toDouble()
    }

    private fun rationalText(at: Int): String? {
        val numerator = u32(at)
        val denominator = u32(at + 4)
        if (denominator == 0L) return null
        return "$numerator/$denominator"
    }

    private fun longAt(at: Int, type: Int): Int? = when (type) {
        TYPE_BYTE -> b.u8(at)
        TYPE_SHORT -> u16(at)
        TYPE_LONG -> u32(at).toInt()
        else -> null
    }

    private fun dmsAt(at: Int): Triple<Double, Double, Double>? {
        val degrees = rationalAt(at) ?: return null
        val minutes = rationalAt(at + 8) ?: return null
        val seconds = rationalAt(at + 16) ?: return null
        return Triple(degrees, minutes, seconds)
    }

    private fun dmsToDegrees(dms: Triple<Double, Double, Double>): Double =
        dms.first + dms.second / 60.0 + dms.third / 3600.0

    private companion object {
        const val MAX_ENTRIES_PER_IFD: Int = 512
        const val MAX_ENTRIES_TOTAL: Int = 256
        const val MAX_OFFSET: Long = Int.MAX_VALUE.toLong()
        const val MAX_VALUES_PER_ENTRY: Long = 64
        const val MAX_VALUES_SHOWN: Int = 8
        const val MAX_TEXT_BYTES: Int = 256

        const val TYPE_BYTE: Int = 1
        const val TYPE_ASCII: Int = 2
        const val TYPE_SHORT: Int = 3
        const val TYPE_LONG: Int = 4
        const val TYPE_RATIONAL: Int = 5
        const val TYPE_SBYTE: Int = 6
        const val TYPE_UNDEFINED: Int = 7
        const val TYPE_SSHORT: Int = 8
        const val TYPE_SLONG: Int = 9
        const val TYPE_SRATIONAL: Int = 10
        const val TYPE_FLOAT: Int = 11
        const val TYPE_DOUBLE: Int = 12

        /** 单个值的字节宽度；0 表示未知类型（跳过）。 */
        fun typeSize(type: Int): Int = when (type) {
            TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1
            TYPE_SHORT, TYPE_SSHORT -> 2
            TYPE_LONG, TYPE_SLONG, TYPE_FLOAT -> 4
            TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8
            else -> 0
        }
    }
}

/** `yyyy-MM-dd HH:mm:ss` 的墙上时间（不折算时区）。 */
private fun localText(p: IntArray): String =
    "${pad(p[0], 4)}-${pad(p[1], 2)}-${pad(p[2], 2)} ${pad(p[3], 2)}:${pad(p[4], 2)}:${pad(p[5], 2)}"

/**
 * 墙上时间 + 偏移 → UTC 时刻。
 *
 * 超出 MySQL `TIMESTAMP` 可表示范围（1970-01-01 ~ 2038-01-19）时返回 `null`：
 * 老照片扫进库不该让整条插入失败，展示侧仍有 [localText]。
 */
private fun instantOf(p: IntArray, offsetMin: Int?): Instant? {
    if (offsetMin == null) return null
    val text = "${pad(p[0], 4)}-${pad(p[1], 2)}-${pad(p[2], 2)}T" +
        "${pad(p[3], 2)}:${pad(p[4], 2)}:${pad(p[5], 2)}${offsetSuffix(offsetMin)}"
    val instant = runCatching { Instant.parse(text) }.getOrNull() ?: return null
    return instant.takeIf { it.epochSeconds in 0L..MAX_TIMESTAMP_EPOCH_SECONDS }
}

/** `+08:00` 形式的时区后缀。 */
private fun offsetSuffix(minutes: Int): String {
    val sign = if (minutes < 0) "-" else "+"
    val abs = abs(minutes)
    return "$sign${pad(abs / 60, 2)}:${pad(abs % 60, 2)}"
}

/** `+08:00` / `+0800` / `Z` → 分钟数；认不出来返回 `null`。 */
private fun parseOffset(text: String?): Int? {
    val value = text?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value.equals("Z", ignoreCase = true)) return 0
    val sign = when (value[0]) {
        '+' -> 1
        '-' -> -1
        else -> return null
    }
    val digits = value.substring(1).replace(":", "").replace(".", "")
    if (digits.length < 4) return null
    val hours = digits.substring(0, 2).toIntOrNull() ?: return null
    val minutes = digits.substring(2, 4).toIntOrNull() ?: return null
    if (hours > 23 || minutes > 59) return null
    return sign * (hours * 60 + minutes)
}

/** `yyyy?MM?dd[ T]HH:mm:ss` → `[年, 月, 日, 时, 分, 秒]`；不可信就返回 `null`。 */
private fun exifDateParts(text: String): IntArray? {
    if (text.length < 19) return null
    val dateSep = text[4]
    if (dateSep != ':' && dateSep != '-' && dateSep != '/') return null
    if (text[7] != dateSep) return null
    if (text[10] != ' ' && text[10] != 'T') return null
    if (text[13] != ':' || text[16] != ':') return null
    // 年占 4 位（0..3），其余各占 2 位
    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val offsets = intArrayOf(5, 8, 11, 14, 17)
    val parts = IntArray(6)
    parts[0] = year
    for (i in offsets.indices) {
        parts[i + 1] = text.substring(offsets[i], offsets[i] + 2).toIntOrNull() ?: return null
    }
    if (parts[0] !in 1900..2100) return null
    if (parts[1] !in 1..12 || parts[2] !in 1..31) return null
    if (parts[3] !in 0..23 || parts[4] !in 0..59 || parts[5] !in 0..60) return null
    return parts
}

private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

/** 2037-12-31 23:59:59 UTC —— MySQL `TIMESTAMP` 的上界。 */
private const val MAX_TIMESTAMP_EPOCH_SECONDS: Long = 2_145_914_399L

private val EXIF_HEADER: ByteArray = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
private val PNG_SIGNATURE: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val RIFF: ByteArray = byteArrayOf(0x52, 0x49, 0x46, 0x46)
private val WEBP: ByteArray = byteArrayOf(0x57, 0x45, 0x42, 0x50)

/** 越界一律读 0：调用方都先做了 [inRange]，这里只是最后一道保险（输入是不可信字节）。 */
private fun ByteArray.u8(at: Int): Int = if (at in indices) this[at].toInt() and 0xFF else 0

private fun ByteArray.inRange(offset: Int, length: Int): Boolean =
    offset >= 0 && length >= 0 && offset.toLong() + length.toLong() <= size.toLong()

private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

private fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

private fun ByteArray.u32be(at: Int): Long =
    (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()

private fun ByteArray.u32le(at: Int): Long =
    u8(at).toLong() or (u8(at + 1).toLong() shl 8) or (u8(at + 2).toLong() shl 16) or (u8(at + 3).toLong() shl 24)

private fun ByteArray.ascii(at: Int, length: Int): String =
    if (!inRange(at, length)) "" else copyOfRange(at, at + length).decodeToString()

private fun ByteArray.matches(at: Int, pattern: ByteArray): Boolean {
    if (!inRange(at, pattern.size)) return false
    for (i in pattern.indices) {
        if (this[at + i] != pattern[i]) return false
    }
    return true
}
