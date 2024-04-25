package top.lolosia.web.annotation

/**
 * 指代当前属性在序列化导出时应使用的另一个指定属性
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class DMapTo(val target: String)
