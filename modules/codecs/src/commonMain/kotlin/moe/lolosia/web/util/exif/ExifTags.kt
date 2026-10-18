package moe.lolosia.web.util.exif

/**
 * EXIF 标签号 → 名字。
 *
 * 只收录**详情页会展示**的那些；没收录的按 `0xXXXX` 原样呈现 ——
 * 漏掉一个标签只是显示成编号，不影响任何筛选能力（可筛字段早已提升成 `media_asset` 的列）。
 *
 * @author 洛洛希雅Lolosia
 */
object ExifTags {

    private val IFD0: Map<Int, String> = mapOf(
        0x010E to "ImageDescription",
        0x010F to "Make",
        0x0110 to "Model",
        0x0112 to "Orientation",
        0x011A to "XResolution",
        0x011B to "YResolution",
        0x0128 to "ResolutionUnit",
        0x0131 to "Software",
        0x0132 to "DateTime",
        0x013B to "Artist",
        0x013E to "WhitePoint",
        0x8298 to "Copyright",
        0x8769 to "ExifIFDPointer",
        0x8825 to "GPSInfoIFDPointer",
    )

    private val EXIF: Map<Int, String> = mapOf(
        0x829A to "ExposureTime",
        0x829D to "FNumber",
        0x8822 to "ExposureProgram",
        0x8827 to "ISOSpeedRatings",
        0x9000 to "ExifVersion",
        0x9003 to "DateTimeOriginal",
        0x9004 to "DateTimeDigitized",
        0x9010 to "OffsetTime",
        0x9011 to "OffsetTimeOriginal",
        0x9012 to "OffsetTimeDigitized",
        0x9201 to "ShutterSpeedValue",
        0x9202 to "ApertureValue",
        0x9204 to "ExposureBiasValue",
        0x9205 to "MaxApertureValue",
        0x9207 to "MeteringMode",
        0x9209 to "Flash",
        0x920A to "FocalLength",
        0x9286 to "UserComment",
        0xA001 to "ColorSpace",
        0xA002 to "PixelXDimension",
        0xA003 to "PixelYDimension",
        0xA401 to "CustomRendered",
        0xA402 to "ExposureMode",
        0xA403 to "WhiteBalance",
        0xA404 to "DigitalZoomRatio",
        0xA405 to "FocalLengthIn35mmFilm",
        0xA406 to "SceneCaptureType",
        0xA408 to "Contrast",
        0xA409 to "Saturation",
        0xA40A to "Sharpness",
        0xA433 to "LensMake",
        0xA434 to "LensModel",
        0xA435 to "LensSerialNumber",
    )

    private val GPS: Map<Int, String> = mapOf(
        0x0000 to "GPSVersionID",
        0x0001 to "GPSLatitudeRef",
        0x0002 to "GPSLatitude",
        0x0003 to "GPSLongitudeRef",
        0x0004 to "GPSLongitude",
        0x0005 to "GPSAltitudeRef",
        0x0006 to "GPSAltitude",
        0x0007 to "GPSTimeStamp",
        0x001D to "GPSDateStamp",
    )

    /** 取标签名；未收录时回 `0xXXXX`。 */
    fun nameOf(group: String, tag: Int): String = when (group) {
        GROUP_EXIF -> EXIF[tag]
        GROUP_GPS -> GPS[tag]
        else -> IFD0[tag]
    } ?: "0x" + tag.toString(16).uppercase().padStart(4, '0')

    /** 自指的 JPEG 缩略图指针，解析器会直接跳过（我们不需要缩略图）。 */
    const val TAG_JPEG_INTERCHANGE_FORMAT: Int = 0x0201

    /** MakerNote：厂商私有、体积可能很大，一律不解析。 */
    const val TAG_MAKER_NOTE: Int = 0x927C

    const val GROUP_IFD0: String = "ifd0"
    const val GROUP_EXIF: String = "exif"
    const val GROUP_GPS: String = "gps"
    const val GROUP_IFD1: String = "ifd1"
}
