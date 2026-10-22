package moe.lolosia.web.service.system

/**
 * 服务层查询小工具。
 *
 * @author 洛洛希雅Lolosia
 */

/**
 * 关键字 → `LIKE` 模式串：`null` / 空串 / 全空白都退化成 `%`（即「不过滤」）。
 *
 * 各仓库的 `findAllByKeys(keys, ...)` 收到 `%` 时等价于全量分页，与旧版
 * 「关键字为空就不加条件」的行为一致。
 */
internal fun likePattern(keys: String?): String = "%${keys?.trim().orEmpty()}%"
