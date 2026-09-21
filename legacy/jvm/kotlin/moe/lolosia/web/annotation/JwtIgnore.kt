package moe.lolosia.web.annotation

/**
 * 标记接口无需认证（保留兼容，当前认证走路径白名单）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class JwtIgnore(val value: Boolean = true)
