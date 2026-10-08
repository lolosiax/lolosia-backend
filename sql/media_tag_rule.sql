/*
 目录 / 文件名 → 标签或评级的规则（见 doc/media-index-design.md §4.5 / §7.4）。

 这是**零依赖兜底**：不装 worker、不跑模型，光靠目录结构（`VRChat/`、`白上吹雪/`）与文件名
 （`*r18*`）就能打出一批标签与评级。它只读路径与文件名、不读文件内容、不出网，
 因此允许在后台扫描时自动跑，与「外发分析必须显式触发」不冲突（§8.2）。

 默认规则集在内嵌资源 `resources/media-rules.yaml`：本表一条规则都没有时回退用它，
 表里有规则就完全以表为准（照搬 rbac §3.6 的两条来源优先级）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for media_tag_rule
-- ----------------------------
DROP TABLE IF EXISTS `media_tag_rule`;
CREATE TABLE `media_tag_rule`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `match_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'path_prefix / name_glob / ext',
  `pattern` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '如 VRChat/ 或 *r18* 或 jpeg',
  `tag_id` bigint(20) NULL DEFAULT NULL COMMENT '命中后打的标签',
  `rating` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '命中后设的评级（与 tag_id 至少填一个）',
  `priority` int(11) NOT NULL DEFAULT 100 COMMENT '小者优先，用于 rating 冲突时定胜负',
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_enabled` (`enabled`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
