/*
 标签词表（见 doc/media-index-design.md §4.3 / §7.5）。

 parent_id 单列（与 sys_role.parent_id 同构），**NULL 是合法状态**：
 还不知道这是什么东西时先手输一个根级自由标签（namespace = user），以后再挂到某个父级下面。

 alias / merged_into 保证「旧名永远搜得到」：
 - rename：旧名追加进 alias；
 - merge ：被并入的行记 merged_into，命中它时沿链重定向到目标。
 两者都不是「删除」，所以历史检索不会失效。

 alive_key 的唯一性用 `namespace:name`（**utf8mb4，不转 ascii** —— 转 ascii 会把所有中文名压成同一个
 问号，导致互撞）；collation 是 utf8mb4_unicode_ci，因此大小写不敏感的写法会被判为同一个标签，
 这正是想要的效果（手打标签最常见的死法就是「白上吹雪 / 白上吹雪酱 / fubuki」各占一行）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for media_tag
-- ----------------------------
DROP TABLE IF EXISTS `media_tag`;
CREATE TABLE `media_tag`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `namespace` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'character / series / object / action / scene / meta / user',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '展示名；自由标签就是用户手输的原文',
  `parent_id` bigint(20) NULL DEFAULT NULL COMMENT '父标签；NULL = 根级自由标签，完全合法（§7.5）',
  `alias` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '逗号分隔的别名/旧名；改名与合并都往这里追加',
  `merged_into` bigint(20) NULL DEFAULT NULL COMMENT '被并入的标签 id；本行降级为「重定向」（§7.5）',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  `alive_key` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      GENERATED ALWAYS AS (if(`deleted` = 0, concat(`namespace`, ':', `name`), NULL)) VIRTUAL,
  UNIQUE KEY `uk_alive_key` (`alive_key`) USING BTREE,
  KEY `idx_parent` (`parent_id`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
