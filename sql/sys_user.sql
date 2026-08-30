/*
 Navicat Premium Data Transfer

 Source Server         : MariaDB
 Source Server Type    : MariaDB
 Source Server Version : 110402
 Source Host           : localhost:3306
 Source Schema         : ktor-template

 Target Server Type    : MariaDB
 Target Server Version : 110402
 File Encoding         : 65001

 Date: 03/09/2024 10:42:36
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`  (
  `id` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `real_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `is_use` tinyint(1) NULL DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE CURRENT_TIMESTAMP,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_user
-- ----------------------------
INSERT INTO `sys_user` VALUES ('00000000-0000-0000-0000-000000000000', 'guest', '$2a$10$qYlm0SsCI0Pcyral/LdxhOiIA9mmRiTi12zxRjVoIZ7Btx/ZxtNxK', '访客', '', '', NULL, 1, '2024-06-24 15:12:28', NULL, '2024-06-24 15:30:10', '00000000-0000-0000-0000-000000000000', 0);
INSERT INTO `sys_user` VALUES ('0a99e77c-4e36-4239-9be0-43d07272f2c3', 'qwen', '$2a$10$iURHRjX9m3SQiWPYdGm3pebyFBqI4CeMG7ZzWjJzNnBW2e.rieEBO', '', NULL, '2623587501@qq.com', NULL, 1, '2024-06-24 08:33:46', NULL, '2024-06-24 08:33:46', NULL, 0);
INSERT INTO `sys_user` VALUES ('500cd780-ba0c-4e27-a77c-12a2d00b80c9', 'lolosia', '$2a$10$hpCjmzrKxTbIxzOB4.V2DeejXcWAueej571KUbCZzA9SYhVzUqvlu', '洛洛希雅Lolosia', NULL, 'lolosiax@qq.com', '500cd780-ba0c-4e27-a77c-12a2d00b80c9.jpg', 1, '2024-06-24 17:51:24', NULL, '2024-06-24 10:04:45', '500cd780-ba0c-4e27-a77c-12a2d00b80c9', 0);
INSERT INTO `sys_user` VALUES ('ab635c13-d266-42af-8bae-f7834b628f60', 'admin', '$2a$10$TBLXfwLKf.M6zC0XeiOElOGZluu9kMWP1hersa9kbzmzaNeaw2IYS', '超级管理员', '18615277528', NULL, 'ab635c13-d266-42af-8bae-f7834b628f60.jpeg', 1, '2022-07-05 17:19:45', NULL, '2023-06-16 08:39:20', NULL, 0);

SET FOREIGN_KEY_CHECKS = 1;
