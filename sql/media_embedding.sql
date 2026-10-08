/*
 向量**索引**（见 doc/media-index-design.md §2.3 / §4.6）。

 向量本体不在库里：append 到 `<root>/.cache/lolosia/media/v1/vec/<model>.f32`，
 本表只用 row_index 记账（第几行 = 第几个 dim 的起始偏移）。检索时整块读进内存做点积 ——
 十万级数据暴力算就够，不值得为此引一套向量数据库。

 一行 = 一个资产 × 一个模型，所以换模型重跑不会覆盖旧向量（alive_key 保证唯一）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for media_embedding
-- ----------------------------
DROP TABLE IF EXISTS `media_embedding`;
CREATE TABLE `media_embedding`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `asset_id` bigint(20) NOT NULL,
  `model` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '如 clip-vit-b32 / wd14-swinv2',
  `dim` int(11) NOT NULL,
  `row_index` bigint(20) NOT NULL COMMENT '向量本体在 .cache 的 <model>.f32 里的行号',
  `norm` double NULL DEFAULT NULL COMMENT '归一化前的模长，仅用于诊断',
  `created_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  `alive_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      GENERATED ALWAYS AS (if(`deleted` = 0, concat(`asset_id`, ':', `model`), NULL)) VIRTUAL,
  UNIQUE KEY `uk_alive_key` (`alive_key`) USING BTREE,
  KEY `idx_model` (`model`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
