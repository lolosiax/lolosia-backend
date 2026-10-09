/*
 **文件夹** ↔ 标签关联（目录本身不在 `media_asset` 里，见 doc/media-index-design.md §2.1）。

 媒体索引只登记文件（`MediaIndexService.process` 第一行就 `if (entry.directory) return`），
 所以 `media_asset_tag` 挂不到目录上。属性页要给文件夹打「这层要按日期分组」这种开关，
 只能另起一张按**相对路径**定位的关联表 —— 词表仍复用 `media_tag`，不另造一套标签。

 与 `media_asset_tag` 一样：**没有 UNIQUE(path, tag_id)**，去重放在服务层做（本项目一贯做法）；
 也没有 negative —— 文件夹标签只有人工来源，没有规则 / 模型会来抢。

 用途（前端）：`feature:日期分组` 打开分组能力，`feature:自动分组-年份` / `feature:自动分组-月份`
 指定进入该目录时默认选用的分组模式。

 查询约定：按路径取标签，路径就是相对浏览根的目录路径（根目录是空串）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for folder_tag
-- ----------------------------
DROP TABLE IF EXISTS `folder_tag`;
CREATE TABLE `folder_tag`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '相对浏览根的目录路径；根目录是空串',
  `tag_id` bigint(20) NOT NULL COMMENT 'media_tag.id',
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'manual（目录没有规则 / 模型来源）',
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_path` (`path`(191), `deleted`),
  KEY `idx_tag` (`tag_id`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
