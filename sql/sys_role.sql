/*
 Navicat Premium Data Transfer

 Source Server         :
 Source Server Type    : MariaDB
 Source Server Version : 110402
 Source Host           :
 Source Schema         :

 Target Server Type    : MariaDB
 Target Server Version : 110402
 File Encoding         : 65001

 Date: 03/09/2024 10:38:46
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
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_role
-- ----------------------------
INSERT INTO `sys_role` VALUES (1, '超级管理员', 'super_admin', '2022-06-05 10:44:51', NULL, '2023-05-29 09:34:56', NULL, 0);
INSERT INTO `sys_role` VALUES (2, '管理员', 'admin', '2022-07-05 13:48:08', NULL, '2022-11-13 16:34:33', NULL, 0);
INSERT INTO `sys_role` VALUES (3, '高级用户', 'special_user', '2022-08-05 14:05:32', NULL, '2023-01-10 15:13:23', NULL, 0);
INSERT INTO `sys_role` VALUES (4, '用户', 'user', '2022-10-10 15:52:45', NULL, '2023-01-10 15:13:39', NULL, 0);
INSERT INTO `sys_role` VALUES (5, '访客', 'guest', '2024-06-24 15:57:06', NULL, '2024-06-24 15:57:08', NULL, 0);

SET FOREIGN_KEY_CHECKS = 1;
