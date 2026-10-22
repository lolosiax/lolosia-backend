# 最小移动客户端设计：登录、基础框架与本地服务

> 状态：**M1–M3 与 M5 主体已实现**（Windows / Android 共用 `ClientApp` 与同一套页面代码，Android 为 SDLActivity + JNI 宿主）；
> 剩余：Android 本地服务（当前固定离线）、番组关注/取关、图标资源接入；Linux UI 按设计不提供。验收记录见 §10。
> 规范约定见 [AGENTS.md](../AGENTS.md)；进程模型沿用 [modularization-plan.md](modularization-plan.md) §5.6。
> 本文不写线上域名、IP、SSH/数据库口令与测试账号，统一用占位符；实际值从本机 `platforms.json` 或本地配置读取。

---

## 0. 结论速览

1. **目标**：Android 优先、Windows 同步可跑的 minimal client。能登录服务端公网入口，能看「个人资料 + 番组表」两页，能显示本地系统服务状态。WebView 只作为后续少数特殊页面的兜底（类比 B 站打开专栏类页面），**本期不实现**。
2. **UI 分工**：Kotlin 负责应用壳、路由、状态、绑定、页面逻辑；RML/RCSS 负责页面结构与尺寸配置；C++/RmlUi 只负责窗口、GPU、输入、字体、纹理、measure/arrange 与绘制。**不为每个页面写 C++。**
3. **进程模型**：Windows/Linux 是同一个可执行文件，`--service` 跑系统服务、`--ui` 跑界面；Android 是同一个 APK，Activity + 前台 Service。UI 同时连**远程服务端**和**本地系统服务**，两者互不替代，本地服务**不代理**远程 API。
4. **最小功能**：登录、自动登录、401 回登录、个人资料、头像、登出、番组表列表、本地服务健康/指标卡片。
5. **必须先补的底层能力**：列表/结构体绑定、多文档/多模型、动作参数、内存资源注册（异步图片）、滚动到项、移动端 insets/IME、Android JNI 桥。
6. **实施顺序**：M0 骨架 → M1 Windows 登录/资料 → M2 列表 + 图片 → M3 Android 同页面 → M4 本地服务 → M5 控件库与体验补齐（可选）。

---

## 1. 目标与非目标

### 1.1 目标

- **登录**：用户名 + 密码登录远程服务端；token 持久化；重启自动校验；401/403 清会话回登录。
- **个人资料**：`/api/user/myInfo` 展示用户名、姓名、邮箱、角色；头像从服务端取；支持登出。
- **一个现有 Web 功能**：番组表 `/api/anime/timetable`（普通用户默认有 `lolosia.anime.read`），展示封面、标题、放送星期/时间、关注态。
- **本地系统服务**：UI 能发现并连接本机服务，显示健康状态、版本、CPU/内存/磁盘指标；服务不在线时 UI 正常降级。**本地服务不代理远程 API。**
- **控件库**：形成独立于业务的控件库（状态、RML 结构、RCSS 外观），本期先做登录/资料/列表所需的最小集，后续按需补齐，供其他页面直接复用。
- **WebView（后续，不在本期）**：仅用于少数特殊页面（类比 B 站打开专栏类页面），只保留架构位置，不实现。
- **底层机制落地**：应用壳、路由、Tab/全屏布局、主题、会话、设置、错误映射、列表绑定、异步图片、滚动、insets、页面生命周期。
- **双端**：Windows 先跑通，Android 复用同一套 Kotlin 页面与 RML 资源；Android 通过 JNI 驱动同一套 `ll_ui_*` / RmlUi 后端。

### 1.2 非目标（本期不做）

- **参考项目的 61 个业务页面压根不做**；只做业务无关的页面，或与本项目功能本来就有交集的页面（登录、资料、设置、文件/媒体入口等按需）。
- 不做 Compose、不做自研 Vulkan 渲染器、不做自研布局引擎；RmlUi 的 flexbox 继续负责 measure/arrange。
- 不做富文本、视频、音频、Canvas 图表、复杂手势（下拉刷新/Pager/双指缩放放到 M5 可选）。
- **本期不做 WebView**；后续是否接入、开放哪些页面另行决定。
- 不做 Linux UI；Linux 只保留 `--service`。
- 不把服务端业务代码搬进客户端；本地服务只做最小系统能力，不代理远程 API。

---

## 2. 现状与差距

### 2.1 已有

| 能力 | 现状 |
| --- | --- |
| 渲染 | RmlUi 6.3 + SDL3 GPU（Windows 走 Vulkan），C++ 门面 `liblolosia` |
| 界面 | `probe.rml` 单页，4 种标量、32 个槽位、单个文档 |
| Windows | `lolosia-backend.exe ui` 进 UI 实验，cinterop 绑定 `ll_ui_*` |
| Android | `SDLActivity` + `LolosiaUiAndroid.cpp` 写死 `ProbeState`，Kotlin 未参与 UI |
| 网络 | `:modules:core` 的 `post/get/sse/ws`，信封解包、重试、错误 |
| 契约 | `:modules:protocol` 已有 `LoginResult`、`ApiResult`、`AnimeSeriesDto` 等 |
| 服务端 | 登录、用户、番组表、头像等接口已存在 |
| 进程规划 | [modularization-plan.md](modularization-plan.md) §5.6 已定 `--service` / `--ui` / Android 前台 Service |

### 2.2 关键差距

| 差距 | 影响 | 本期处理 |
| --- | --- | --- |
| 没有应用壳/路由/主题/会话/设置 | 无法从「一个实验页」变成「一个客户端」 | `:modules:ui` 新建 |
| 只有单文档、单模型 | 登录页/主壳/弹层无法共存 | 多文档 + 每页模型 |
| 只有 4 种标量、32 槽 | 番组表列表无法表达 | 列表/结构体绑定 |
| 动作只有名字、没有参数 | 列表项点击无法区分 | action 回调带 JSON payload |
| 图片只有同步 classpath/磁盘 | 头像/封面无法异步加载 | 内存资源注册 + 图片缓存 |
| 没有滚动到项/滚动事件 | 长列表、聊天无法定位 | 补 scroll API |
| 没有 insets/IME | 登录、聊天输入会被键盘挡住 | 平台注入 insets |
| Android 没有 Kotlin UI | 双端无法复用页面 | JNI 桥 + 共享 `ui-core` |
| 本地服务未实现 | UI 无法接系统服务 | 按 §7 落地 |

---

## 3. 总体架构

### 3.1 进程与连接

```
┌─────────────────────────────┐
│ 远程服务端 :modules:server   │  HTTPS /api/*
│ （服务端公网入口）          │
└──────────────▲──────────────┘
               │
┌──────────────┴──────────────┐        ┌──────────────────────────┐
│ UI 进程                      │  loopback │ 本地系统服务             │
│ :modules:client / app-android│◄────────►│ 同一 exe --service       │
│ 页面 / 状态 / 路由 / 绑定     │  HTTP/JSON│ Windows SCM / Android FG │
└──────────────▲──────────────┘        └──────────────────────────┘
               │ native surface
┌──────────────┴──────────────┐
│ RmlUi + SDL3 GPU（C++ 胶水） │
└─────────────────────────────┘

┌─────────────────────────────┐
│ WebView（后续，本期不实现） │  仅用于少数特殊页面
└─────────────────────────────┘
```

- **Windows**：同一个 `lolosia-backend.exe`。SCM 以 `--service` 在 session 0 拉起服务；用户登录后以 `--ui` 拉起界面。两者通过 `127.0.0.1` 的 loopback API 通信。
- **Linux**：同一个 kexe 由 systemd 以 `--service` 拉起；本期不做 UI。
- **Android**：同一个 APK。Activity 跑 UI；前台 Service 跑本地服务（可独立进程 `:service`）。两者通过 loopback API 通信；不假设 Binder。
- **远程服务端**：UI 直接连，不经过本地服务。本地服务不可用时，远程功能仍然可用。

### 3.2 模块布局

```
:modules:protocol      跨端 DTO / 信封 / SSE 帧（已有）
:modules:core          对外 HTTP 客户端（native，已有）
:modules:ui            新增：纯 Kotlin 应用框架 + 页面逻辑 + 绑定 + 路由
:modules:client        Windows UI 后端（cinterop）+ 页面资源
:modules:app-android   Android 宿主（SDLActivity + JNI + Kotlin UI）
:modules:server        远程服务端 + 本地服务端（同一代码库）
```

- `:modules:ui` **不依赖平台**：只依赖 `:protocol` 与 `kotlinx.serialization`；网络、存储、图片、insets 通过接口注入。
- Windows：`:client` 实现 `UiBackend`（cinterop）与 `RemoteApi`（复用 `:core`）。
- Android：`app-android` 的 Kotlin 源集直接包含 `:modules:ui` 与 `:protocol` 的 `commonMain` 源码目录；Android 侧实现 `UiBackend`（JNI）与 `RemoteApi`（Ktor JVM）。
- **构建约束**：`app-android` 是独立 Gradle 构建，不把 AGP 引入根构建；Phase 1 用「共享源码目录」而不是 `androidTarget()`。Phase 2 再评估 JVM/Android 目标或本地 Maven 发布。
- 页面代码只写一份：`ui-core` 的 `Page` / `ViewModel` / `Route` / RML 资源在两端共用。

### 3.3 UI 分层

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| `ui-core` | 应用壳、路由、Tab/全屏布局、状态、命令、列表状态、会话、设置、错误映射、页面 ViewModel | 平台 API、绘制 |
| RML/RCSS | 页面结构、类名、尺寸/间距/颜色配置、`data-*` 绑定 | 业务逻辑 |
| `UiBackend` | 文档/模型/动作/资源/滚动/insets/frame 的后端接口 | 业务状态 |
| C++/RmlUi | 窗口、GPU、输入、字体、纹理、measure/arrange、绘制 | 业务、路由、会话 |

**原则**：C++ 是胶水；RmlUi 是布局/绘制引擎；Kotlin 是应用框架与页面逻辑。新增一个页面 = 新增 Kotlin Route/ViewModel + 一个 RML，不改 C++。

### 3.4 页面描述方式

本期采用 **RML/RCSS 作为 XML 层**（类似 WPF XAML / Android XML）：

- 应用壳、TabBar、全屏壳由 Kotlin 的 `AppLayout` / `TabBarLayout` / `FullScreenLayout` 决定，壳用 RML 描述。
- 页面内容用 RML 描述；Kotlin `Page` 持有 ViewModel，负责数据绑定、事件、生命周期。
- 不在本期实现完整 Kotlin DSL；后续需要类型安全时，再由 Kotlin 生成 RML。
- 低级 measure/arrange 继续由 RmlUi 负责；Kotlin 只决定「用哪个壳、哪个页面、什么尺寸配置」。

---

## 4. 远程服务对接

### 4.1 地址与网络

- 目标：服务端公网入口。
- 文档与代码不写死域名；客户端从本地配置读取，默认值放在本机配置/构建参数中。
- 备用入口：同一服务的备用公网入口；实现「主地址失败 → 备用地址」的重试策略。
- 仅 HTTPS；使用系统信任链；超时沿用 `:core` 的 14s；GET/POST 失败按既有策略重试。
- Android 禁止明文 HTTP；IPv6 不可用时自动回退备用地址。

### 4.2 登录

```
POST /api/login
{ "userName": "<user>", "password": "<password>" }

→ { code, msg, data: LoginResult }
```

- `LoginResult.authorization` 就是会话 id；后续请求带 `Authorization: Bearer <sessionId>`。
- 登录接口当前**不要求图形验证码**；若服务端后续开启，再接入：
  - `POST /api/captcha` → `{ image: base64 PNG, id }`
  - `POST /api/verify` → 校验
- 登录成功：保存 token、用户信息、baseUrl；进入主壳。
- 登录失败：展示 `msg`；401/403 清 token；网络错误给出重试。

### 4.3 个人资料

```
POST /api/user/myInfo        Authorization: Bearer <token>
→ { code, msg, data: UserInfoRow }

GET /api/user/avatar/get/{id}?t=<version>
→ image/png | image/jpeg
```

- `myInfo` 与其他 JSON 接口一样使用 `{code,msg,data}` 信封。
- 头像端点是**既有二进制资源取件地址**（Web 用 `<img>` 直接访问），不是 JSON 业务接口；客户端 `ImageLoader` 按 URL 取字节。**不新增任何无信封的 JSON 接口。**
- `UserInfoRow` 目前在 `:server` 的 `SystemDtos.kt`；客户端不应依赖服务端模块。把它迁到 `:protocol`，或在 `ui-core` 定义镜像 DTO。
- 头像 URL 带版本参数，避免缓存不刷新；图片走 §5.6 的异步图片管线。

### 4.4 随机功能：番组表

```
GET /api/anime/timetable?weekday=<1..7>   Authorization: Bearer <token>
→ { code, msg, data: List<AnimeSeriesDto> }
```

- 普通用户默认有 `lolosia.anime.read`，不需要 admin。
- 展示字段：`title`、`cover`、`airWeekday`、`airTime`、`followed`、`followedGroups`。
- `cover` 可能是相对路径（如 `/res/resources/anime/cover/...`），客户端按 baseUrl 拼绝对 URL。
- 本期只读；关注/取消关注（`/anime/subscription/save|delete`）留到后续。
- 页面需要列表绑定；这是本期验证 C ABI 列表能力的主要场景。

### 4.5 登出

```
POST /api/logout             Authorization: Bearer <token>
```

- 无论服务端是否成功，客户端都清本地 token；回登录页。

### 4.6 信封与错误

- 所有 `/api/` JSON 响应为 `{code, msg, data}`；`code != 200` 视为业务错误。
- `401/403`：清会话、跳登录；`403` 额外提示「没有权限」。
- `503`：数据库暂不可用；展示可重试提示。
- 网络层错误统一映射为 `AppError`（network / auth / permission / server / unknown）。

### 4.7 客户端接口

```kotlin
interface RemoteApi {
    suspend fun login(userName: String, password: String): LoginSession
    suspend fun myInfo(token: String): UserProfile
    suspend fun avatar(userId: String, version: Long): ByteArray?
    suspend fun animeTimetable(token: String, weekday: Int?): List<AnimeItem>
    suspend fun logout(token: String)
}
```

- Windows 实现：`:modules:core` 的 `post/get`。
- Android 实现：`HttpURLConnection` + kotlinx.serialization（不引 Ktor：其 native 目标没有 `androidNative` 产物；JVM 侧直接用标准库即可）。
- DTO：优先放 `:protocol`；Android 通过共享源码目录使用同一份 DTO。

---

## 5. UI 基础机制

### 5.1 应用壳与布局

对应参考项目的 `layout/` + `App.kt`：

| 组件 | 职责 |
| --- | --- |
| `AppLayout` | 启动态、登录门禁、主壳、错误页、Toast/弹层宿主 |
| `TabBarLayout` | 底部 Tab（首页 / 我的）+ 内容区 |
| `FullScreenLayout` | 全屏页（详情、设置等）+ 返回 |
| `Route` | `title`、`icon`、`layoutType`、`pageId`、`rmlPath` |
| `Navigator` | 当前 Tab、全屏栈、弹层栈、返回处理 |

- 壳文档：`ui/layout/app.rml`（内容宿主、覆盖层、Toast）。
- 页面文档：`ui/pages/*.rml`。
- 文档承载：优先「壳 + 页面 + 覆盖层」多文档；若 RmlUi 文档叠放成本高，退回「壳文档 + `SetInnerRML` 内容宿主」，但数据模型仍需多模型。
- 高级布局配置（谁在 Tab 里、谁全屏、页面间距/尺寸）由 Kotlin + RML 决定，C++ 不算这些。

### 5.2 状态与页面生命周期

- `State<T>` / `Derived<T>` / `ListState<T>` / `Command`：沿用现有 `Property` / `Command` 扩展，不引 Compose。
- `Page` 接口：`onCreate` / `onEnter` / `onLeave` / `onDestroy`；ViewModel 随页面创建/释放。
- `AppSession`：token、用户、baseUrl、登录态；实现 `SessionIdProvider`。
- `SettingsStore`：token、baseUrl、上次用户、主题；Windows 落 `FileStore`，Android 落 SharedPreferences/DataStore。
- `EventBus`：Phase 2 接 SSE 时使用；本期可先不建。

### 5.3 数据绑定

现有能力：`ll_ui_model_bind_string/int/float/bool` + `data-value` / `data-checked` 回读。
本期必须补：

| 能力 | ABI | 用途 |
| --- | --- | --- |
| 多模型 | `ll_ui_model_create(name)` + 文档绑定模型 | 登录页/主壳/弹层各自模型 |
| 列表 | `ll_ui_model_list_create(name, fields, capacity)`、`list_resize`、`list_set_*`、`list_dirty` | 番组表、后续信息流 |
| 动作参数 | `ll_ui_set_action_callback` 回调增加 `const char* payload`（JSON） | 列表项点击、带 id 的操作 |
| 表单回读 | 现有 `ll_ui_model_get_*` + 帧末同步 | 登录输入、设置项 |

RML 侧：

```html
<div data-for="anime.items">
  <div class="anime-row" data-event-click="action('open-anime', id)">
    <img src="/mem/anime/{{ id }}.jpg" />
    <span>{{ title }}</span>
  </div>
</div>
```

- 列表字段用 `list_create` 的字段描述声明；C++ 侧固定容量（如 256 行），超出由 Kotlin 分页。
- 行内点击参数通过 `payload` 回传；Kotlin 解析 JSON。
- 表单控件沿用 `data-value` / `data-checked`，在 `ll_ui_frame` 返回后统一读回，避免事件顺序问题。

### 5.4 路由与返回

- `Navigator.push(route)` / `pop()` / `replace(route)`。
- Android 返回键：栈非空则 `pop`，否则退出；Windows `Esc` 同义。
- 登录成功：`replace(Main)`；登出：清栈 + `replace(Login)`。
- 401：任意页面收到后清会话 + `replace(Login)`。
- 本期不做深链；WebView 路由设计留到后续（见 §8）。

### 5.5 主题与组件

- `ui/theme/theme.rcss`：颜色、字号、间距、圆角、阴影、按钮/输入框/卡片样式。
- 用 RCSS 变量 `var()` 做 token；`var()` 在 RmlUi 6.3 已支持。
- 组件（RML 片段 + Kotlin 状态）首批：button、input、textarea、checkbox/switch、tabs、card、list-row、avatar、badge、spinner、progress、empty/error、dialog、toast、navbar、tabbar。
- 图标：使用 **Bootstrap Icons**（MIT），随 classpath 内嵌；优先 TTF（FreeType 兼容性最稳），构建确认支持 Brotli 后再用 woff2。整套字体体积可接受，不需要图片图集。
- RmlUi 没有默认样式表；所有控件子元素都要显式给尺寸/颜色（输入框、滑杆、滚动条、进度条）。
- 现代移动端效果按此前评估：圆角/阴影/半透明/flex/横向滚动/文字/图标/进度/弹窗可以直接做；`aspect-ratio`、多行省略、异步图片、下拉刷新、Pager、手势需要额外实现。

### 5.6 异步图片

```
RemoteApi.avatar / anime.cover
        │
        ▼
ImageLoader（Kotlin）
  - 内存 LRU + 磁盘缓存
  - HTTP 取字节（带 token/版本参数）
  - 解码/缩放/裁剪（Windows codecs；Android BitmapFactory）
  - 注册为虚拟资源：/mem/<hash>
        │
        ▼
ll_ui_register_resource(path, bytes)
        │
        ▼
RML: decorator: image(/mem/<hash>) / img src
```

- 占位图：先显示纯色/默认图，资源注册后切换 class 触发 `transition: opacity`。
- 裁剪：头像按正方形、封面按固定比例在 Kotlin 侧预裁剪；避免在 C++ 里做 cover 数学。后续再考虑自定义 `Decorator`。
- 失败：显示默认头像/封面；不阻塞页面。

### 5.7 滚动、insets、手势

- 滚动：RmlUi 自带 overflow 滚动；本期补 `ll_ui_scroll_to(elementId, top, animate)` 与 `ll_ui_get_scroll_top`，供后续列表/聊天使用。
- insets：
  - Android 从 `WindowInsets` 取 status/navigation/IME 高度，经 JNI 写入模型字段 `safeTop` / `safeBottom` / `imeBottom`。
  - 壳 RML 用 `data-style-padding-top` / `data-style-padding-bottom` 消费；或在 C++ 侧设置 CSS 变量。
  - Windows 全部为 0。
- 手势：下拉刷新、Pager、双指缩放放 M5 可选；本期只做点击/滚动/输入。

### 5.8 参考项目机制映射

| 参考项目机制 | 本方案落点 | 阶段 |
| --- | --- | --- |
| `App.kt` 启动态 / token 校验 / 401 / 错误页 | `AppState` + `AppLayout` | M1 |
| `router/Routes.kt` + TabBar/FullScreen | `Navigator` + `Route` + 布局壳 | M1 |
| `theme/` | `theme.rcss` + `Theme` | M1 |
| `store/` + `SystemStore` | `AppSession` + `SettingsStore` | M1 |
| `api/package.kt` 信封/错误/重试 | `:core` + `RemoteApi` | M1 |
| SSE + `EventBus` | `EventBus` + `SseManager` | M5（可选） |
| Compose 状态/绑定 | `State` / `ListState` / `Command` | M1/M2 |
| `LazyColumn` | RmlUi 滚动 + 列表绑定 | M2 |
| Coil 异步图片 | `ImageLoader` + 内存资源注册 | M2 |
| `PullToRefresh` | 自定义元素/手势 | M5（可选） |
| 全屏图片查看器 | Pager + pinch/pan 自定义元素 | M5（可选） |
| `imePadding` / system bars | 平台 insets | M3 |
| 通用控件（TextField/Chip/Dialog…） | `ui/widgets` + `ui/components` | M1 起逐步，M5 补齐 |
| WebView | 仅保留后续设计 | 后续，本期不做 |
| 本地系统服务 | `LocalServiceApi` + loopback | M4 |

### 5.9 控件库计划

- 位置：
  - `ui/widgets/`：Kotlin 侧状态、命令、校验、格式化；
  - `ui/components/`：RML 片段（可被页面 `<include>` 或由 Kotlin 注入）；
  - `ui/theme/components.rcss`：控件外观与状态（normal/hover/active/disabled/focus/error）。
- 原则：控件库不依赖任何具体业务；页面只组合控件，不复制样式。
- 本期最小集（M1–M2）：button、text-input、password-input、checkbox/switch、list/list-row、avatar、spinner、empty/error/loading、navbar、tabbar、card。
- 后续补齐（M5 可选）：select/dropdown、textarea、tabs、badge、progress、slider、dialog、toast、bottom-sheet、search-bar、segmented、skeleton、pull-to-refresh、pager、image-viewer。
- 控件库是长期资产：即使当前页面不用，也按计划补齐，供后续有交集的页面直接复用。

---

## 6. 平台与渲染后端

### 6.1 Windows

- 继续使用 `lolosia-backend.exe ui` / `--ui`；SDL3 + RmlUi + `liblolosia.a`。
- 调试可用 `--embed`：同一进程内启动本地服务（进程内传输，不建端口、不写发现文件），不依赖 SCM / systemd；生产仍按 §7 的进程模型拆分。
- `UiBackend` 由 `:client` 的 cinterop 实现；资源走 classpath，字体走磁盘/系统字体。
- 新增 `--service` 模式（§7），UI 与服务通过 loopback 通信。
- 开发命令：`gradlew runDebug -PappArgs=ui`（只开 UI）、`gradlew runDebug -PappArgs=--embed`（UI + 本地服务单进程）；服务模式 `-PappArgs=--service`。

### 6.2 Android

- `MainActivity` 继续继承 `SDLActivity`，加载 `libSDL3.so` + `liblolosia.so`。
- 新增 JNI 桥（C++ 侧）：`nativeCreate` / `nativeFrame` / `nativeLoadDocument` / `nativeSetModel*` / `nativeAction` / `nativeRegisterResource` / `nativeInsets` / `nativeDestroy`。
- Kotlin UI 线程与 native 帧线程：
  - native 帧循环在 `SDL_main` 线程；
  - Kotlin 状态变化写入命令队列，native 每帧消费；
  - native action 回调通过 JNI 投递到 Kotlin 主线程。
- `app-android` 的 Kotlin 源集包含 `:modules:ui` 与 `:protocol` 的 `commonMain` 源码目录；Android 侧实现 `RemoteApi` / `SettingsStore` / `ImageLoader` / `InsetsProvider`。
- assets 继续复用 `modules/client/src/commonMain/resources`；字体用系统 Noto CJK；图片解码走 `BitmapFactory`。
- 返回键、生命周期、旋转、IME 由 Activity 转发给 `AppState` / `UiBackend`。

### 6.3 C ABI 增补

| 函数 | 用途 |
| --- | --- |
| `ll_ui_model_create(name)` | 多模型 |
| `ll_ui_model_list_create/resize/set_*/dirty` | 列表绑定 |
| `ll_ui_set_action_callback(cb, user, with_payload)` | 动作参数 |
| `ll_ui_register_resource(path, bytes, len)` | 内存图片/资源 |
| `ll_ui_scroll_to(id, top, animate)` | 滚动到项 |
| `ll_ui_get_scroll_top(id, *top)` | 读取滚动位置 |
| `ll_ui_set_insets(top, bottom, ime)` | 移动端 insets |
| `ll_ui_document_push/pop` | 页面/弹层栈 |

- ABI 需要版本号；新增函数不破坏现有 `ll_ui_*`。
- C++ 侧仍不持有业务状态；模型只做展示数据与表单回读。

### 6.4 构建

```powershell
# Windows UI
gradlew runDebug -PappArgs=ui

# Android 原生库 + APK
gradlew androidNative
gradlew -p modules/app-android assembleDebug

# 安装
adb install -r modules/app-android/build/outputs/apk/debug/lolosia-android-debug.apk
adb shell am start -n moe.lolosia.android/.MainActivity
```

---

## 7. 本地系统服务

### 7.1 进程模型

- **Windows/Linux**：同一个可执行文件，`main()` 按参数分发：
  - `--service`：无界面，SCM/systemd 拉起，按服务权限运行；
  - `--ui` 或默认：用户会话，跑界面；
  - 两者**不是同一个进程**，不共享内存。
- **Android**：同一个 APK，不同组件/进程：
  - Activity：UI 进程；
  - 前台 Service：`android:process=":service"`（建议），按服务权限运行；
  - 不假设同一进程，也不假设能直接互相调用对象。

### 7.2 本地 IPC

- 统一走 **loopback HTTP**：`127.0.0.1:<port>`，JSON 信封 `{code,msg,data}`。
- 端口与 token：
  - 服务启动时生成随机端口 + 随机 token；
  - 端口/token 写入受保护文件：Windows 用 ACL 只允许服务账户与交互用户读取；Android 用应用私有目录；
  - UI 读取后连接；token 放 `Authorization: Bearer <local-token>`；
  - token 每次服务启动轮换，不落日志。
- 协议版本：`/local/v1/health` 返回 `{version, protocol, capabilities}`；版本不匹配时 UI 提示升级。
- 只绑定 `127.0.0.1`，不监听 `0.0.0.0`；Windows 防火墙不开放端口。
- 备选：Windows named pipe / Android Binder；先保持 `LocalServiceApi` 接口，底层可替换。

### 7.3 最小接口

| 接口 | 返回（统一信封） | 用途 |
| --- | --- | --- |
| `GET /local/v1/health` | `{code,msg,data:{ok, version, uptime}}` | 服务发现 |
| `GET /local/v1/capabilities` | `{code,msg,data:{items:[...]}}` | UI 决定显示哪些卡片 |
| `GET /local/v1/metrics` | `{code,msg,data:{cpu, mem, disk, ...}}` | 本地状态卡片 |
| `GET /local/v1/version` | `{code,msg,data:{version, build}}` | 关于页 |

- 本地接口与远程 `/api/*` 使用同一套 `{code,msg,data}` 信封；**不新增裸 JSON 接口**。
- 本期不做文件读写、命令执行、远程代理；这些需要单独的权限模型与审计。
- 服务端已有的系统指标采集（`SystemMetricsReader`）可复用；Windows 服务模式下采集本机指标。
- UI 端 `LocalServiceApi`：`health()` / `capabilities()` / `metrics()`；失败时返回 `Unavailable`，不抛到页面。

### 7.4 安全

- 服务进程权限高于 UI：**不信任 UI 传入的任何路径/命令**；本期只读指标，不接受参数。
- token 文件权限最小化；服务启动后不把 token 打到日志。
- 请求体大小限制、超时、并发限制。
- 未来增加写操作时：能力白名单 + 每次请求审计 + 用户确认；不允许任意 shell。
- UI 不把远程 token 发给本地服务；本地服务也不把本地 token 发给远程。

### 7.5 Android 细节

- `FOREGROUND_SERVICE` + 通知渠道；Android 13+ 需要 `POST_NOTIFICATIONS`。
- Service 启动方式：Activity 启动时 `startForegroundService`；可选开机自启（本期不做）。
- 本地 HTTP 服务用 `ServerSocket` 绑 `127.0.0.1`；只允许本应用 UID 连接（可通过 token + 私有文件；进一步可校验 `SO_PEERCRED`/`getsockopt`，本期先 token）。
- 通知提供「停止服务」动作；停止后 UI 显示未运行。

### 7.6 Windows 细节

- SCM 安装/卸载脚本或 `--install-service` / `--uninstall-service`；服务名固定。
- 服务账户：LocalSystem 或专用低权限账户（按实际能力选择）；本期只读指标，专用账户即可。
- token 文件放在 `%ProgramData%\<app>\local-service.json`，ACL 允许 `Users` 读取、仅服务账户写入。
- UI 启动时读取；服务重启后 token 变化，UI 重新发现（端口/token 文件 mtime 变化）。

### 7.7 远程事件订阅（EventBus）

- **Windows / Linux**：事件订阅是 `--service` 服务进程内的一个模块，随服务启动、随服务停止；不再单独注册 `LolosiaEventBus`，也不再有 `--event-service*` 入口。
- Windows 的 `--service` 内置 SCM dispatcher（服务名 `LolosiaService`），可直接用 `sc create ... binPath= "...\lolosia-backend.exe --service"` 注册；非 SCM 启动自动回退为控制台模式。
- 服务与 UI 共用同一份客户端设置（Windows `%ProgramData%\Lolosia\client-settings.json`，Linux `work/client-settings.json`），UI 登录后写入，服务每 10 秒重读，因此不需要单独配置 token。
- **Android** 用前台 Service（`EventBusService`，`dataSync` 类型）保持一条 `/api/sse` 长连接；
  三步握手与服务端 `SseController` 一致：`POST /api/sse`（`x-upgrade: event-source`）→
  `POST /api/sse/registry`（Bearer token）→ `GET /api/sse?eventSourceId=…`。
- 事件转系统通知：`anime.release` 显示「番名 + 第 N 话 + 发布标题」；未知事件显示事件名与原始数据。
- 断线按 1s → 30s 指数退避重连；未登录时每 10 秒重读一次设置，登录后自动接上。
- 本地联调：`tools/mock-sse-server.mjs`（Node，无依赖）复刻握手、心跳、`anime.release` 定时推送与
  `POST /api/mock/publish` 手动推送；模拟器访问宿主机用 `http://10.0.2.2:18080`。

---

## 8. WebView（后续，不在本期）

> 本期不实现，仅记录架构位置与形态，避免后续接入时改动应用壳。

### 8.1 使用场景

- 只在少数特殊页面使用，模式类似 B 站打开专栏/文章类页面：原生列表点进去，跳到 WebView 承载。
- 不在原生端重复实现；原生只保留入口、标题、返回。
- 本期没有必须使用 WebView 的页面，因此不排期。

### 8.2 形态

- `WebViewPage(url)` 是一个 `FullScreenLayout` 路由；打开时隐藏 RmlUi 内容区，显示原生 WebView。
- Android：`android.webkit.WebView`；Windows：WebView2（后续）。
- 顶部原生返回栏（RML 或系统栏）负责关闭；WebView 内导航不改变原生栈。

### 8.3 登录态（后续设计）

- 若后续接入：WebView 打开 Web 登录页，用户自行登录；不与原生会话共享。
- 更后续：服务端增加一次性 `loginCode` 接口；原生用 token 换 code，WebView 用 code 换 cookie/session；code 短时效、一次性、绑定用户与来源。
- 不在 URL 里直接放 token；不使用 `javascript:` 注入 token。

### 8.4 安全

- 仅允许服务端 origin；外部链接交给系统浏览器。
- 禁用文件访问、禁用任意 JS bridge；只暴露 `close` / `title` / `authExpired` 三个最小消息。
- HTTPS only；关闭 mixed content；开启 Safe Browsing（Android）。
- WebView 进程崩溃/无网络时显示原生错误页。

---

## 9. 页面、路由与数据模型

### 9.1 路由

| Route | 布局 | 页面 | 说明 |
| --- | --- | --- | --- |
| `Splash` | 无 | `splash.rml` | 读设置、校验 token |
| `Login` | 全屏 | `login.rml` | 用户名/密码、错误、加载 |
| `Main/Home` | TabBar | `home.rml` | 番组表列表 |
| `Main/Profile` | TabBar | `profile.rml` | 用户信息、头像、本地服务卡片、登出 |
| `Settings` | FullScreen | `settings.rml` | 服务器地址、清除会话（调试） |

> WebView 路由本期不建；后续仅作为特殊页面的全屏兜底（§8）。

### 9.2 页面状态

| 页面 | 字段 |
| --- | --- |
| Login | `userName`、`password`、`loading`、`error`、`canSubmit` |
| Home | `anime.items[]`（title/cover/weekday/time/followed）、`loading`、`error`、`empty` |
| Profile | `user.name`、`realName`、`email`、`avatarUrl`、`role`、`local.available`、`local.cpu/mem/disk` |
| Settings | `baseUrl`、`tokenMasked`、`clearSession` |

### 9.3 RML 资源

```
modules/client/src/commonMain/resources/ui/
  layout/app.rml
  layout/tabbar.rml
  layout/fullscreen.rml
  pages/splash.rml
  pages/login.rml
  pages/home.rml
  pages/profile.rml
  pages/settings.rml
  theme/theme.rcss
```

- 每个页面一个模型；壳文档只做容器。
- 列表用 `data-for`；行点击用 `action('open-anime', id)`。
- 空/错/加载三态用 `data-if` + 独立块，避免页面抖动。

---

## 10. 实施阶段与验收

### M0：骨架

- 新建 `:modules:ui`（commonMain）：`AppState`、`Navigator`、`Route`、`AppSession`、`SettingsStore`、`RemoteApi`、`LocalServiceApi`、`ImageLoader`、`State/Command`。
- `:modules:protocol` 补客户端需要的 DTO（`UserInfoRow` 等）。
- Windows/Android 的 `UiBackend` 接口先打桩。
- 验收：两端能编译；一个空壳页面能启动/退出。

### M1：Windows 登录 + 资料

- 登录页、主壳、TabBar、Profile、登出。
- token 持久化、自动登录、401 回登录。
- 标量绑定、错误映射、加载态。
- 验收：用测试账号登录真实服务端；杀掉进程重启仍自动登录；错误密码有提示。

### M2：列表 + 图片

- 列表/结构体绑定、动作参数。
- 异步图片：头像、番组封面；内存/磁盘缓存；占位/失败。
- Home 番组表；空/错/加载状态。
- 验收：列表滚动流畅；封面加载不阻塞；点击行能拿到 id。

### M3：Android 同页面

- JNI 桥、命令队列、Kotlin/JNI 回调。
- 共享 `ui-core` 与 RML 资源；Android 实现 `RemoteApi`/`SettingsStore`/`ImageLoader`。
- insets/IME、返回键、旋转。
- 验收：真机/模拟器登录、资料、番组表；键盘不挡输入框；返回键正确。

### M4：本地服务

- Windows `--service`（本地 API + 事件订阅同进程）、loopback、共享设置文件；Android 前台 Service + loopback。
- UI `LocalServiceApi`、Profile 本地状态卡片。
- 验收：服务未启动时 UI 正常；启动后显示 CPU/内存/磁盘；服务重启后 UI 自动恢复。

### M5：控件库与体验补齐（可选）

- 控件库按 §5.9 补齐：select/dropdown、textarea、tabs、badge、progress、slider、dialog、toast、bottom-sheet 等。
- 下拉刷新、Pager、双指缩放、SSE/EventBus 按实际需要排期。
- 不包含 WebView（后续另行决定）。
- 验收：新增页面只组合控件、不复制样式；控件在 Windows/Android 表现一致。

### 验收清单

- [x] 登录成功 / 自动登录 / 401 清会话（Windows 与 Android 均验证；服务器地址由登录页维护）。
- [x] 个人资料与头像路径（测试账号无头像，首字占位已验）。
- [x] 番组表列表（普通用户权限，108 条 + 封面）。
- [x] 本地服务在线/离线两种状态（Windows；Android 目前固定离线，见 §7.5）。
- [x] 控件库最小集可复用（两端口径一致）。
- [x] Android 返回键、IME（软键盘输入与收起已验；旋转按 `configChanges` 原地处理，未单独回归）。
- [x] 无域名/口令/测试账号入库；日志不含 token。
- [x] Windows 与 Android 使用同一套页面代码与 RML。

---

## 11. 风险与待决策

| 风险 | 说明 | 处理 |
| --- | --- | --- |
| Android 共享 UI 的构建方式 | 根构建不能引 AGP；`app-android` 独立 | Phase 1 共享源码目录；Phase 2 评估 JVM/Android 目标或 Maven 发布 |
| JNI 线程模型 | native 帧线程 vs Kotlin 主线程 | 命令队列 + 主线程回调；先做最小 API |
| 远程入口 IPv6/网络 | 移动网络可能没有 IPv6 | 主/备地址重试；真机验证 |
| 列表绑定复杂度 | `data-for` 需要 C++ 结构体/数组 | M2 单独做；先固定容量 + 分页 |
| 图片裁剪/crossfade | RmlUi 不保证 cover/异步 | Kotlin 预裁剪 + 内存资源；后续自定义 Decorator |
| 本地服务安全 | 服务权限高于 UI | 只读最小接口；token + loopback + ACL；不做任意命令 |
| WebView 登录态（后续） | 若接入则不无缝 | WebView 自行登录；后续一次性 code |
| 测试账号权限 | 番组表需要 `lolosia.anime.read` | 已准备普通用户与管理员账号；凭据不入库 |
| RmlUi 样式子集 | 无默认样式、无 grid/line-clamp | 组件库显式样式；Kotlin 算尺寸/截断 |

### 已决策

1. Android UI 路线：JNI + 共享 `ui-core`（Phase 1 不采用 Compose/XML）。
2. 本地服务**不代理**远程 API：UI 直连远程，本地服务只做本机能力。
3. **本期不实现 WebView**；后续只在少数特殊页面使用。
4. 测试账号：已准备普通用户与管理员各一个；凭据不入库、不写入文档。
5. 主/备服务地址的实际值由本机配置提供，仓库内只保留占位符。

---

## 12. 附录

### 12.1 最小 API 契约

| 方法 | 路径 | 鉴权 | 用途 |
| --- | --- | --- | --- |
| POST | `/api/login` | 无 | 登录，返回 `LoginResult` |
| POST | `/api/logout` | Bearer | 登出 |
| POST | `/api/user/myInfo` | Bearer | 当前用户 |
| GET | `/api/user/avatar/get/{id}` | 无（路径取件） | 头像 |
| GET | `/api/anime/timetable` | Bearer + `lolosia.anime.read` | 番组表 |
| GET | `/local/v1/health` | local token | 本地服务健康 |
| GET | `/local/v1/capabilities` | local token | 本地能力 |
| GET | `/local/v1/metrics` | local token | 本地指标 |

> 所有 JSON 接口统一返回 `{code,msg,data}`；头像为既有二进制资源取件地址（供 `<img>` / `ImageLoader` 直接取字节），不是 JSON 业务接口，也不新增同类接口。

### 12.2 关键文件地图（计划）

```
modules/ui/src/commonMain/kotlin/moe/lolosia/web/ui/
  app/AppState.kt
  app/AppLayout.kt
  layout/TabBarLayout.kt
  layout/FullScreenLayout.kt
  router/Route.kt
  router/Navigator.kt
  state/State.kt
  state/ListState.kt
  state/Command.kt
  session/AppSession.kt
  session/SettingsStore.kt
  api/RemoteApi.kt
  api/LocalServiceApi.kt
  image/ImageLoader.kt
  platform/UiBackend.kt
  widgets/                    # 控件 Kotlin 状态/行为
  pages/login/LoginPage.kt
  pages/home/HomePage.kt
  pages/profile/ProfilePage.kt

modules/client/src/commonMain/resources/ui/
  layout/*.rml
  components/*.rml            # 控件片段
  pages/*.rml
  theme/theme.rcss
  theme/components.rcss
  fonts/bootstrap-icons.ttf

modules/client/src/nativeCpp/include/lolosia_ui.h     # 增补 ABI
modules/client/src/nativeCpp/src/LolosiaUi.cpp        # 多模型/列表/资源/滚动
modules/client/src/nativeCpp/android/LolosiaUiAndroid.cpp  # Android JNI 宿主
modules/app-android/src/main/java/moe/lolosia/android/     # Activity + Service + JNI 调用
```

### 12.3 与参考项目的对应关系

- 参考项目的 `App.kt` / `router/` / `layout/` / `theme/` / `store/` / `api/` / `util/` 是**框架层**，本方案按 §5.8 映射。
- 参考项目的 61 个业务页面**压根不做**；只做业务无关页面，或与本项目功能本来就有交集的页面。页面仍按「Route + ViewModel + RML」增量添加，控件库按 §5.9 长期补齐。
- 参考项目的 Compose/Coil/FileKit 等依赖不引入；用 `ui-core` + RmlUi + 自研 `ImageLoader` 替代。
- 参考项目的 SSE/EventBus 放 M5 可选；本期先把 HTTP + 登录 + 列表跑通。
