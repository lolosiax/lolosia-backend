/*
 发布条目：**一次发布一行**，全量历史（见 doc/anime-timetable-design.md §3.3）。

 来自 MioBT 系发布站的 RSS（comicat.org 等）；guid 是 RSS 的全局唯一键，跨轮去重靠它。
 group_name 是 RSS 的 author（发布组）；series_id 是匹配到的番，NULL 表示未匹配。

 match_state：
   pending   刚入库、尚未匹配
   matched   已匹配到番剧
   unmatched 匹配失败（管理员可在界面手动映射）
   ignored   人工判定为噪音（音乐 / 周边 / 无关内容）

 source 预留换源（miobt / mikan …），RSS 结构变动只影响采集层。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for anime_release
-- ----------------------------
DROP TABLE IF EXISTS `anime_release`;
CREATE TABLE `anime_release`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `guid` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'RSS guid；跨轮去重靠它',
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'miobt' COMMENT '数据源代号，预留换源',
  `series_id` bigint(20) NULL DEFAULT NULL COMMENT '匹配到的番；NULL=未匹配',
  `group_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '发布组（RSS author）',
  `title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'RSS 标题原文',
  `episode_no` int(11) NULL DEFAULT NULL COMMENT '解析出的集数；解析失败为 NULL',
  `category` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'RSS category（动画 / 音乐 / 周边）',
  `torrent_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'enclosure 种子直链',
  `detail_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '详情页',
  `match_state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending' COMMENT 'pending / matched / unmatched / ignored',
  `published_at` timestamp NULL DEFAULT NULL COMMENT 'pubDate，转 UTC',
  `created_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_guid` (`guid`) USING BTREE,
  KEY `idx_published` (`published_at`),
  KEY `idx_series` (`series_id`, `published_at`),
  KEY `idx_group` (`group_name`(64), `published_at`),
  KEY `idx_match` (`match_state`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
