package top.lolosia.web.annotation

/**
 * 表示属性属于决策系统运行过程中生成的
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class DResultValue()
