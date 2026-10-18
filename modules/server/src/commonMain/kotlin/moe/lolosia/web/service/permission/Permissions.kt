package moe.lolosia.web.service.permission

/**
 * 权限规则的解析与判定 —— **纯函数，无 IO，可单测**。
 *
 * 模式串语法（见 `doc/rbac-design.md` §3）：
 * - `*`：匹配一切；
 * - `a.b.c`：精确匹配同名的节点；
 * - `a.b.*`：匹配 `a.b` **自身及其后代**（`a.b.c`、`a.b.c.d`）；通配只能出现在**末尾整段**。
 *
 * 判定口径与 Bukkit 一致：命中的规则里**负号优先**，一条都没命中即**拒绝**。
 * 注意 `a.b` **不**蕴含 `a.b.c`（要覆盖后代就显式写 `a.b.*`）。
 *
 * @author 洛洛希雅Lolosia
 */
object Permissions {

    /** 单个节点段；节点整体为 `段(.段)*`。 */
    private const val SEGMENT = "[a-z0-9][a-z0-9_-]*"

    /** 合法模式：`*` 或 `段(.段)*(.*)?` —— 通配只允许出现在末尾整段。 */
    private val PATTERN = Regex("^(?:\\*|$SEGMENT(?:\\.$SEGMENT)*(?:\\.\\*)?)$", RegexOption.IGNORE_CASE)

    /** 解析规则原文（`+a.b.*` / `-a.b.c` / `a.b.c`，符号可省）；语法非法返回 `null`。 */
    fun parse(raw: String): PermissionRule? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val allow = text[0] != '-'
        val body = when (text[0]) {
            '+', '-' -> text.substring(1)
            else -> text
        }.trim().lowercase()
        if (!PATTERN.matches(body)) return null
        return PermissionRule(body, allow)
    }

    /** 校验并规范化原文（统一小写、补全符号）；非法返回 `null`。写入侧据此拒 400。 */
    fun canonical(raw: String): String? = parse(raw)?.text

    /**
     * 合并「用户直授」与「组」两个来源的规则原文。
     *
     * 顺序只影响展示，**不影响判定** —— [granted] 只看命中集合且负号优先。
     * 库里的串在写入时已校验过，这里 `mapNotNull` 只是兜底（坏数据不该让请求 500）。
     */
    fun merge(group: List<String>, direct: List<String>): List<PermissionRule> =
        (direct + group).mapNotNull { parse(it) }

    /** 单条规则是否命中节点。 */
    fun matches(rule: PermissionRule, node: String): Boolean {
        val pattern = rule.pattern
        val target = node.trim().lowercase()
        if (pattern == "*") return true
        if (pattern == target) return true
        if (!pattern.endsWith(".*")) return false
        val prefix = pattern.dropLast(2)
        return target == prefix || target.startsWith("$prefix.")
    }

    /** 一组规则是否放行节点：未命中拒绝；命中则负号优先。 */
    fun granted(rules: List<PermissionRule>, node: String): Boolean {
        val hit = rules.filter { matches(it, node) }
        if (hit.isEmpty()) return false
        return hit.none { !it.allow }
    }
}
