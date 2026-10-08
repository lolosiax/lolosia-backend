package moe.lolosia.web.util.exif

/**
 * 一条已解析的 EXIF / TIFF 项。
 *
 * 值统一以**字符串**保留（长尾字段只在详情页展示，不参与筛选），
 * 真正要用于 `WHERE` / `ORDER BY` 的字段已提升成 [ExifFields] 的强类型属性。
 *
 * @property group 所属 IFD：`ifd0` / `exif` / `gps`
 * @property tag 标签号
 * @property name 标签名（[ExifTags.nameOf]）
 * @property value 展示用文本（已截断）
 * @author 洛洛希雅Lolosia
 */
data class ExifEntry(
    val group: String,
    val tag: Int,
    val name: String,
    val value: String,
)
