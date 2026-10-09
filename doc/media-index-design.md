# 媒体库索引设计：标签 · 元数据 · 语义检索

> 状态：**设计稿（尚未实现）**。落地后按 [rbac-design.md](./rbac-design.md) 的做法在文末补「实现落点与偏差」。
> 规范约定见 [AGENTS.md](../AGENTS.md)；在线文件管理器的现状与实测数据见 [lolosia-backend-notes.md](./lolosia-backend-notes.md) §3。

---

## 0. 结论速览

1. **存储选型：MariaDB 做真相源，磁盘缓存只放派生物，向量不逐行落库。**
   标签、评级、抽取出来的元数据都是「小而关系化」的数据，库已经在跑、有事务、有备份、与 RBAC 同栈。
   为个人相册这个量级引入 Elasticsearch / Milvus / Qdrant 是**负收益**（多一个要运维的进程，替不掉任何已有能力）。
2. **身份不是「路径 还是 sha256」二选一，而是三层各司其职**（§2.2）：
   `path` 是**位置**（所有接口的入参，沿用现有契约）、`size + mtime` 是**新鲜度**（扫描时零读取短路）、
   `sha256` 是**内容身份**（改名 / 移动 / 跨盘去重时认回同一张图）。
   中间再加一层便宜的 `quick_id`，避免「碰一下 mtime 就丢掉全部标签」。
3. **全量 sha256 不能默认开**：10 万张图 ≈ 300GB，机械盘 30MB/s 要跑近 3 小时（实测顺序读 352MB/s 是理想值，
   随机小文件远达不到）。默认只算 `quick_id`（读首尾各 64KiB），全量哈希走**低优先级后台补齐**。
4. **元数据（光圈 / 设备 / GPS / 时间）在进程内自研解析。** K/N 上没有 `javax.imageio`，
   引 libexif / exiv2 要背上三目标的静态库 + cinterop（exiv2 还是 C++，K/N 侧基本没法绑）。
   JPEG APP1 / TIFF IFD 规范稳定，几百行 Kotlin 够用，风格与 [`util/crypto/BCrypt.kt`](../src/commonMain/kotlin/moe/lolosia/web/util/crypto/BCrypt.kt) 一致。
5. **外发分析不自动跑：只有用户点了「全盘分析」才外发。** 后台周期扫描只做本地的事（stat / EXIF / 缩略图 / 规则打标），
   **一个字节都不出网**；标签、描述、向量这些「要把图交给模型」的事一律由用户显式触发（§8.2）。
   在那之前，打标是**人手动的**。
6. **模型必须出进程。** 板子 4 核 ARM64 跑 CLIP 每张要好几秒，且 K/N 侧没有可用的推理运行时。
   后端只做**编排 + 持久化 + 检索**，模型挂在带 GPU 的 PC 上的独立 worker，通过 HTTP 调用；
   没有 worker 时自动降级为「EXIF + 规则打标」。
7. **标签来源必须分层**：`manual`（人工，永不被自动覆盖）> `rule`（目录 / 文件名规则）> `model`（只由显式分析产生）。
   每条关联带 `provider` + `confidence`；人工可打**否决**（`negative`），重新跑模型不会把它加回来（§7.3）。
8. **NSFW 是纯人工评级，并且是「外发闸门」**：在属性页（形如 Windows 文件权限对话框）里勾选，**不做自动识别**。
   一旦标记，该文件**入队时就跳过、发包前再挡一次**，绝不外发给第三方模型服务 ——
   否则服务商只会回一个「内容不合规」的错误，白跑一趟还可能留下记录（§10）。
9. **「能不能看 NSFW」折进直链签名**：`sig = HMAC(key, "path|exp|flags")`，`flags` 由**有会话、有权限**的
   `files/list` 签发。否则 `/api/files/raw` 在白名单里、只验签名 —— 谁拿到 URL 都能看到原图，前端 CSS 遮罩纯属自欺（§10.4）。
10. **标签与目录是两个正交的视图，且标签是「事后长出来的」**：标签挂在资产上、资产靠 `quick_id` / `content_sha256` 认人，
   所以「把图从 `杂图/` 挪到 `VRChat/白上吹雪/`」标签一个不丢；目录树回答「文件在哪」，媒体库回答「这张图是什么」（§7.5）。
   模型必须支持**先随便写、之后再收拾**：根级自由标签是一等公民，改名 / 挂父级 / 合并三个操作齐全，且**一条关联都不许丢**。
11. **检索入口要能被程序（智能体）调用**：`media/manifest` 自描述「能查什么」，`media/search` 用**可组合的原语 + 游标翻页**，
   结果自带直链凭据（`exp`/`sig`，按签发者权限决定能看到什么）。「自然语言 → 查询」放在 agent 侧，后端不塞黑盒问答（§9.4）。

---

## 1. 需求与现状

### 1.1 要解决什么

| 需求 | 具体形态 |
| --- | --- |
| 打标签 | 自动给图片打「里面有什么」：`Vtuber` / `白上吹雪` / `VRChat` / `巧克力`；也支持手工补 / 删 |
| 分级 | `NSFW` 之类的敏感分级，前端默认**闭眼遮罩**，点击才展开 |
| 检索 | 按标签（含子标签）、按元数据（相机 / 光圈 / ISO / 时间 / 有无 GPS）、按文件名、按**语义相似** |
| 元数据留存 | 从文件里**能读多少读多少**：拍摄信息、光圈、快门、ISO、镜头、设备、GPS、时间、朝向、时长 |
| 非破坏 | 媒体盘是**只读**浏览目标；派生数据（索引、向量、缩略图）不许污染媒体树 |

### 1.2 现状（as-is）

| 组件 | 现状 | 对本方案的约束 |
| --- | --- | --- |
| [`FileBrowser`](../src/commonMain/kotlin/moe/lolosia/web/service/files/FileBrowser.kt) | 只读列目录，**每个子项只做一次 stat**，明说不读文件内容 | 目录列表是热路径；给它加索引摘要不能变成 N 次查询 / 读文件 |
| [`FileTypes`](../src/commonMain/kotlin/moe/lolosia/web/service/files/FileTypes.kt) | `normalizeRelative` **拒绝一切 `.` 开头的段** | 索引数据天然可以塞进 `.cache/lolosia/` 下，用户与 WebDAV 都看不见 |
| [`ThumbnailCache`](../src/commonMain/kotlin/moe/lolosia/web/service/files/ThumbnailCache.kt) | 按**相对路径 SHA-1** 分片落盘；新鲜度靠 `size + mtime`；**不读内容算哈希**（机械盘读不动） | 现成的「薄 DB / 落盘派生 / 零读取判新鲜」范式，本方案直接沿用 |
| [`ThumbnailService`](../src/commonMain/kotlin/moe/lolosia/web/service/files/ThumbnailService.kt) | **单工作协程 + 优先队列 + 去重集合**，完成后 SSE 推 `files:thumb` | 打标 / 索引服务与它**同构**，甚至共用队列骨架 |
| [`FileSigner`](../src/commonMain/kotlin/moe/lolosia/web/service/files/FileSigner.kt) | `sig = HMAC-SHA256(key, "相对路径\|过期时间")` | 扩展为带 `flags` 才能防「无权限者直接拉 NSFW 原图」 |
| `DavStore` | 跳过一切 `.` 开头的段 | `.cache` 对 WebDAV 也隐藏（拿不到「别的客户端能看到 sidecar」的好处，见 §2.1） |
| [`Image`](../src/commonMain/kotlin/moe/lolosia/web/util/image/Image.kt) | 只做「识别格式 / 尺寸 / 色彩类型 / 解码 / 绘制」，**没有 EXIF** | EXIF 是新增能力，落 `util/exif/` |
| `api/package.kt` | 已有对外 HTTP 客户端（Ktor Client） | 调外部打标 worker 走它，不引新依赖 |

### 1.3 硬约束

- **Kotlin/Native 单二进制**：没有 JVM、没有 `javax.imageio`、没有现成的 ML 运行时；加依赖要谨慎。
- **算力**：开发板 ARM64 4 核（`tsp-1m`），媒体盘是机械盘（30MB/s 量级）。
- **网络**：PC ↔ 板子 有线 75–114MB/s，**SMB 只有 17.2MB/s，Wi-Fi 只有 3.2MB/s** —— 大量图片出板子打标会很疼（§8.5）。
- **sqlx4k**：`@Query` 不派生、不支持 `IN (列表)`、批量只能循环单条、`@Query` 主表必须与实体 `@Table` 一致。
- **无自动化测试**：行为验证靠一次性冒烟脚本。

---

## 2. 三个直接问题的答复

### 2.1 用数据库，还是别的技术？

| 方案 | 能干什么 | 为什么不用 / 怎么用 |
| --- | --- | --- |
| **MariaDB（既有）** | 标签、评级、元数据、关系、事务、分面统计 | ✅ **真相源**。库已在跑，与 RBAC 同栈，权限/备份/迁移的路子都是现成的 |
| 媒体树里的 sidecar 文件（`.tags.json` / xattr） | 跟着盘走、DB 挂了也能读 | ❌ **不做真相源**：会污染媒体树；且本项目 `DavStore` 与 `FileBrowser` **都跳过 `.` 开头的段**，sidecar 想「对别的客户端可见」也做不到。只作**导出/备份**通道（§7.5 可选） |
| 独立搜索/向量服务（ES、Milvus、Qdrant、sqlite-vec） | 模糊检索、向量近邻、分面 | ❌ 规模不够（十万级），多一个进程就是多一份运维。相似检索在 Kotlin 里暴力算就够（§9.2），超过阈值再引入 |
| 静态索引文件（自研 BSQ / 落盘向量） | 大矩阵检索省 DB 往返 | ✅ **只用于向量本体**（派生物），与 `ThumbnailCache` 同思路 |

**结论**：DB 存「关系与元数据」，磁盘 `.cache` 存「大块派生物」，进程内做「小规模计算」，模型放「外部 worker」。四层各司其职，不引入任何新服务。

### 2.2 路径 + sha256 够不够？

**不够，但也别只用其中一个。** 三个键各管一件事：

| 层 | 键 | 回答什么问题 | 成本 | 存在哪 |
| --- | --- | --- | --- | --- |
| 位置 | `path`（相对浏览根） | 「现在在哪」；前端所有接口的入参 | 1 次 stat | `media_asset.path` |
| 新鲜度 | `size` + `mtime` | 「**要不要重做**」 | **0 次读取**（纯 stat） | `media_asset.size/mtime` |
| 快速内容指纹 | `quick_id` = `sha256(size ‖ 首 64KiB ‖ 尾 64KiB)` | 「mtime 变了，内容变了没有」 | 读 ≤128KiB | `media_asset.quick_id` |
| 内容身份 | `content_sha256`（全量） | 「**是不是同一张图**」；改名 / 移动 / 跨盘去重 | **读全文件** | `media_asset.content_sha256` |

几个必须说清的点：

- **`path` 不能当身份**：改个名、移动一次、换个目录，标签就全丢。媒体盘通过 WebDAV / SMB 天天被整理，这是必然事件。
- **`mtime` 不能当变更判据的终点**：`cp` / `rsync` / 恢复备份都会改 mtime，内容却一个字节没变 → 必须用 `quick_id` 兜一层，否则每次同步都会「新资产 +N、标签 +0」。
- **全量 `sha256` 不能当日常判据**：它要读完整文件。机械盘上对整库做一轮是不可接受的例行开销 → 它是**身份归属的最终裁决**（异步补齐），不是扫描路径上的判据。
- **`quick_id` 不是密码学身份**：首尾相同、大小相同的不同文件理论上存在（尤其视频）。它只用于**快速匹配**；一旦 `content_sha256` 补齐，以 sha256 为准，不一致就拆开（§5.2）。

**这条分层直接换来一个业务性质：标签与目录正交。** 位置（`path`）随时可以变，身份（哈希）不变，
所以标签挂在资产上就与「文件放在哪个文件夹」无关 —— 移到别处、甚至复制到另一块盘，标签都跟着走（§7.5）。

### 2.3 embedding 存哪、怎么查？

规模先算清楚（假设 512 维 float32，L2 归一化后存）：

| 条数 | 向量体积 | 一次暴力检索（单核） | 结论 |
| --- | --- | --- | --- |
| 1 万 | 20MB | < 10ms | DB BLOB 也行 |
| 10 万 | 205MB | 几十毫秒 | **落盘向量文件 + 整块读** |
| 100 万 | 2GB | 几百毫秒 + 2GB 常驻 | 该上独立向量库 / 量化 / IVF |

**推荐做法**（与 `ThumbnailCache` 同构）：

- DB 只存**索引**：`media_embedding(asset_id, model, dim, row_index, norm)`；且**一模型一条**。
- 向量本体 **append-only** 落在 `<root>/.cache/lolosia/media/v1/vec/<model>.f32`（float32 小端，第 `row_index` 行就是它）。
  对 WebDAV / 文件管理器**天然不可见**（`.` 开头），不污染媒体树，也不吃 DB 空间与 BLOB 往返。
- 检索：整块 `readByteArray()` 进内存 → Kotlin 里做**矩阵 × 向量**点积（归一化后余弦 = 点积）→ topK。
- **先收窄候选集再算向量**：绝大多数查询是「在这个目录 / 这些标签 / 这个时间段里找相似」，先用 DB 过滤出 `asset_id` 集合，再只对这些行算点积 —— 通常只剩几百~几千行，成本可忽略。
- 删除用**空洞**表示（DB 里那行标 `deleted`），后台 compact 时重写文件、重排 `row_index`（与缩略图缓存「版本号一改整体作废」是一个力度）。

> ⚠️ **待验证**：MariaDB ≥ 11.7 有原生 `VECTOR` 类型与 `VEC_DISTANCE_COSINE`。
> 若线上确实是这个版本，可以先省掉落盘那一层（一条 SQL 就能 topK）。
> 但**必须先确认 sqlx4k 能绑定向量列**（二进制参数的往返取法在它的 `RowMapper` 上未验证）——
> 没验证前不要把方案压在它上面。

---

## 3. 总体架构

```
                   ┌──────────────────── 媒体盘 /mnt/hdd1（只读浏览目标）────────────────────┐
                   │  a/b/c.jpg   d/e.mp4   …                                                  │
                   └───────┬───────────────────────────────────────────┬─────────────────────┘
                           │ stat / 读文件头 / （低优先）读全量        │ 原图（范围读）
                           ▼                                           ▼
   ┌───────────────────────────────────┐                    ┌──────────────────────────┐
   │ MediaIndexService（单工作协程）    │                    │  外部 tagging worker      │
   │  · 后台周期全量扫描 / 打开目录插队  │  HTTP(内网)        │  （PC + GPU，进程外）      │
   │  · 目录规则打标（零依赖）           │ ─────────────────► │  · CLIP 零样本 / WD14 标签 │
   └───────┬───────────────────────────┘ ◄───────────────── │  · 图像 embedding         │
           │ probe / hash / 落库         标签 + 向量           │  · NSFW 人工标记（§10）    │
           ▼                                                    └──────────────────────────┘
   ┌──────────────────────────────┐        ┌───────────────────────────────────────────┐
   │ util/exif（自研 IFD 解析）    │        │ <root>/.cache/lolosia/media/v1/            │
   │ util/media（quick_id / sha256）│        │   vec/<model>.f32   ← 向量本体（append）   │
   └───────┬──────────────────────┘        └───────────────────────────────────────────┘
           ▼
   ┌───────────────────────────────────────────────────────────────────────────────────┐
   │ MariaDB：media_asset / media_metadata′ / media_tag / media_asset_tag /             │
   │          media_embedding（只存索引）/ media_tag_rule                               │
   └───────────────────────────────────────────────────────────────────────────────────┘
           ▲
           │ 一次 dir_path 查询取回整目录摘要（绕开 sqlx4k 的 IN 限制）
   ┌───────┴───────────────────────────────────────────────────────────────────────────┐
   │ /api/files/list（增量加 rating / takenAt）+ /api/media/*（标签 · 检索 · 详情）      │
   │ 直链签名带 flags → 无权限者拿不到 NSFW 原图                                        │
   └───────────────────────────────────────────────────────────────────────────────────┘
```

> ⚠️ `MediaIndexService → worker` 这条线**只有在用户显式点了「全盘分析」时才存在**（§8.2）。
> 平时它只跟左边的盘和下面的库打交道：扫描、probe、算哈希、落库，一个字节都不出网。

---

## 4. 数据模型

### 4.1 表总览

| 表 | 角色 | 量级 |
| --- | --- | --- |
| `media_asset` | **一个文件一行**：位置 + 新鲜度 + 身份 + 提升成列的元数据 + 评级 | = 文件数 |
| `media_tag` | 标签词表（命名空间 + 层级 + 别名） | 几百~几千 |
| `media_asset_tag` | 文件 ↔ 标签关联，带 `source` / `provider` / `confidence` / `negative` | 文件数 × 5~20 |
| `media_tag_rule` | 目录 / 文件名 → 标签（或评级）的规则 | 几十 |
| `media_embedding` | 向量**索引**（本体在 `.cache` 的 `.f32` 文件里） | = 有向量的文件数 × 模型数 |
| `folder_tag` | **目录** ↔ 标签关联（目录不在 `media_asset` 里）：给文件管理器记展示开关，如 `feature:日期分组` / `feature:自动分组-月份` | 目录数 × 0~3 |

> 元数据**不单开 EAV 表**：能建索引的字段提升成 `media_asset` 的列，长尾原文进 `metadata_json`。理由见 §4.7。

### 4.2 `media_asset`

```sql
-- sql/media_asset.sql
DROP TABLE IF EXISTS `media_asset`;
CREATE TABLE `media_asset` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '相对浏览根的路径（当前位置）',
  `dir_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '父目录相对路径，空串=根；列目录摘要靠它一次取回',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `kind` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'other' COMMENT 'image / video / audio / other',

  -- 位置与新鲜度（§2.2）
  `size` bigint(20) NOT NULL DEFAULT 0,
  `mtime` bigint(20) NOT NULL DEFAULT 0,
  `quick_id` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'size+首尾各 64KiB 的 sha256',
  `content_sha256` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '全量 SHA-256；异步补齐',

  -- 评级（§7.2，独立成列，不靠标签）+ 外发闸门（§10.2）
  `rating` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'unknown'
      COMMENT 'unknown / safe / questionable / explicit；只由人工或规则写入，模型不参与',
  `rating_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'manual / rule',
  `analysis_skip` tinyint(1) NOT NULL DEFAULT 0
      COMMENT '禁止外发分析（第三方模型）；rating=explicit 时强制为 1，见 §10.2',

  -- 提升成列的元数据（§6，要能 WHERE / ORDER BY）
  `width` int(11) NULL DEFAULT NULL,
  `height` int(11) NULL DEFAULT NULL,
  `duration_ms` bigint(20) NULL DEFAULT NULL,
  `taken_at` timestamp NULL DEFAULT NULL COMMENT '折算成 UTC 的拍摄时刻，用于排序/范围筛选',
  `taken_at_local` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'EXIF 原文墙上时间，用于展示',
  `taken_at_offset_min` int(11) NULL DEFAULT NULL COMMENT 'EXIF Offset* 的分钟数；缺失为 NULL',
  `camera_make` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `camera_model` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `lens` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `aperture` double NULL DEFAULT NULL COMMENT 'F 数，如 1.80',
  `exposure_time` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '原文，如 1/250',
  `iso` int(11) NULL DEFAULT NULL,
  `focal_length` double NULL DEFAULT NULL COMMENT '毫米',
  `gps_lat` double NULL DEFAULT NULL,
  `gps_lon` double NULL DEFAULT NULL,
  `orientation` int(11) NULL DEFAULT NULL,
  `sample_rgb565` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL
      COMMENT '8x8 采样，每像素 4 位十六进制（RGB565 大端）；缩略图生成时顺带产出，用于加载前的占位与敏感内容的遮罩底图（§10.3）',

  -- 长尾原文与探测器版本（§4.7）
  `metadata_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '原始探测结果（EXIF/TIFF/容器头），截断保存',
  `probe_at` timestamp NULL DEFAULT NULL,
  `probe_version` int(11) NULL DEFAULT NULL COMMENT '探测器版本；升版即重探（与 THUMBNAIL_CACHE_VERSION 同义）',

  -- 外发分析状态（§8.2：只有显式触发才会变，后台扫描不碰）
  `analyze_state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'none'
      COMMENT 'none / queued / running / done / failed / skipped（被外发闸门跳过）',
  `analyzed_at` timestamp NULL DEFAULT NULL,
  `analyze_provider` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '产出当前标签/向量的模型版本',
  `caption` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '模型生成的描述（§8.3）',
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
  -- 唯一性靠生成列：软删的行取 NULL，而唯一索引忽略 NULL，于是「历史软删行」不会互相撞车
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
```

> `path` 是 `varchar(512)`，utf8mb4 下 2048 字节 < InnoDB 的 3072 字节索引上限，**不需要前缀索引**（前缀索引会破坏唯一性语义）。
>
> `aperture` / `focal_length` 用 `double` 而**不是** `decimal`：sqlx4k 的 native 侧不认 MySQL `DECIMAL`，
> 用 `decimal` 会让「读回来」这一步炸掉。光圈与焦距本来也不需要十进制精确性，`double` 足够。

### 4.3 `media_tag` —— 词表

```sql
-- sql/media_tag.sql
DROP TABLE IF EXISTS `media_tag`;
CREATE TABLE `media_tag` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `namespace` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'character / series / object / action / scene / meta / user',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '展示名；自由标签就是用户手输的原文',
  `parent_id` bigint(20) NULL DEFAULT NULL COMMENT '父标签（层级，与 sys_role.parent_id 同构）；NULL = 根级自由标签，完全合法（§7.5）',
  `alias` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '逗号分隔的别名/旧名；改名与合并都往这里追加，保证旧名仍搜得到',
  `merged_into` bigint(20) NULL DEFAULT NULL COMMENT '被并入的标签 id；本行降级为「重定向」，命中它沿链跳到目标（§7.5）',
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
```

> `alive_key` 用 `utf8mb4_unicode_ci`，**不能**写成 `convert(name using ascii)`：标签名大多是中文，
> 转 ascii 会把它们统统压成同一个 `?`，于是「白上吹雪」与「婉钥」在唯一键上互相撞车。
> 用 `utf8mb4_unicode_ci` 还顺带拿到「大小写不敏感」的去重语义，正是想要的。

层级示例（`parent_id` 指向上级；注意**根级自由标签**是合法状态，不是「待整理」）：

```
character:Vtuber
  └─ character:白上吹雪
series:VRChat
object:巧克力
action:拿着（东西）        ← 动作类，命名空间单独给
user:银发                   ← 根级自由标签：还不知道是什么时先记下来（§7.5）
```

**层级只用来「向下蕴含」**：查 `character:Vtuber` 要能命中所有成员的图。
展开逻辑与 [`PermissionResolver`](../src/commonMain/kotlin/moe/lolosia/web/service/permission/PermissionResolver.kt) 的组链完全同构（已访问 id 收敛、防环），一并复用那套心智模型。

### 4.4 `media_asset_tag` —— 关联

```sql
-- sql/media_asset_tag.sql
DROP TABLE IF EXISTS `media_asset_tag`;
CREATE TABLE `media_asset_tag` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `asset_id` bigint(20) NOT NULL,
  `tag_id` bigint(20) NOT NULL,
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'manual / rule / model',
  `provider` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '模型名+版本，如 wd14-v1.4',
  `confidence` double NULL DEFAULT NULL COMMENT '0~1；manual 为 NULL',
  `negative` tinyint(1) NOT NULL DEFAULT 0 COMMENT '人工否决：这张图明确不是该标签（§7.3）',
  `created_at` timestamp NULL DEFAULT NULL,
  `created_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `updated_by` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_asset` (`asset_id`, `deleted`),
  KEY `idx_tag` (`tag_id`, `deleted`),
  KEY `idx_tag_source` (`tag_id`, `source`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;
```

**没有 `UNIQUE(asset_id, tag_id)`**：同一个标签理论上可能同时有 `manual` 与 `model` 两条来源（判定时取最高优先级那条）。要去重就在服务层做（本项目一贯做法，见 rbac §4.4）。

### 4.5 `media_tag_rule` —— 零依赖兜底

```sql
-- sql/media_tag_rule.sql
DROP TABLE IF EXISTS `media_tag_rule`;
CREATE TABLE `media_tag_rule` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `match_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'path_prefix / path_contains / name_glob / ext',
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
```

**默认规则集放内嵌资源 `resources/media-rules.yaml`**（与 [`permissions.yaml`](../src/commonMain/resources/permissions.yaml) 同风格）：
库里 `media_tag_rule` 一条都没有时用它兜底，库里有规则就完全以库为准（**完全照搬 rbac §3.6 的两条来源优先级**）。

### 4.6 `media_embedding` —— 只存索引

```sql
-- sql/media_embedding.sql
DROP TABLE IF EXISTS `media_embedding`;
CREATE TABLE `media_embedding` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `asset_id` bigint(20) NOT NULL,
  `model` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '如 clip-vit-b32 / wd14-swinv2',
  `dim` int(11) NOT NULL,
  `row_index` bigint(20) NOT NULL COMMENT '向量本体在 .cache 的 <model>.f32 里的行号',
  `norm` float NULL DEFAULT NULL COMMENT '归一化前的模长，仅用于诊断',
  `created_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  `alive_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      GENERATED ALWAYS AS (if(`deleted` = 0, concat(`asset_id`, ':', `model`), NULL)) VIRTUAL,
  UNIQUE KEY `uk_alive_key` (`alive_key`) USING BTREE,
  KEY `idx_model` (`model`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;
```

### 4.7 为什么这些列是列、那些进 JSON

| 归置 | 判据 | 例子 |
| --- | --- | --- |
| **提升成列** | 要在 UI 里筛 / 排 / 建索引；取值域稳定 | `taken_at` / `camera_model` / `aperture` / `iso` / `gps_*` / `rating` |
| **进 `metadata_json`** | 只在详情页展示，或极少筛；字段随格式千变万化 | EXIF 的 `Software` / `WhiteBalance` / `SceneCaptureType`、IPTC、容器头的长尾 box |

不选 EAV（`media_metadata(asset_id, tag, value)`）的理由：
- 「我要补一个新的可筛字段」在 EAV 下要改 JOIN 与查询逻辑；在列方案下就是 `ALTER TABLE ... ADD COLUMN` + 一次性回填（`probe_version` 升版自动重探）。
- 详情页要展示几十个字段，EAV 得再拼一遍；JSON 直接原样下发。
- EAV 的行数 = 文件数 × 字段数（百万级），而提升成列后一个文件只占一行。

### 4.8 ⚠️ 落地注意（sqlx4k 相关）

1. **`dir_path` 是为绕开 `IN` 限制而设的**：列目录摘要时**不能**写 `where asset_id in (...)`。
   有了 `dir_path`，就是**两条单语句查询**搞定整个目录：
   ```sql
   select * from media_asset where dir_path = :dirPath and deleted = 0;                       -- 评级/尺寸/时间
   select t.* from media_asset_tag t join media_asset a on a.id = t.asset_id
     where a.dir_path = :dirPath and t.deleted = 0;                                           -- 整目录标签
   ```
   第二条**以 `media_asset_tag` 为驱动表** join 回来，符合 AGENTS.md §5 的「`@Query` 主表必须与实体 `@Table` 一致」。
2. **聚合 / GROUP BY 不要写进 `@Query`**：`@Query` 的返回被当成实体映射，`group by` 出的列对不上表结构。
   分面统计走**原生 SQL 逃生口**（`db.driver.fetchAll("select … group by …")` + `row.get("x")`，与 [`Database.ping()`](../src/commonMain/kotlin/moe/lolosia/web/database/Database.kt#L132-L141) 同一条路子），
   或**取回后在 Kotlin 里算**（与 `PermissionResolver` 的「全量取回 + Kotlin 合并」一致）。
3. **批量写入只能循环单条**（`@Query` 不支持 `IN`）：打一次标签的 N 个关联就是 N 条 `executeInsert`，包在一个 `ctx.transaction { }` 里。
4. **DB 不可用时列目录不能挂**：`files/list` 的索引摘要必须**best-effort** —— 库没连上就当「无标签、`rating = unknown`」，照常回文件列表。
   这与 `ctx.db` 抛 503 的既有语义不冲突：那条适用于「这个接口本质就是查库」，而这里是**增强**。

---

## 5. 扫描与身份收敛

### 5.1 新鲜度短路（照抄缩略图那套）

```
if (row != null && row.size == size && row.mtime == mtime) → 什么都不做
```

这是唯一的**零读取**路径，也是整库扫描能在几分钟内跑完的原因（`ThumbnailService.scanAll` 用的是同一招）。

> ⚠️ **扫描全程不外发**（§8.2）：这里只做本地的 stat / `quick_id` / EXIF probe / 缩略图 / 规则打标。
> 标签、描述、向量一律要等用户显式点「全盘分析」，**扫描器不认识 worker**。

### 5.2 合并算法

```
onScan(path):
  meta = stat(path);  row = findByPath(path)

  (1) 快捷路径：size+mtime 都没变 → 返回，结束（不读一个字节）

  (2) 路径行不存在，或 size/mtime 变了 → 算 quick_id（读首尾各 64KiB）
      hit = findByQuick(size, quick_id)
      · hit != null 且 hit.path != path
            → 这是「改名 / 移动 / cp 过后 mtime 变了」：
              把 hit.path 改成新 path、更新 size/mtime，
              **标签、评级、向量、EXIF 全部原样保留**      ← 方案的核心价值
      · 否则若 row != null（同路径、内容变了）
            → 标记派生数据失效（EXIF / 向量 / 缩略图重做），更新 size/mtime/quick_id
      · 否则（全新文件）
            → 插入新行，入队 probe(EXIF)

  (3) 低优先级后台：补全 content_sha256
      · 若 sha256 命中另一行 → 判定「同内容不同路径」：保留一行作为主行，
        另一行写 merged_into 并软删；标签合并到主行
      · 若与 quick_id 的判定冲突（quick 说同一张、sha 说不是）→ **以 sha 为准**，把误并的两行拆开
```

- 步骤 (2) 让**日常整理工作（改名、搬目录）零成本保住标签**。
- 步骤 (3) 只在后台跑，且**可以被完全关掉**（配置 `hashMode = quick`）—— 代价是跨盘去重与「换盘后认回」的能力下降，功能不受影响。
- **同一内容出现在多个位置时**（复制、备份、同一张图放进两个相册）：步骤 (3) 会把它们收敛成**一行**，标签共用；
  代价是**非代表位置在文件列表里读不到评级与标签**。这是**已确认接受的取舍**（不拆表，理由见 §16-12）。

### 5.3 缺失与删除

**不要一发现不见就删行。** 媒体盘可能临时不可达（WebDAV 挂载抖动、机械盘休眠唤醒、网络短暂断开），
一次误判就把几个月的人工标签清空。

| 状态 | 动作 |
| --- | --- |
| 本次扫描未见 | 只写 `missing_since = now()`，`deleted` 不动；接口照常返回（前端显示为「暂不可用」） |
| 连续 N 次扫描（默认 3）仍未见 | `deleted = 1`（软删），标签保留在关联表里 |
| 文件又回来了（`quick_id` / `sha256` 命中） | 清空 `missing_since`、`deleted = 0`，标签**原样复活** |

软删的行长期占位无害；真要清理，交给一个单独的 compaction（物理删除 `deleted = 1 且 older than 90 天 且 merged_into is null`）。

### 5.4 哈希成本（决策依据）

| 阶段 | 每文件读取量 | 10 万张图（均值 3MB / 张）总量 | 机械盘 30MB/s 估计 |
| --- | --- | --- | --- |
| stat 扫描 | 0 | 0 | 分钟级 |
| EXIF probe | ≤ 256KiB（多数 < 64KiB） | ~6GB | ~20 分钟 |
| `quick_id` | ≤ 128KiB（仅变更文件） | 首次 ~12GB | ~40 分钟 |
| 全量 `sha256` | 全文 | ~300GB | **~3 小时** |

结论写在配置里：`hashMode` 默认 `quick`；`full` 只在「显式开启且盘空闲」时做，并且**必须可中断、可续跑**（按 `content_sha256 is null` 队列推进，重启接着跑）。

---

## 6. 元数据抽取（EXIF 等）

### 6.1 各格式能拿到什么

| 格式 | 容器/段 | 能拿到 | v1 是否做 |
| --- | --- | --- | --- |
| JPEG | APP1 (Exif) + APP13 (IPTC) + APP1 (XMP) | 光圈 / 快门 / ISO / 焦距 / 镜头 / 机身 / 时间 / 朝向 / GPS / 软件 | ✅ EXIF 主体 |
| TIFF | IFD 链 | 同 EXIF（EXIF 本就是 TIFF 的 IFD 结构） | ✅（与 JPEG 共用一个解析器） |
| PNG | `eXIf` chunk | 同 EXIF（较少见） | ✅ 顺手 |
| WebP | `EXIF` / `XMP` chunk | 同 EXIF | ✅ 顺手 |
| HEIC / HEIF | ISOBMFF box（`meta`/`iinf`/`Exif`） | 同 EXIF | ❌ v1 跳过（要另写一层 box 解析），worker 可用 exiftool 补 |
| MP4 / MOV | `moov/mvhd`、`moov/udta/meta`、`©xyz` | 时长 / 宽高 / 创建时间 / 码率 / GPS | ✅ 基础几项 |
| MKV / WebM | EBML | 时长 / 宽高 / 创建时间 | ❌ v1 跳过（EBML 另写） |
| MP3 / FLAC / M4A | ID3 / Vorbis comment | 标题 / 艺术家 / 专辑 / 时长 | ⭕ 可延后 |
| 任意 | 文件系统 stat | 大小 / mtime / ctime | ✅ |

### 6.2 为什么自研解析器

| 方案 | 代价 |
| --- | --- |
| **自研 `util/exif/`** | 几百行 Kotlin；无依赖；三目标自动可用；风格同 `BCrypt.kt` / `util/captcha/` |
| 引 libexif / exiv2（cinterop） | 要为 3 个目标各备一份静态库入库 + `.def`；exiv2 是 C++，K/N 绑定几无可能；只为一个「读几十个字段」的需求，性价比极低 |
| 交给外部 worker（exiftool） | 可行，但**引入对 worker 的硬依赖** —— 而没有 worker 时应当仍能用（§8.6）。故只能作为**补充**（HEIC / MKV 这类自研覆盖不到的） |

**分工**：自研解析器负责 `image/*` 的 EXIF 与 MP4/MOV 的基础字段（覆盖率 95%，零依赖）；
worker 若在，可回一份更全的 `metadata`（HEIC / MKV / 音频），**字段名归一化后写同一批列**。

### 6.3 时间与时区

EXIF 的 `DateTimeOriginal` 是**没有时区的墙上时间**（除非有 `OffsetTimeOriginal`）。
直接用 `Instant` 会失真（相机的「14:30」不是 UTC 的 14:30）。故**三列并存**：

| 列 | 内容 | 用途 |
| --- | --- | --- |
| `taken_at_local` | 原文 `2024:06:24 15:57:06` | 展示（用户看到的就是相机上那个时间） |
| `taken_at_offset_min` | EXIF `Offset*`，没有则 `NULL` | 换算依据 |
| `taken_at` | 按 offset 折算的 UTC；**offset 缺失时**按 `files.media.defaultTimezoneOffset`（默认 +480 即 UTC+8）折算 | 排序 / 范围筛选 |

> 这是 AGENTS.md §5「时间列一律 `Instant`」的**有意例外**：拍摄时刻的语义是「相机本地时间」，
> 强转 UTC 会把跨时区旅行的照片全部错位。业务时间（`created_at` 等审计列）仍一律 `Instant`。

### 6.4 GPS 与隐私

- GPS 是**最敏感的一类元数据**（精确到米 + 时间，等于行程轨迹）。
- 默认策略：
  - 库里保存，但**不下发给无权限者** —— 单独权限节点 `lolosia.media.location`（默认只给 `owner` / `admin`）。
  - 列表接口**永不带 GPS**；详情接口按权限决定是否含 `gps` 字段。
  - 可选「对外抹除」：若将来要把这部分图分享出去，导出/分享链路必须显式剥离 GPS。
- 反查地名（反向地理编码）**不做** —— 那要外部服务与流量，收益不成正比；只在详情页显示经纬度。

### 6.5 面对不可信输入的安全口径

EXIF 解析器吃的是**可能来自网络、被恶意构造的文件**，与内嵌 C 图像库同一威胁模型：

- **绝不跟随任意偏移递归**：只走「IFD0 → ExifIFD → GPSIFD → InteropIFD」这几条**白名单路径**。
- **每层限制项数与总读取量**（如 IFD 项 ≤ 512、总解引用字节 ≤ 64KiB），越界即放弃。
- **所有读写都做边界检查**：任何 offset + length 超出 buffer 即返回 `null`。
- **坏数据只回 `null`，不抛异常、不崩进程**（与 C 库「坏图只回 null」完全一致的口径）。
- 词表里的标签名来自模型/用户输入 → 写库前统一 trim + 长度截断 + 拒绝控制字符。

---

## 7. 标签体系

### 7.1 命名空间与层级

```
namespace:name            character:白上吹雪
namespace:name            series:VRChat
namespace:name            object:巧克力
```

- `namespace` 固定枚举（`character` / `series` / `object` / `action` / `scene` / `meta` / `user`），避免「人物 / 角色 / 人物名」三种写法各占一行。
  `action` 单列是因为**「人物在做什么」与「画面里有什么」是两类检索意图**（「所有坐着的图」vs「所有含巧克力的图」）。
- 层级用 `parent_id`（`character:Vtuber → character:白上吹雪`）。**只向下蕴含**：选 `Vtuber` 含全部成员。
- `user` 命名空间保留给纯人工的自由标签（「我的壁纸」「待整理」这类），不参与模型打标。

### 7.2 `rating` 为什么单独成列，且**只由人工写**

| 理由 | 说明 |
| --- | --- |
| 它是**强制行为**的触发器 | 前端遮罩、服务端是否放行原图、**是否外发分析**，都要一个稳定、不可被误删的值 |
| 标签是可变的运营数据 | 词表可以改名 / 合并 / 软删；评级不能因为「`NSFW` 这个标签被改名」而失效 |
| 值域有限且互斥 | `unknown / safe / questionable / explicit`，天然是一列枚举 |
| 要用它做**默认过滤** | `where rating != 'explicit'` 是最高频的查询之一，必须是索引列 |
| **人工判定，模型不参与** | 不做自动 NSFW 识别（§15）：这是**合规闸门**，宁可人工漏标，也不能让「自动判错」把内容送出去 |

写入来源只有两种：`manual`（属性页勾选）与 `rule`（目录 / 文件名规则预置，如 `*r18*`、`NSFW/`）。
**worker 的响应里即使带了评级字段，后端也一律忽略**（§8.3）。

同时**照常生成一条 `meta:explicit` 标签**（`source` 跟随评级来源），供「按标签检索」复用 —— 但**列是权威，标签是投影**。

### 7.3 来源优先级与人工否决

判定「这张图有什么标签」时的合并规则（`manual > rule > model`）：

| 情形 | 结果 |
| --- | --- |
| 同一标签有 `manual` 正标 | 取用（无论 model 说什么） |
| 同一标签有 `manual` 的 `negative` | **判定为无**，且重新跑模型时**跳过写入**（避免「人工纠正过又被模型加回来」） |
| 只有 `model` 正标 | 置信度 < 阈值（默认 0.5）的不下发、不建关联 |
| `rule` 与 `model` 冲突 | `rule` 优先（规则是人写的） |

`rating` 的合并同理，但只有两级：`manual` 最大；否则取 `priority` 最小的 `rule`；都没有就是 `unknown`（§7.2）。
`analysis_skip` 取「属性页显式勾选」与「`rating = explicit`」的 **OR** —— 任一为真就禁止外发（§10.2）。

重新打标（换模型 / 升级版本）时的动作：**只重写 `source = 'model'` 的行**，`manual` 与 `rule` 的行一个都不动。
触发方式是显式的 `POST /api/media/reanalyze`（§11.2），**不存在「后台自己重跑一遍」**。

### 7.4 规则打标（零依赖兜底，v1 就要有）

即使一个模型都没有，下面这套也能把「大量老照片」整理得可用：

| 规则类型 | 例子 | 效果 |
| --- | --- | --- |
| `path_prefix` | `VRChat/` | 该目录（含子目录）全部图片 → `series:VRChat` |
| `path_prefix` | `VRChat/白上吹雪/` | 再加 `character:白上吹雪`（多规则叠加，`priority` 决定 rating 冲突） |
| `path_contains` | `Moe` | 路径里出现该串就命中；娃照那种「日期-主题」目录（`20260523-Moe初次拍摄`）只能这么匹配 |
| `name_glob` | `*r18*` / `*nsfw*` / `*_h*` | `rating = explicit` |
| `ext` | `heic` | 标记「需要 worker 补 EXIF」 |

这套东西的价值：**新盘挂上来当天就能按目录结构搜出东西**，不必等模型跑完几万张。

> 规则打标是**本地派生**（只读路径与文件名，不读内容、不出网），因此**允许在扫描时自动跑**，
> 与「外发分析必须显式触发」（§8.2）不冲突。想连它都关掉、完全手动，就把 `files.media.rules.enabled` 设为 `false`。

### 7.5 标签是「事后长出来的」：自由标签 · 改名 · 挂父级 · 合并

真实的用法**不像 §7.1 那样一开始就有干净的树**。几百上千张照片，不认识里面的角色、也叫不出动作的名字 ——
所以模型必须支持**先随便写、之后再收拾**：

| 阶段 | 用户做的事 | 落到模型上 |
| --- | --- | --- |
| ① 先记下来 | 属性页里手输一个文本标签：「银发」「拿剑」「夜景」 | `media_tag(namespace = 'user', parent_id = NULL)` —— **根级自由标签是一等公民**，不是半成品 |
| ② 后来认识了 | 知道那是「白上吹雪」 | 建 `character:白上吹雪`，把「银发」那批关联**合并**过去（旧名留作 alias） |
| ③ 组织起来 | 给「白上吹雪」挂到 `character:Vtuber` 下 | 改 `parent_id`（`media/tag/move`） |
| ④ 拼写统一 | 发现「白上吹雪酱」和「白上吹雪」是同一个 | **改名 + 别名**，或直接**合并** |

三个写操作（都挂 `lolosia.media.admin`，都在**同一个事务**里完成）：

| 操作 | 语义 | 不变量 |
| --- | --- | --- |
| `media/tag/rename` | 改 `name`（或 `namespace`），旧名**追加进 `alias`** | **旧名仍搜得到**：检索先按 `alias` 归一，再按 `id` 取 |
| `media/tag/move` | 改 `parent_id`（传 `null` = 拎回根级） | **禁止成环**：目标不能是自己或自己的子孙 —— 这里要在**写入时拒绝**，不是运行时收敛 |
| `media/tag/merge` | A 的关联全部挪到 B；A 记 `merged_into = B` 并软删 | **关联一条都不丢**；命中 A 的查询沿 `merged_into` 链重定向到 B |

**输入侧归一**（这一条比上面三个操作更能救命）：属性页输入标签时，先拿输入串去 `name` 与 `alias` 里做匹配
（忽略大小写、全半角、首尾空白），命中就**复用已有标签** —— 否则「白上吹雪 / 白上吹雪酱 / fubuki」会分裂成三行，
而这正是手打标签最常见的死法。

两个配套查询：

| 查询 | 用途 |
| --- | --- |
| `media/tag/orphans` | **只被引用 1 次**的标签（错别字、随手打的）—— 人工清理与合并的待办列表 |
| `media/tag/tree`（带 `count`） | 一眼看出哪些标签是真的在用，哪些是建了没管 |

> **「标签与目录正交」在数据模型里的体现**：`media_asset.path` 只是**当前所在位置**，
> 标签挂在 `asset` 上，而 `asset` 靠 `quick_id` / `content_sha256` 认人（§5.2）。
> 所以把图从 `杂图/` 挪到 `VRChat/白上吹雪/`，**标签一个都不会丢**，也不会因为换了目录就查不到。
> 目录树回答「文件在哪」，媒体库回答「这张图是什么」，两者互不干扰。
> （同一内容被**复制**到多个位置时，会把它们收敛成一行、共用标签，但非代表位置在列表里读不到摘要 —— 已确认接受的取舍，见 §16-12。）

> ⚠️ 现在是**单父**（`parent_id` 单列，与 `sys_role.parent_id` 同构）。
> 「白上吹雪同时属于 `Vtuber` 与 `hololive`」这种多归属做不了，要引入关联表并改成图遍历 —— 列进 §16。

---

## 8. 外发分析：标签 · 描述 · 向量（外部 worker）

### 8.1 为什么必须出进程

| 理由 | 事实 |
| --- | --- |
| 没有运行时 | K/N 侧没有可用的推理框架；ONNX Runtime 的 C API 要 cinterop + 三目标静态库，与「产物入库、日常构建不碰 C 工具链」的既定策略直接冲突 |
| 没有算力 | 板子 ARM64 4 核跑 CLIP 每张图数秒；10 万张要跑几天，还会把机械盘读满 |
| 模型会换 | 模型迭代很快（WD14 → v3、CLIP → SigLIP）；把模型关进可执行文件，等于每次换模型都要重新链接发布二进制 |

### 8.2 触发模型：**必须显式触发，不做自动**

外发（把图交给模型）**永远不会由后台定时器发起**，只有人在界面上主动点了才开始：

| 动作 | 接口 | 说明 |
| --- | --- | --- |
| 全盘分析 | `POST /api/media/analyze/start { scope: "all" }` | 把全部 `analyze_state = 'none'` 的资产置 `queued`，唤醒工作协程 |
| 范围分析 | `…{ scope: "path", path }` / `{ scope: "query", query }` / `{ paths: [...] }` | 只分析某个目录 / 某次筛选结果 / 勾选项 |
| 查看进度 | `GET /api/media/analyze/status` | `{ state, total, done, failed, skipped, currentPath }` |
| 取消 | `POST /api/media/analyze/cancel` | 清空 `queued`、中止当前请求；已完成的保留 |

- **后台周期扫描（`indexIntervalSeconds`）只做本地的事**：stat、`quick_id`、EXIF probe、缩略图、规则打标 —— **一个字节都不出网**。
- **进度与续跑不另建作业表**：游标就是资产自己的状态列（`analyze_state ∈ {none, queued, running, done, failed, skipped}`），
  重启后从 `queued` / `failed` 接着跑；进度 = `count(done) / count(总目标)`（原生 SQL 聚合，§4.8-2）。
- 入队有**批上限**（`analyze.batchSize`，默认 500）：避免一次把几万条压进内存队列，让用户能看清第一批的结果再继续。

**推荐操作顺序**（也是界面上该给的引导文案）：

```
① 挂盘 → 本地索引自动跑（EXIF / 缩略图 / 规则标签；不外发）
② 人工整理与定级（属性页、批量应用；NSFW 在此标记）
③ 点「全盘分析」→ 闸门挡掉已定级的 NSFW → 其余才外发打标 / 描述 / 向量
```

这样 §10.2 的闸门就从「兜底」变成**主路径**：绝大多数 NSFW 在第 ② 步就被标掉了，第三步的队列里本来就不该有它们。

### 8.3 协议（后端 ↔ worker）

```
POST {worker.url}/v1/analyze
Authorization: Bearer {worker.token}
{
  "source": { "url": "http://<backend>/api/files/raw?path=…&exp=…&sig=…" },   // 或 "bytesBase64": "…"
  "tasks":  ["tags", "caption", "embedding", "metadata"],       // 不含 rating（§7.2：分级是人工的）
  "vocab":  ["Vtuber", "白上吹雪", "VRChat", "巧克力", …],        // 零样本词表，来自 media_tag
  "maxBytes": 67108864
}
→ 200
{
  "provider": { "name": "wd14", "version": "v1.4" },
  "tags":     [ { "namespace": "character", "name": "白上吹雪", "confidence": 0.93 } ],
  "caption":  "白上吹雪在 VRChat 的夜景街道上拿着巧克力",
  "embedding":{ "model": "clip-vit-b32", "dim": 512, "normalized": true, "vector": [0.013, …] },
  "metadata": { "…": "…" }                                          // 可选：更全的 EXIF / 容器头
}
```

> 即使 worker 主动回了一个 `rating` 字段，**后端也一律丢弃** —— 分级不能由模型写进库（§7.2）。
> 「会不会被服务商判不合规」这件事，靠**不把文件发出去**解决，而不是靠模型自己判。

约定：

- ⚠️ **进队列前与发包前各挡一次闸门**（§10.2）：`rating = explicit` 或 `analysis_skip = 1` 的文件**根本不入队**；
  入队后评级被改的，在真正发 HTTP 之前重新读一次。**这是本方案唯一一处「合规优先于功能」的地方，不允许被优化掉。**
  注意闸门与「显式触发」（§8.2）是**两层独立的保险**：显式触发保证「平时不外发」，闸门保证「即使点了全盘分析，NSFW 也不外发」。
- worker **无状态**，不碰 DB、不碰盘（除自己的模型权重）；所有持久化都在后端。
- worker 拿图的方式二选一：**后端签发一条内部直链**（`sig` 由后端自己签，允许取原图），或直接 POST 字节。
- 单次超时（默认 120s）；失败**不重试超过 1 次**，记 WARN 并把该资产置 `analyze_state = 'failed'`，下一轮显式分析会重试。
- 协议里带 `provider.version`：标签落 `media_asset_tag.provider`，资产落 `media_asset.analyze_provider` —— **这是「换模型重跑」的筛选依据**。
- `caption` 与标签同规矩：`caption_source = 'manual'` 时**模型不得覆盖**（§7.3）。

### 8.4 模型选型建议（属 worker 内部的事，后端只认协议）

| 任务 | 候选 | 说明 |
| --- | --- | --- |
| 二次元图标签 | WD14 Tagger（SwinV2 / ViT） | 输出的是 **booru 规范标签**，天然覆盖 `白上吹雪` / `VRChat` 这类；日语作品名需要一份 别名映射 表 |
| 通用零样本标签 | CLIP / SigLIP + 词表 | 适合「巧克力」「猫」「夜景」这类开放词汇；词表就是 `media_tag` 表 |
| 描述生成（caption） | 小 VLM / 多模态 captioner | 一条自然语言描述，落 `media_asset.caption`；**只作展示与模糊检索**（`LIKE`），不参与结构化筛选 |
| 文本/图像同空间检索 | CLIP（图 + 文同一空间） | 语义检索的前提是「文字 query」和「图片」在同一向量空间 |

**敏感分级不在这个表里**：它是人工判断的合规闸门，不由模型产出，也不作为分析任务下发（§7.2 / §10.2）。
worker 协议里的 `tasks` 因此只有 `tags / embedding / metadata` 三项。

> worker 若同时给出 CLIP 向量与 WD14 标签，两者矛盾时按 §7.3 的来源优先级处理，**不要试图在模型层调和**。

### 8.5 带宽现实（必须正视）

| 路径 | 实测 | 影响 |
| --- | --- | --- |
| PC 有线 → 板子 | 75–114MB/s | 出板子打标可行 |
| 板子 → PC 经 SMB | 17.2MB/s | 10 万张 × 3MB = 300GB ≈ 5 小时纯传输 |
| 板子 → PC 经 Wi-Fi | 3.2MB/s | **不可行** |

结论：

1. **别指望「一次全量重打」**。默认走**增量**：只对新出现 / 内容变化的文件打标。
2. 若机器上能**挂载同一块盘**（PC 直接读机械盘），优先让 worker 本地读 —— 传输成本归零。现网这块盘同时挂在 Samba 上，物理上可行。
3. worker 的**并发压到 1~2**：媒体盘是瓶颈，且并发打标只会让机械盘来回跳（与 `ThumbnailService` 单协程的理由一字不差）。

### 8.6 后端侧的抽象与降级

```kotlin
// service/media/worker/TaggingProvider.kt
interface TaggingProvider {
    val name: String
    suspend fun analyze(request: AnalyzeRequest): AnalyzeResult?
}
```

- `HttpTaggingWorker`：默认实现，走既有 `api/` 的 Ktor Client。
- `NoopTaggingProvider`：`files.media.worker.url` 为空时装配它 —— **整个系统仍然可用**（只有 EXIF + 规则打标），
  这也是「不把外发分析做成硬依赖」的落地方式。
- **闸门放在 `MediaAnalyzeService`，不在 provider 里**：provider 只负责「怎么发」，不负责「该不该发」——
  换 provider（或将来多接一个）都不会绕过闸门。
- 与 `ThumbnailService` 一样由 `@Service` / `@Injectable` 进容器，不做全局单例。

---

## 9. 检索

### 9.1 能力矩阵（v1 目标）

**检索是全局的**：`media/search` 默认扫全库，**不需要先一层层进目录**；目录（`path` 前缀）只是众多可选筛选维度之一。
这正是「标签是全局索引」的落地形态 —— 索引跟着内容走（§5.2 / §7.5），与文件放在哪个文件夹无关。

| 查询 | 形态 | 落地 |
| --- | --- | --- |
| 按标签（含子标签） | `?tag=character:白上吹雪&includeDescendants=1` | `media_asset_tag` join，子标签在 Kotlin 展开 id 集合后**循环单条**（或直接用 `tag_id` 精确查、子标签查走原生 SQL） |
| 按评级 | `?rating=safe,questionable` | `media_asset.rating` |
| 按元数据 | `?cameraModel=…&isoMin=3200&apertureMax=2.0&takenFrom=…&takenTo=…` | 提升列直查 |
| 有 / 无 GPS | `?hasGps=1` | `gps_lat is not null` |
| 文本 | `?q=…` | `path` / `name` 的 `LIKE`（个人库规模够用；不引 ES） |
| 语义 | `?mode=semantic&q=…` | 文本 → worker 算 query 向量 → Kotlin 余弦 topK（§9.2）。`explicit` 没有向量，天然不在结果里（§10.2） |
| 相似图 | `GET /api/media/similar?path=…` | 同上，用该图自己的向量 |
| 分面 | `GET /api/media/facets?…` | 原生 SQL `group by` + `count(*)`（§4.8-2） |

### 9.2 语义检索的算法

```
1. query 向量 = worker.embed(text)          // 文本与图片同一空间（CLIP 系）
2. 候选集 = DB 过滤（标签 / 目录 / 时间 / 评级）→ row_index 集合
3. 读 <model>.f32 整块到内存 → 只对候选行算 点积（已归一化 → 即余弦）
4. topK + 阈值（默认 0.2），回 asset_id → 拉 media_asset 摘要
```

- **归一化在写入时就做**（worker 返回 `normalized: true`，否则后端自己除 `norm`）—— 检索时省一次开方，且避免逐行比较时的重复运算。
- 结果里**必须带 `score`**，让前端能解释为什么命中；纯 topK 无分数会让「不相关的结果」看起来同样权威。
- 若候选集为空（没给任何过滤条件）且库超过 ~20 万条，**先按标签/时间粗筛再算**，而不是全库点积。

### 9.3 分面与聚合的 sqlx4k 落地

```kotlin
// 原生 SQL 逃生口，绕开「@Query 返回必须是实体」的约束
val rs = db.driver.fetchAll(
    "select a.camera_model as model, count(*) as cnt from media_asset a " +
        "where a.deleted = 0 and a.camera_model is not null group by a.camera_model order by cnt desc limit 50"
).getOrThrow()
rs.rows.forEach { println("${it.get("model").asString()} ${it.get("cnt").asString()}") }
```

分面结果**不要缓存进 `sys_cache`**：它是纯派生、可重算，且带 `deleted` 维度，失效逻辑比收益贵。

### 9.4 给智能体的检索入口（可编排，不自作聪明）

「标签是全局索引」这件事的另一面是：**检索入口必须能被程序调用**，不能只给人点。能，但有三条规矩。

**① 自描述：先问「能查什么」，再查**

```
GET /api/media/manifest
→ {
    "fields":     [ { "name": "cameraModel", "type": "string" },
                    { "name": "iso",         "type": "int", "ops": ["min","max"] },
                    { "name": "takenFrom",   "type": "timestamp" }, … ],
    "ratings":    ["safe", "questionable", "explicit"],
    "namespaces": ["character", "series", "object", "action", "scene", "meta", "user"],
    "tags":       [ { "id": 12, "key": "character:白上吹雪", "parentId": 3, "count": 184 }, … ],
    "modes":      ["filter", "semantic"]
  }
```

调用方（人写的脚本、或 agent）**不需要把字段名写死**，也不该靠猜。`count` 是必要的 —— agent 得知道哪些标签真的有货，
否则会把时间浪费在空标签上。

**② 给可组合的原语，不给黑盒问答**

`media/search` 的入参本身就是正交组合的（标签 ∧ 评级 ∧ 元数据 ∧ 时间 ∧ 文本 ∧ 语义排序）。
推荐的 agent 行为是**先 `facets` 探路、再收窄**：先看清这个库里相机有哪些、标签有哪些，再下查询 ——
比让 agent 一次编一个大 query 稳得多。

> **「自然语言 → 结构化查询」放在 agent 侧**（它本来就有推理能力），后端不提供这一步。
> 真要做在服务端，那就是又一个模型任务，必须走 §8 那套（出进程 + 显式触发），**不能塞进只读检索接口里**。

**③ 翻页要稳定：游标，不是 offset**

结果按 `(taken_at desc, id desc)` 排序，翻页用 `cursor`（上一页末行的 `(taken_at, id)`）。
不用 `offset` 的原因有两个：深分页在几十万行上越翻越慢；而且资产在翻页途中被重新索引会让结果漂移。

**④ 结果自带「能直接取图」的凭据**

每条结果带 `path` + `rating` + `caption` + `tags` + `score` + **`exp` / `sig`**（§10.4）——
agent 拿到就能去取图，不必再问一轮。签名里的 `flags` 由**签发者权限**决定，
所以**agent 用谁的凭据、就只能看到谁被允许看的东西**；默认它拿不到 `explicit` 的图，也拿不到 GPS（§6.4）。

**⑤ 只读与写入分开**

`media/{manifest,search,similar,detail,facets}` 全是**只读**，挂 `lolosia.media.read`，链路上没有任何副作用。
agent 要**改**（打标签、定级）必须走 `lolosia.media.write`，那是另一条链路，且闸门与审核照旧（§10.2）。

> **不实现 MCP 协议**：本项目是单二进制 K/N 服务，为 MCP 引一套协议栈不划算；
> agent 侧只要能发 HTTP + 读 JSON 就够了（要不要薄适配见 §16）。

---

## 10. 人工评级 · 外发闸门 · 遮罩

### 10.1 属性编辑页（人工定级，形如 Windows 文件权限对话框）

`files/detail` 已经在下发单文件详情，属性页在它之上加两块**可编辑**区（其余只读）：

| 区块 | 内容 | 写回 |
| --- | --- | --- |
| 通用（只读） | 路径 / 大小 / mtime / 尺寸 / 时长 / MIME | — |
| 元数据（只读） | EXIF 全量（光圈 / 快门 / ISO / 机型 / 镜头 / 时间 / 朝向）、GPS（另需 `lolosia.media.location`） | — |
| 标签（可编辑） | 词表选择器 + 自由标签；支持「否决」（这张图明确不是这个标签） | `media/tags/{attach,detach,deny}` |
| **描述（可编辑）** | 一句话描述；手填后模型不再覆盖（`caption_source = manual`） | `media/property/save` 的 `caption` |
| **评级（可编辑）** | 单选：`unknown / safe / questionable / explicit` | `media/property/save` |
| **分析策略（可编辑）** | 勾选「不上传到外部分析」 | `media/property/save` 的 `analysisSkip` |

两条行为约定：

1. **`rating = explicit` ⇒ `analysisSkip` 强制为 1**（界面上该勾选框自动置灰打勾）。
   反向不成立：`analysisSkip = 1` 而 `rating = safe` 是合法状态 —— 「这张不涉黄，但我就是不想让它上云」。
2. **可批量应用**：属性页支持「应用到本目录（含子目录）」，一次给一整批图定级 ——
   对应 `media/property/apply`（服务侧循环单条，不是新的批量语义）。

### 10.2 外发闸门（本方案里唯一「合规优先于功能」的地方）

**NSFW 一律不外发**：不做自动识别，也不把图交给第三方模型去判 —— 那正是会换来「内容不合规」错误的那一步。

两处检查，缺一不可：

| 时机 | 检查 | 为什么两处都要 |
| --- | --- | --- |
| **入队时**（`MediaIndexService.enqueue`） | `rating != 'explicit' && !analysis_skip` | 省掉无用排队；绝大多数文件在这里就被滤掉 |
| **发包前**（真正 `POST /v1/analyze` 之前重新读一次该 asset） | 同上 | 文件可能在排队期间被人工标成 NSFW —— 队列是异步的，中间隔着几分钟到几小时 |

被挡掉的文件：

- **不发标签 / 不发 embedding / 不发元数据**给任何第三方；**本地能做的照做不误**（EXIF 解析、`quick_id` / sha256、缩略图都出不了网，不涉合规）。
- **没有向量 ⇒ 不会出现在语义检索结果里**（这是期望行为，不是副作用）。
- 记 INFO 日志（`跳过外发分析：<path>（rating=explicit）`），`media:index` 负载带 `skipped: true`，让前端能解释「这张为什么没有标签」。

逃生口：`files.media.worker.processNsfw`（默认 `false`）。**只在你确认 worker 是自建、素材不出你控制的机器时**才打开，
打开时启动日志打一条 WARN。默认对所有 worker 生效 —— 「本地 worker」这个前提很脆：今天本地跑，明天换成云 API 就漏了。

### 10.3 前端遮罩（表现层）

- `FileEntry` / `MediaDetail` 增量带 `rating` 与 `sample`（§11.1）→ 网格里对 `explicit` / `questionable` 的项盖遮罩，
  显示「闭眼遮罩」占位（低饱和底 + 眼睛图标 + 「点击查看」），点击后按本地偏好展开。
- **遮罩态一个字节都不拉**：真图（含缩略图）在放行之前完全不请求，底下垫的是 `sample` —— 8×8 RGB565
  采样按 `object-fit: cover` 铺满格子，够看出「这是张偏暖的、横竖构图」这种粗信息，又不可能看清内容。
- `sample` 对**所有**图片都有（不只是敏感的）：真图加载出来之前先顶上，加载完就撤掉 —— 缩略图那几十 KB
  在映射盘上也要等一会儿，这块色斑比白板舒服。
- 偏好（`showNsfw: false|blur|always`）存前端本地即可，**它不是授权**（下一节才是）。

### 10.4 把「能不能看」折进直链签名（关键，不是可选项）

现状的漏洞：`/api/files/{thumb,raw}` 在 `AuthInterceptor` **白名单**里，只验 `sig = HMAC(key, "path|exp")`。
**签名只证明「曾经有人列过这个目录」，不携带任何授权信息** —— 在没有 NSFW 概念的今天无所谓（文件管理器只给 admin），
一旦引入分级，就等于「任何拿到 URL 的人（含被转发、被浏览器历史缓存）都能看到 NSFW 原图」，前端遮罩形同虚设。

**改法**：签名串加一段 `flags`，由**有会话、有权限的签发方**决定。

```
sig = HMAC-SHA256(key, "path|exp|flags")

flags 位：
  bit0 RAW   允许取原图（否则 raw 只能回服务端模糊图 / 403）
  bit1 NSFW  允许取 NSFW 内容
```

| 环节 | 现在 | 改后 |
| --- | --- | --- |
| 签发（`files/list` / `files/detail` / `media/*`） | `signer.sign(path, exp)` | `signer.sign(path, exp, flags)`，`flags` 由控制器按 `ctx.hasPermission("lolosia.files.nsfw")` 与文件 `rating` 决定 |
| 校验（`thumb` / `raw`，白名单内） | `verify(path, exp, sig)` | `verify(path, exp, flags, sig)` —— **不看会话，只看签名里的 flags** |
| 无权者拿到 URL | 能看 | 看得到非 NSFW；NSFW 一律 `403`（`raw`）/ 回模糊图（`thumb`） |

要点：

- **不引入会话**：白名单的设计（`<img src>` 带不了请求头）原样保留。凭据的**内容**由有权限的人签发，这本身就是一个完整的授权链路。
- **是破坏性契约**：老前端只传 `path/exp/sig` 必然校验失败。本项目前后端同仓同部署，约定**同期升级**；
  若必须灰度，就让 `flags` 缺省时退化为旧语义（只放行 `rating in (safe, unknown)`），文档里明确这是过渡态。
- `flags` 参与 HMAC，**伪造不了**（改一位就等于改了整条签名）。

### 10.5 服务端模糊图（可选，P4）

前端那一侧已经用 `sample` 解决（§10.3）：遮罩态**不请求真图**、垫 8×8 采样，不需要多一档缓存。

这一节剩下要挡的是另一件事：**拿到直链 URL 的人**。`/api/files/{thumb,raw}` 只验签名（§10.4），
签过名的 URL 一旦外流（转发、浏览器历史、日志），对方照样取得到原样缩略图。真要堵，
就在 `ThumbnailService` 里对 `explicit` 的图**额外生成一档模糊占位**
（`<hash>.256.blur.webp`：先缩到 8px 再放大回 256，失真到不可辨认），`thumb?blur=1` 取它（§10.4 的 `flags` 决定给哪一档）。
v1 不做：文件管理器当前只给 `admin`，§10.4 的签名已经够；服务端多一档就多一份生成成本与缓存。

---

## 11. 接口契约

> 新增接口路径**新增即定**（本方案即契约）；`files/*` 现有接口的路径与出参形状**不改**，只**增量加字段**。

### 11.1 现有接口的增量

`FileEntry`（`dto/FileDtos.kt`）加四个字段（都有默认值，老前端不受影响）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rating` | `String = "unknown"` | 评级；DB 不可用时恒为 `unknown` |
| `indexed` | `Boolean = false` | 是否已被媒体索引（前端据此决定「未索引」样式） |
| `takenAt` | `Long = 0` | 拍摄时刻（毫秒，UTC）；无则 0 |
| `sample` | `String = ""` | 8×8 RGB565 采样（hex）；没有为空串。缩略图生成时顺带产出（§10.3） |

`MediaDetail` 同样加 `sample`；`media:index` 事件也带它，好让网格在索引跑完时立刻拿到采样、不必重列。

> **列表不带标签正文**：标签在 `/api/media/detail` 给全。
> 若将来要在网格里显示标签角标，走同一目录的那条 join 查询即可（§4.8-1），不必改契约。

> **标签项带来源与否决位**：`MediaTagItem` 除 `namespace` / `name` 外还给 `source`（同标签多来源里按
> `manual > rule > model` 合并后生效的那条）与 `negative`。人工否决的标签**照列不隐藏** —— 属性页要把
> 「这张图明确不是什么」显示出来，否则否决会变成一个看不见的写操作。

### 11.2 新增接口（全部挂 `/api/media/*`）

| 接口 | 入参 | 出参 | 权限 |
| --- | --- | --- | --- |
| `GET /api/media/detail` | `path` | 完整元数据 + 标签 + 评级 + 是否有向量 + 文件名/尺寸 | `lolosia.media.read`（GPS 另需 `lolosia.media.location`） |
| `POST /api/media/tags/attach` | `{ path, tags: [ {namespace,name} ] }` | `MessageResult` | `lolosia.media.write` |
| `POST /api/media/tags/detach` | `{ path, tags: [...] }` | `MessageResult` | `lolosia.media.write` |
| `POST /api/media/tags/deny` | `{ path, tags: [...] }` | `MessageResult` | `lolosia.media.write`（写 `negative = 1`） |
| `GET /api/media/folder-tags` | `path` | `List<MediaTagItem>`（该**目录**的标签） | `lolosia.media.read` |
| `POST /api/media/folder-tags/attach` | `{ path, tags: [...] }` | `MessageResult` | `lolosia.media.write` |
| `POST /api/media/folder-tags/detach` | `{ path, tags: [...] }` | `MessageResult` | `lolosia.media.write` |
| `POST /api/media/property/save` | `{ path, rating?, analysisSkip?, caption?, tagIds?[] }` | `MessageResult`（属性页一次性提交） | `lolosia.media.write` |
| `POST /api/media/property/apply` | `{ path, recursive, rating?, analysisSkip? }` | `MessageResult`（批量定级） | `lolosia.media.write` |
| `POST /api/media/search` | `{ q?, tags?[], rating?[], cameraModel?, isoMin?, apertureMax?, takenFrom?, takenTo?, hasGps?, mode: "filter"\|"semantic", cursor?, size? }` | 条目（含 `path`/`rating`/`caption`/`tags`/`score`/`exp`/`sig`）+ `nextCursor` | `lolosia.media.read` |
| `GET  /api/media/manifest` | — | **自描述**：可查字段 / 标签树 / 评级取值 / 模式（给 agent，§9.4） | `lolosia.media.read` |
| `GET /api/media/similar` | `path`, `limit?` | 相似条目 + `score` | `lolosia.media.read` |
| `GET /api/media/facets` | 与 search 同源的过滤条件 | 各维度计数 | `lolosia.media.read` |
| `GET /api/media/tag/tree` | — | 标签树 + 每个标签的**引用计数** | `lolosia.media.read` |
| `GET /api/media/tag/orphans` | — | 只被引用 1 次的标签（待整理/合并，§7.5） | `lolosia.media.read` |
| `POST /api/media/tag/save` | `{ id?, namespace, name, parentId?, alias? }` | `MessageResult` | `lolosia.media.admin` |
| `POST /api/media/tag/rename` | `{ id, name?, namespace? }` | `MessageResult`（旧名自动进 `alias`） | `lolosia.media.admin` |
| `POST /api/media/tag/move` | `{ id, parentId? }` | `MessageResult`（`parentId` 空 = 拎回根级；成环则 400） | `lolosia.media.admin` |
| `POST /api/media/tag/merge` | `{ fromId, intoId }` | `MessageResult`（关联迁移 + `merged_into` 重定向） | `lolosia.media.admin` |
| `POST /api/media/index/scan` | `{ path? }` | `MessageResult`（入队，**仅本地索引**） | `lolosia.media.read` |
| `POST /api/media/analyze/start` | `{ scope: "all"\|"path"\|"query"\|"paths", path?, query?, paths?[], tasks?[] }` | `{ total, batch }`（**外发分析从此开始**） | `lolosia.media.admin` |
| `GET /api/media/analyze/status` | — | `{ state, total, done, failed, skipped, currentPath }` | `lolosia.media.read` |
| `POST /api/media/analyze/cancel` | — | `MessageResult` | `lolosia.media.admin` |
| `POST /api/media/reanalyze` | `{ path?, scope?, provider? }` | `MessageResult`（清掉旧 `model` 标签/向量后重跑） | `lolosia.media.admin` |

### 11.3 SSE 事件

| 事件名 | 负载 | 时机 |
| --- | --- | --- |
| `media:index` | `{ path, rating, indexed, takenAt, sample, skipped }` | 单个文件索引/打标完成（`skipped = true` 表示因 NSFW 未外发分析） |
| `media:analyze` | `{ state, total, done, failed, skipped, currentPath }` | 显式分析作业的**状态变化**（开始 / 推进若干个 / 结束 / 取消） |

与 `files:thumb` 同构：**只推给有 `lolosia.media.read` 的连接**（否则等于把评级白送）。
前端据此就地更新遮罩，不必整目录重列（重列在机械盘上很贵）。

### 11.4 权限节点

| 节点 | 含义 | 默认归属 |
| --- | --- | --- |
| `lolosia.media.read` | 读标签 / 元数据 / 检索 | `admin` 及以上（被 `+lolosia.*` 覆盖） |
| `lolosia.media.write` | 人工打标 / 改评级 | `admin` 及以上 |
| `lolosia.media.admin` | 词表管理 / 重跑模型 | 同上 |
| `lolosia.media.location` | 看 GPS | 建议只给 `owner` |
| `lolosia.files.nsfw` | 直链签名里允许带 `NSFW` 位 | 建议只给 `owner` / `admin` |

前四个在 [`permissions.yaml`](../src/commonMain/resources/permissions.yaml) 里声明默认值（沿用 `admin +lolosia.*` 的覆盖关系）；
`lolosia.files.nsfw` 是**直链签发**用的，走 `@Permission` 的接口才需要标，签名器只读判定结果。

---

## 12. 配置增量

`AppSettings.FilesConfig` 下新增 `media`（**只改数据类 + 必要时 `envOverrides`，不另起读取逻辑**）：

```jsonc
{
  "files": {
    "media": {
      "enabled": true,
      "indexIntervalSeconds": 1800,      // 后台**本地**索引扫描间隔（EXIF/缩略图/规则标签），下限 300
      "probeMaxBytes": 262144,           // 读文件头上限
      "probeVersion": 1,                 // 探测器版本；升版即重探
      "hashMode": "quick",               // none | quick | full
      "hashMaxBytes": 268435456,         // 超过则只算 quick（默认 256MiB）
      "missingScanLimit": 3,             // 连续几次未见才软删
      "defaultTimezoneOffset": 480,      // EXIF 无 offset 时的兜底（分钟，+480 = UTC+8）
      "rules": {
        "enabled": true                  // 目录/文件名规则打标：**本地、不外发**；想全手动就关掉它
      },
      "analyze": {
        "batchSize": 500                 // 每次显式分析最多入队多少条（§8.2）
      },
      "worker": {
        "url": "",                       // 空 = 完全禁用外发分析；只有显式分析作业才会用到它
        "token": "",
        "timeoutSeconds": 120,
        "concurrency": 1,
        "processNsfw": false             // ⚠️ 默认不许把 NSFW 外发；仅在 worker 自建时才可打开（§10.2）
      },
      "vector": {
        "enabled": false,
        "model": "clip-vit-b32",
        "dim": 512
      }
    }
  }
}
```

- 派生物目录：`<root>/.cache/lolosia/media/v<MEDIA_CACHE_VERSION>/`（沿用 `CACHE_DIR` 的命名法，版本号一改整体作废）。
- `enabled = false` 时所有入口都是空操作 —— 与 `ThumbnailService.start()` 的写法一致。
- **与缩略图同一个告诫**：`worker.concurrency` 与「是否在别的机器上也开索引」要想清楚，两台同时写同一份缓存会互相覆盖。

---

## 13. 代码落点

| 层 | 文件 |
| --- | --- |
| SQL | `sql/media_asset.sql` / `media_tag.sql` / `media_asset_tag.sql` / `media_tag_rule.sql` / `media_embedding.sql` / `folder_tag.sql` |
| 实体 | `database/model/MediaAssetEntity.kt` / `MediaTagEntity.kt` / `MediaAssetTagEntity.kt` / `MediaTagRuleEntity.kt` / `MediaEmbeddingEntity.kt` / `FolderTagEntity.kt` |
| 仓库 | `database/repository/Media*Repository.kt`（全 `@Query` 单语句；`@Repository` + `@Injectable`） |
| 身份与哈希 | `util/media/QuickId.kt` / `ContentHash.kt`（`okio.HashingSink`，见下）/ `MediaKinds.kt` / `package.kt` |
| EXIF | `util/exif/ExifReader.kt`（IFD 解析）/ `ExifTags.kt` / `ExifValue.kt` / `Gps.kt` / `package.kt` |
| 服务 | `service/media/MediaIndexService.kt`（**本地**索引：扫描 + 队列 + 缩略图联动 + SSE）/ `MediaAnalyzeService.kt`（**显式分析作业**：入队 / 进度 / 取消 / 续跑 + 外发闸门）/ `MediaProbe.kt`（探测调度）/ `MediaMetadata.kt`（列映射）/ `MediaTagService.kt`（人工 / 规则）/ `MediaQueryService.kt`（检索 / 分面）/ `MediaPropertyService.kt`（属性页读写 + 批量定级）/ `MediaVectorStore.kt`（落盘 + 余弦）/ `RatingPolicy.kt`（人工评级 + `analysisSkip` 派生）/ `package.kt` |
| Worker | `service/media/worker/TaggingProvider.kt` / `HttpTaggingWorker.kt` / `NoopTaggingProvider.kt` / `AnalyzeDtos.kt` |
| 直链 | `service/files/FileSigner.kt`（加 `flags`）/ `FileController.kt` + `MediaController.kt`（签发时算 flags） |
| 缩略图 | `service/files/ThumbnailGenerator.kt`（解码一次 → 各档缩略图 + 8×8 采样）/ `ThumbnailService.kt`（生成后把采样回写 `media_asset`，并给老缓存回填）/ `ThumbnailCache.kt`（元数据带采样） |
| DTO | `dto/MediaDtos.kt`；`dto/FileDtos.kt` 的 `FileEntry` 加 `rating` / `indexed` / `takenAt` / `sample` |
| 接口 | `controller/MediaController.kt` |
| 配置 | `settings/AppSettings.kt`（`FilesConfig.MediaConfig`）/ `resources/media-rules.yaml` / `resources/permissions.yaml` |
| 装配 | `configuration/AppModule.kt`（provider 选择：URL 空 → Noop）/ `AppConfigure.kt`（`start()` 挂进应用作用域） |

**哈希怎么算**（已核实可用，不必自研）：okio 3.9.1 的 **`okio.HashingSink` 在 commonMain 与 nonJvmMain 都有实现**，
native 目标可用：

```kotlin
val digest = HashingSink.sha256(fs.sink(filePath)).use { sink ->
    // 边读边喂（分块），读完取 digest
    sink.buffer().use { buf -> fileSource.readAll(buf) }
    sink.hash
}
```

`quick_id` 是纯拼接哈希，用现成的 `ByteString.sha256()`（`ThumbnailCache` 用 `sha1()` 的同一条路子）即可。

---

## 14. 分阶段与验证

| 阶段 | 内容 | 可独立验证 |
| --- | --- | --- |
| **P1 身份 + 元数据 + 属性页 + 遮罩** | 5 张表 + 扫描器 + `quick_id` + EXIF 解析 + `FileEntry.rating/takenAt` + **属性编辑页（含 NSFW 人工定级）** + 前端遮罩 + 规则预置评级 | 冒烟：造一个含 EXIF 的 JPEG，`files/detail` 回光圈/机型/时间；改名后评级仍在；把某图标成 `explicit` 后它**不进打标队列** |
| **P2 标签体系 + 检索** | 词表 + 层级展开 + 人工打标 + 规则打标 + `media/search` + `facets` | 冒烟：`VRChat/` 目录自动出 `series:VRChat`；按相机型号 / ISO 区间能筛出图 |
| **P3 显式外发分析** | worker 协议 + `HttpTaggingWorker` + **`analyze/start` 作业（进度 / 取消 / 续跑）** + 标签与 caption 入库 + `media:index` / `media:analyze` SSE + **外发闸门（入队 / 发包两处检查）** | 冒烟：**不点 start 时假 worker 收到 0 个请求**；点了之后 NSFW 文件仍**一次都没被请求过**；关掉 worker 后系统仍可用 |
| **P4 语义检索** | 向量落盘 + 余弦 topK + `similar` + `search?mode=semantic` | 冒烟：给两张同主题图算相似度 > 不同主题 |
| **P5 加固** | 直链 `flags`（NSFW 越权）+ 服务端模糊图 + 视频容器头 + 视频/音频打标（交给 worker） | 冒烟：无 `lolosia.files.nsfw` 的会话拿到的 `raw` 对 explicit 文件回 403 |

**P1 必须能在没有 worker、没有网络的情况下独立可用**，这是排期的硬边界。

---

## 15. 非目标（v1 明确不做）

- **不做对象级授权**（「只能看自己目录的图」）—— 与 rbac §9.4 同一立场，另做所有权断言。
- **不做自动 NSFW 识别** —— 分级是人工的**合规闸门**（§7.2 / §10.2）：模型既不产出评级，也不作为分析任务下发。
- **不做标签的多父归属** —— `parent_id` 单列（与 `sys_role.parent_id` 同构）；「一个角色同属多个企划」要引入关联表，见 §16。
- **不实现 MCP 协议** —— 只给 REST + `media/manifest`（§9.4）；agent 能发 HTTP 就能用。
- **不做人脸识别 / 人物聚类**（「找出所有含我的照片」）—— 敏感度高、误判成本大，且需要另一类模型。
- **不做 OCR / 文本 in 图检索** —— 收益不明，成本不小。
- **不做反向地理编码**（经纬度 → 地名）。
- **不在后端进程内跑任何模型**（§8.1）。
- **不做视频关键帧缩略图** —— 需要解码器（ffmpeg），K/N 侧没有；交给 worker 产出。
- **不改 `oss_user_file` 那套**：OSS 的图本来就有 md5 主键、归属到用户，挂同一套标签表是**将来的事**；v1 只做机械盘（`files.root`）。
- **不做 sidecar 文件作为真相源**（§2.1）—— 可选只做「导出」。

---

## 16. 开放问题与备选

1. **`hashMode` 默认取 `quick` 还是 `full`？** 本方案取 `quick`（机械盘成本）。代价是跨盘去重与「换盘认回」弱一档。
   若你更在意「标签跟着内容走、换盘也不丢」，就把 `hashMode` 开成 `full` 并接受一次性 3 小时后台。
2. **标签层级要不要更彻底？** 现在是单父（与 `sys_role.parent_id` 同构）。若要「一个角色同时属于多个作品」，得引入关联表 —— 目前不需要。
3. **语义检索的空间混用**：若 worker 用 CLIP 出图向量、却用另一个模型出文向量，两个空间不可比。
   **必须**保证「文字 query 与图片用同一模型」，这一点要写进 worker 协议与运维文档。
4. **`rating` 的取值域**：现在是 4 档。若要更细（如区分「暴露」与「血腥」），建议**加一个 `content_flags` 位图列**而不是继续扩 `rating` 枚举。
5. **缩略图服务端模糊**：§10.5 列为 P4；若这个库将来要对外开放（多用户），应当提前到 P1。
6. **「未标记窗口」已被「显式触发」基本消解**：外发只在用户点「全盘分析」之后才发生（§8.2），
   到那时人工定级已经做完，闸门自然生效 —— 不再有「后台扫描悄悄把没标的 NSFW 送出去」这种事。
   残余风险只剩**漏标**（人忘了标）：① 用规则预置 `*r18*` / `NSFW/` 兜一层；
   ② 点「全盘分析」前，界面先报出「仍未定级的文件数」，让用户确认再点。
7. **MariaDB 版本**：§2.3 的 `VECTOR` 路线是否可用，取决于线上实际版本与 sqlx4k 的二进制绑定能力 —— **落地前先验证，不要预设**。
8. **多父标签**：现在单父。若「一个角色同时属于多个作品 / 企划」成为刚需，要加一张 `media_tag_parent(tag_id, parent_id)`，
   展开从「沿单指针」变成「图遍历」（与组继承一样要防环），查询侧的「含子孙」也要跟着改。收益明确、改动不小，先不做。
9. **保存的查询与订阅**：`media/search` 的入参可以直接存下来（`media_saved_query`），配合「新图入库」事件做通知 ——
   「以后自动把含某角色的新图挑出来」就是这么落地的（§9.4 的原语已经够，缺的只是一张表和触发）。属于增量，不阻塞 v1。
10. **给 agent 的 MCP 薄适配**：若你的 agent 框架只吃 MCP，再加一层适配把同一批端点包一遍即可 ——
    它不带来任何新的数据能力，只是协议翻译，因此**不影响现在的模型设计**。
11. **标签的「同义词归一」到什么程度**：现在只做 `alias` 精确匹配（忽略大小写/全半角）。
    要不要上模糊匹配（拼写距离、拼音、日文假名↔汉字），取决于你实际手打时的混乱程度 —— 先按精确匹配做，不够再加。
12. **同一内容存在多个位置** —— **已定：不做，v1 取舍够用。**
    现在是「一个内容 = 一行」，好处是标签天然共用、去重干净；代价是**非代表位置在文件列表里读不到评级与标签**（§5.2）。
    已确认实际工作流里基本不会把同一张图放进多个目录，所以不值得为它多一张表。
    将来真需要时按 ① **位置拆表** 改（`media_asset` 只留内容，新增 `media_asset_path(path, size, mtime, dir_path, asset_id)`，
    列目录以位置表为驱动 join 回内容表，sqlx4k 下仍是一条单语句）；
    ② 的 `group_id` + 组内冗余同步不如它干净，不推荐。

---

## 附录 A：一次完整的链路 —— 本地索引 & 显式分析

**A-1 新图片进入视野（本地，自动，不外发）**

```
1. 后台周期扫描（或用户打开目录）把 VRChat/白上吹雪/a.jpg 纳入视野
2. MediaIndexService.onScan：stat → 无对应行 → quick_id → 无命中 → 插入 media_asset
   （kind=image、rating=unknown、analyze_state=none）
3. 规则打标（本地）：path_prefix "VRChat/" → series:VRChat；"白上吹雪/" → character:白上吹雪
4. probe：读文件头 256KiB → EXIF 解析 → 光圈 1.8 / 机型 ILCE-7M3 / 拍摄时间 / GPS
   → 写提升列 + metadata_json；probe_version = 1
5. 到此为止 —— analyze_state 仍是 none，一个字节都没出网
6. SSE push media:index { path, rating:"unknown", indexed:true, takenAt }
```

**A-2 人工阶段**：用户在属性页把它标成 `explicit`，顺手「应用到本目录」整套定级
→ `media/property/apply` → `rating = explicit`、`analysis_skip = 1`

**A-3 用户点「全盘分析」（外发，显式触发）**

```
7. analyze/start { scope:"all" } → 把 analyze_state = 'none' 的置 queued（受 batchSize 限制）
8. 工作协程逐条取，发包前查闸门 —— a.jpg 的 rating=explicit → 置 skipped，不发
9. 其余文件 → POST /v1/analyze → 标签（source=model）/ caption / 向量
   → media_asset_tag + media_asset.caption + 向量 append 到 .f32；
     analyze_state='done'（评级仍不由模型写）
10. SSE push media:analyze（作业进度）与 media:index（逐文件）
```

## 附录 B：为什么不用 X

| 方案 | 一句话否掉的理由 |
| --- | --- |
| 用路径当主键 | 改一次名，标签全丢（媒体盘天天在整理） |
| 用 mtime 判「内容变了没」 | `cp` / `rsync` / 恢复备份都会动 mtime，标签会大批丢失 |
| 每次扫描重算全量 sha256 | 机械盘 30MB/s 上，整库一轮近 3 小时，把盘读满还没法天天跑 |
| 标签存成媒体树里的 `.tags.json` | 污染媒体树；且本项目的文件管理器与 WebDAV 都跳过 `.` 开头段，别的客户端一样看不到 |
| 上 Elasticsearch / Milvus | 十万级数据，`LIKE` + 暴力余弦够用；多一个进程就是多一份要运维的东西 |
| 在 K/N 进程内跑 CLIP | 没有可用推理运行时；板子 4 核跑不动；换模型等于重新发布二进制 |
| 让模型自动判 NSFW | 分级是人工的合规闸门，宁可人工慢一点；且第三方服务商对不合规内容本身就会回错误 —— 正解是**不发出去**（§10.2） |
| 把 NSFW 只做在前端 CSS | `/api/files/raw` 在白名单里，URL 一泄原图就泄，遮罩纯属自欺（§10.4） |
| EXIF 用 libexif / exiv2 | 三目标静态库 + cinterop 的长期债；exiv2 是 C++，K/N 侧基本绑不了 |
