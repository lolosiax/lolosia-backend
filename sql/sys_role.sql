/*
 组表（旧称「角色」）。

 type 是组的**稳定代号**，代码里按它判定权限（如 owner 兜底、注册默认给 user）；
 role_name 只是展示名，可改。
 parent_id 指向父组，实现**组继承**：判定时沿它递归合并祖先组的规则。
 具继承关系与默认权限还登记在内嵌资源 `permissions.yaml`（见 doc/rbac-design.md §3.6），
 启动时由 `service.permission.syncRegistryGroups` 把 extends 同步进本表。

 id 被代码引用（见 dto/SystemDtos.kt 的 ROLE_ID_*），不要随意改动：
   1 = owner（拥有者）  2 = admin（管理员）  4 = user（普通用户）  5 = guest（访客）
 3 曾是已废弃的 teacher 组，已删除（历史库用 sql/migrate_rbac_groups.sql 迁走）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role`  (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `role_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `parent_id` int(11) NULL DEFAULT NULL COMMENT '父组 id（组继承），NULL 为根基组',
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_role（admin → user → guest；owner / guest 为根基组）
-- ----------------------------
INSERT INTO `sys_role` VALUES (1, '拥有者',   'owner', NULL, '2022-06-05 10:44:51', NULL, NOW(), NULL, 0);
INSERT INTO `sys_role` VALUES (2, '管理员',   'admin',    4, '2022-07-05 13:48:08', NULL, NOW(), NULL, 0);
INSERT INTO `sys_role` VALUES (4, '普通用户', 'user',     5, '2022-10-10 15:52:45', NULL, NOW(), NULL, 0);
INSERT INTO `sys_role` VALUES (5, '访客',     'guest', NULL, '2024-06-24 15:57:06', NULL, NOW(), NULL, 0);

SET FOREIGN_KEY_CHECKS = 1;
