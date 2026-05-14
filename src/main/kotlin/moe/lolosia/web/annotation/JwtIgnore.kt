package moe.lolosia.web.annotation

/**
 * 表示当前方法不需要进行Jwt验证
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class JwtIgnore(val value: Boolean = true)