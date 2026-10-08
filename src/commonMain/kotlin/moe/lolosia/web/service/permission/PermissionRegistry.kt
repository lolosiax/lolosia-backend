package moe.lolosia.web.service.permission

import kotlinx.serialization.Serializable

/**
 * 默认权限注册表（内嵌资源 `permissions.yaml`）。
 *
 * 登记内置组（键 = `sys_role.type`）的展示名、父组与**默认规则**：
 * - [Group.permissions] 只在「该组在 `sys_role_permission` 里没有任何规则」时生效（DB 优先）；
 * - [Group.extends] 声明父组，启动时同步进 `sys_role.parent_id`，判定时沿 `parent_id` 递归合并；
 * - 这里**不落任何数据**：规则永远由 DB 或本表二者之一提供，不做写回（管理界面保存才落库）。
 *
 * 表缺失或解析失败时由 [loadPermissionRegistry] 退化成空表，此时没有任何默认权限（默认拒绝）。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class PermissionRegistry(
    val groups: Map<String, Group> = emptyMap(),
) {
    /** 注册表里的一个组。 */
    @Serializable
    data class Group(
        /** 展示名，仅用于首次建组时填 `sys_role.role_name`。 */
        val title: String? = null,
        /** 父组 type（组继承）；空表示根基组。 */
        val extends: List<String> = emptyList(),
        /** 默认规则串。 */
        val permissions: List<String> = emptyList(),
    )

    /** 某组的默认规则；未登记的组返回空（即无默认权限）。 */
    fun permissionsOf(type: String?): List<String> = groupOf(type)?.permissions.orEmpty()

    /** 某组登记的父组 type；未登记的组返回空。 */
    fun parentsOf(type: String?): List<String> = groupOf(type)?.extends.orEmpty()

    private fun groupOf(type: String?): Group? = type?.let { groups[it.lowercase()] }
}
