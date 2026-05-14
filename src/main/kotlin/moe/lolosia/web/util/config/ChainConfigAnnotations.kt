package moe.lolosia.web.util.config

/**
 *  默认值
 *
 * @author 一七年夏
 * @since 2022-06-23 21:28
 */

@Target(AnnotationTarget.ANNOTATION_CLASS)
internal annotation class Default

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultInt(val value: Int)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultLong(val value: Long)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultString(val value: String)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultShort(val value: Short)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultDouble(val value: Double)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultChar(val value: Char)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultByte(val value: Byte)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultBoolean(val value: Boolean)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultIntArray(val value: IntArray)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultLongArray(val value: LongArray)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultStringArray(val value: Array<String>)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultShortArray(val value: ShortArray)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultDoubleArray(val value: DoubleArray)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultCharArray(val value: CharArray)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultBooleanArray(val value: BooleanArray)

@Default
@Target(AnnotationTarget.PROPERTY)
annotation class DefaultByteArray(val value: ByteArray)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ChainProp(
    /** 属性在配置文件中的名称 */
    val name: String = "",
    /** 若配置文件未保存此项设置，则写入此项设置 */
    val writeDefault: Boolean = true
)