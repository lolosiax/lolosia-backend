package moe.lolosia.web.service.permission

/**
 * 一条权限规则。
 *
 * [pattern] 是**不含符号**的模式串（形如 `lolosia.user.*`），[allow] 决定符号（`true` → `+`，`false` → `-`）。
 * 只由 [Permissions.parse] 构造，因此 [pattern] 一定已规范化（小写、语法合法）。
 *
 * @author 洛洛希雅Lolosia
 */
data class PermissionRule(val pattern: String, val allow: Boolean) {
    /** 规范化原文：符号 + 模式。 */
    val text: String get() = (if (allow) "+" else "-") + pattern
}
