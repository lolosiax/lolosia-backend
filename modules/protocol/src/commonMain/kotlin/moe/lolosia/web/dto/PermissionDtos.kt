package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 权限体系（组规则 / 用户直授 / 节点目录）的接口 DTO。
 *
 * 规则串语法见 `doc/rbac-design.md` §3：`[+/-]a.b.c`、`a.b.*`、`*`。
 * `save` 是**全量替换**语义（服务层先软删该主体的全部规则再插入），故提交的 `rules` 即最终态。
 *
 * @author 洛洛希雅Lolosia
 */

/** `@Permission` 标注出的权限节点目录（编译期扫描生成，供管理界面做权限选择器）。 */
@Serializable
data class PermissionCatalogEntry(
    val node: String,
    val title: String? = null,
    val controller: String? = null,
    val function: String? = null,
)

/** 某主体（组 / 用户）的规则原文列表。 */
@Serializable
data class PermissionRulesResult(val rules: List<String> = emptyList())

/** 当前用户的权限：生效规则 + 是否拥有者组（前端据此显隐元素）。 */
@Serializable
data class PermissionMyResult(
    val rules: List<String> = emptyList(),
    val owner: Boolean = false,
)

/** 组的规则查询 / 保存（`list` 请求可忽略 `rules`）。 */
@Serializable
data class RolePermissionReq(val roleId: Long, val rules: List<String> = emptyList())

/** 用户直授规则的查询 / 保存（`list` 请求可忽略 `rules`）。 */
@Serializable
data class UserPermissionReq(val userId: String, val rules: List<String> = emptyList())
