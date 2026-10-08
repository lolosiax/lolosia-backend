/*
 媒体资产索引：**一个文件一行**（见 doc/media-index-design.md §4.2）。

 四个身份键分工（§2.2）：
   path            现在在哪（接口入参键）
   size + mtime    要不要重做（零读取的新鲜度短路，§5.1）
   quick_id        改了 mtime 之后内容到底变没变（size + 首尾各 64KiB 的 sha256，扫描路径上就能算）
   content_sha256  是不是同一份内容（全量哈希，异步补齐，只在冲突判定时才用）

 dir_path 是冗余列：专为「列一个目录时用两条单语句取回评级与标签」而设 ——
 sqlx4k 不支持 IN (列表)，没法按 asset_id 批量回查（§4.8-1）。

 rating / analysis_skip 是**人工或规则**写入的合规闸门，模型永不参与（§7.2 / §10.2）。
 analyze_* 只在用户显式点「全盘分析」之后才会变，后台扫描一个字节都不出网（§8.2）。

 唯一性靠生成列：软删的行取 NULL，而唯一索引忽略 NULL，
 于是「历史上被删掉的同路径行」不会互相撞车。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for media_asset
-- ----------------------------
DROP TABLE IF EXISTS `media_asset`;
CREATE TABLE `media_asset`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,

  -- 位置（相对浏览根，无前导 /；根目录为空串）
  `path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '相对浏览根的路径（当前位置）',
  `dir_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '父目录相对路径，空串=根；列目录摘要靠它一次取回',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `kind` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'other' COMMENT 'image / video / audio / other',

  -- 新鲜度与身份（§2.2）
  `size` bigint(20) NOT NULL DEFAULT 0,
  `mtime` bigint(20) NOT NULL DEFAULT 0 COMMENT '源文件修改时间（毫秒）',
  `quick_id` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'size+首尾各 64KiB 的 sha256',
  `content_sha256` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '全量 SHA-256；异步补齐',

  -- 评级（§7.2，独立成列，不靠标签）+ 外发闸门（§10.2）
  `rating` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'unknown'
      COMMENT 'unknown / safe / questionable / explicit；只由人工或规则写入，模型不参与',
  `rating_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'manual / rule',
  `analysis_skip` tinyint(1) NOT NULL DEFAULT 0
      COMMENT '禁止外发分析（第三方模型）；rating=explicit 时强制为 1，见 §10.2',

  -- 提升成列的元数据（§6：要能 WHERE / ORDER BY）
  `width` int(11) NULL DEFAULT NULL,
  `height` int(11) NULL DEFAULT NULL,
  `duration_ms` bigint(20) NULL DEFAULT NULL,
  `taken_at` timestamp NULL DEFAULT NULL COMMENT '折算成 UTC 的拍摄时刻，用于排序/范围筛选',
  `taken_at_local` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'EXIF 原文墙上时间，用于展示',
  `taken_at_offset_min` int(11) NULL DEFAULT NULL COMMENT 'EXIF Offset* 的分钟数；缺失为 NULL',
  `camera_make` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `camera_model` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `lens` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `aperture` double NULL DEFAULT NULL COMMENT 'F 数，如 1.8（不用 DECIMAL：sqlx4k 的 native 侧不认）',
  `exposure_time` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '原文，如 1/250',
  `iso` int(11) NULL DEFAULT NULL,
  `focal_length` double NULL DEFAULT NULL COMMENT '毫米',
  `gps_lat` double NULL DEFAULT NULL,
  `gps_lon` double NULL DEFAULT NULL,
  `orientation` int(11) NULL DEFAULT NULL,
  `sample_rgb565` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL
      COMMENT '8x8 采样，每像素 4 位十六进制（RGB565 大端），共 256 字符；缩略图生成时顺带产出，用于加载前的占位与敏感内容的遮罩底图',

  -- 长尾原文与探测器版本（§4.7）
  `metadata_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '原始探测结果（EXIF/TIFF/容器头），截断保存',
  `probe_at` timestamp NULL DEFAULT NULL,
  `probe_version` int(11) NULL DEFAULT NULL COMMENT '探测器版本；升版即重探',

  -- 外发分析状态（§8.2：只有显式触发才会变，后台扫描不碰）
  `analyze_state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'none'
      COMMENT 'none / queued / running / done / failed / skipped（被外发闸门跳过）',
  `analyzed_at` timestamp NULL DEFAULT NULL,
  `analyze_provider` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '产出当前标签/向量的模型版本',
  `caption` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '模型生成的描述',
  `caption_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'manual / model',

  -- 生命周期
  `missing_since` timestamp NULL DEFAULT NULL COMMENT '上次扫描未见的时间；连续缺失才软删（§5.3）',
  `merged_into` bigint(20) NULL DEFAULT NULL COMMENT '内容重复时并入的资产 id（§5.2）',

  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,

  PRIMARY KEY (`id`) USING BTREE,
  -- 生成列必须排在所有被引用列之后
  `alive_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      GENERATED ALWAYS AS (if(`deleted` = 0, `path`, NULL)) VIRTUAL,
  UNIQUE KEY `uk_alive_path` (`alive_path`) USING BTREE,
  KEY `idx_dir` (`dir_path`(191), `deleted`),
  KEY `idx_quick` (`quick_id`),
  KEY `idx_sha` (`content_sha256`),
  KEY `idx_taken` (`taken_at`),
  KEY `idx_rating` (`rating`, `deleted`),
  KEY `idx_analyze` (`analyze_state`, `deleted`),
  KEY `idx_camera` (`camera_model`(64))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
