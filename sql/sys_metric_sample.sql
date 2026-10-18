/*
 系统指标时序：一次采样一行。

 纯派生数据（CPU / 内存 / 磁盘 / 网络往返速率），随时可清空、可由后台采样重建，
 不影响任何业务语义。写入的是**每分钟一条的降采样行**（内存里另有更高频的环形缓冲），
 旧行由服务层按 dashboard.retainHours 周期清理。

 cpu / mem / disk 是占用率百分数（0~100）；net_in / net_out / disk_read / disk_write 是速率（MB/s）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_metric_sample
-- ----------------------------
DROP TABLE IF EXISTS `sys_metric_sample`;
CREATE TABLE `sys_metric_sample`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sampled_at` timestamp NOT NULL,
  `cpu` double NULL DEFAULT NULL COMMENT 'CPU 占用率（0~100）',
  `mem` double NULL DEFAULT NULL COMMENT '内存占用率（0~100）',
  `mem_used` bigint(20) NULL DEFAULT NULL COMMENT '已用内存（字节）',
  `mem_total` bigint(20) NULL DEFAULT NULL COMMENT '总内存（字节）',
  `disk` double NULL DEFAULT NULL COMMENT '磁盘占用率（0~100）',
  `disk_used` bigint(20) NULL DEFAULT NULL COMMENT '已用磁盘（字节）',
  `disk_total` bigint(20) NULL DEFAULT NULL COMMENT '总磁盘（字节）',
  `net_in` double NULL DEFAULT NULL COMMENT '入站速率（MB/s）',
  `net_out` double NULL DEFAULT NULL COMMENT '出站速率（MB/s）',
  `disk_read` double NULL DEFAULT NULL COMMENT '读速率（MB/s）',
  `disk_write` double NULL DEFAULT NULL COMMENT '写速率（MB/s）',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_sampled` (`sampled_at`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
