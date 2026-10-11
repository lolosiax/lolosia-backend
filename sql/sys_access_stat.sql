/*
 仪表板的访问统计（按小时对齐的计数桶）。

 一行 = 某个「小时桶 + 客户端 IP + Host」上累计的请求数，由 `AccessStatService` 在内存里聚合、
 周期性落库，请求链路本身不写库。只保留最近若干天（按 `bucket_at` 清理），
 是纯派生数据，可随时清空。

 桶取小时而非分钟：排行是「谁访问得多」的长期视角，小时粒度足够，且窗口内的行数小到可以
 直接取回内存里合并（`@Query` 不支持聚合返回，见 AGENTS.md §5）。

 `host` 是请求的 Host 头。自建服务没有 CF 那样的 CDN，此处只作「Origin 排行」的近似维度。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_access_stat
-- ----------------------------
DROP TABLE IF EXISTS `sys_access_stat`;
CREATE TABLE `sys_access_stat`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `bucket_at` timestamp NOT NULL COMMENT '小时对齐的统计桶（UTC）',
  `ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户端 IP（优先取 X-Forwarded-For 首个）',
  `host` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'Host 头（Origin 维度的近似）',
  `hits` bigint(20) NOT NULL DEFAULT 0 COMMENT '该桶内累计请求数',
  `created_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_bucket_ip_host` (`bucket_at`, `ip`, `host`, `deleted`) USING BTREE,
  KEY `idx_bucket` (`bucket_at`, `deleted`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '仪表板访问统计（纯派生，可清空）' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
