/*
 权限体系：组（sys_role）上挂的权限规则。

 规则串语法（见 doc/rbac-design.md §3）：
   +node     允许（`+` 可省略）
   -node     拒绝
   node       允许
   a.b.*       通配，覆盖 `a.b` 自身及其后代
   *           通配，覆盖一切

 判定：合并「全部所属组（含继承的祖先组）的规则 + 用户直授规则」→ 命中的规则里**负号优先**
 → 未命中即拒绝。写入侧由服务层按同一语法校验，非法串不入库。

 **这里不灌种子**：内置组（owner / admin / user / guest）的默认规则登记在内嵌资源
 `src/commonMain/resources/permissions.yaml`，仅在「该组一条规则都没有」时兜底生效；
 管理界面保存后会落到本表，此后一律以本表为准。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_role_permission
-- ----------------------------
DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission`  (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `role_id` int(11) NOT NULL,
  `permission` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_role` (`role_id`, `deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
