/*
 番剧目录：**一部番一行**（见 doc/anime-timetable-design.md §3.1）。

 数据来自 Bangumi API（`GET /calendar` 每日放送 + `/v0/search/subjects` 搜索补番），
 也可由管理员手工新建（此时 bangumi_id 为 NULL）。

 唯一键 uk_bangumi 建在 bangumi_id 上：MySQL 唯一索引**忽略 NULL**，
 于是手工建的番（bangumi_id 全为 NULL）不会互相撞车。

 air_weekday 口径同 Bangumi calendar：1=周一 … 7=周日；没定档的番为 NULL。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for anime_series
-- ----------------------------
DROP TABLE IF EXISTS `anime_series`;
CREATE TABLE `anime_series`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `bangumi_id` bigint(20) NULL DEFAULT NULL COMMENT 'Bangumi subject id；手工建的番为 NULL',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '展示名（中文优先）',
  `title_original` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '原文名（日文 / 罗马字）',
  `cover` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '封面图 URL',
  `air_weekday` tinyint(4) NULL DEFAULT NULL COMMENT '放送星期 1..7（1=周一，同 Bangumi calendar）',
  `air_time` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '放送时刻（JST），如 23:30',
  `air_date` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '首播日期，如 2026-10-05',
  `season` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '季度，如 2026秋',
  `site_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'Bangumi 条目页',
  `rating` double NULL DEFAULT NULL COMMENT 'Bangumi 评分（不用 DECIMAL：sqlx4k 的 native 侧不认）',
  `summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '简介',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'airing' COMMENT 'airing / finished / unknown',
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_bangumi` (`bangumi_id`) USING BTREE,
  KEY `idx_weekday` (`air_weekday`, `deleted`),
  KEY `idx_season` (`season`, `deleted`),
  KEY `idx_title` (`title`(64))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
