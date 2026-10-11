/*
 **足迹地图 · 区域圈**：人工命名的一块地理范围（圆形），如「江汉路步行街」「X118」。

 背景：媒体索引已经从 EXIF 里抽出了 `media_asset.gps_lat/gps_lon`（见 doc/media-index-design.md §6.4），
 但「这张图属于哪个地点」是**人的判断**，模型与反向地理编码都给不了 —— 于是只存**人画的圈**：
 中心点 + 半径（米），外加一个名字与颜色。

  统计口径：某个区域里有多少张图，是**现算**的（把圈内点按几何包含判定），
  不落库、不做反向索引 —— 库里的点本来就要整份读出来做聚类（见 service/media/MediaFootprintService）。

 与 `media_asset` 的关系：**没有外键**。圈是独立的标注层，删图不影响圈、删圈不影响图。

 查询约定：全表按 `deleted = 0` 取，量级很小（人手工画的那几十个圈）。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for media_location_region
-- ----------------------------
DROP TABLE IF EXISTS `media_location_region`;
CREATE TABLE `media_location_region`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '区域名，如「江汉路步行街」',
  `center_lat` double NOT NULL COMMENT '中心点纬度',
  `center_lon` double NOT NULL COMMENT '中心点经度',
  `radius_m` double NOT NULL DEFAULT 0 COMMENT '半径（米）',
  `color` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '展示色，如 #ff6600；空则由前端取默认色',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '一句话备注',
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_deleted` (`deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
