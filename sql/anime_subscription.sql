/*
 用户关注：**(用户, 番剧, 发布组) 一行**（见 doc/anime-timetable-design.md §3.4）。

 group_name 为空串表示「该番的任意发布组」；只关心某几个组时按组各存一行。

 唯一性靠生成列 alive_key：软删的行取 NULL，而唯一索引忽略 NULL，
 于是「历史上被取关的同一条」不会挡住重新关注（同 media_asset.alive_path 的做法）。
 分隔符用 #：user_id / group_name 里不应出现它。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for anime_subscription
-- ----------------------------
DROP TABLE IF EXISTS `anime_subscription`;
CREATE TABLE `anime_subscription`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `series_id` bigint(20) NOT NULL COMMENT 'anime_series.id',
  `group_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '空串 = 该番任意发布组',
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  -- 生成列必须排在所有被引用列之后
  `alive_key` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      GENERATED ALWAYS AS (if(`deleted` = 0, concat(`user_id`, '#', `series_id`, '#', `group_name`), NULL)) VIRTUAL,
  UNIQUE KEY `uk_alive` (`alive_key`) USING BTREE,
  KEY `idx_user` (`user_id`, `deleted`),
  KEY `idx_series` (`series_id`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
