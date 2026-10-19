# 视觉大模型自动打标：构图 / 有效性 / 题材 / 角色 / 描述

> 状态：**V1–V4 已实现并线上端到端验证**（V5 角色参考库待做）。实现落点与偏差见 §12；原型指标（构图 / 有效性 / 题材 / 属性 / 一句话描述）已可跑。
> 目标目录示例：`影集/娃照/20260930-CGF`（文件管理器路由 `…/home/files/…`，取图走 `GET /api/files/raw?path=…&exp=…&sig=…`）。
> 依赖：复用刚落地的 [util/llm](../src/commonMain/kotlin/moe/lolosia/web/util/llm/)（原生 `ChatClient`）+ [media-index-design.md](./media-index-design.md) 已实现的本地索引。

---

## 0. 结论速览

1. **不新造模型，填已有的坑**：[media-index-design.md](./media-index-design.md) 的「外发分析」槽位（§8 `TaggingProvider`）**尚未实现** —— 本方案就是它的**第一个实现**，只不过后端不是自建 CLIP/WD14 worker，而是 **DeepSeek 视觉模型（`deepseek-flash`）**，传输层复用我们自己的 `ChatClient`。
2. **成本的第一杠杆不是模型，是「发多少张、发多大」**（§3）：
   - `detail: low`（缩到 512×512）拿构图 / 有效性 / 题材，`original` 只在需要时才升级；
   - **一图一请求 → 一批多图一请求**（DeepSeek 单请求最多 600 张），摊薄系统提示与指令；
   - **本地先把废片、连拍近似图筛掉**，能不外发的绝不外发。
3. **五类标签分两条流水线**（§4）：便宜的「构图 / 有效性 / 题材」一次批量过；「角色识别」单独且**只在需要时**升级（§5）。
4. **角色识别不能靠"认得"**：模型只对**它训练时见过的知名角色**能命名；对 BJD 娃照 / 自建 VRChat 模型 / OC，**必须先有一份带名字的参考图库**，靠"候选图 + 参考图同请求对比"来判（§5.2）。**能稳定拿到的是属性**（粉发 / 双马尾 / 蓝色和服），先把属性标签做满，再用参考库把属性收敛到名字（§5.3）。
5. **省 token 的记账必须可核**：把提供的 DeepSeek 分词器移植成 Kotlin（§6），用于**精确算文本 token**、**反推图片 token**、**全局累计消耗**（§6.4）；图片 token 以响应 `usage` 为准（文档只给了缩放规则与 1024 上限，**精确公式要实测标定** §6.5）。
6. **合规闸门照旧**：娃照目录很可能含 NSFW，`rating=explicit` / `analysis_skip=1` 的图**一律不出网**（[media-index-design.md](./media-index-design.md) §10.2）；这套标签同样是"外发分析"，必须挂在那条闸门后面。
7. **一句话描述（caption）默认开**：不写解释性长文，但**保留一句"画面里发生了什么事"** —— 对全局模糊查询与将来的 embedding 有用（落 `media_asset.caption`，`caption_source='model'`）。
8. **分词器不进资源包**：运行时从 DeepSeek CDN / ModelScope 拉取并缓存到 `.cache`，拉不到就降级为字符启发式（§6）。
9. **按价目表排期**：输入未命中 ¥1~2/M、输出 ¥4~8/M、缓存命中 ¥0.02~0.04/M、**空闲时段一律半价**（周一~五 9:00–12:00、14:00–18:00 之外都算空闲）。按"每天 ≤ ¥5~10"反算，**空闲时段一天能跑 1 万~3 万张**（§3.5）—— 把全盘分析排到夜间 / 周末。

---

## 1. 需求拆解

| # | 要判什么 | 期望标签（示例） | 难度 |
| --- | --- | --- | --- |
| 1 | 构图 / 人数 | `单人`（写真）、`双人`、`三人`、`多人`、`合影` | 低，视觉可数 |
| 2 | 是否有效内容 | `有效` / `无效`（废片、纯色、截图、遮挡、过曝） | 低~中 |
| 3 | 题材 | `VRChat`、`娃照`、`杂项` | 低~中 |
| 4 | 角色是谁 | `character:萌` / `attribute:粉发` … | **高**（§5） |
| 5 | 图像描述（caption） | "粉发双马尾角色在夜景街道上比耶" | 中（贵在输出 token） |

用途：**搜索 + 推荐**（`media/search` 的过滤维度）+ 图像本身的**可描述性**。

---

## 2. 与现有系统的关系

| 现有件 | 关系 |
| --- | --- |
| `MediaIndexService`（**已实现，本地**） | 只做 stat / `quick_id` / EXIF / 缩略图 / **规则打标**，**一个字节不出网**。本方案**不动它**，是它下游的"外发分析" |
| `MediaTagService`（**已实现**） | 已支持 `manual > rule > model` 三级来源、人工否决、评级投影。**模型的标签直接落 `source = model`**，不新增机制 |
| `media_asset_tag.provider` 列（**已建**） | 就是为"换模型重跑"准备的；本方案填 `deepseek-flash@vision` |
| `media_asset.analyze_state / analyzed_at / analyze_provider / caption`（**已建**） | 逐图状态与描述列，**现成可用**，不建新表 |
| `TaggingProvider` / `MediaAnalyzeService`（**未实现**） | 本方案**就是要建它们**（`media-index-design.md` §8.6 的接口形状） |
| `util/llm`（**已实现**） | `ChatClient` 已支持多模态（`imageUrl` / `media(...)`）、`inspect`、`Flow<String>`。本方案的多模态请求直接用它 |
| `FileSigner`（**已实现**） | 用签名 URL 让 DeepSeek 自己来取图（`image_url` 外链模式），**不 base64**（省 48MiB 体积限制与 1/3 编码膨胀） |

> **关键判断**：这是一条**云 API**路线。它与 `media-index-design.md` §8 设想的"自建 worker"并存 —— 谁被装配由配置决定（§7.4）。自建 worker 适合大吞吐 / 有 GPU 的场合；DeepSeek 路线**开箱即用、零运维**，代价是**按 token 付费 + 素材出网**。

---

## 3. Token 成本模型与最省策略（核心）

### 3.1 钱花在哪

一次请求的 token ≈ `系统提示 + 指令 + Σ(每图图片token) + 输出`。

- **图片 token 是大头**：每张 ≤ **1024**（缩放到约 1300×1300 后的上限）；`detail: low` 会先缩到 512×512，**明显更便宜**。
- **输出 token 是第二大头**：让模型"解释"会烧钱，**只要结构化 JSON**。
- **系统提示 / 指令**：单图单请求时会被重复计 N 次，这是**批量能省掉的部分**。

### 3.2 六个杠杆（按性价比排序）

| 杠杆 | 做法 | 省什么 | 代价 |
| --- | --- | --- | --- |
| **① 本地预筛（最重要）** | 解码缩略图算**模糊度（Laplacian 方差）/ 纯色熵 / 极小尺寸 / 近重复 dHash**，废片与连拍近似图**直接不打标**或只打一个代表 | **直接砍掉要外发的张数**（娃照/展会连拍常砍 30%~70%） | 需本地图像统计（`util/image` 已有解码，够用） |
| **② `detail: low`** | 构图/有效性/题材一律 low | 图片 token（512² 档） | 细节场景/小字可能判错 |
| **③ 批量多图一请求** | 一请求塞 **4~8 张**（`i` 索引对齐），一次回 `[{i,c,v,s},…]` | 系统提示×N、请求开销、并发占用 | 太多会"中间遗忘"；用 4~8 稳妥 |
| **④ 增量 + 缓存** | 只分析 `analyze_state='none'` 且 `quick_id` 未变；结果落库，`provider` 变了才重跑 | 重复分析 | 无 |
| **⑤ 严格输出** | 强制 JSON、**短键名**（`c/v/s`）、`max_tokens` 卡死、**不用思考模式** | 输出 token | 可读性差（无所谓，机器读） |
| **⑥ 两阶段 + 按需升级** | 便宜的 low 批量先过；只有"可疑/重要"（如判为有角色且要识别名字）才 `original` 复看 | 高分辨率只花在刀刃上 | 多一次调用 |

> **最省组合（推荐默认）**：`本地预筛 → detail:low → 每请求 6 张 → 严格 JSON` 一次性拿到 `构图/有效性/题材`，**角色识别与 caption 按需另开**（默认关，§5/§7.5）。

### 3.3 粗略量级（用于决策，非承诺）

设目录内 2000 张，本地预筛后 1200 张外发：

| 方案 | 图片 token（估） | 文本 token（估） | 备注 |
| --- | --- | --- | --- |
| 单图 + `original` | ~1200 × ≤1024 ≈ 1.2M | 指令 ×1200 次，随系统提示线性膨胀 | 最贵 |
| **批量 6 图 + `low`** | ~1200 × ~256 ≈ 0.3M | 指令摊薄到 200 次 | **省 3~4 倍** |
| 再加本地预筛砍 40% | ~0.2M | 同上 | 再省 ~40% |

→ 结论：**先砍张数（预筛）、再砍单张（low）、再砍每张的固定开销（批量）**，比换更便宜的模型更有效。

### 3.4 取图方式与带宽（链路现状：60Mbps、**仅 IPv6**）

三种投喂方式都要吃我们这边的**上行**，所以第一杠杆是**发多小的图**，而不是"用哪种方式"：

| 方式 | 上行开销 | 额外往返 | 适用 |
| --- | --- | --- | --- |
| **内联 base64（默认：低清档）** | 图字节 ×4/3 | 0 次 | 批量过一次的图；**发送前本地降采样**后几乎不占带宽 |
| **Files API `file_id`（稳定档）** | 图字节 ×1 | +上传/删除各 1 次 | 同一张图会被**多阶段复看**、或要求最稳时；**用完即删** |
| 签名外链 `image_url` | 由 DeepSeek 回源拉取（**仍走我们上行**） | 0 次，但依赖可达性 | 备选；本机**仅 IPv6**，能否被稳定回源有风险，不作默认 |

**关键手段：发送前本地降采样。**
- 阶段 A 要的是 `detail: low`（服务端本来就会缩到 512×512）—— 那**不如我们直接发一张 ~512px、几十 KB 的 JPEG**：上行从 MB 级降到几十 KB，**图片 token 不变**，还顺带绕开官方 **48MiB 请求体限制**。缩略图管线（`util/image` 解码 + 编码）已有，直接复用。
- 阶段 B（要角色 / 细节）才发**原图**，那时再考虑 Files API。
- 约束（官方）：URL ≤ 8192 字符、单图 ≤ 32MiB、外链需 **60 秒内下载完**（`exp` 要够长）。

**默认策略**：阶段 A 走**内联 base64 的低清档**（最稳、零往返、最省带宽）；阶段 B 或需复看时切 **Files API 上传 → 用 → 删**；外链仅作备选。

### 3.5 预算与时段（按 token 反算）

官方价目（元 / 百万 token，输入分缓存命中/未命中两档）：

| | 空闲时段 | 高峰时段 |
| --- | --- | --- |
| 输入（**缓存命中**） | 0.02 | 0.04 |
| 输入（**缓存未命中**） | 1 | 2 |
| 输出 | 4 | 8 |

- **时段**：北京时间周一~周五 9:00–12:00、14:00–18:00 为**高峰**；其余（含周末、法定节假日全天）**空闲，半价**。→ **把「全盘分析」排到夜间 / 周末，直接省一半。**
- **缓存命中近乎免费**：批量请求**共用同一段系统提示 + 指令**，DeepSeek 的上下文缓存会把它按 **0.02~0.04 元/M** 计。所以「批量」不只摊薄，还把固定文本成本压到近零。
- **反算 token 预算**（保守按"输出 ≈ 输入 ×15%"、且**不依赖缓存命中**估）：

| 目标花费 | 空闲时段可吃输入 token | 高峰时段可吃输入 token |
| --- | --- | --- |
| ¥5/天 | ≈ **3.1M** | ≈ **1.6M** |
| ¥10/天 | ≈ **6.2M** | ≈ **3.1M** |

  （口径：`输入×1 + 输出×4 = 预算`，输出按输入 0.15 倍。）
- **折算成图**：`detail:low` 每图 ≈ **256 token（待标定，§6.5）**、文本可忽略 → **空闲时段 ¥5/天 ≈ 1.2 万张/天**；若本地预筛再砍 40%、批量缓存命中，实际可达**数万张/天**。
  → 结论：**十万张整库一次全盘分析，空闲时段也就几块到十几块**，不是"巨额资费"。真正的风险是**没 low、没批量、没预筛**。
- 配置里落地为**双阈值**：`maxPromptTokensPerRun`（单次作业）+ `dailyTokenLimit`（日累计），超了**暂停并提示**（§7.4）。

---

## 4. 两阶段流水线

```
                 ┌──────────── 本地（零 token，不出网）────────────┐
   扫描发现 ──►  │ 缩略图 → 模糊度/熵/dHash 去重 → 近重复分组        │
                 └───────┬───────────────────────────────┬────────┘
                         │ 有效且代表图                    │ 判为废片/重复
                         ▼                                ▼
        ┌─────────────────────────────┐        直接落 meta:无效 或 继承代表图标签
        │ 阶段 A：构图/有效性/题材     │
        │  detail=low · 6 图/请求     │  1 次调用/6 张
        │  → [{i,c,v,s}]              │
        └───────────┬─────────────────┘
                    │  按需（可关）
                    ▼
        ┌─────────────────────────────┐
        │ 阶段 B：角色 / caption       │
        │  original · 1 图(或+参考图)  │  仅当 A 判为"有角色"
        └─────────────────────────────┘
```

- **阶段 A（默认必跑）**：一次批量拿到三件事 + **一句话描述**，落 `source=model` 标签 + `caption` + `analyze_provider`。
- **阶段 B（默认关，按目录/范围开启）**：只在"要搜角色名字"时才跑；实现见 §5。
- **去重联动**：近重复组只把**代表图**送阶段 A，组内其余图**继承**代表图的 `构图/题材` 标签（`provider` 标 `dedup-from:<代表path>`），既省 token 又不丢检索能力。

---

## 5. 角色识别怎么做（诚实版）

### 5.1 先分清两种"角色"

| 类型 | 模型能否"认得" | 做法 |
| --- | --- | --- |
| **知名角色**（Vtuber / 动漫 / 游戏） | **可能** —— 仅限它训练数据里见过的 | 直接问名字，**接受误判**，用 `confidence` 兜底 |
| **自建 / BJD娃照 / OC / 自定义 VRChat 模型** | **不能** —— 世上不存在它的"先验名字" | **必须给参考库**（§5.2） |

> 用户说的"粉发的萌每张都有，但没法让 AI 知道这是萌"—— 本质就是第 2 类。**没有参考，任何模型都做不到命名**，这不是调参能解决的。

### 5.2 参考图库匹配（第 2 类的正解）

在 `media_tag` 建"要识别的角色"（`character:*`），每个角色**挂 3~5 张参考图**（人工挑，存成一组 `asset_id` 或独立小目录）。识别时：

```
一条请求： [候选图] + [该角色的参考图 1..k] + 文字
          "参考图里的人是谁？候选图里是否出现同一人？只回 {role, match:0|1, conf}"
```

- **参考图缓存**：参考图用 **Files API `file_id`** 上传一次、反复引用（不然每次都要重传，token 与带宽双输）。
- **怎么配参考**：不必穷举所有角色 —— **先只建你真正会搜的那几个**（比如"萌"），其余先落属性标签。
- **降级**：参考库还没建时，只出**属性标签**，名字留空（`character` 不下发），等参考库补上再 `reanalyze` 收敛。

### 5.3 属性标签是"现在的正确答案"（先把这层做满）

模型对**视觉属性**是稳的：发色、瞳色、发型、服饰、配饰、姿势。这些**不依赖先验名字**，且**恰好是搜索/推荐真正吃的维度**。

- 命名空间：**已定新增 `attribute`**（`attribute:粉发` / `attribute:双马尾` / `attribute:水手服` …）。
- 路线：**阶段 A 顺带（免费）出少量高频属性 → 需要时阶段 B 出全**；等有了参考库，再把 `attribute:粉发` 这类**收敛/合并**到 `character:萌`（`media/tag/merge` 已有，§7.5）。
- 这与 [media-index-design.md](./media-index-design.md) §7.5"标签是事后长出来的"完全一致：**先随便写属性，之后再收拾成角色**。

---

## 6. DeepSeek 分词器 → Kotlin 移植（用于全局统计）

给的包 `deepseek_v4_tokenizer.zip` 里是 HF 格式：`tokenizer.json`（6.3MB）+ `tokenizer_config.json` + 一个 python 壳。已核实结构：

| 项 | 值 |
| --- | --- |
| `model.type` | **BPE**（byte-level，GPT-2 系） |
| vocab / merges | ~12.9 万 / **127741** 条 |
| `byte_fallback` | `false` |
| `normalizer` | 无 |
| `pre_tokenizer` | `Sequence`[ `Split(\p{N}{1,3})` → `Split(CJK 段)` → `Split(GPT2 系正则)` → **`ByteLevel`**(`add_prefix_space=false`, `use_regex=false`) ] |
| `decoder` | `ByteLevel` |
| `added_tokens` | 1283（`<｜begin▁of▁sentence｜>` 等，注意是**全角 ｜ 与 ▁**） |
| `tokenizer_class` | `LlamaTokenizerFast`（决定加载方式，**不是** SentencePiece） |

### 6.1 算法（照 HF 的 BPE）

1. **加特殊 token 匹配**（先切出 `<｜…｜>` 整块，不做 BPE）。
2. **预切分**：按上面 4 步 Split；`ByteLevel` 用 GPT-2 的 `bytes_to_unicode` 把每个字节映射成可见 Unicode。
3. **BPE 合并**：每个 chunk 内，反复挑 rank 最小的相邻对合并（HF 的"symbol pairs + 缓存"实现，别用朴素 O(n²)）。
4. **查表得 id**。

### 6.2 落地要点

- 落 `util/tokenizer/`（`DeepSeekTokenizer.kt` + `ByteLevel.kt` + `Bpe.kt`），`@Injectable` 由容器持单例。
- **正则**：三处 `\p{N}` / CJK 段 / `\p{L}\p{M}\p{P}\p{S}`。K/N 的 `Regex` 对 Unicode 类支持有限 —— **手写扫描器**（用 `Char.isLetter()/isDigit()` 等 + CJK 码点区间）最稳，且更快。
- **不进资源包，运行时拉取**：`tokenizer.json` 是 DeepSeek 官方产物，**不打包进二进制**。首次用时从
  `https://cdn.deepseek.com/api-docs/deepseek_v4_tokenizer.zip`（或 ModelScope 仓库 **`deepseek-ai/DeepSeek-V4.1-Flash`**）下载，解压后
  **缓存到 `<root>/.cache/lolosia/llm/`**（记录来源 URL + 体积 + 校验），之后离线用缓存。
- **拉不到就降级**：下载失败 / 校验不符 / 解析出错 → 回退**字符启发式**（中文 ≈ 0.6 token/字、英文 ≈ 0.3 token/字符），
  只保证台账"量级正确"，**不阻断打标**（与 `NoopTaggingProvider` 同一口径）。
- **解析开销**：首次解析 6.3MB JSON 在 AR 板上偏慢 → 解析一次后**转成紧凑二进制**缓存（vocab 序列 + merges 数组），后续秒起。

### 6.3 用途定位（别高估它）

这套请求里**文本只占小头**（指令 + JSON 输出），主力是**图片 token**。所以分词器主要用来：
1. **精确算文本 token**（指令、输出、系统提示）；
2. **反推图片 token**（见 §6.5）；
3. **全局消耗台账**（§6.4）。

### 6.4 全局消耗统计（"注册"进系统）

- 每次请求响应里读 `usage`（`prompt_tokens` / `completion_tokens`），累加进一个 `TokenLedger`（`@Service` 单例，进程内计数 + 可选落 `sys_*` 表）。
- 请求前用分词器**预估**文本 token，配合图片 token 估算公式给出**预算拦截**（超单次/单日阈值就暂停并提示）。
- 对外一个只读接口 `GET /api/media/analyze/usage`：`{ requests, promptTokens, completionTokens, byProvider[], todayCost }`。
- ⚠️ **不用非流式 API**（会阻塞，与现有全 `Flow` 的形态冲突）。改走 `stream:true` +
  `stream_options.include_usage=true`，**从末尾 usage 帧读 `prompt_tokens` / `completion_tokens`**。
  `ChatCompletionChunk` 已开 `ignoreUnknownKeys`，只需给它**加一个可选的 `usage` 字段**、并在
  `ChatRequestSpec` 收尾处把它**回调出去**（加 `onUsage` 钩子或让 `stream()` 产出"结束事件"）。
  **这是本方案对 `util/llm` 的唯一扩展点**，不改变"流式、不阻塞"的既有形态。

### 6.5 图片 token 要"实测标定"，不是猜（重要）

官方文档只给了**缩放规则**（小图放大到约 544²、大图缩到约 1300²、每图 **≤1024**）与一个 **JS 计算器**，**没有给公式**。所以：

```
图片token = usage.prompt_tokens − 文本token(分词器精确算)
```

1. 造一批**已知尺寸**的纯色/极简图，逐个发、逐个读 `usage`；
2. 用 §6 的分词器算出文本 token，相减得到该尺寸的**真实图片 token**；
3. 拟合分段函数（含 544² 地板、1300² 天花板、1024 上限），**用 `detail:low` 与 `original` 各标一遍**；
4. 拟合式只用于**请求前预估/预算**；**计费与台账一律以响应 `usage` 为准**。

---

## 7. 集成与落点

### 7.1 新增件（对齐 [media-index-design.md](./media-index-design.md) §8.6 / §13）

| 层 | 文件 | 说明 |
| --- | --- | --- |
| 服务 | `service/media/MediaAnalyzeService.kt` | **外发分析作业**：入队 / 进度 / 取消 / 续跑 / **闸门（入队 + 发包两处）**（设计稿 §8.2 已定接口） |
| Provider | `service/media/worker/TaggingProvider.kt` / `DeepSeekVisionProvider.kt` / `NoopTaggingProvider.kt` | 本方案实现 `DeepSeekVisionProvider`（用 `ChatClient`），URL 空则装 Noop |
| 提示词 | `service/media/worker/VisionPrompts.kt` | 阶段 A/B 的系统提示 + JSON 契约（短键名） |
| 批与去重 | `service/media/PreFilter.kt`（模糊度/熵/极小图）+ `NearDup.kt`（dHash 分组） | 本地零 token 预筛 |
| 分词器 | `util/tokenizer/*` + 构建期资源 | §6 |
| 台账 | `service/media/TokenLedger.kt` | §6.4 |
| DTO/接口 | `dto/MediaDtos.kt` 增量；`controller/MediaController.kt` 加 `analyze/*`、`usage` | 设计稿 §11.2 已列表 |
| 配置 | `AppSettings.FilesConfig.MediaConfig` 加 `vision` 段 | 见 §7.4 |

### 7.2 标签命名空间

现有固定枚举：`character / series / object / action / scene / meta / user / feature`。建议：

| 需求 | 落法 | 说明 |
| --- | --- | --- |
| 构图 / 人数 | `scene:单人写真` `scene:双人` `scene:三人` `scene:多人` `scene:合影` | 复用 `scene`，**不加新命名空间** |
| 有效性 | `meta:有效` / `meta:无效` | 无效图由**本地预筛**先判，模型兜底 |
| 题材 | `series:VRChat`（已存在）/ `series:娃照` / `series:杂项` | 复用 `series` |
| 角色属性 | **新增 `attribute` 命名空间**（`attribute:粉发`…） | 若不新增只能塞 `object`，语义差 |
| 物件 / 道具 | `object:蛋糕` / `object:塔罗牌` … | **复用已有的 `object`**（§15） |
| 场合 / 活动 | **新增 `event` 命名空间**（`event:生日会`…） | 主要依据路径 / 文件夹名推断，也可望文生义（§15） |
| 动作 | `action:比耶` / `action:切蛋糕` … | **复用已有的 `action`**（§15） |
| 角色名 | `character:*`（已存在） | 阶段 B |

> 这只动**命名空间枚举常量**（`MediaTagService` 一侧 + 词表），不动表结构。

### 7.3 闸门与来源

- 沿用 [media-index-design.md](./media-index-design.md) §10.2：**入队 + 发包**两处查 `rating != 'explicit' && !analysis_skip`；
- 所有模型标签落 `source = 'model'` + `provider = 'deepseek-flash@vision'`；**重跑只重写 model 行**（§7.3），人工/规则行不动；
- `caption` 落 `media_asset.caption`（`caption_source='model'`），人工填过则不覆盖。

### 7.4 配置增量（`FilesConfig.MediaConfig` 下）

```jsonc
"vision": {
  "enabled": false,                 // 默认关；外发分析必须显式开启（沿用 §8.2 的立场）
  "provider": "deepseek",           // deepseek | noop
  "model": "deepseek-flash",
  "baseUrl": "https://api.deepseek.com",
  "apiKeyEnv": "KTOR_LLM_API_KEY",  // 密钥仍走环境变量，不入库
  "batchSize": 6,                   // 每请求图片数（阶段 A）
  "detail": "low",                  // low | original
  "concurrency": 1,                 // 机械盘 + 限速，压到 1（同 ThumbnailService 理由）
  "uploadMode": "inline-lowres",    // inline-lowres（默认，本地降采样后 base64）| files（上传→用→删）| url（外链备选）
  "analyze": {
    "composition": true,            // 构图/人数
    "validity": true,               // 有效性
    "subject": true,                // 题材
    "caption": true,                // 一句话描述（默认开；长解释一律禁止）
    "character": false              // 角色识别（默认关，需参考库，§5）
  },
  "prefilter": {
    "blurThreshold": 60,            // Laplacian 方差下限；以下判无效
    "dedupHamming": 6,              // dHash 汉明距离阈值；以内判近重复
    "maxUploadEdge": 512            // 内联档最长边像素（本地降采样目标）
  },
  "budget": {
    "preferOffPeakOnly": true,      // 只在空闲时段开跑（半价）；手动触发可覆盖
    "maxPromptTokensPerRun": 1000000,   // 单次作业上限（≈¥1.6 空闲）
    "dailyPromptTokenLimit": 3000000    // 日累计上限（≈¥5 空闲；放宽到 6e6 ≈ ¥10）
  },
  "tokenizer": {
    "url": "https://cdn.deepseek.com/api-docs/deepseek_v4_tokenizer.zip",
    "mirrorUrl": "https://modelscope.cn/models/deepseek-ai/DeepSeek-V4.1-Flash",  // 拉不动时用它取 tokenizer.json
    "cacheDir": ".cache/lolosia/llm"     // 下载后缓存；拉不到 → 字符启发式降级
  },
  "reference": {                     // §5.2 参考库
    "enabled": false,
    "maxPerCharacter": 5
  }
}
```

### 7.5 分阶段默认

- **默认全关**（`vision.enabled=false`）：与 `media-index-design.md` §8.2「不点就外发」的立场一致。
- 管理员在设置页开 `vision.enabled` 并对某目录点「分析」→ 只跑 §7.4 里开的项。

---

## 8. 提示词与输出契约（示意）

阶段 A（6 图一请求），**短键名 + 强制 JSON + 低 max_tokens**：

```
system: 你是图像标注器。对每张图输出一行 JSON，不要任何解释或前后缀。
        字段：c=人数(0/1/2/3/many) v=是否有效布尔 s=题材(vrchat/doll/misc)
             d=一句话描述（≤30 字，只讲"画面里发生了什么事"，不评价、不推测身份）
        只回 JSON 数组：[{"i":0,"c":1,"v":true,"s":"doll","d":"粉发双马尾角色在夜景街道比耶"}, …]

user:   [图0][图1]…[图5]   ← 6 个图（低清档），与 i 对齐
```

- **要一句话描述（`d`），但禁止解释性长文**：`d` 落 `media_asset.caption`，供全局模糊查询与将来的 embedding；"为什么这么判"这类推理一律不要 —— **那才是输出 token 黑洞**。
- 用 `ChatClient` 的 `.toolChoice(Named("submit"))` 可强制结构化（自研 `ChatTool.parameters` 接受任意 JsonObject）；若嫌重，直接解析 JSON 文本即可（首尾去掉 ```json 围栏）。
- 阶段 B 才需要更长输出（角色名 / 更细属性），按需开启。

> ⚠️ 实际契约已扩展为 `i/c/v/s/d/a` + 物件 `o` / 场合 `e` / 动作 `x`，并允许依据路径派生 `event` 标签 —— 见 §13.2 与 §15。

---

## 9. 分阶段与验证

| 阶段 | 内容 | 可独立验证 |
| --- | --- | --- |
| **V1 计量先行** | Kotlin 分词器 + `TokenLedger` + `usage` 读取 + 图片 token 实测标定 | 用分词器算的文本 token 与响应 `usage` 对得上；标出 3 种尺寸的图片 token |
| **V2 阶段 A（批量 low）** | `DeepSeekVisionProvider` + 批量 6 图 + 构图/有效性/题材 + 闸门 + `analyze/*` 接口 | 对 `20260930-CGF` 跑一遍：标签进库、`media/search` 能按 `scene:单人写真`/`series:娃照` 搜到 |
| **V3 本地预筛 + 去重** | 模糊度/熵/dHash + 近重复继承标签 | 同一组连拍只外发 1 张，其余继承；总 token 明显下降 |
| **V4 属性 + 描述** | 属性标签（§5.3）+ 一句话 caption（默认开，随阶段 A 出） | 按 `attribute:粉发` 能搜到；`caption` 进库 |
| **V5 角色参考库** | 参考图库 + Files API 复用 + 名字匹配 | 给"萌"建 3 张参考图后，能判出同角色的图 |

**V2 是分水岭**：先证明"批量 + low"能把成本压住且标签可用，再谈角色这类难题。

---

## 10. 决策记录（原开放问题）

1. **分词器**：接受移植（§6），但**不进资源包** —— 运行时从 DeepSeek CDN / ModelScope 拉取并缓存到 `.cache`，拉不到降级为字符启发式。
2. **命名空间**：**新增 `attribute`**（角色属性）；构图 / 题材 / 有效性复用 `scene` / `series` / `meta`。
3. **取图方式**：默认**内联低清档**（本地降采样后 base64，最省上行、零往返）；需复看时用 **Files API 上传 → 用 → 删**；外链仅作备选（本机仅 IPv6，回源有风险）。
4. **角色识别**：先只做**属性标签**（稳、便宜、够搜索），名字识别作为"参考库就绪后"的增强。
5. **`caption`**：**默认开**，一句话（落 `media_asset.caption`，`caption_source='model'`），禁止长篇解释。
6. **`util/llm` 扩展**：**不加非流式 API**（避免阻塞）；走 `stream + include_usage`，给 `ChatCompletionChunk` 加一个可选 `usage` 字段 + 在 `ChatRequestSpec` 收尾回调（§6.4）。
7. **成本上限**：目标 **¥5~10/天**；反算约为**空闲时段 3.1M~6.2M 输入 token/天**（§3.5），配置为 `maxPromptTokensPerRun` / `dailyPromptTokenLimit` 双阈值，并默认只在空闲时段开跑。
8. **参考库流程**：**先跑一轮属性标签，之后人工从结果里挑图建参考库**（不专门做标注工具）。
9. **ModelScope 镜像**：仓库用 **`deepseek-ai/DeepSeek-V4.1-Flash`**（`tokenizer.json` 等文件从该仓库取；CDN zip 拉不动时用它）。

---

## 11. 以后再说：交互式标注（右键上下文菜单）

> 记录一个**已确认要做、但本期不做**的交互：用户右键一张图 → 上下文菜单里选「**标注**」→ 打开一个标注面板，
> 可在图上**框选人**并逐个打标（人物 → 名字 / 属性）。它本质是**人工标签**（`source = manual`，优先级最高，
> 永不被模型覆盖），是参考库（§5.2）与"属性→角色"收敛（§5.3）的**最佳人工入口**。

- **落点**：`FilesController` 的右键菜单加一项；面板复用属性编辑页的标签选择器（`media/tags/attach` 已是 `manual`）。
- **只加**"框选区 → `media/region`"这套可选的区域标注（表 `media_asset_region(asset_id, x, y, w, h, tag_id)`），
  其余沿用现有手工打标。
- **前置**：不阻塞 V1~V5；等参考库真需要批量挑图时再做。

---

## 12. 实现落点与偏差（V1–V4）

### 12.1 新增 / 改动文件

| 层 | 文件 | 说明 |
| --- | --- | --- |
| 协议扩展 | `util/llm/ChatUsage.kt`；`ChatCompletionChunk.kt` / `ChatProtocol.kt` / `ChatRequestSpec.kt` / `ContentPart.kt` | 流式 `usage`（`stream_options.include_usage`）+ `onUsage` 回调 + 图片 `detail` 字段 |
| Provider | `service/media/worker/TaggingProvider.kt` / `NoopTaggingProvider.kt` / `DeepSeekVisionProvider.kt` / `VisionPrompts.kt` / `VisionTags.kt` | 批量多图一请求，短键名 JSON；未启用时装 Noop |
| 服务 | `service/media/MediaAnalyzeService.kt` | 作业队列 + 两道闸门 + 落库 + 批内近重复去重 |
| 台账 | `service/media/TokenLedger.kt` + `LlmPricing.kt` | 实测 `usage` 累计、按 provider 汇总、空闲/高峰计价、双阈值暂停 |
| 去重 | `service/media/NearDup.kt` | 从 `sample_rgb565`（8×8）算 dHash，零解码 |
| 分词器 | `util/tokenizer/*`（`DeepSeekTokenizer` / `TokenizerData` / `PreTokenizer` / `ByteLevel` / `Bpe` / `package.kt`） | 运行期拉取 + 缓存 + 字符启发式降级 |
| 接口 | `controller/MediaController.kt` | `media/analyze/start\|status\|cancel\|usage` |
| 配置 | `settings/AppSettings.kt` | `FilesConfig.MediaConfig.VisionConfig`（默认 `enabled=false`） |

### 12.2 与设计稿的偏差

1. **分词器来源**：设计稿默认 DeepSeek CDN 的 **zip**；原生端不便解压，改为直接取 ModelScope 的**原始 `tokenizer.json`**（`…/repo?Revision=master&FilePath=tokenizer.json`），CDN zip 作备选注释保留（§7.4 配置已更新）。
2. **本地预筛**：`blur/entropy`（模糊/纯色）**暂未实现** —— `util/image.Image` 目前只写不读（无像素回读接口），要做得先给 native 图像对象加取像素能力。本期只落地**近重复去重**（`NearDup`，基于已有的 8×8 采样），且限定在**批内**（跨批不合并），足以砍掉同一姿势的连拍。
3. **角色识别（V5）**：未做，配置项 `analyze.character` 保留但开了也不生效（无参考库）。
4. **`detail`**：默认 `low`；实现把它原样透传给 `image_url.detail`，与本地降采样（≤`prefilter.maxUploadEdge`，默认 512）叠加，二者不冲突。

### 12.3 验证

- **分词器**：用 HF `tokenizers`（Rust，与 `LlamaTokenizerFast` 同内核）对 32 条覆盖中英/标点/数字/空白/换行/emoji/特殊 token 的样例生成参考 id，Kotlin 实现**逐串完全一致**（一次性校验后移除临时用例）。
- **编译**：`compileKotlinMingwX64` / `LinuxArm64` / `LinuxX64` 均通过。
- **端到端（2026-10-11，tsp-1m 线上，谷时段）**：对 `影集/娃照/20260930-CGF` 全量 345 张跑一遍 ——
  345 张全部 `analyze_state=done`，模型标签 / 描述均落库，`failed=0`、`running=0`；
  可按 `scene:单人写真`（24 张）、`series:娃照`（312 张）、`meta:有效`（335 张）与 `attribute:*`（197 个不同取值）检索；
  caption 平均 18 字；近重复继承（`provider=dedup-from:<代表图>`）87 条。
  （`media/search` 接口尚未落地，本次以库内精确查询代验收。）
- **成本**：本轮 43 请求，输入 6.19 万 / 输出 3.64 万 token，**缓存命中率 ~75%**（`cacheHitTokens` 4.62 万），
  按空闲价折合约 **¥0.02/张**（整目录 ¥0.21）；实测 ≈ **203 图 token/张**（`detail:low`，落在 §6.5 的待标定量级内）。

### 12.4 端到端暴露并修复的问题

1. **`created_by` 超长**：模型行把 `providerId` 写进了 `media_asset_tag.created_by/updated_by`（varchar(40)）；
   继承图的 `dedup-from:<代表图路径>`（>40）落库报 `1406 Data too long`，导致**继承图的标签 / 描述整批丢失**。
   修：`apply()` 的 `operator` 改传 `null`（模型写入无人工操作者，与规则写入 `applyRules(..., null)` 同口径），
   `provider` 仍记 `dedup-from:...` 并按列宽 `take(64)` 保底。
2. **续跑与数据库启动竞态**：`start()` 里 `resume()` 与 DB 建连并发，`db.driver` 未就绪时静默返回，
   库里残留的 `queued` 行不会被拉起。修：`resume()` 先短等重试（`RESUME_DB_RETRY = 15` 次 × 1s），
   仍不就绪才放弃并告警。

---

## 13. 入口、上下文增强与目录备注（V4 后补）

补齐上线后暴露的三处缺口：**没有可见的触发入口**、**模型看不到历史分析结果**、**目录没有备注**。

### 13.1 触发入口（前端）

| 入口 | 语义 | 实现 |
| --- | --- | --- |
| 工具栏「开始打标」（`bi-stars`，紧邻「刷新索引」） | **增量**：只打 `analyze_state='none'` | `FileToolbar` emit `analyze` → `index.vue#onAnalyze` → `analyzePath(path, false)` |
| 右键文件夹「开始打标」 | **增量**，作用于被右键的目录 | `index.vue#analyzeEntry` |
| 右键文件夹「重新打标（全部）」 | **全量**：`reanalyze=true`，含已分析项（先弹确认） | `index.vue#reanalyzeEntry` |

与「刷新索引」的分工不变：那是**纯本地**（stat/EXIF/规则打标，不出网），这是**外发分析**（素材交给外部大模型）。
重打只重写 `source='model'` 的行（§7.3），人工 / 规则行与人工填过的描述都不动。

### 13.2 prompt 上下文增强

设计稿原 §8 的 prompt 只喂图片本身。现改为**每张图前附上下文**（`VisionPrompts.image`）：

```
图N（路径：<相对路径>；已有标签：a、b；已有描述：……；文件夹备注：……）：
```

- `已有标签`：`MediaTagService.labelsOf` 取该图的 `namespace:name`（跳 `negative`，去重）；空则整段省略。
- `已有描述` / `文件夹备注`：分别取 `media_asset.caption` / 所属目录的 `folder_remark`；空则省略。
- system 提示同步加一段引导：**路径与备注只作上下文、不要写进描述**；已有标签「成立则沿用、可增补、不凭空否定」；
  已有描述「作改写基础，更准更简洁，不必照抄」。`TaggingProvider.Item` 相应加 `tags` / `caption` / `folderRemark` 字段。

目的：模型据此**沿用 / 增补标签、改写描述**，而不是每次从零重来（配合 §13.1 的「重新打标（全部）」使用）。

### 13.3 目录备注（`folder_remark`）

目录不在 `media_asset` 里（索引只登记文件），复用不了 `caption`，故**按相对路径另存一张表**。

- 表 `folder_remark(id, path, remark, created_at/by, updated_at/by, deleted)`，一个目录最多一条活行；保存「有则更新、无则插入」，清空即软删（DDL 见 `sql/folder_remark.sql`）。
- 后端：`FolderRemarkEntity` / `FolderRemarkRepository` / `FolderRemarkService`；接口 `GET /api/media/folder-remark`、`POST /api/media/folder-remark/save`。
  `FolderRemarkService.remarkOf(exec, path)` **不需要 `Context`**，供后台外发分析读取。
- 前端：右侧预览面板在**文件名 / 夹名下方**展示备注（view 态文本 + 铅笔按钮，edit 态 textarea + 取消 / 保存），
  文件写 `caption`、目录写 `folder_remark`；属性页原「描述」一律改称「**备注**」（描述即备注，同一概念）。

### 13.4 验证（2026-10-11，tsp-1m 线上）

- **接口**：`folder-remark` 的存 / 取 / 清（空串软删）三条路径逐一走通。
- **前端产物**：deploy 后线上 `static/js` 分块里能 grep 到「开始打标」（工具栏块与 `index` 块各一处）与 `folder-remark`。
- **外发链路**：对 1 张未分析图（另建目录备注后）跑增量分析 —— `processed=1`、`failed=0`，
  caption 与 `model` 标签均落库（`provider=deepseek-flash@vision`），规则标签 `series:VRChat` 保留；
  日志 `本批 1 图：实测输入 638 token`（文本预估含新增的路径 / 备注上下文）。验证用的测试备注已清除。

---

## 14. Token 用量落库与仪表盘展示（后补）

原 `TokenLedger` 只在**内存**里累加（进程重启即清零），且 `isPeak` **未考虑法定节假日**。现补齐：

- **峰谷口径**（`LlmPricing`）：新增中国法定节假日**放假日期**列表；高峰 = 北京时间周一~周五、**非节假日**、9–12 / 14–17，
  其余（含周末与节假日全天）谷段半价（周末即使调休上班也算谷段）。跨年需补录当年国务院通知。
- **落库**：新增表 `sys_token_usage`（**北京时间小时桶** `hour_bucket` 唯一键，累加式 upsert，DDL 见 `sql/sys_token_usage.sql`）；
  `service/dashboard/TokenUsageService` 按 `dashboard.sampleIntervalSeconds` 采样台账增量落库，
  启动时把「今日」基线从库读回 —— 重启不归零。
- **计价**：成本**不落库**，由 `LlmPricing.cost` 按**每个小时桶**当时的峰谷档位逐桶折算，再求和（比"按当前时段给今日全部用量定价"准确）。
- **仪表盘**：`/dashboard/status` 加 `todayTokens` / `totalCost`；`/dashboard/metrics` 加第 7 条 `token` 曲线（**今日累计**，阶梯状）。
  前端「实时监控」多一个 **Token** 指标（数字读数 = 今日累计 + 曲线），右上角多一个 **词元成本** 胶囊（**累计**、单位元）。
- **口径**：实时监控区显示**今日**，屏幕右上角显示**累计**。

---

## 15. 物件 / 场合 / 动作三个新维度（路径派生标签）

上线后暴露的问题：**路径确实传给了模型，但契约里没有承载它的出口**。以 `影集/VRChat/20260419-洛洛希雅生日会合影` 为例 —— 路径已含「生日会」，但该目录所有标签里**没有一个提到生日会**；模型识别到的「黑板 / 塔罗牌」也无处安放，只能塞进 `attribute`（如 `attribute:塔罗牌`）。

> 事实核对：路径确实进了请求体 —— `MediaAnalyzeService.process()` 用 `path = row.path` 构造 `Item`（[L328-L338]），
> `DeepSeekVisionProvider.tag()` 逐图 `spec.user(VisionPrompts.image(...))` + `spec.image(...)`（[L43-L50]），
> `ChatRequestSpec.user()` 追加文本段、`stream()` 合成**单条** User 消息，`ChatProtocol.userContent()` 把它序列化成
> `{"type":"text","text":"图N（路径：…）："}` 写进 `content` 数组。所以问题**不在有没有传，而在输出契约与命名空间**。

**根因**（三处契约缺失）：

1. system 提示只要求 `c/v/s/d/a` 五个维度，并且明确「路径与文件夹备注**不要写进描述**」——
   模型**没有任何输出位置**放「生日会」这类路径线索。
2. `VisionTags.kt` 只定义 `scene/series/meta/attribute`，落库映射也只认这四类 ——
   **即使模型想输出 `object:黑板`，也没有字段可放、没有映射可落**。
3. 模型只剩 `a`（角色视觉属性数组）能放名词 —— 于是「塔罗牌」这类**物件**被错误地塞进 `attribute`。

**修复**：在输出契约里补三个维度，并落到对应命名空间（`namespace` 是自由字符串，**不改表结构**）：

| 维度 | 短键 | 含义 | 命名空间 | 来源 |
| --- | --- | --- | --- | --- |
| 物件 | `o` | 画面里的显著物件 / 道具 / 场景元素 | **复用 `object`** | 纯视觉 |
| 场合 / 活动 | `e` | 这场合 / 活动是什么（如「生日会」） | **新增 `event`** | **路径 / 文件夹名**为主 + 画面望文生义 |
| 动作 | `x` | 画面里正在做的事（如「比耶」「切蛋糕」） | **复用 `action`** | 纯视觉（望文生义） |

- **prompt**（`VisionPrompts.system`）：新增 `o`/`e`/`x` 三行；`e` 行下加引导 ——「路径与文件夹名是判断 `e` 的**重要依据**，
  如路径含『生日会』就应给出 `e:["生日会"]`；也可结合画面里正在做的事望文生义补充」；同时把旧句「仅帮助理解上下文、不要写进描述」
  改为「路径与文件夹备注**不要原样抄进描述**」，不再禁止它影响标签。
- **配置**（`AnalyzeConfig`）：新增 `objects` / `events` / `actions` 三个开关，**默认 true**（`character` 仍默认 false）。
- **解析 / 映射**：`DeepSeekVisionProvider.toResult` 解析 `o/e/x`；`MediaAnalyzeService.tagRefs` 分别映射到
  `NAMESPACE_OBJECT` / `NAMESPACE_EVENT` / `NAMESPACE_ACTION`；`start()` 的维度日志同步加「物件 / 事件 / 动作」。
- **代价**：每图输出多三项数组（多数为空 `[]`），输出 token 略增；这是「路径线索可检索」必须付的账。

---

## 16. 自定义命名空间标签（文件夹备注里的显式指令）

**问题**：目录备注写「紫色头发角色标记为 `player:洛洛希雅`」后，模型确实照做了，但契约里没有
承载自定义命名空间的字段，字符串只能塞进 `a`，最终落成 `attribute:player:洛洛希雅` —— 按 `player:` 检索不到。

**修复**：输出契约新增 `t` 维度，**原样保留 `namespace:name`**：

| 维度 | 短键 | 含义 | 命名空间 |
| --- | --- | --- | --- |
| 自定义标签 | `t` | 文件夹备注等上下文里**显式要求**的标签（如 `player:洛洛希雅`） | 由用户指定 |

- **prompt**（`VisionPrompts.system`）：`t` 行说明「原样写成 `namespace:name`」；并明确「备注里以
  `namespace:name` 形式给出的打标要求，画面满足其条件时必须原样放进 `t`，不要再放进 `a`」。
- **解析 / 映射**：`DeepSeekVisionProvider.toResult` 用独立解析器校验形状与列宽（`namespace` ≤ 32、
  `name` ≤ 128，不受固定维度 24 字上限约束）；`MediaAnalyzeService.tagRefs` 按**第一个冒号**拆成
  `namespace` / `name` 后直接落库。
- **修复历史数据**：tsp-1m 上的 `attribute:player:*` 已迁移为 `player:*`，旧标签软删（2026-10-19）。

