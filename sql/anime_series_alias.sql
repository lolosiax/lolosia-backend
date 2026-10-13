/*
 番剧别名：**匹配的弹药**（见 doc/anime-timetable-design.md §3.2 / §4.2）。

 发布站 RSS 标题里的番名形态五花八门（中文 / 日文 / 罗马字 / 英文），靠一张别名表
 做「标题 → 番剧」的模糊匹配。source=bangumi 来自目录同步，source=manual 来自
 管理员手动映射（形成学习闭环：同类标题下次自动命中）。

 alias_norm 是规范化后的形态（小写、去空白与符号），与匹配侧的归一化**同口径**。
 没有 deleted 列：别名随番剧重建（同步目录时整表重写）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for anime_series_alias
-- ----------------------------
DROP TABLE IF EXISTS `anime_series_alias`;
CREATE TABLE `anime_series_alias`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `series_id` bigint(20) NOT NULL COMMENT '所属番剧 id（anime_series.id）',
  `alias` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '别名原文',
  `alias_norm` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规范化后（小写、去空白与符号），匹配口径',
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'bangumi' COMMENT 'bangumi / manual',
  `created_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_series` (`series_id`),
  KEY `idx_norm` (`alias_norm`(191))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
