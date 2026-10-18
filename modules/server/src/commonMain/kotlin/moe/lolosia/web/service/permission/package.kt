package moe.lolosia.web.service.permission

import com.charleskorn.kaml.Yaml
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import moe.lolosia.web.database.repository.SysRoleRepository
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.classpath.readText
import moe.lolosia.web.util.log.Log

/** `sys_role.type` 里的拥有者组代号：判定时视为拥有 `*`，且不可被 `-` 规则剥夺。 */
const val OWNER: String = "owner"

/** 拥有者的等价规则串，用于对外展示（`/api/permission/my` 等）。 */
const val OWNER_RULE: String = "+*"

/** 默认权限注册表在 classpath 里的路径。 */
const val PERMISSION_REGISTRY_RESOURCE: String = "permissions.yaml"

/** 注册表解析失败/缺失时只记日志，**不阻断启动** —— 退化成空表即「没有默认权限」（未命中即拒绝）。 */
private val log = Log.of("Permission")

/** 加载默认权限注册表；资源缺失或解析失败时返回空表。 */
fun loadPermissionRegistry(classpath: Classpath): PermissionRegistry {
    val text = classpath.readText(PERMISSION_REGISTRY_RESOURCE)
    if (text == null) {
        log.warn { "classpath 里没有 $PERMISSION_REGISTRY_RESOURCE，默认权限为空" }
        return PermissionRegistry()
    }
    return try {
        val registry = Yaml.default.decodeFromString(PermissionRegistry.serializer(), text.trimStart('\uFEFF'))
        log.info { "$PERMISSION_REGISTRY_RESOURCE 已加载：${registry.groups.size} 个内置组（${registry.groups.keys}）" }
        registry
    } catch (e: Exception) {
        log.warn { "$PERMISSION_REGISTRY_RESOURCE 解析失败，默认权限为空：${e.message}" }
        PermissionRegistry()
    }
}

/**
 * 把注册表声明的组继承同步进 `sys_role.parent_id`（幂等，每进程跑一次即可）。
 *
 * 只同步**继承关系**：不建组、也不写规则 —— 组由 `sql/sys_role.sql` 建（id 被代码引用），
 * 规则由 DB 或注册表在判定时提供（见 [PermissionRegistry]）。
 * 组在注册表里登记了但库里没有时只记 WARN，不改库（避免自增 id 与代码里的固定 id 错位）。
 */
suspend fun syncRegistryGroups(
    exec: QueryExecutor,
    registry: PermissionRegistry,
    roles: SysRoleRepository,
) {
    val byType = roles.findAllByDeletedFalse(exec).getOrThrow().associateBy { it.type?.lowercase() }
    for ((type, group) in registry.groups) {
        val role = byType[type]
        if (role == null) {
            log.warn { "注册表登记了组 $type，但 sys_role 里没有（检查 sql/sys_role.sql 是否已导入）" }
            continue
        }
        if (group.extends.size > 1) {
            log.warn { "组 $type 声明了多个父组 ${group.extends}；parent_id 只支持单个，取第一个" }
        }
        val parentType = group.extends.firstOrNull()
        val parent = parentType?.let { byType[it.lowercase()] }
        if (parentType != null && parent == null) {
            log.warn { "组 $type 的父组 $parentType 不存在，跳过继承同步" }
            continue
        }
        if (role.parentId != parent?.id) {
            roles.executeUpdateParentById(exec, role.id, parent?.id).getOrThrow()
            log.info { "组 $type 的父组已同步为 ${parent?.type ?: "(无)"}" }
        }
    }
}
