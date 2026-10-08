/*
 文件 ↔ 标签关联（见 doc/media-index-design.md §4.4 / §7.3）。

 **没有 UNIQUE(asset_id, tag_id)**：同一个标签理论上可能同时存在 manual 与 model 两条来源，
 判定时取优先级最高的那条（manual > rule > model）；去重放在服务层做（本项目一贯做法）。

 negative = 1 是人工否决：「这张图明确不是这个标签」。它必须存在，否则重新跑模型会把人工纠正过的
 标签一遍遍加回来（§7.3）。

 查询约定：按目录批量取标签时**以本表为驱动表** join 回 media_asset
 （`@Query` 的主表必须与实体 @Table 一致，见 AGENTS.md §5）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for media_asset_tag
-- ----------------------------
DROP TABLE IF EXISTS `media_asset_tag`;
CREATE TABLE `media_asset_tag`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `asset_id` bigint(20) NOT NULL,
  `tag_id` bigint(20) NOT NULL,
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'manual / rule / model',
  `provider` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '模型名+版本，如 wd14-v1.4',
  `confidence` double NULL DEFAULT NULL COMMENT '0~1；manual 为 NULL',
  `negative` tinyint(1) NOT NULL DEFAULT 0 COMMENT '人工否决：这张图明确不是该标签（§7.3）',
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_asset` (`asset_id`, `deleted`),
  KEY `idx_tag` (`tag_id`, `deleted`),
  KEY `idx_tag_source` (`tag_id`, `source`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
