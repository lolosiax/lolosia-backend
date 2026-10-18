# 足迹地图（Footprint Map）设计

> 面向对象：实现者与后来维护者。约定类内容以 [`../AGENTS.md`](../AGENTS.md) 为准。
> 一句话：把媒体库里带 GPS 的照片**聚成热点**、把带拍摄时间的照片**切成拍摄会话**，
> 并允许人在地图上**画圈命名**（如「江汉路步行街」「X118」），回答「我在哪儿、什么时候拍得多」。

## 1. 定位

- 复用媒体索引已抽取的 EXIF 经纬度与拍摄时间（见 [media-index-design.md](media-index-design.md) §6.4），
  不新增扫描链路 —— 足迹只是**换一种视角**看同一批 `media_asset`。
- 面板名「足迹地图」，与「仪表板 / 文件管理 / 媒体索引」并列，属角色路由（`owner` / `admin`）。
- **两类聚类共用一套 x-y 心智**：地点聚类 x/y = 平面米制坐标；时间聚类 x = 时间轴。

## 2. 数据来源

| 来源 | 用途 |
| --- | --- |
| `media_asset.gps_lat` / `gps_lon` | 空间聚类的点集（`kind in ('image','video') and deleted = 0`） |
| `media_asset.taken_at` | 时间聚类的点集（无拍摄时间的图不会进时间轴，正是「相机 Bug / 程序导出丢时间」的场景） |
| `media_location_region` | **人工**圈画并命名的区域（见 §3） |

读点走**精简投影实体** [`MediaGeoAssetEntity`](../modules/server/src/commonMain/kotlin/moe/lolosia/web/database/model/MediaGeoAssetEntity.kt)：
只取 `id / path / name / kind / gps_lat / gps_lon / taken_at / rating / deleted` 九列，避免把 `metadata_json`（MEDIUMTEXT）整表拉回。
两份快照（带 GPS / 带时间）在服务进程内做 **30s TTL 缓存**，前端缩放、切页不反复整表读。

## 3. 表 `media_location_region`

只存**人画的圈**：中心点 + 半径（米）+ 名字 + 颜色 + 备注。脚本见 [`sql/media_location_region.sql`](../sql/media_location_region.sql)。

- **没有外键**：圈是独立的标注层，删图不影响圈、删圈不影响图。
- **圈内图数是现算的**：由几何包含判定得出，不落库 —— 库里的点本来就要整份读出来做聚类。
- **代表图也不落库**：区域行的缩略图由服务端现挑 —— 圈内**带「喜欢」（`feature:喜欢`）标签且离圆心最近**的一张，
  没有喜欢的就退回离圆心最近的一张；圈里一张图都没有时留空（前端出占位图标）。
- 全表按 `deleted = 0` 取（软删）；量级很小（人手画的几十个圈）。

## 4. 接口契约

控制器 [`FootprintController`](../modules/server/src/commonMain/kotlin/moe/lolosia/web/controller/FootprintController.kt)，前缀 `/api`。
DTO 在 [`dto/FootprintDtos.kt`](../modules/protocol/src/commonMain/kotlin/moe/lolosia/web/dto/FootprintDtos.kt)（控制器与服务共用，故独立成文件）。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/media/footprint/overview` | `lolosia.media.location` | 热点 + 区域 + 总数/无 GPS 数；参数 `eps`(米) `minPts` `maxClusters` |
| GET | `/media/footprint/points` | `lolosia.media.location` | 视野内散点；参数 `minLat/maxLat/minLon/maxLon/limit` |
| GET | `/media/footprint/timeline` | `lolosia.media.location` | 拍摄会话；参数 `gapHours` `limit` |
| GET | `/media/footprint/period/photos` | `lolosia.media.location` | 某时段全部图（含无 GPS）；参数 `from/to/limit` |
| POST | `/media/footprint/region/save` | `lolosia.media.write` | 新建 / 更新区域圈（`id` 为空即新建） |
| POST | `/media/footprint/region/delete` | `lolosia.media.write` | 软删区域圈 |

- 读接口挂**更严**的 `lolosia.media.location`（裸奔的是行程轨迹）；写接口挂 `lolosia.media.write`。
- 聚类粒度全部开成入参：后端不预设「什么算一个地点」，前端按用户选择传。

## 5. 聚类算法

纯函数模块 [`FootprintClusterer`](../modules/server/src/commonMain/kotlin/moe/lolosia/web/service/media/FootprintClusterer.kt)，无 IO。

### 5.1 空间（DBSCAN）

- **等距圆柱投影**：以全库**均值纬度**定 `kx = cos(lat0)·111320`，`x = lon·kx`、`y = lat·111320`，
  把经纬度就地转成米制平面。个人相册跨度有限，局部误差可忽略。
- 邻域查询挂一张**边长 = eps 的网格索引**（`HashMap<Long, List<Int>>`，键 = `(cellX shl 32) or (cellY and 0xffffffff)`），把朴素 O(N²) 降到与局部密度成正比。
- `eps` 默认 250m、`minPts` 默认 3；簇数由数据决定，稀疏孤立点当噪声丢弃 —— 这正是「热点」想要的。
- 汇总：中心取成员**质心**，半径取成员到中心**最大距离**，代表图取离中心最近的一张（出缩略图）。
- ⚠️ **反投影必须用与正投影同一个 `kx`**：`lat = cy / 111320`、`lon = cx / kx`。
  若反投影改用 `cos(代表点纬度)`，会引入 `cos(lat0)/cos(repLat)` 的经度缩放误差 ——
  跨纬度相册（如本库横跨 23°~37°N）里能让整簇经度**漂移上百公里**，症状是「点热点聚焦到荒野、圈里 0 张图」。

### 5.2 时间（相邻间隔切段）

- 输入须**已按 `taken_at` 升序**（SQL 已 `order by`）；无拍摄时间（0）的点跳过。
- 相邻两张间隔 > `gapHours`（默认 6h）即断开成新会话；代表图取时段**中点**那张。
- 与空间聚类同一心智：只是 x 从「米」换成「毫秒」。

## 6. 前端

- 面板 `src/views/footprint/index.vue`：顶栏（地点/时间切换、统计、聚簇粒度、新建区域、刷新）+
  左侧栏（我的区域 / 自动热点 / 拍摄会话，按数量从大到小，点击聚焦）+ 主区（地图 or 画廊）+ 抽屉画廊 + 区域编辑对话框 + 全屏查看器。
- 地图组件 `FootprintMap.vue` 无状态：只管画（`AMap.Circle` 热点/区域圈、`AMap.Text` 数字标签、`AMap.CircleMarker` 散点）与交互；
  数据、选中态、圈选模式由父组件传入，便于替换底图。
- **人工区域圈优先于自动热点**：热点只画**描边空心圆 + 数字**（无填充底色），且
  - 默认（未聚焦）只画**没被任何区域圈覆盖**的热点 —— 已经被圈起来的不用再糊一层小圆，否则满屏都是；
  - 侧栏「自动热点」列表用同一套判定（`hotspotClusters`，以圆心纬度做等距圆柱的圆形包含）过滤，
    只列未被区域覆盖的热点，和地图口径保持一致；
  - 「聚焦」时（选中了某个区域圈，或地图放大到 `FOCUS_ZOOM` 以上）把被覆盖的热点也一并画出来，便于下钻；
  - 当前选中的那一个热点始终画。
- **路网按缩放显隐 + 独立省界层**：全国/省域视图（`zoom < ROAD_ZOOM`，约 10 级即地级市尺度）用
  `map.setFeatures(['bg','point'])` 砍掉路网与建筑物，免得被全国路网糊满；缩放到区县/街道尺度再切回
  `['bg','road','building','point']`。只在缩放跨过阈值时切换一次（`syncMapFeatures()` 记忆上次 key）。
  ⚠️ 坑：AMap 2.0 的 `setFeatures` 只有 bg / point / road / building，且**把行政区界和路网一起归进 `'road'`** ——
  所以砍掉 `'road'` 会连省界一起砍没。对策是用 `AMap.DistrictLayer.Province`（脚本带 `plugin=AMap.DistrictLayer`
  加载）单独描一层省界 `districtLayer`：低缩放时挂上、高缩放时 `setMap(null)`（放大后底图自带省界，避免重影）。
- **标签避让 + 引线**（`layoutLabels()`）：
  - 区域标签：圆的**直径装得下**整条标签（按像素量宽）就居中；否则按所在半屏分到**左右两侧**，用
    「饼图式折线」引出 —— 从圆周斜拉 `LEADER_STUB` px 到拐点，再水平 `LEADER_RUN` px 接到标签。
    同侧共用一根**脊柱竖线**（`spineX` = 该侧最外圆的 x 再外推 `LEADER_STUB`）：所有标签沿脊柱靠立成一列、
    锚点一律落在脊柱内侧，因此引线永远伸向标签、**不会横穿同侧别的标签**；同侧标签再按锚点纵坐标排序、
    用 `cursor` 游标逐行错开。两侧都顶到屏幕边界（`fitsH` 为假）时，才退回**圆下方竖排** + 圆周正下方拉短线。
  - 自动热点只有一个数字、没有汉字，**不参与**避让：始终居中、不拉引线（用户口径「自动区域不受此限」）。
  - 标签像素位置随平移/缩放变化，故在 `moveend`/`zoomend` 各重排一次。
- 聚焦一个**区域**时按整个圆的 `fitBounds` 收视野（而不是固定 zoom），否则大区域的圆周会跑到屏幕外；
  首次自动取景（`fitAll`）与按圆取景都会给四周留点余量（`FIT_PADDING_RATIO` / `1.15`），免得贴边的点连标签一起被裁掉。
- 敏感图遮罩沿用文件管理器口径（`rating` 为 `explicit`/`questionable` 先糊上，点一下才看）。
- 直链签名复用文件管理器：缩略图 `files/thumb?path&size&exp&sig`、原图 `files/raw?...`（见 AGENTS.md §6）。

### 地图底图：高德 JS API 2.0

- 动态加载器 `src/views/footprint/amap.ts`：首进面板时才注入 `https://webapi.amap.com/maps?v=2.0&key=...`，全局只注入一次。
- 安全密钥必须在脚本加载**之前**挂到 `window._AMapSecurityConfig`（否则逆地理等接口报 `INVALID_USER_SCODE`）。
- Key 可用 `.env*` 覆盖：`VITE_APP_AMAP_KEY` / `VITE_APP_AMAP_SECURITY_CODE`；
  内置的是测试 Key（未限域名）。Web 端 Key 本就**随前端下发**（浏览器人人可见），不属服务端凭据。

## 7. 底图选型与替代（成本 / 合规备选）

需求里预留了「嵌不进去 / 要收费就只出方案」的退路。结论：**高德 JS API 2.0 可嵌入且本项目已接入**，
个人自用场景（低并发、非商业）在其免费额度内。若要替换或担心商用授权，按下列顺序考虑：

| 方案 | 资质 / 成本 | 适配度 | 备注 |
| --- | --- | --- | --- |
| **高德 JS API 2.0**（现用） | 需实名 + 申请 Web 端 Key；个人开发者免费额度足够自用；**商用需授权** | 高 | 国内坐标（GCJ-02）与 POI 最全；若换底图注意 GPS 原始 WGS-84 需转换 |
| **天地图** | 需注册申请 Key；国家背景，免费 | 高 | 权威、合规友好；样式朴素、JS API 文档略旧 |
| **MapLibre GL + 自建/第三方瓦片** | 无 Key 依赖，可完全离线；瓦片源可自建或买 | 中 | 完全可控、无厂商绑定；自建瓦片有运维成本，国内底图数据要另找 WGS-84 源 |
| **OpenStreetMap 瓦片** | 免费但有**使用政策**（禁批量、需 UA） | 低 | 仅适合极低量；国内底图与路网不如高德 |
| **纯自绘（无底图）** | 零依赖 | 低 | 只画点/圈，无地图上下文；仅作降级 |

> 坐标注意：高德用 **GCJ-02**，而照片 EXIF 的 GPS 是 **WGS-84**。本项目的点直接用 WGS-84 经纬度落在高德底图上，
> 在城市尺度下有几十~上百米偏移（热点圈与散点会整体平移）。若要求精确套合，需要加一步 WGS-84 → GCJ-02 纠偏。

## 8. 隐私

- GPS 是最敏感的一类元数据（media-index-design §6.4），故读接口单独挂 `lolosia.media.location`，默认只给 `owner`/`admin`。
- 区域圈是**标注层**，删除只软删自己画的圈，不动任何照片。

## 9. 已知取舍 / 后续

- 一次性把带 GPS 的行读进内存做聚类：个人库（千级）无压力；若要支持十万级以上，应下推到 SQL / 增量聚类。
- 投影用单一 `lat0`：跨大洲的库会有距离误差，届时把投影换成球面距离即可（接口不变）。
- 尚未做**逆地理编码**给热点起名（那要调高德「逆地理编码」接口、消耗配额），当前热点只有编号与数量。
