/*
 **Token 用量小时桶**：按「本地小时桶」累加 LLM 的 token 用量，供仪表盘展示「今日 token」「累计成本」。

 纯派生数据，随时可清空。`hour_bucket` 是北京时间的小时桶 `yyyy-MM-ddTHH`，字典序等价于时间序。
 唯一键 `uk_bucket` 支撑 upsert 累加（见 `SysTokenUsageRepository.executeUpsert`）。

 成本不落库：由 `LlmPricing.cost` 按「该小时桶所属峰谷档位」逐桶折算，节假日 / 周末规则见 `LlmPricing`。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_token_usage
-- ----------------------------
DROP TABLE IF EXISTS `sys_token_usage`;
CREATE TABLE `sys_token_usage`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `hour_bucket` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '北京时间小时桶 yyyy-MM-ddTHH',
  `requests` bigint(20) NOT NULL DEFAULT 0 COMMENT '请求次数',
  `prompt_tokens` bigint(20) NOT NULL DEFAULT 0 COMMENT '输入 token',
  `cache_hit_tokens` bigint(20) NOT NULL DEFAULT 0 COMMENT '输入 token 中缓存命中的部分',
  `completion_tokens` bigint(20) NOT NULL DEFAULT 0 COMMENT '输出 token',
  `updated_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_bucket` (`hour_bucket`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
