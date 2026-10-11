/*
 仪表板的系统指标采样（CPU / 内存 / 磁盘 / 网络 / 磁盘 IO）。

 一行 = 一次采样（宽表），由 `AccessStatService` 之外的后台采样服务按固定间隔写入。
 只保留最近若干天（按 `sampled_at` 清理），是纯派生数据，可随时清空。

 采样来源是进程所在主机的 `/proc`（Linux）；Windows 目标下不采集，表保持为空。
 单位：cpu / mem / disk 是百分比（0~100）；net_* / disk_* 是速率（字节/秒）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_metric_sample
-- ----------------------------
DROP TABLE IF EXISTS `sys_metric_sample`;
CREATE TABLE `sys_metric_sample`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `cpu` double NOT NULL DEFAULT 0 COMMENT 'CPU 使用率（%）',
  `mem` double NOT NULL DEFAULT 0 COMMENT '内存使用率（%）',
  `disk` double NOT NULL DEFAULT 0 COMMENT '根分区使用率（%）',
  `net_in` double NOT NULL DEFAULT 0 COMMENT '入站速率（字节/秒）',
  `net_out` double NOT NULL DEFAULT 0 COMMENT '出站速率（字节/秒）',
  `disk_read` double NOT NULL DEFAULT 0 COMMENT '磁盘读速率（字节/秒）',
  `disk_write` double NOT NULL DEFAULT 0 COMMENT '磁盘写速率（字节/秒）',
  `sampled_at` timestamp NOT NULL COMMENT '采样时刻（UTC）',
  `created_at` timestamp NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_sampled` (`sampled_at`, `deleted`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '仪表板系统指标采样（纯派生，可清空）' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
