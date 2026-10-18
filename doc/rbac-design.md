# RBAC 权限体系设计：组 + 点分权限 + `@Permission`

> 状态：**P1–P6 已实现**（代码已入库；与本文的有意偏差见 §11）。
> 规范约定见 [AGENTS.md](../AGENTS.md)；现有 WebDAV / 部署事实见 [lolosia-backend-notes.md](./lolosia-backend-notes.md)。

---

## 0. 结论速览

1. **「角色」改称「组」**，物理上沿用 `sys_role` / `sys_user_roles`（`type` 列就是组的稳定代号），不新造一套表，前端/文案改称呼即可。
2. 组上挂**规则串**：`+a.b.c`（允许，`+` 可省）/ `-a.b.c`（拒绝）/ `+a.b.*`（通配）/ `+*`（全量）。用户还能被**直授**同样的规则串。
3. 判定遵循 **Minecraft / Bukkit 口径**：合并全部来源的规则 → 命中的规则里**负号优先** → 都没命中就**默认拒绝**。
4. 新增 `@Permission("a.b.c")` 注解（可标在函数或 `object` 上），由 `router-ksp` 在**生成路由时**插入一行校验调用；运行时用请求上下文里已缓存的权限集判定，未通过抛 `403`。
5. 新增两张表（`sys_role_permission` / `sys_user_permission`），**不引入任何视图**；规则一次取回后在 Kotlin 里按组合并。
6. `@Permission` 的**全量节点目录**由 KSP 从注解扫描生成（顺带带出 KDoc 首行当标题），供管理界面做「权限选择器」，避免手抄。
7. **组可以继承组**（`sys_role.parent_id`，内置链 `admin → user → guest`），判定时沿父指针递归合并祖先组的规则。
8. **内置组（owner / admin / user / guest）的默认权限登记在内嵌资源 [`permissions.yaml`](../modules/server/src/commonMain/resources/permissions.yaml)**：某组在库里一条规则都没有时用它兜底，库里一旦有规则就以库为准（§3.6）。

---

## 1. 现状（as-is）

### 1.1 数据

| 对象 | 说明 |
| --- | --- |
| `sys_role` | 组：`id` / `role_name`（展示名）/ `type`（稳定代号：`owner` / `admin` / `user` / `guest`）/ `parent_id`（父组，组继承） |
| `sys_user_roles` | 用户 ↔ 组绑定（表支持多绑定，代码只取第一条） |
| `view_user_role` | 绑定 + 用户 + 组 的联表视图（只读，**历史遗留**） |

> 组名在 P6 由「角色」语境迁移而来：`super_admin` → `owner`、`student` → `user`，历史遗留的 `teacher` 组已删除。
> 历史库用 [`sql/migrate_rbac_groups.sql`](../sql/migrate_rbac_groups.sql) 迁；新库直接看 [`sql/sys_role.sql`](../sql/sys_role.sql)。

仓库：`SysRoleRepository` / `SysUserRoleRepository` / `ViewUserRoleRepository`（实体见 `src/commonMain/kotlin/moe/lolosia/web/database/model/`）。

> `view_user_role` 是上古遗留：它只是三个表的 `JOIN`，没有任何计算。本方案**不再新增视图**（理由见 §4.3）。
> 存量这一个可以单独一轮替换成 `JOIN` 查询后删掉，但**不与本特性同一轮做** —— 它被 `UserInfo` / `UserService.list` /
> `UserRoleService.getByUserId` 三处用到，且必须先改代码、后删视图（顺序反了线上直接报错）。

### 1.2 代码

- 取当前用户与组：[`UserInfo`](../modules/server/src/commonMain/kotlin/moe/lolosia/web/util/session/UserInfo.kt) —— `role()` 取**第一条**绑定，`roleType()` / `isAdmin()` 都是**字符串比对**；`isOwner()` 走解析器判是否拥有者组。（`isTeacher()` / `isStudent()` 随 `teacher` / `student` 组消亡在 P6 删掉。）
- 鉴权：[`AuthInterceptor`](../modules/server/src/commonMain/kotlin/moe/lolosia/web/interceptor/AuthInterceptor.kt)（`@Interceptor(-3)`）**只判断「有没有登录」**：`/api/**` 默认要会话，白名单前缀直接放行。
- 路由：`@Controller` + `@GET/@POST/...` 由 [`RouterProcessor`](../modules/router-ksp/src/main/kotlin/moe/lolosia/router/ksp/RouterProcessor.kt) 编译期生成 `generatedRoutes()`。

### 1.3 前端

- `permissionMode: 'roles'`（前端 `src/settings.ts`）；路由守卫 `Promise.all([getMyInfo(), getMyRole()])` 后 `roles = [userRole.roleType]`（前端 `src/permission.ts`）。
- 元素级控制用 `v-roles-permission="['admin']"`（前端 `src/directives/roles-permission.ts`），路由用 `filterRouters(routes, role)` 按 `meta.roles` 过滤。
- `/api/menu/config` 目前固定回 `{"default": true}`。

### 1.4 缺什么

**授权粒度只到「登录」**。任何有效会话都能调 `/api/user/delete`、`/api/role/destroy`；`isAdmin()` 这类判断散落在业务代码里且只认字符串，既不能表达「能调哪个接口」，也没有「拒绝」的表达能力。

---

## 2. 目标与非目标

### 目标

- 组承载 `±通配` 规则；用户可直授规则；两者合并判定。
- 组可**继承父组**（`sys_role.parent_id`），继承链上的规则一并参与判定。
- 内置组的**默认权限**由内嵌资源 `permissions.yaml` 声明，库为空时兜底生效。
- 每个接口可声明所需权限，未声明的不受影响（保持现状）。
- 判定是**数据驱动**的：加/改权限不改代码、不重启（除 `@Permission` 声明与 `permissions.yaml` 需要重新编译）。
- 复用既有实体/仓库/拦截器骨架，不引入反射、不引入新依赖。

### 非目标（v1 明确不做）

- **对象级授权**（「只能改自己的文件」这类数据级约束）——属于另一层，见 §9。
- 权限的**资源维度**（如按 `fileId` 授权）——不做。
- WebDAV（`/dav`）走 HTTP Basic、路径不在 `/api` 下，**不纳入**本体系（要控制就另立规则）。
- **不新增任何数据库视图**（存量 `view_user_role` 的处置另议，见 §1.1 / §4.3）。
- 组继承**只支持单父组**（`parent_id` 单列）；`permissions.yaml` 里 `extends` 写了多个时取第一个并记 WARN。

---

## 3. 权限模型

### 3.1 节点（node）

```
node := segment ( "." segment )*
segment := [A-Za-z0-9_-]+
```

约定语义为 `域.模块.动作` 三段（`域` 用产品名 `lolosia`，与域名无关），例如：

```
lolosia.user.read
lolosia.user.write
lolosia.role.permission
lolosia.oss.file.read
lolosia.files.read
```

节点**大小写不敏感**（统一按小写比较），存库保留原文。

### 3.2 规则（rule）

一条规则 = 可选符号 + 节点，**允许通配**：

```
rule := ( "+" | "-" | "" ) pattern
pattern := node | node ".*" | "*"
```

- `+` / 省略 → **允许**；`-` → **拒绝**。
- `a.b.*` 匹配 `a.b` 自身**及**其后代（`a.b.c`、`a.b.c.d`）。
- `*` 匹配一切。
- 通配只能出现在**末尾整段**（不接受 `a.*.c`）——表达能力够用，且让匹配与排序都可判定。

> 与 Bukkit 的差别：Bukkit 里 `a.b` 不会自动蕴含 `a.b.c`，本项目保持同样口径 —— 要覆盖后代就显式写 `a.b.*`。

### 3.3 匹配

```kotlin
fun matches(rule: String, node: String): Boolean = when {
    rule == "*" -> true
    rule == node -> true
    rule.endsWith(".*") -> {
        val prefix = rule.dropLast(2)                 // "a.b.*" -> "a.b"
        node == prefix || node.startsWith("$prefix.")
    }
    else -> false
}
```

### 3.4 判定（deny-wins）

把**用户直授规则 + 组链上（所属组与其全部祖先组）的规则**合并成一个集合（来源只用于管理和审计，不参与优先级），然后：

```kotlin
fun granted(rules: List<PermissionRule>, node: String): Boolean {
    val hit = rules.filter { matches(it.pattern, node) }
    if (hit.isEmpty()) return false          // 默认拒绝
    if (hit.any { !it.allow }) return false  // 负号优先
    return true
}
```

要点：

- **负号优先**是全局的：组 A 给 `+lolosia.user.*`、组 B 给 `-lolosia.user.delete`，则 `delete` 被拒 —— 与 Bukkit 一致，可预期。
- 多个 `@Permission` 值之间是 **OR**（满足任一即放行）；需要 AND 就在业务里显式判两次（v1 不引入 `mode`）。
- **未命中即拒绝**：新接口标了 `@Permission` 但没人被授权时，只有 `owner` 组能进（见 §3.5），其余一律 403 —— 这是「默认安全」的方向，代价是灰度期要给存量组补规则（见 §8）。

### 3.5 `owner` 兜底

所属组链里含 `type == "owner"` 的用户，**直接视为拥有 `*`**，且**不可被 `-` 规则剥夺**。

- 理由：避免「管理员误删 `+*` 规则把自己锁死」这类事故；与 [`RoleService`](../modules/server/src/commonMain/kotlin/moe/lolosia/web/service/system/RoleService.kt) 里「拥有者组不可删除」的既有约定一致。
- 这条兜底**写死在判定的最前面**，不依赖数据（常量 `service.permission.OWNER`）。
- 该组旧称 `super_admin`（P6 改名），`sys_role.type` 一并改过，代码里不再有 `super_admin` 字样。

### 3.6 默认权限注册表与组继承

**注册表**是一份内嵌资源 [`permissions.yaml`](../modules/server/src/commonMain/resources/permissions.yaml)，按 `sys_role.type` 登记内置组的展示名、父组与默认规则：

```yaml
groups:
  owner: { title: 拥有者, permissions: ["+*"] }
  admin: { title: 管理员, extends: [user], permissions: ["+lolosia.*"] }
  user:  { title: 普通用户, extends: [guest], permissions: ["+lolosia.user.read", "+lolosia.oss.file.write"] }
  guest: { title: 访客, permissions: ["+lolosia.oss.file.read"] }
```

（实际文件是等价的展开写法，含逐条注释。）

> 在线文件管理器的 `lolosia.files.read`（机械盘浏览）与 `lolosia.files.write`（新建 / 改名 / 删除 / 上传）就是被 `admin` 的 `+lolosia.*` 覆盖到的，`user` / `guest` 都拿不到 ——
> 这正是因为节点放在 `lolosia.files.*` 而不是 `lolosia.oss.*`：OSS 是「用户自己的云存储」，机械盘不是，两者的授权对象不同。

**两条来源的优先级**（这是「默认」二字的全部含义）：

| 来源 | 何时生效 |
| --- | --- |
| `sys_role_permission` 里该组的规则 | 只要有**一条**（`deleted = 0`），就**完全以库为准**，注册表对该组不再参与 |
| `permissions.yaml` 的 `permissions` | 仅当该组在库里**一条规则都没有**时兜底 |

- 运营在管理界面保存过某组的规则 → 库里就有了 → 之后改 YAML 也不再影响它（不会被覆盖）。
- `/api/role/permission/list` 返回的是**生效值**（库为空时就是注册表默认值），所以界面不会出现「显示为空、实际有权限」的错位。
- YAML 里显式写 `+`，与库里存法一致（界面看到的形态统一）。

**组继承**：

- `extends` 在**启动时**由 [`syncRegistryGroups`](../modules/server/src/commonMain/kotlin/moe/lolosia/web/service/permission/package.kt) 同步进 `sys_role.parent_id`（幂等，只改继承、不建组、不写规则）。
- 运行时沿 `parent_id` 递归展开组链（用已访问组 id 收敛，成环不会死循环），链上每个组各自套用上面那张优先级表。
- 内置链：`admin → user → guest`；`owner` / `guest` 是根基组。
- 建组本身仍在 `sql/sys_role.sql`（id 被代码引用），注册表只按 `type` 认领已有组。

---

## 4. 数据模型

### 4.1 「组」= 现有 `sys_role`

不新造表：`sys_role.type` 就是组的稳定代号，`role_name` 是展示名，P6 新增的 `parent_id` 承载组继承。改动仅限于**文案与语义**（前端「角色」→「组」）与一列。

> 若日后坚持物理改名（`sys_role` → `sys_group`、`sys_user_roles` → `sys_user_groups`），那是一次纯搬运迁移：改仓库 SQL + 实体 `@Table` + 现有 DTO 字段名（外加存量 `view_user_role` 的处置），**与本方案不冲突**，但不建议和权限特性同一轮做。

### 4.2 新表：组规则 / 用户直授

```sql
-- 组上挂的规则（sys_role 的一条组 → N 条规则）
DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `role_id` int(11) NOT NULL,
  `permission` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_role` (`role_id`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- 用户直授的规则
DROP TABLE IF EXISTS `sys_user_permission`;
CREATE TABLE `sys_user_permission` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `permission` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user` (`user_id`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;
```

**为什么两张表而不是一张 `(subject_type, subject_id, permission)`**：两张表各自带真实外键语义、索引更直、SQL 更好读；且「用户直授」的删除/覆盖操作与「组规则」完全独立（组规则改动不该影响任何用户的直授）。代价只是解析时多一条小查询。

### 4.3 数据访问：两条查询，不用视图

「组 → 规则」与「用户直授」各由自己的仓库负责，都是普通 `@Query` 单语句：

```sql
-- 判定用：全部生效规则一次取回（SysRolePermissionRepository.findAllByDeletedFalse）
-- 组继承后组数不固定，逐组查询会变成 N 次往返；表本身很小，取回后在 Kotlin 里按 role_id 分组
select * from sys_role_permission where deleted = 0 order by id asc;

-- 某组自身的规则（管理界面回填，SysRolePermissionRepository.findAllByRoleId）
select * from sys_role_permission where role_id = :roleId and deleted = 0 order by id asc;

-- 该用户直授的规则（SysUserPermissionRepository.findAllByUserId）
select * from sys_user_permission where user_id = :userId and deleted = 0 order by id asc;
```

组链（绑定组 + 沿 `sys_role.parent_id` 递归的祖先组）由 `SysRoleRepository.findAllByDeletedFalse` 一次取回后在 Kotlin 里走：

```kotlin
// PermissionResolver.groupsOf：用「已访问组 id」收敛，父子成环也不会死循环
// PermissionResolver.rulesOfUser：链上每个组各取「库里的规则」或「注册表默认值」，再与直授合并
```

**为什么不建视图**：

- **视图的 `DEFINER` 是运维债**：跨账号 / 跨库迁移就失效。本次把线上库搬到本地已经踩过一次 —— `view_user_role` 带着 `DEFINER=lolosia@%`，本地没这个账号，查它直接 `ERROR 1446`。新建视图等于把这份债再欠一次。
- **视图藏不住复杂度**：它只是 `JOIN`，写成仓库里的 `@Query` 一样是单语句、一样吃索引，还省掉「谁有权建 / 改视图」的授权问题。
- **`@Query` 的返回就是实体类型**，用视图则要额外造一个「不落在任何表上」的实体。
- **递归的父子链本来也写不成简单视图**，最终仍要在 Kotlin 里展开。
- 代价只有**一次本地回环的往返**，而且**只有带 `@Permission` 的接口**才会走这段（§5.2）。

### 4.4 校验（写入侧）

- `permission` 串在**服务层**用正则校验（§3.2 语法 + 长度 ≤ 255），不合法直接 `400`；不允许把任意字符串塞进库（否则匹配器会静默漏判）。
- 去重：同一主体下**同一串只允许一条**（服务层查重后拒绝，或 `save` 全量替换时天然去重）。
- 组规则用**全量替换**语义（`save` = 先软删该组的全部规则，再插入新列表，放同一事务）——比逐条 diff 简单且不会产生「删除一个 `+` 却留下同名 `-`」的歧义。

---

## 5. 服务端实现

### 5.1 代码落点

| 位置 | 内容 |
| --- | --- |
| `resources/permissions.yaml` | **默认权限注册表**（内置组的展示名 / 父组 / 默认规则，见 §3.6） |
| `sql/sys_role.sql` | 组表：加 `parent_id` 列 + 内置四组（`owner`/`admin`/`user`/`guest`，含继承链） |
| `sql/sys_role_permission.sql` / `sys_user_permission.sql` | 建表（**无视图**，**不灌种子** —— 默认值在 YAML 里） |
| `sql/migrate_rbac_groups.sql` | 历史库一次性迁移（改名、删 `teacher`、加列、落继承、清旧种子） |
| `database/model/SysRoleEntity.kt` | 加 `parentId` |
| `database/model/SysRolePermissionEntity.kt` / `SysUserPermissionEntity.kt` | 实体（`@Table`，`permission` 列 → `permission` 字段） |
| `database/repository/SysRoleRepository.kt` | 加 `parent_id` 读写（`executeInsert` / `executeUpdateById` / `executeUpdateParentById`） |
| `database/repository/SysRolePermissionRepository.kt` / `SysUserPermissionRepository.kt` | 仓库（`@Repository` + `@Injectable`，全 `@Query` 单语句） |
| `service/permission/Permissions.kt` | **纯函数**：解析 + 匹配 + 判定（可单测，无 IO） |
| `service/permission/PermissionRegistry.kt` + `package.kt` | 注册表类型、加载器 `loadPermissionRegistry`、继承同步 `syncRegistryGroups` |
| `service/permission/PermissionResolver.kt` | **判定侧解析**：组链展开、注册表兜底、`isOwner` |
| `service/permission/PermissionService.kt` | **管理侧读写**：组规则 / 用户直授 / 当前用户 / （复用解析器） |
| `util/session/Context.kt` | 加 `suspend fun hasPermission(vararg nodes: String): Boolean` |
| `util/session/UserInfo.kt` | 加 `roles()`（全部绑定）、`permissionRules()`（一次请求只查一次）、`isOwner()` |
| `util/ktor/ApplicationCallExt.kt` | `suspend fun ApplicationCall.requirePermission(vararg nodes: String)` |
| `util/router/Annotations.kt` | 新增 `@Permission` |
| `router-ksp/RouterProcessor.kt` | 读注解 → 在生成的路由体首行插入校验；顺手收集目录 |
| `configuration/AppModule.kt` / `AppConfigure.kt` | 注册表进 Koin；连库后触发继承同步 |
| `controller/system/PermissionController.kt` | 管理接口 + `/permission/my` + 目录 |
| `generated/GeneratedRegistries.kt` | 新增 `expect fun generatedPermissions(): List<PermissionCatalogEntry>` |

### 5.2 权限解析与缓存

```kotlin
// UserInfo：一次请求只查一次（与 entity()/role() 的既有写法一致），解析委托给 PermissionResolver
private var rulesLoaded = false
private var cachedRules: List<PermissionRule> = emptyList()

suspend fun permissionRules(): List<PermissionRule> {
    if (!rulesLoaded) {
        cachedRules = resolver.rulesOfUser(ctx.db, id)   // 组链 + 注册表兜底 + 用户直授
        rulesLoaded = true
    }
    return cachedRules
}

suspend fun isOwner(): Boolean = resolver.isOwner(ctx.db, id)
```

```kotlin
// Context
suspend fun hasPermission(vararg nodes: String): Boolean {
    if (user.isOwner()) return true
    val rules = user.permissionRules()
    return nodes.any { Permissions.granted(rules, it) }
}
```

- **v1 不跨请求缓存**：一次请求最多**三条**小查询（全部组规则 + 用户直授 + 组表用于展开继承），且只有带 `@Permission` 的接口才会走。改权限即时生效（下一请求就变），没有失效问题。
- 若日后 QPS 起来，再加「进程级缓存 + `sys_role_permission`/`sys_user_permission` 的 `updated_at` 版本号」，属于纯优化，不改判定语义。

### 5.3 `@Permission` 注解

```kotlin
/**
 * 声明接口所需权限；多个值是 **OR**（满足任一即放行）。
 * 标在 object 上作默认值，函数上的声明覆盖 object 的。
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Permission(vararg val value: String)
```

**校验点放在生成的路由体里**（不是再加一层全局拦截器）：

```kotlin
// generatedRoutes() 里由 KSP 生成
post("/user/create") {
    call.requirePermission("lolosia.user.write")   // 未通过 → 403
    val req: UserCreateReq = call.receive()
    call.respond(UserController.create(call.inject(), call.inject(), req))
}
```

```kotlin
suspend fun ApplicationCall.requirePermission(vararg nodes: String) {
    val ctx = attributes.getOrNull(ContextKey) ?: throw ApiException(500, "请求上下文缺失")
    if (!ctx.hasPermission(*nodes)) throw ApiException(403, "没有访问该接口的权限")
}
```

为什么不用全局拦截器（靠路径 → 权限映射表）：那等于**把路由表抄第二遍**，必然漂移。注解与路由声明在同一处、由同一个处理器生成，**唯一的真相源**。

### 5.4 与现有 `AuthInterceptor` 的关系

| 层 | 职责 | 不改 |
| --- | --- | --- |
| `AuthInterceptor`（`-3`） | 「**是否登录**」；`/api/**` 默认保护 + 白名单 | ✅ 保留 |
| `@Permission`（路由级） | 「**这个接口你能不能调**」 | 新增 |

- 白名单只免「没登录就 401」，**不免权限**：白名单里若标了 `@Permission`，会因无会话而判失败 → 403。现有 `/api/user/avatar`（PUT，白名单内）就是这种「自己校验登录态」的例子，做法保持。
- 返回码：**未登录 401 / 已登录但无权限 403**。二者都经 `StatusPages` 走信封 `{code,msg,data}`，前端 `request.ts` 对 401 清登录态、对 403 只弹 `msg`（现有逻辑已如此）。

### 5.5 审计与操作者

改动权限的两条链路都把操作者记进审计列（`created_by` / `updated_by`），由控制器显式传 `ctx.userId` —— 与 AGENTS.md §4 的约定一致，服务层不从 `ctx.userId` 隐式取。

---

## 6. 接口契约

> 路径与形状**新增即定**（前端暂无对应实现，本方案即契约）；已存在接口的路径与出参**不改**。

### 6.1 组的规则

```
POST /api/role/permission/list   { "roleId": 2 }                    → { "rules": ["+lolosia.user.*", "-lolosia.user.delete"] }
POST /api/role/permission/save   { "roleId": 2, "rules": [...] }    → EmptyResult      // 全量替换，事务内
```

### 6.2 用户直授

```
POST /api/user/permission/list   { "userId": "<uuid>" }             → { "rules": [...] }
POST /api/user/permission/save   { "userId": "<uuid>", "rules": [...] } → EmptyResult
```

### 6.3 当前用户 + 目录

```
POST /api/permission/my       → { "rules": [...], "owner": false }
POST /api/permission/catalog  → [ { "node": "lolosia.user.write", "title": "新建用户", "controller": "UserController", "function": "create" }, … ]
```

- `/api/permission/my` 也可**并入现有** `/api/user/myRole` 的返回（加一个 `rules` 字段），这样前端守卫的 `Promise.all` 不用加第三次请求。**建议并入**（改动更小），`/api/permission/my` 作为可选别名保留给非前端调用方。
- `catalog` 的内容来自 KSP 扫描全部 `@Permission`（节点 + KDoc 首行 + 所在控制器/函数），**编译期生成**，不会与代码漂移。

### 6.4 命名规范与现有接口的初始映射

`域.模块.动作`，`域` = `lolosia`：

| 接口 | 节点 |
| --- | --- |
| `/api/user/list`、`/searching`、`/get`、`/myInfo`、`/myRole` | `lolosia.user.read` |
| `/api/user/create`、`/edit`、`/delete` | `lolosia.user.write` |
| `/api/user/avatar`（PUT）、`/avatar/get/{id}` | `lolosia.user.avatar` |
| `/api/role/list`、`/queryRoleByPage` | `lolosia.role.read` |
| `/api/role/create`、`/update`、`/destroy` | `lolosia.role.write` |
| `/api/role/permission/*` | `lolosia.role.permission` |
| `/api/user/permission/*` | `lolosia.user.permission` |
| `/api/permission/*` | `lolosia.permission.read` |
| `/api/oss/list`、`/getMetas`、`/fileCheck` | `lolosia.oss.file.read` |
| `/api/oss/upload` | `lolosia.oss.file.write` |
| `/api/oss/get/{user}/{md5}`、`/show/*`（取件，白名单） | 不加注解（匿名可取） |
| `/api/files/{list,dirs,detail,scan}` | `lolosia.files.read` |
| `/api/files/{mkdir,rename,delete,upload}` | `lolosia.files.write` |
| `/api/files/{thumb,raw}`（直链，白名单） | 不加注解（控制器校验 URL 签名 `exp` + `sig`） |
| `/api/classpath/*`（运维探针） | `lolosia.system.classpath` |
| `/api/menu/config` | 不加（前端守卫必经） |
| `/api/login` `/logout` `/captcha` `/verify` `/register` `/sse*` | 不加（登录态之前 / 长连接） |

**`myInfo` / `myRole` 标 `lolosia.user.read` 的风险**：如果某个组被配成「连 `read` 都没有」，前端守卫会拿不到用户信息而进 500 页。故这两条**建议不加注解**（只要求登录即可），其余按表标注。

---

## 7. 前端影响（backend 只定契约，改动在前端工程）

1. `basicStore` 加 `permissionRules: string[]`（来自 `myRole.rules`），并持久化无必要（随会话走）。
2. 新增 `v-permission="'lolosia.user.write'"` 指令：与 `roles-permission.ts` 同构，但用**同一套匹配函数**（前端也要实现 §3.3 的 20 行匹配器；口径必须与后端一致）。
3. `permissionMode` 由 `'roles'` 演进为 `'permission'`：路由 `meta.roles` → `meta.permissions`，`filterRouters` 改用权限集过滤。
4. 「用户管理」编辑弹窗加「直授权限」编辑区；「角色管理」页改称「组管理」，加规则编辑器（`catalog` 做候选，手输兜底）。

> 后端是**权威**：前端隐藏按钮只是体验，越权请求一律 403。

---

## 8. 迁移与灰度

### 8.1 迁移步骤（不破坏现网）

1. 建两张表（`sql/` 新增 2 个脚本，**不涉及视图**，导出 / 导入也不会有 definer 问题）。
2. **默认权限**不改 SQL，改内嵌资源 `permissions.yaml`（§3.6）；历史库跑一次 `sql/migrate_rbac_groups.sql`（改名 `super_admin→owner` / `student→user`、删 `teacher`、加 `parent_id`、落继承、清掉旧种子让 YAML 生效）。
3. 代码侧：`Permissions` + `PermissionRegistry` + `PermissionResolver` + `Context.hasPermission` + `requirePermission` + 注解 + 生成器 + 管理接口。
4. 给接口**增量**补注解：先标高危写接口（`user.write` / `role.write` / `role.permission` / `user.permission`），再标读接口。
5. 前端跟进（§7）。

### 8.2 兼容

- 未标 `@Permission` 的接口 → 行为与今天**完全一致**（只要求登录）。
- `UserInfo.isAdmin()` 保留不动（存量业务判断继续可用），新代码一律走 `hasPermission`；`isTeacher()` / `isStudent()` 随对应组消亡已删除。
- `UserInfo.role()` 保留（取第一条），新增 `roles()` 供权限合并；**多绑定**因此可用而不改现有契约。

### 8.3 分阶段

| 阶段 | 内容 | 可独立验证 |
| --- | --- | --- |
| P1 | 建表/实体/仓库 + `Permissions` 纯函数 + 测试向量 | 直接跑匹配器单测（本项目无测试框架，用一次性冒烟脚本，见 development-notes） |
| P2 | `Context.hasPermission` + `/api/permission/my` | 接口返回规则集 |
| P3 | `@Permission` + KSP 校验 + catalog 生成 | 未授权 403、`owner` 通 |
| P4 | 管理接口（组规则 / 用户直授 / catalog）+ 前端契约 | 改规则后下一请求生效 |
| P5 | 存量接口补注解 | 逐接口回归 |
| P6 | 组 → `owner/admin/user/guest`、`parent_id` 组继承、`permissions.yaml` 默认注册表、OSS 权限下放到 `user` | 组链继承生效、清空规则后回落到注册表默认 |

---

## 9. 开放问题与备选方案

1. **判定优先级**：本方案是「合并 + 负号优先」（Bukkit 口径）。备选是「**按特异性**（exact > `a.b.*` > `*`）+ 用户直授 > 组」（LuckPerms 口径），好处是 `+*` 不会压过更细的 `-x.y.z`。取舍：本方案的规则更少、更好解释；若运营上出现「管理员想精细收回某个权限」的诉求，再切到备选（判定函数是纯函数，切换成本低）。
2. **多组**：表已支持多绑定，本方案也按「合并全部组」实现；但用户管理界面目前是**单选**。要不要开多选，是产品决策，不影响数据模型。
3. **组继承**：**P6 已落地** —— `sys_role.parent_id` 单父指针 + 解析时递归展开（用已访问 id 集合防环）。若要「一个组继承多个父组」，得再引入关联表，属于另一种模型，目前不需要。
4. **对象级授权**：如「只能改自己上传的文件」。建议不要塞进本体系，另做「所有权校验」的服务层断言。
5. **缓存**：v1 每请求最多三条小查询；后续可加进程级 TTL 缓存，失效用 `updated_at` 版本号。
6. **`@Permission` 的 AND 语义**：v1 只做 OR。若确有「同时要 A 和 B」的场景，再加 `mode` 属性（默认 OR），并同步改生成器。

---

## 10. 附录：匹配器测试向量

| 规则 | 节点 | 期望 |
| --- | --- | --- |
| `*` | `lolosia.user.write` | ✅ |
| `+lolosia.*` | `lolosia.role.destroy` | ✅ |
| `lolosia.user.*`（省略 `+`） | `lolosia.user` | ✅ |
| `lolosia.user.*` | `lolosia.userx.write` | ❌（段边界，非前缀字符串） |
| `lolosia.user.read` | `lolosia.user.read` | ✅ |
| `lolosia.user.read` | `lolosia.user.read.all` | ❌（不蕴含后代） |
| `[+lolosia.user.*, -lolosia.user.delete]` | `lolosia.user.delete` | ❌（负号优先） |
| `[-lolosia.user.delete]` | `lolosia.user.read` | ❌（未命中 → 默认拒绝） |
| `[]`（无任何规则） | `lolosia.user.read` | ❌ |
| 任一规则 | 任意节点（`owner`） | ✅（§3.5 兜底） |
| `lolosia.*.write`（非法：通配在中间） | — | 写入时 `400` |

---

## 11. 实现落点与偏差（已落地）

与上面设计有意偏差的地方：

| 设计 | 实现 | 原因 |
| --- | --- | --- |
| §5.2 的 `PermissionRule` 带 `source`（GROUP / USER） | 只有 `pattern` + `allow` | 来源不参与判定，展示侧由「读哪个仓库」天然区分，不必进模型 |
| §8.1 种子给 `teacher` / `student` 加 `+lolosia.user.avatar` | 已去掉 | 头像接口在鉴权白名单里（`<img src>` 带不了请求头），**没有**也不该有 `@Permission`，该节点不在目录里 |
| §4.3 原先那条「按用户 JOIN 组规则」的单查询 | P6 换成 `findAllByDeletedFalse` 全量取回 + Kotlin 按 `role_id` 分组 | 组继承后组数不固定，逐组查会变 N 次往返；表本身很小。原 JOIN 版还踩过 sqlx4k 的「`@Query` 主表必须与实体 `@Table` 一致」约束（已补进 AGENTS.md §5），换成全表取回后这条约束不再出现在本特性里 |
| §3.6 的 `extends` 是列表 | 落库只取**第一个**父组，多值时记 WARN | `sys_role.parent_id` 是单列（见 §2 非目标） |
| §2 原写「组继承 v1 不做」 | P6 做了 | 需求来了：OSS 权限要下放到 `user`，而 `admin` 不该重复声明 `user` 已有的一切 |

| 层 | 文件 |
| --- | --- |
| 注册表 | `resources/permissions.yaml`、`service/permission/PermissionRegistry.kt`、`service/permission/package.kt`（`OWNER` / `loadPermissionRegistry` / `syncRegistryGroups`） |
| SQL | `sql/sys_role.sql`（含 `parent_id` 与内置四组）、`sql/sys_role_permission.sql`、`sql/sys_user_permission.sql`、`sql/migrate_rbac_groups.sql` |
| 实体 | `database/model/SysRoleEntity.kt`（`parentId`）、`SysRolePermissionEntity.kt`、`SysUserPermissionEntity.kt` |
| 仓库 | `database/repository/SysRoleRepository.kt`、`SysRolePermissionRepository.kt`、`SysUserPermissionRepository.kt` |
| 判定 | `service/permission/Permissions.kt`（纯函数）、`PermissionRule.kt`、`PermissionResolver.kt`（组链 + 兜底 + `isOwner`） |
| 服务 | `service/permission/PermissionService.kt`（管理侧读写） |
| 上下文 | `util/session/Context.kt`（`hasPermission`）、`UserInfo.kt`（`roles` / `isOwner` / `permissionRules`）、`ApplicationCallContext.kt`、`interceptor/CtxInterceptor.kt` |
| 生成 | `util/router/Annotations.kt`（`@Permission`）、`router-ksp/RouterProcessor.kt`、`generated/GeneratedRegistries.kt`（`generatedPermissions()`） |
| 校验点 | `util/ktor/ApplicationCallExt.kt`（`requirePermission`） |
| 装配 | `configuration/AppModule.kt`（注册表进 Koin）、`configuration/AppConfigure.kt`（连库后 `syncRegistryGroups`） |
| 接口 | `controller/system/PermissionController.kt`、`dto/PermissionDtos.kt` |

**已标注 `@Permission` 的存量接口**：`/api/user/{list,searching,get}` 与 `/api/userRole/getByUserId` → `lolosia.user.read`；`/api/user/{create,edit,delete}` → `lolosia.user.write`；`/api/role/{list,queryRoleByPage}` → `lolosia.role.read`；`/api/role/{create,update,destroy}` → `lolosia.role.write`；`/api/oss/{list,getMetas,fileCheck}` → `lolosia.oss.file.read`；`/api/oss/upload` → `lolosia.oss.file.write`；`/api/files/{list,dirs,detail,scan}` → `lolosia.files.read`；`/api/classpath/{list,read}` → `lolosia.system.classpath`。

**刻意不标的**：`/api/login` `/logout` `/captcha` `/verify` `/register` `/sse*` `/menu/config`（登录态之前 / 长连接 / 前端守卫必经）；`/api/user/{myInfo,myRole,updatePassword,avatar,avatar/get/{id}}`（自服务；头像与改密码在白名单里，标了会因无会话判失败）；`/api/oss/get/{user}/{md5}`（匿名取件）；`/api/files/{thumb,raw}`（给 `<img src>` 用，在白名单里、改为校验 URL 签名 `exp` + `sig`，标了同样会因无会话判失败 —— 它自带一套独立的凭据）。

**实测**（本机 mingw + 本地库，真实走链路）：

- P1–P5：`student` 用户 `/api/role/create` → `403`、`/api/oss/list` → `200`；直授 `+lolosia.role.write` → `200`；再加 `-lolosia.role.write` → `403`（负号优先）；`save` 提交含 `a.*.c` 的列表 → `400`。
- P6（注册默认 `user` 组，库里的组规则已清空、全走注册表兜底）：
  - `/api/permission/my` → `["+lolosia.user.read","+lolosia.oss.file.write","+lolosia.oss.file.read"]`、`owner:false` —— 前两条来自 `user` 组默认值，第三条是**继承 `guest`** 拿到的；`/api/oss/list` → `200`、`/api/role/list` → `403`。
  - 绑到 `guest` 组：`/api/oss/list` → `200`、`/api/user/list` → `403`（继承有边界，`guest` 拿不到 `user` 的读权限）。
  - 绑到 `admin` 组：`/api/role/list`、`/api/user/list`、`/api/oss/list` 全 `200`（`admin → user → guest` 三层展开）。
  - 绑到 `owner` 组：`/api/permission/my` → `["+*"]`、`owner:true`，`/api/classpath/list` → `200`。
  - 注册表兜底与 DB 覆盖：`/api/role/permission/list {roleId:4}` 库为空 → 回注册表 `["+lolosia.user.read","+lolosia.oss.file.write"]`；`save` 一条后再查 → 回 DB 值；`save` 空列表后再查 → 又回注册表值。
  - `/api/role/list` 出参已带 `parentId`（`owner`=null、`admin`=4、`user`=5、`guest`=null）。

**顺带修掉的构建缺陷**：classpath 资源此前只以路径形式进 `linkerOpts`，Gradle 不把它算作链接任务输入 —— 改了资源（如 `permissions.yaml`）不会触发重新链接，跑的还是旧二进制。现已在 `NativeClasspath.kt` 的两个分支上补 `inputs.files(classpath.res / classpath.o)`，并实测「只改资源内容」即可触发 `classpathZip → windres → link` 重跑。
