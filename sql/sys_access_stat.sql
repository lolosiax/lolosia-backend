/*
 访问统计：按「小时桶 + ip + host」聚合的命中次数。

 纯派生数据，随时可清空。小时桶是本地时区的 `yyyy-MM-ddTHH`（形如 2026-10-10T14），
 字符串天然按字典序等价于时间序，便于范围查询；唯一键保证同一桶同一来源只有一行，
 写入用 `on duplicate key update hits = hits + :delta` 累加。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_access_stat
-- ----------------------------
DROP TABLE IF EXISTS `sys_access_stat`;
CREATE TABLE `sys_access_stat`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `hour_bucket` varchar(13) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '本地时区小时桶，形如 2026-10-10T14',
  `ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源 IP',
  `host` varchar(190) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Host 头（含端口）',
  `hits` bigint(20) NOT NULL DEFAULT 0 COMMENT '该桶内的命中次数',
  `updated_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_bucket_ip_host` (`hour_bucket`, `ip`, `host`) USING BTREE,
  KEY `idx_bucket` (`hour_bucket`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
