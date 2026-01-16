/*
 Navicat Premium Data Transfer

 Source Server         :
 Source Server Type    : MariaDB
 Source Server Version : 100148
 Source Host           :
 Source Schema         :

 Target Server Type    : MariaDB
 Target Server Version : 100148
 File Encoding         : 65001

 Date: 19/06/2024 15:38:48
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_cache
-- ----------------------------
DROP TABLE IF EXISTS `sys_cache`;
CREATE TABLE `sys_cache`  (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '数据存储路径',
  `tag` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '应用自定义的标签',
  `value` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '数据值',
  `expires_at` timestamp NULL DEFAULT NULL COMMENT '过期时间',
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `created_at` timestamp NULL DEFAULT NULL COMMENT '创建时间',
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '修改者',
  `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间',
  `deleted` tinyint(1) NULL DEFAULT NULL COMMENT '已被删除',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '键值对存储的信息，此表用于存储程序的一些缓存，可以随时清空里面的内容。' ROW_FORMAT = Compact;

SET FOREIGN_KEY_CHECKS = 1;
