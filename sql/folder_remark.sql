/*
 **文件夹备注**：给目录挂一段自由文本（与文件的 `media_asset.caption` 对齐，目录没有 caption 列）。

 目录不在 `media_asset` 里（媒体索引只登记文件），所以复用不了 `caption`；按**相对路径**另存一张表。
 一个目录最多一条活行（`deleted = 0`），保存走「有则更新、无则插入」，清空即软删。

 用途：
 1. 前端右侧属性面板在文件名/夹名下方展示与编辑（`media/folder-remark`）；
 2. 外发分析（视觉打标）时，把**图片所属文件夹**的备注作为上下文一并喂给模型。

 查询约定：路径就是相对浏览根的目录路径（根目录是空串）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for folder_remark
-- ----------------------------
DROP TABLE IF EXISTS `folder_remark`;
CREATE TABLE `folder_remark`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '相对浏览根的目录路径；根目录是空串',
  `remark` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '人工填写的自由文本备注',
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_path` (`path`(191), `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
