# 番组表（Anime Timetable）设计

> 面向对象：实现者与后来维护者。约定类内容以 [`../AGENTS.md`](../AGENTS.md) 为准。
> 一句话：订阅 MioBT 系发布站的番组更新（**番剧 × 发布组**）并落库成可回看的番组表，
> 当命中的发布组出新资源时，经既有 SSE 通道**实时**推给浏览器 / 桌面端。

## 1. 定位

- 新增一个面板「番组表」，与「仪表板 / 文件管理 / 媒体索引 / 足迹地图」并列，属角色路由。
- **数据来源两条，职责分离**：
  - **MioBT 系发布站 RSS** → 「谁在什么时候发了一集」（发布事件）；
  - **Bangumi API** → 「这一季有哪些番、周几几点播、封面与别名」（番剧目录 / 播出时间表）。
- 用户的关注粒度是 **(番剧, 发布组)**：`group_name` 留空表示「该番任意发布组」。
- 更新**全量落库**（页面可回看历史），**增量实时推送**（只推新出现的条目）。

## 2. 数据来源

### 2.1 MioBT 系发布站 RSS（发布事件）

MioBT 是番组发布/播报框架（comicat.org 是其实例）。它对外的稳定接口是**标准 RSS 2.0**：

```
GET http://www.comicat.org/rss.xml      →  <generator>MioBT RSS Generator</generator>
```

每条 `<item>` 就是一次发布，字段映射：

| RSS 字段 | 落到 | 说明 |
| --- | --- | --- |
| `guid` | `anime_release.guid` | 全局唯一键，MioBT 形如 `show-<sha1>.html` |
| `author` | `anime_release.group_name` | **发布组**（LoliHouse / 喵萌奶茶屋 / TSDM…） |
| `title` | `anime_release.title` | 解析出番名与集数 |
| `enclosure@url` | `anime_release.torrent_url` | 种子直链 |
| `link` | `anime_release.detail_url` | 详情页 |
| `category` | `anime_release.category` | 动画 / 音乐 / 周边（`sort-N`） |
| `pubDate` | `anime_release.published_at` | `+0800` 墙上时间，转 UTC 存 |

⚠️ **硬约束与风险**：

- **非官方、无 API 文档、可能变**：解析必须逐字段容错（缺字段不崩、整条跳过），并留 `source` 列以便**换源**（蜜柑 Mikan 有带字幕组过滤的订阅 RSS，是天然备选）。
- **是 http 不是 https**：后端出站要允许明文 HTTP。
- **Native 上没有 XML 库**：RSS 解析**手写**（参照 [`service/dav/DavXml.kt`](../src/commonMain/kotlin/moe/lolosia/web/service/dav/DavXml.kt) 的既有做法），只认本项目用到的这几个标签，不做通用 XML。
- **它不给播出时间表**：周几/几点/封面靠 §2.2。

### 2.2 Bangumi API（番剧目录）

| 接口 | 用途 |
| --- | --- |
| `GET https://api.bgm.tv/calendar` | 每日放送：按周几分组当季番剧（公开） |
| `POST https://api.bgm.tv/v0/search/subjects` | 关键词搜索 / 补番（**必须带 UA**） |

- 同步进 `anime_series` + `anime_series_alias`（含中文名、原文名、罗马字等别名）。
- **低频**：默认 24h 一次，远端限流；UA 由配置下发（Bangumi 会拒无 UA 的请求）。
- **墙内直连会被 SNI 阻断 + DNS 污染**：可另配**境外节点中继**取数（异步两段、明文 HTTP + 令牌，**非反代 / 非隧道**）——见 §4.4。
- 拉取失败**不影响** RSS 链路（番组更新照推，只是目录暂时不更新）。

## 3. 数据模型

四张表，全部按约定**不加 `_bin` 排序规则、跟表默认 `utf8mb4_unicode_ci`**，
时间列一律 `timestamp`（业务侧 `kotlin.time.Instant`）。脚本放 `sql/`。

> 命名统一用 `anime_*` 前缀；番剧编号列命名 `bangumi_id`（写全名）。**不要用 `bgm` 缩写**
> —— 它易被理解成「背景音乐」（background music）。

### 3.1 `anime_series`（番剧目录）

```sql
CREATE TABLE `anime_series` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `bangumi_id` bigint(20) NULL DEFAULT NULL COMMENT 'Bangumi subject id；手工建的番为 NULL',
  `title` varchar(255) NOT NULL COMMENT '展示名（中文优先）',
  `title_original` varchar(512) NULL DEFAULT NULL COMMENT '原文名（日文 / 罗马字）',
  `cover` varchar(512) NULL DEFAULT NULL,
  `air_weekday` tinyint(4) NULL DEFAULT NULL COMMENT '放送星期 1..7（1=周一，同 Bangumi calendar）',
  `air_time` varchar(16) NULL DEFAULT NULL COMMENT '放送时刻（JST），如 23:30',
  `air_date` varchar(32) NULL DEFAULT NULL COMMENT '首播日期，如 2026-10-05',
  `season` varchar(16) NULL DEFAULT NULL COMMENT '季度，如 2026秋',
  `site_url` varchar(512) NULL DEFAULT NULL,
  `rating` double NULL DEFAULT NULL COMMENT '不用 DECIMAL：sqlx4k 的 native 侧不认',
  `summary` text NULL,
  `status` varchar(16) NOT NULL DEFAULT 'airing' COMMENT 'airing / finished / unknown',
  `created_at` timestamp NULL DEFAULT NULL, `created_by` varchar(40) NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL, `updated_by` varchar(40) NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_bangumi` (`bangumi_id`) USING BTREE,  -- NULL 被唯一索引忽略，手工番可多条
  KEY `idx_weekday` (`air_weekday`, `deleted`),
  KEY `idx_season` (`season`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;
```

### 3.2 `anime_series_alias`（番剧别名：匹配的弹药）

```sql
CREATE TABLE `anime_series_alias` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `series_id` bigint(20) NOT NULL,
  `alias` varchar(512) NOT NULL COMMENT '别名原文',
  `alias_norm` varchar(512) NOT NULL COMMENT '规范化后（小写、去空白与符号），匹配口径',
  `source` varchar(16) NOT NULL DEFAULT 'bangumi' COMMENT 'bangumi / manual',
  `created_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_series` (`series_id`),
  KEY `idx_norm` (`alias_norm`(191))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;
```

### 3.3 `anime_release`（发布条目，全量历史）

```sql
CREATE TABLE `anime_release` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `guid` varchar(128) NOT NULL COMMENT 'RSS guid；跨轮去重靠它',
  `source` varchar(16) NOT NULL DEFAULT 'miobt' COMMENT '数据源代号，预留换源',
  `series_id` bigint(20) NULL DEFAULT NULL COMMENT '匹配到的番；NULL=未匹配',
  `group_name` varchar(128) NOT NULL DEFAULT '' COMMENT '发布组（RSS author）',
  `title` varchar(512) NOT NULL COMMENT 'RSS 标题原文',
  `episode_no` int(11) NULL DEFAULT NULL COMMENT '解析出的集数',
  `category` varchar(32) NOT NULL DEFAULT '' COMMENT 'RSS category（动画 / 音乐 / 周边）',
  `torrent_url` varchar(512) NULL DEFAULT NULL COMMENT 'enclosure 种子直链',
  `detail_url` varchar(512) NULL DEFAULT NULL COMMENT '详情页',
  `match_state` varchar(16) NOT NULL DEFAULT 'pending'
      COMMENT 'pending / matched / unmatched / ai-unmatched / ignored',
  `published_at` timestamp NULL DEFAULT NULL COMMENT 'pubDate，转 UTC',
  `created_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_guid` (`guid`) USING BTREE,
  KEY `idx_published` (`published_at`),
  KEY `idx_series` (`series_id`, `published_at`),
  KEY `idx_group` (`group_name`(64), `published_at`),
  KEY `idx_match` (`match_state`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;
```

### 3.4 `anime_subscription`（用户关注）

```sql
CREATE TABLE `anime_subscription` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` varchar(40) NOT NULL,
  `series_id` bigint(20) NOT NULL,
  `group_name` varchar(128) NOT NULL DEFAULT '' COMMENT '空串 = 该番任意发布组',
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` timestamp NULL DEFAULT NULL, `created_by` varchar(40) NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL, `updated_by` varchar(40) NULL DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  `alive_key` varchar(200) GENERATED ALWAYS AS (
      if(`deleted` = 0, concat(`user_id`, '#', `series_id`, '#', `group_name`), NULL)) VIRTUAL,
  UNIQUE KEY `uk_alive` (`alive_key`) USING BTREE,     -- 软删后取 NULL，可重新关注
  KEY `idx_user` (`user_id`, `deleted`),
  KEY `idx_series` (`series_id`, `deleted`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;
```

> 生成列必须排在所有被引用列之后（同 `media_asset.alive_path`）。分隔符用 `#`：`user_id` /
> `group_name` 里不应出现它；若将来要放开命名，改成校验和列。

## 4. 采集与匹配

### 4.1 轮询 worker 时序

`AnimeFeedService`（`@Service`，构造注入 `AppSettings` / `Database` / `SseService` 与各仓库，
照 [`DdnsService`](../src/commonMain/kotlin/moe/lolosia/web/service/ddns/DdnsService.kt) 的
`while (isActive) { runCatching { … }; delay(interval) }` 写），一轮：

1. `get<String>(rssUrl, rawResponse = true)` 拉 RSS 文本（带 UA、超时）。
2. `AnimeRssParser` 解析成 `List<RawRelease>`（容错，整条坏就跳过）。
3. 按 `includeCategories` 过滤（默认只收「动画」）。
4. 逐条 `existsBy guid`（前缀 `existsBy*` 返回 `Result<Boolean>`）→ 只处理**新条目**。
5. 标题 → 番剧：走内存缓存（`anime_series_alias` 全表，随目录刷新重建）+ `AnimeTitleMatcher`。
6. 写 `anime_release`（`@Query` 单语句 `executeInsert`；`uk_guid` 作并发兜底）。
7. 命中关注：查 `anime_subscription` 命中 `series_id` 且（`group_name=''` 或 `=` 本条组名）的
   `user_id` 集合（**本表当驱动表**，循环单条，无 `IN`）。
8. `sse.broadcast("anime.release", payload) { userId -> userId in 命中集合 }`（集合先算好，避免在 `allow` 里逐用户查库）。
9. `delay(pollIntervalSeconds)`；`retainDays` 之外的老 `anime_release` 顺手清理。

- 走 `runCatching { db.driver }.getOrNull()`（即 `driverOrNull()`）取执行句柄：**库不可用就跳过本轮，不崩**。
- **失败短重试**：取不到执行句柄 / RSS 拉取失败 → 60s 后重试，而非干等一整个 `pollIntervalSeconds`。
- **单工作协程、单实例**：与缩略图同一口径 —— 只在一台机器上开 `anime.enabled`，否则两边重复广播
  （`uk_guid` 能挡住重复落库，但挡不住重复推送）。

### 4.2 标题匹配 `AnimeTitleMatcher`（纯函数，无 IO）

RSS 标题形态多样（`[LoliHouse] 魔法骑士雷阿斯 / Magic Knight Rayearth (2026) - 01 [WebRip …]`、
`【喵萌奶茶屋】★01月新番★[佐佐木與文鳥小嗶 第二季 / Sasaki to Pii-chan S2][01][1080p]`…），流程：

1. **剥前缀**：循环去掉开头 `[...]` / `【...】` 的发布组块与 `★…★` 装饰。
2. **提集数**：`- 01` / `[01]` / `第(\d+)话|集` / `EP(\d+)` 等模式，取最左命中；合集 / SP / OVA 可能解析失败 → 留 `NULL`。
3. **归一化**：全角转半角、转小写、去掉空白与标点 → 与 `alias_norm` **同口径**。
4. **匹配**：候选标题与别名做双向「包含」；归一化长度 **< 4 的别名不参与**（防短词误命中）；
   多条命中取**最长别名**判胜。
5. 未命中 → `match_state = 'unmatched'`；管理员可在界面**手动映射**（写 `series_id` 并追加一条
   `manual` 别名），形成**学习闭环**，同类标题下次自动命中。

#### 4.2.1 AI 兜底匹配 `AnimeMatchAiService`（第二道闸）

规则（§4.2）是**纯函数、零成本**的第一道闸；漏网的条目再交给大模型判一次。核心约束是**成本可控**
—— 不能把整批发布与整张番剧表一次性喂进去。

- **触发**：RSS 采集轮里规则未命中的新条目（`aiMatchEnabled` 时）自动兜底；另有右上角
  「匹配所有未识别」（`POST /anime/release/rematch`）对全部 `unmatched` 手动跑一轮。
- **发布分批**：每 `aiMatchBatchSize` 条一次请求（默认 20），逐批判定、逐批落库。
- **候选先给窄的**：系统提示只塞**本季度在播**（`status = airing`）的候选表；表里没有合适的，
  模型自己调 `search_series` 工具去整个库检索（命中标题 / 原名 / 别名，限量返回）。
- **一次交卷**：全部判完调 `decide` 工具一次性交回（`i` = 条目序号，`seriesId` 省略 = 匹配不到）；
  拦截器捕到 `decide` 即中断后续轮次（`ToolEvent.throwIt`）。
- **成本闸门**：整轮累计 token 超 `aiMatchTokenLimit`（默认 20 万）就抛 `TokenBudgetExceeded`
  **硬止损**（`onUsage` 回调里抛，直接中断请求），已判部分照常落库。`llm.apiKey` 为空或目录为空时直接跳过。
- **记账**：每轮的 `usage` 记进全局 `TokenLedger`（provider = `<model>@anime-match`），与视觉打标同一本账，
  仪表盘 / `sys_token_usage` 可见（先记账再判上限，超限中断那一轮也已入账）。
- **落库口径**（「不再重复匹配」的关键）：命中 → `matched`；明确判「匹配不到」→ **`ai-unmatched`**
  （下次不再被捞）；整批失败 / token 超限 → 原地不动（仍是 `unmatched`，下轮还能再试）。
- **命中即补 SSE**：兜底命中的条目不重新入库，直接补一发与规则命中相同的推送（§5）。

细节实现见 `service/anime/AnimeMatchAiService.kt`。

### 4.3 目录同步 `AnimeMetaService`

- 按 `metaRefreshHours` 拉 `/calendar`，upsert `anime_series` 与别名，重建内存别名缓存。
- **低频 + 对方限流 → 按「挂钟时间」排期，而非以进程启动时间为准**：排期持久化在 `sys_cache`
  （path `/anime/catalog`：tag `nextRefreshAt` = 下次刷新 epoch 毫秒、`failCount` = 连续失败数，TTL 取 3 倍间隔）。
  进程启动时**从缓存继承**排期，于是**频繁重启（部署）不会重复拉 Bangumi**，重启也不重置计时；
  缓存过期（进程停超 TTL）视为该同步，立刻拉一次。（高频定时器才跟进程启动走 —— 见 §4.1 采集。）
- **失败退避**：库不可用 / `/calendar` 拉取失败时按 `60s · 2ⁿ` 退避（封顶 = 正常间隔）并写回排期，
  免得对方长时间不可达时每 60s 空拉一次（刷日志 + 无谓请求）；拉取失败会**抛错**
  （不再冒充「成功」，`/anime/series/refresh` 手动触发时也能回报错误）。
- 手动「同步目录」立即跑一次（不改动排期）。

对比：采集 `AnimeFeedService`（§4.1）是 300s 级**高频**任务，排期仍以进程启动为准，但同样在失败时短重试（60s）。

### 4.4 境外中继取数（墙内取不到 Bangumi 时）

**背景**：`api.bgm.tv` 在墙内被 **SNI 阻断**（TCP 可连、TLS ClientHello 后被 RST）**+ DNS 污染**
（解析到 Facebook 段），直连必失败；换境内节点（如十堰）同样被阻断。故在**境外节点**跑一个取数脚本帮忙。

**它不是反向代理，也不是隧道**（两条硬性红线，不能踩）：

- **没有端口转发**，墙内后端**连不到** Bangumi，只连得到中继；
- **没有长连接**，每次交互都是一次性短请求；
- **入站与出站没有一一对应**：`notify` 只登记任务、**立刻返回**（此刻中继**不出站**），真正出站发生在其后的
  延迟 + 随机抖动之后；后端过几分钟才另起一次请求取结果。三者时间上**相互错开**，特征上只是「一台普通
  服务器在定时拉一个公开 API」。

**两段式时序**（[`AnimeMetaService.syncViaRelay`](../src/commonMain/kotlin/moe/lolosia/web/service/anime/AnimeMetaService.kt)）：

```
墙内后端 ──POST notify──▶ 中继：登记任务，立即 200（jobId/etaSeconds）         ← 此刻不出站
                      ⏱ 中继侧 delay(RELAY_DELAY_SECONDS) + 抖动(0..JITTER)    ← 才真正出站拉 Bangumi
墙内后端 ──⏱ delay(bangumiRelayWaitSeconds) ──▶ ──GET result──▶ 中继：返回最近结果   ← 隔几分钟取回
```

- 后端 `syncNow()` 按 `bangumiRelayUrl` 是否为空**分流**：空=直连（§4.3 原逻辑），非空=走中继；
  两条路径最终都汇到同一个 `applyCalendar()` 落库，**排期 / 退避口径不变**（仍是 §4.3 的挂钟排期）。
- 后端 `requestRefresh()`（手动按钮）在中继模式下**只发通知、秒回**，不在请求里干等出站。
- 中继取结果前先等 `bangumiRelayWaitSeconds`（默认 300s）再轮询（`ANIME_RELAY_POLL_MILLIS`，每 15s 一次，
  总预算 `RELAY_POLL_BUDGET` 10 分钟）；中继侧 `DELAY+jitter` 落在 180~300s，正好卡在预算内。

**正文编码——自定义请求通道**：`notify` / `result` 的响应体**不走明文 JSON**，而是走项目既有的
**自定义请求通道** `application/vnd.lolosia.xhr.data`（与前端基座 `utils/request.ts`、各 JVM 后端
`MsgPackWebFilter` 同一套口径）：`JSON → msgpack → gzip`，再把**前 10 字节用随机数盖掉**；解码端只需把
**前 4 字节改回 gzip 魔数 `1f 8b 08 00`** 即可 `gunzip` → `msgpack` 还原。它只用于**抹掉「明文 JSON + Bangumi
特征」**，**不是加密**（无密钥，别当安全机制）。`/healthz` 与 404 例外，保持明文便于探活。

- 后端侧：`xhrRequest`（[`api/package.kt`](../src/commonMain/kotlin/moe/lolosia/web/api/package.kt)）拿原始字节后交给
  `decodeXhrData`（[`util/xhrdata/`](../src/commonMain/kotlin/moe/lolosia/web/util/xhrdata/XhrData.kt)）；native 实现里 gzip 用
  `platform.zlib`，msgpack 用 thirdparty 的 **msgpack-c** 静态库（`thirdparty/` + `src/nativeInterop/cinterop/msgpack.def`）。
- 中继侧：`relay.py` 用 `msgpack` 包做同样的编解码，因此**不再是纯标准库零依赖**（`pip install msgpack` 或
  `apt install python3-msgpack`）。

**中继契约**（同样遵循项目 `{code, msg, data}` 信封，HTTP 状态码与 `code` 一致；传输是**明文 HTTP**、正文走
上面的自定义通道；鉴权 `X-Relay-Token`——够用即止，不引入 TLS/反代）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/healthz` | 探活，无鉴权：`data.{ok, pending, last}` |
| POST | `/anime/bangumi/notify` | 登记一次拉取任务，立即返回 `data.{jobId, etaSeconds}`；已有任务则去重返回 |
| GET | `/anime/bangumi/result` | 取最近结果：`data.status` = `pending` / `ready`（带 `count` / `calendar`）/ `error`（带 `error`） |

**部署**（[`backend/deploy/anime-relay/`](../deploy/anime-relay/)，纯 Python3，仅依赖 `msgpack` 包）：

- `relay.py`：单文件服务；`relay.env.example` 为配置模板（`RELAY_TOKEN` 必填、`RELAY_DELAY_SECONDS`、
  `RELAY_JITTER_SECONDS`、`RELAY_TARGET_URL`、封面相关的 `RELAY_COVER_*`…），落盘到 `/srv/anime-relay/relay.env`（权限 600）。
  封面落在 `RELAY_COVER_DIR`（默认 `/srv/anime-relay/covers`，需服务用户可写，见 §4.5）。
- `anime-relay.service`：systemd 单元（`Restart=always` + `ProtectSystem=full` 等加固），部署到
  `/srv/anime-relay/`。变更后 `systemctl restart anime-relay`。
- 最近一次成功结果会落盘到 `RELAY_STATE_FILE`（默认 `/srv/anime-relay/state.json`），**进程重启后仍能供数**。

### 4.5 封面回源（Bangumi 图床墙内打不开）

番剧封面在 Bangumi 图床（`lain.bgm.tv`），**墙内同样打不开** —— 若 `anime_series.cover` 直接存原始 URL，
前端 `<img>` 一律加载失败。所以封面也要**回源到本地**（同 §4.4 一样，境外节点先备、墙内后端再取）：

```
中继（境外）：拿到日历时顺带把各番封面逐个下载到 /srv/anime-relay/covers/{id}.jpg（间隔 + 抖动，慢慢来）
墙内后端 AnimeCoverService：周期问中继「已备好哪些」→ 分批把就绪的取回 → 落 work/resources/anime/cover/{id}.jpg
                                                              → 把 anime_series.cover 改写成本地路径
前端：<img src="/res/anime-cover?id={id}&size=256">（后端按需缩图，缓存在 anime.coverCacheRoot，默认 work/.cache/anime/cover）
```

- 中继新增两个接口（都走 §4.4 的鉴权）：`GET /anime/bangumi/covers/status`（走自定义通道，返回
  `data.{total, ready, ids}`）与 `GET /anime/bangumi/covers/{id}`（直接返回 JPEG 字节 —— 图片本身即不透明，
  不必再套通道）。
- 后端 [`AnimeCoverService`](../src/commonMain/kotlin/moe/lolosia/web/service/anime/AnimeCoverService.kt) 周期分批回源
  （间隔 / 批量见 §7 的 `coverPollMinutes` / `coverBatchSize`）；**直连模式**下直接从原始 URL 下载，无需中继。
- `cover` 落地后就长成 `/res/resources/anime/cover/{bangumiId}.jpg`；目录同步再跑时
  [`AnimeMetaService`](../src/commonMain/kotlin/moe/lolosia/web/service/anime/AnimeMetaService.kt) 的 upsert
  **不再把它覆盖回原始 URL**（按 `isLocalAnimeCover` 判定）。
- 存储走既有 `FileStore`（根 = `work`）：`resources/anime/cover/{id}.jpg` ⇒ URL `/res/resources/anime/cover/{id}.jpg`。
- **大图不下发**：Bangumi 原图动辄上千像素、单张数 MB，直接塞进 62×86 的卡片会把浏览器拖卡（实测整页滚动掉到 ~13 FPS）。
  大图照旧留在 `work/resources/anime/cover/`，另由公开路由 `GET /res/anime-cover?id=&size=` 下发**缩略图**：
  首次访问时按档位解码缩放成有损 WebP，落盘 `<coverCacheRoot>/v1`（**复用文件管理器那套**
  [`ThumbnailCache`](../src/commonMain/kotlin/moe/lolosia/web/service/files/ThumbnailCache.kt) + [`ThumbnailGenerator`](../src/commonMain/kotlin/moe/lolosia/web/service/files/ThumbnailGenerator.kt)，
  档位 + SHA-1 分片 + 元数据），之后直接命中缓存。档位 / 质量 / 缓存根见 §7 的 `coverThumbSizes` / `coverThumbQuality` / `coverCacheRoot`。
  路由与 `/res/resources` 一样**公开**（`<img>` 带不了鉴权头），且封面本就是从公开图床回源的。
- **缓存盘选择**：缩略图是**可再生**的写密集缓存，别放系统 SD 卡 —— tsp-1m 上 `/srv`（含 `work/`）就挂在 SD 卡上，
  频繁小写入既拖慢系统又磨损卡。生产把 `anime.coverCacheRoot` 指到 `/mnt/hdd1/.cache/anime/cover`
  （与文件管理器缩略图 `/mnt/hdd1/.cache/lolosia/thumb` 同盘），大容量盘更适合这种可丢弃缓存。
- 前端 `coverSrc(cover, size)`（[`web/src/api/anime.ts`](../../web/src/api/anime.ts)）把 `/res/resources/anime/cover/{id}.jpg`
  映射成 `/res/anime-cover?id={id}&size={size}`（卡片 256、详情抽屉 512）；尚未回源的原始 URL 原样返回，
  组件加载失败回退占位块。

## 5. 实时推送（复用既有 SSE）

**不新增推送通道**，直接用现成的三步握手与过滤广播：

- 建流 / 登录补绑 / 前端封装见 [`SseService`](../src/commonMain/kotlin/moe/lolosia/web/service/SseService.kt)、
  [`SseController`](../src/commonMain/kotlin/moe/lolosia/web/controller/SseController.kt)、[`api/sse.ts`](../../web/src/api/sse.ts)。
- `broadcast` 的 `allow` 回调收到**已绑定登录态**的连接所属 `userId` —— 正好表达「这个用户的关注命中了没」；
  未登录连接一律收不到（符合预期）。
- 事件名固定 **`anime.release`**，`data`：

```json
{
  "releaseId": 123, "seriesId": 45, "seriesTitle": "魔法骑士雷阿斯",
  "groupName": "LoliHouse", "title": "[LoliHouse] … - 01 …", "episodeNo": 1,
  "torrentUrl": "http://…", "detailUrl": "http://www.comicat.org/show-….html",
  "publishedAt": 1760000000000
}
```

## 6. 接口契约

控制器 `AnimeController`（`@Controller("/api")`，`object`），DTO 独立成 `dto/AnimeDtos.kt`（控制器与服务共用）。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/anime/timetable` | `lolosia.anime.read` | 番组表：按 `air_weekday` 分组 + 是否已关注；参数 `weekday` / `season` |
| GET | `/anime/series/{id}` | `lolosia.anime.read` | 番详情：别名、各发布组最近更新 |
| GET | `/anime/search` | `lolosia.anime.read` | 关键词搜番（用于关注），参数 `q` |
| GET | `/anime/releases` | `lolosia.anime.read` | 更新历史（分页，返回 `{ items, total }`）；参数 `seriesId` / `groupName` / `offset` / `limit` |
| GET | `/anime/subscriptions` | `lolosia.anime.read` | 我的关注 |
| POST | `/anime/subscription/save` | `lolosia.anime.write` | 新建 / 更新关注（`seriesId`, `groupName`） |
| POST | `/anime/subscription/delete` | `lolosia.anime.write` | 取消关注（软删） |
| POST | `/anime/series/refresh` | `lolosia.anime.admin` | 手动同步 Bangumi 目录 |
| POST | `/anime/release/map` | `lolosia.anime.admin` | 手动把一条发布映射到番剧（并可选加别名） |
| POST | `/anime/release/rematch` | `lolosia.anime.admin` | 对全部「未识别」发布跑一轮 AI 兜底匹配（§4.2.1） |
| POST | `/anime/feed/poll` | `lolosia.anime.admin` | 立即触发一次 RSS 拉取（排障用） |

- 参数类型限 `String/Int/Long/Boolean/Uuid/枚举`；批量只能循环单条（§AGENTS）。
- 搜索走 `/anime/search` 而非 `/anime/series/search`：后者会与 `/anime/series/{id}` 的通配段在路由层抢匹配。
- 出参一律 `@Serializable` DTO，信封交给 `EnvelopeInterceptor`。

## 7. 权限与配置

**权限节点**（`resources/permissions.yaml`，改完要重编）：

- `lolosia.anime.read` —— 浏览番组表 / 关注 / 历史 → 给 `user`（`+` 到 user 段，admin 由 `+lolosia.*` 覆盖）。
- `lolosia.anime.write` —— 增删自己的关注 → 同上给 `user`。
- `lolosia.anime.admin` —— 维护目录、手动映射、立即拉取 → **只给 admin**（不加给 user）。

**配置**（`settings/AppSettings.kt` 加一个 `AnimeConfig`，多级覆盖沿用 `SettingsLoader`）：

```kotlin
val anime: AnimeConfig = AnimeConfig(),

@Serializable
data class AnimeConfig(
    val enabled: Boolean = false,                       // 关掉时全部空转
    val rssUrl: String = "http://www.comicat.org/rss.xml",
    val pollIntervalSeconds: Int = 300,                 // 下限 60
    val includeCategories: List<String> = listOf("动画"),
    val retainDays: Int = 365,                          // 历史清理
    val bangumiEnabled: Boolean = true,
    val bangumiCalendarUrl: String = "https://api.bgm.tv/calendar",
    val bangumiUserAgent: String = "lolosia-anime/1.0 (+http://lolosia.moe)",
    val metaRefreshHours: Int = 24,
    // 境外中继取数（见 §4.4）：`bangumiRelayUrl` 留空 = 直连 `bangumiCalendarUrl`
    val bangumiRelayUrl: String = "",              // 如 http://<境外节点>:8790
    val bangumiRelayToken: String = "",            // 与中继 RELAY_TOKEN 一致，走 X-Relay-Token
    val bangumiRelayWaitSeconds: Int = 300,        // notify 后先等这么久再开始轮询结果
    // 封面回源（见 §4.5）：把 Bangumi 图床的封面拉到本地 work/resources/anime/cover
    val coverEnabled: Boolean = true,
    val coverPollMinutes: Int = 30,                // 封面回源轮询间隔（分钟），下限 1
    val coverBatchSize: Int = 30,                  // 每轮最多回源多少张（分批，避免猛拉）
    val coverThumbSizes: List<Int> = listOf(256, 512), // 下发缩略图的档位（最长边），缓存在 coverCacheRoot
    val coverThumbQuality: Int = 80,               // 缩略图有损 WebP 质量（1~100）
    val coverCacheRoot: String = "work/.cache/anime/cover", // 缩略图缓存根目录；生产指到大容量盘（如 /mnt/hdd1/.cache/anime/cover）
    // AI 兜底匹配（见 §4.2.1）：规则漏网的发布交给大模型再判一次
    val aiMatchEnabled: Boolean = true,            // RSS 事件触发时是否自动兜底（手动「匹配所有未识别」不受此限）
    val aiMatchBatchSize: Int = 20,                // 单批送进 AI 的发布条数上限（超出的分批）
    val aiMatchTokenLimit: Long = 200_000,         // 单次匹配累计 token 上限，超了抛异常硬止损
)
```

**装配**（`configuration/AppConfigure.kt` 末尾，照 `DdnsService`/`ThumbnailService`）：

```kotlin
koin.get<AnimeFeedService>().start(this)     // RSS 轮询（未启用只记一条日志）
koin.get<AnimeMetaService>().start(this)     // Bangumi 目录同步
koin.get<AnimeCoverService>().start(this)    // 封面回源（见 §4.5）
```

封面缩略图路由 `GET /res/anime-cover?id=&size=` 也在 `AppConfigure` 的 `routing { }` 里注册（与 `/res/resources`
同一层，**在 `/api` 之外**、不经会话鉴权），见 §4.5。

## 8. 前端

- **接口层** `src/api/anime.ts`（`get` / `post` 走既有 `utils/request`）。
- **路由** 新增 `src/router/modules/anime.ts`，并在 [`src/router/index.ts`](../../web/src/router/index.ts) 的
  `roleRoutes()` 数组里登记（与 `filesRoute` / `footprintRoute` 同列）。
- **页面** `src/views/anime/index.vue`：
  - 主区：**周一到周日分栏**的番组表卡片（封面、标题、播出时刻、已关注标记）；
  - 番详情抽屉：该番各**发布组** + 最近更新列表 + 「关注此发布组」操作；
  - 「我的关注」页签 + 更新历史（游标/时间筛选）。
- **全局实时通知**：`utils/bus.ts` 的 `useEventBus().on('anime.release', …)` 会在首次 `on` 时
  自动把该事件挂到 SSE 连接上（`bus.ts` 的 Proxy 逻辑）。在应用启动
  （[`App.vue`](../../web/src/App.vue) 的 `onMounted()`，`initSSEConnect()` 之后）**注册一次**：

```ts
useEventBus().on('anime.release', (p: AnimeReleaseEvent) => {
  ElNotification({ title: `${p.seriesTitle}${p.episodeNo ? ` 第${p.episodeNo}话` : ''}`,
                   message: `[${p.groupName}] 有新资源`, type: 'success' })
  if (Notification.permission === 'granted') new Notification(`${p.seriesTitle} 更新`, { body: p.title })
})
```

- **桌面端**（本期只做 Web）：复用同一条 SSE + 浏览器 `Notification` API；日后要原生通知再包
  Tauri / Electron 壳，事件名与 data 形状不变。

## 9. 文件清单

**后端**

```
sql/anime_series.sql / anime_series_alias.sql / anime_release.sql / anime_subscription.sql
src/commonMain/kotlin/moe/lolosia/web/
  controller/AnimeController.kt
  dto/AnimeDtos.kt
  database/model/AnimeSeriesEntity.kt / AnimeSeriesAliasEntity.kt / AnimeReleaseEntity.kt / AnimeSubscriptionEntity.kt
  database/repository/AnimeSeriesRepository.kt / AnimeSeriesAliasRepository.kt / AnimeReleaseRepository.kt / AnimeSubscriptionRepository.kt
  service/anime/AnimeFeedService.kt / AnimeMetaService.kt / AnimeCoverService.kt / AnimeSubscriptionService.kt
               / AnimeMatchAiService.kt / AnimeRssParser.kt / AnimeTitleMatcher.kt
  settings/AppSettings.kt（加 AnimeConfig）· resources/permissions.yaml（加三节点）· configuration/AppConfigure.kt（启三个服务）
  util/xhrdata/XhrData.kt + nativeMain/…/util/xhrdata/XhrData.native.kt（自定义通道编解码，见 §4.4）
  api/package.kt（加 xhrRequest：走通道请求外部服务）
src/nativeInterop/cinterop/msgpack.def · thirdparty/（msgpack-c 静态库，见 §4.4）
deploy/anime-relay/relay.py · anime-relay.service · relay.env.example（境外节点异步取数中继，见 §4.4）
```

**前端**

```
src/api/anime.ts · src/router/modules/anime.ts
src/views/anime/index.vue · src/views/anime/components/SeriesCard.vue · SeriesDetailDrawer.vue
src/App.vue 注册 anime.release 通知监听
```

## 10. 已知取舍 / 后续

- **匹配准确率是最大风险点**：别名靠 Bangumi + 手动补，`match_state='unmatched'` 需可人工消化（§4.2 学习闭环）；
  短别名 / 同名番（如多季）仍可能误命中，必要时把「多季」纳入归一化打分。
- **MioBT 非官方接口**：解析容错 + `source` 列 + 预留 Mikan 换源；RSS 结构一旦变动只影响采集层。
- **单实例轮询**：与缩略图同口径，多实例需另做「谁负责拉取」的选主（暂不做）。
- **Bangumi 限流 / 必须带 UA**；拉取失败不阻塞 RSS 链路。
- **境外中继是可选依赖**（§4.4）：中继挂了只影响目录刷新（采集照旧），后端会在预算内轮询后报 `Pending/Failed` 并走退避；不配 `bangumiRelayUrl` 即回到直连（墙内会失败）。
- **集数解析**对合集 / SP / OVA / 无集数标题会失败（置 `NULL`），前端展示时降级为「新资源」。
- **时区**：RSS `pubDate` 带 `+0800`，解析后按 UTC 存 `Instant`（§AGENTS 时间约定）。
- **历史膨胀**：按 `retainDays` 清理 `anime_release`；关注与番剧目录长期保留。
