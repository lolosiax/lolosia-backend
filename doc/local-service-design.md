# 本地服务（Windows / Linux）设计

> 状态：**P0 已实施**（`:modules:local` + 进程内传输 + `--embed`）；P1 起的传输切换仍为设计稿。
> 规范约定见 [AGENTS.md](../AGENTS.md)；进程模型沿用 [modularization-plan.md](modularization-plan.md) §5.6 与
> [mobile-minimal-client-design.md](mobile-minimal-client-design.md) §7。
> 本文不写线上域名、口令与测试账号；本机路径只用占位说明。

---

## 0. 结论速览

1. **本地服务另起一套骨架**：不复用 `:modules:server` 的 `@Controller`、KSP 路由、拦截器、`Context`、RBAC 与数据库。
2. **两种传输、一套消息**：`--service` 走 loopback WebSocket；`--embed` 走进程内直连。两者进入**同一个 dispatcher**（路径/方法匹配 → 鉴权 → 解码 → 处理 → 编码），只是不共享 socket。
3. **协议是对称 RPC**：`request / response / event` 三种帧；服务可以向 UI 反向发 `ui.prompt` 并要求应答，这是"弹确认窗"的落点。
4. **长任务用 job 模型**：`job.start` 立即返回 `jobId`，进度走事件；不断开、不轮询、不挂长请求。
5. **设置分权**：UI 拥有 `client-settings.json`（远程地址与 token），服务拥有自己的本地配置；服务只读前者，不双写。

---

## 1. 目标与非目标

### 1.1 目标

- 给 UI 提供本机能力：指标、版本、能力清单，后续扩展到同步配置、任务进度、确认提示。
- 同一份可执行文件支持三种启动方式：
  - `--service`：无界面，本地 API + 事件订阅；
  - `--embed`：本地服务 + 事件订阅 + UI 同进程（调试用）；
  - `ui`：只有 UI，本地能力降级。
- 协议可扩展，但实现保持简单：无反射、无代码生成、无数据库依赖。
- 两种传输行为一致：同一套帧、同一套鉴权、同一套错误码。

### 1.2 非目标

- 不做远程 API 代理；本地服务不碰 `/api/*`。
- 不做通用插件系统、动态方法注册、脚本执行。
- v1 不接受 UI 传入任意路径或命令；同步相关能力落地时再按白名单逐个开放。
- 不引入 Koin、KSP、注解路由、`Context`、RBAC、信封拦截器。
- 不做 Linux 图形界面；Linux 只跑 `--service`。

---

## 2. 现状与问题

| 现状 | 问题 |
| --- | --- |
| `localservice/LocalService.kt` 写在 `:modules:server`，用 Ktor routing 直接实现 4 个 GET | 与服务器业务同模块，容易被误认为同一套 API；加方法就要碰 Ktor routing 与手写信封 |
| `CoreLocalServiceApi` 每次调用都读发现文件、发一个 GET | 只能一问一答；无法推送进度，也无法让服务反向请求 UI |
| 事件总线已并入 `--service`，但通知靠 spool 文件轮询 | 连接存在时也要轮询；UI 不在时才能体现 spool 的价值 |
| `--embed` 为隔离发现文件引入 `EMBED_ENDPOINT_PATH` | 单进程仍绕一圈 loopback，端口、token、文件都是多余状态 |
| UI 与服务共写/共读设置文件 | 没有明确归属，原子性靠运气 |

---

## 3. 结构

```
                 ┌──────────────────────────────┐
                 │ :modules:local               │
                 │  dispatcher · 方法表 · job    │
                 │  prompt 管理 · 指标采集       │
                 └──────────┬───────────────────┘
                            │ LocalTransport（接口）
          ┌─────────────────┴─────────────────┐
          │                                   │
  InProcessTransport                  WsTransport（app-desktop 装配）
  （--embed，无端口）                  （--service，127.0.0.1 + token）
          │                                   │
          └───────────────┬───────────────────┘
                          │
                 ┌────────▼─────────┐
                 │ LocalClient      │  :modules:client
                 │ 请求挂起表 · 事件流 · 重连
                 └────────┬─────────┘
                          │
                   :modules:ui（LocalServiceApi）
```

### 3.1 模块职责

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `:modules:local` | 服务侧：`LocalDispatcher`、方法表、job 管理、prompt 管理、指标采集、帧编解码、`LocalTransport` 接口 | `:protocol`、`:core`、okio |
| `:modules:client` | 客户端：`LocalClient`（请求/响应关联、事件流、重连）、WS 客户端传输、进程内客户端传输 | `:ui`、`:local`、`:core` |
| `:modules:app-desktop` | 装配：`--service` 起 WS 适配器与服务；`--embed` 直接接进程内传输 | `:local`、`:client`、`:server` |
| `:modules:ui` | 保留 `LocalServiceApi` 作为 UI 面向的接口；页面不感知传输 | 不变 |
| `:modules:server` | 仪表盘复用 `:modules:local` 的指标采集 | `:local` |

要点：

- 指标采集（`SystemMetricsReader`）从 `:modules:server` 迁到 `:modules:local`：它是本机能力，不是服务器业务。
- WS 适配器放在 `:modules:app-desktop`，避免把 `ktor-server` 带进 `:modules:client`。
- `:modules:local` 不引入任何 Ktor server 依赖；只有 app-desktop 的适配器碰 socket。

### 3.2 包结构（建议）

```
modules/local/src/commonMain/kotlin/moe/lolosia/local/
  LocalDispatcher.kt       方法表：name -> suspend (JsonObject) -> JsonElement
  LocalRequest.kt          帧模型与编解码
  LocalServiceConfig.kt    version / capabilities / 超时 / 上限
  method/                  MetricsMethod · VersionMethod · HelloMethod · SettingMethod
  job/                     JobManager · JobHandle · JobEvent
  prompt/                  PromptManager · PromptRequest · PromptAnswer
  LocalTransport.kt        request/response + events 的传输接口
  InProcessTransport.kt    进程内直连实现
  metrics/                 SystemMetricsReader（mingw / linux actual）
```

`:modules:client` 侧：

```
modules/client/src/commonMain/kotlin/moe/lolosia/web/ui/client/local/
  LocalClient.kt           连接、请求挂起表、事件分发、重连
  WsLocalTransport.kt      Ktor client WebSocket 传输
  InProcessLocalTransport.kt
```

---

## 4. 协议

单帧 JSON（文本帧），一问一答与推送共用一个通道。

### 4.1 帧

```json
{"id":7,"method":"metrics.get","params":{}}
{"id":7,"result":{"cpu":12.5,"mem":41.2,"disk":63.0}}
{"id":7,"error":{"code":503,"message":"指标暂不可用"}}

{"event":"job.progress","data":{"jobId":"sync-1","done":312,"total":1280}}

{"id":9,"method":"ui.prompt","params":{"kind":"confirm","title":"开始同步？","message":"上行 1.2 GB","actions":["ok","cancel"],"defaultAction":"cancel","timeoutMs":30000}}
{"id":9,"result":{"action":"ok","remember":false}}
```

约定：

- `id` 只在 request / response 出现；事件没有 `id`。
- `method` 是点分小写，与服务器 `/api/` 契约无关。
- 未知方法与字段：方法报 `404`；多余字段忽略（向前兼容）。
- 单帧上限 1 MiB；超限直接断连并记日志。

### 4.2 方法（v1）

| 方法 | 参数 | 结果 | 说明 |
| --- | --- | --- | --- |
| `hello` | `clientProtocol`, `clientVersion` | `protocol`, `version`, `capabilities[]` | 首帧；版本不匹配即拒绝 |
| `metrics.get` | — | `{cpu,mem,disk}` | 采不到为 `null` |
| `version.get` | — | `{version,build}` | 关于页 |
| `settings.get` | — | `{baseUrl,tokenMasked}` | 不返回明文 token |
| `settings.set` | `{baseUrl}` | `{}` | 仅非敏感字段 |

后续扩展（本文不实施）：`sync.plan` / `sync.start` / `sync.cancel` / `job.list`。

### 4.3 事件

| 事件 | 数据 | 说明 |
| --- | --- | --- |
| `job.progress` | `{jobId,done,total,phase}` | 合并到 ≤10 Hz |
| `job.done` | `{jobId,ok,summary}` | 终止事件 |
| `prompt.dismissed` | `{id,reason}` | 多 UI 时通知其他窗口撤回 |
| `ui.toast` | `{level,text}` | 服务请求 UI 弹轻提示，无应答 |

### 4.4 错误码

沿用 HTTP 语义：`400` 参数错误、`401` 未授权、`404` 未知方法、`409` 状态冲突、`501` 未实现、`503` 暂不可用。

---

## 5. 传输与生命周期

### 5.1 进程内（`--embed`）

- `LocalDispatcher` 与 UI 在同一进程；`InProcessTransport` 直接把帧交给 dispatcher。
- 不建端口、不写发现文件、不生成 token 文件；`EMBED_ENDPOINT_PATH` 删除。
- **仍然走消息泵**：两侧各有接收循环。UI 发请求时不阻塞接收协程，否则服务反向 `ui.prompt` 会与 UI 的等待互相卡死。

### 5.2 loopback（`--service`）

- `127.0.0.1` 上随机端口，仅监听回环。
- 一个 WebSocket 端点承载全部请求、响应、事件与反向提示。
- 保留一个纯诊断用的 `GET /local/v1/health`（普通 JSON，不进协议），供人工与脚本探活；UI 不依赖它。
- 心跳 20 s，60 s 无响应判定断线。

### 5.3 发现与连接

| 平台 | 发现文件 | 说明 |
| --- | --- | --- |
| Windows | `%ProgramData%\Lolosia\local-service.json` | 服务写、用户可读；含 `port` / `token` / `pid` / `protocol` |
| Linux | `work/local-service.json` | 权限 `0600` |

- 每次服务启动轮换 token；文件写入用「临时文件 + 原子替换」。
- UI 每次连接前重读文件；端口或 token 变化即重连。
- 重连退避 1 s → 30 s；重连成功后先 `hello`，再按需 `job.list` 恢复界面状态。

### 5.4 启动模式

| 参数 | 本地服务 | 事件订阅 | UI |
| --- | --- | --- | --- |
| `--service` | 有（WS） | 有 | 无 |
| `--embed` | 有（进程内） | 有 | 有 |
| `ui` | 无 | 无 | 有（本地能力降级） |

单实例：服务启动时检查发现文件里的 `pid` 是否仍存活；存活则记录日志并退出，避免两个服务抢同一份配置。

---

## 6. 认证与安全

- 鉴权只用启动时随机生成的 token，`Authorization: Bearer <token>` 或 WS 首帧携带；不参与远程会话。
- token 不写日志、不进异常消息、不出现在 URL。
- 只绑定 `127.0.0.1`；不监听 `0.0.0.0`，不开放防火墙。
- v1 的方法全部无参数或只接受白名单字段；**不接受任意路径、命令、环境变量**。
- 请求体上限 1 MiB、单请求默认超时 15 s（长任务改用 job）。
- 未捕获异常在 dispatcher 内转成 `error` 帧，不允许把进程带走；`--embed` 下尤其重要。

---

## 7. 关键语义

### 7.1 Job

- `job.start` 立即返回 `jobId`，不占用请求通道。
- 进度事件合并发送；`job.done` 是终止事件，之后不再有该 job 的事件。
- `job.cancel` 幂等；不存在或已结束返回 `404`。
- 服务重启后 job 全部丢失；UI 以 `job.list` 为准刷新，不假设历史。

### 7.2 Prompt（服务向 UI 要答案）

- 服务发 `ui.prompt`，UI 必须回一个 `response` 帧。
- 默认 30 s 超时；超时按 `action: "timeout"` 处理，业务侧默认取**拒绝**。
- 没有 UI 连接时立即返回 `action: "unavailable"`，不等待。
- 多 UI：第一个应答生效，其余收到 `prompt.dismissed`。
- Prompt 只是交互，不是授权：服务在真正执行前仍自行校验参数与白名单。

### 7.3 事件与通知

- UI 在线：事件直接转为页面状态或系统通知。
- UI 不在线：需要保留的通知继续写 `%ProgramData%\Lolosia\event-bus-events.jsonl`，UI 启动后按游标补读；在线时不写 spool，避免双份。
- Toast 由 UI 展现；服务进程（session 0）不直接弹通知。

### 7.4 背压与慢客户端

- 每个连接一个发送队列；队列超过阈值直接断开，不无限缓存。
- 进度类事件允许丢弃旧帧，只保留最新值；终止事件与 prompt 不允许丢。

---

## 8. 数据归属

| 文件 | 拥有者 | 内容 | 读写方 |
| --- | --- | --- | --- |
| `%ProgramData%\Lolosia\client-settings.json`（Linux：`work/client-settings.json`） | UI | 远程 `baseUrl`、登录 token | UI 写；服务只读（事件订阅用） |
| `%ProgramData%\Lolosia\local-service.json` | 服务 | 端口、token、pid、协议版本 | 服务写；UI 读 |
| `%ProgramData%\Lolosia\event-bus-events.jsonl` | 服务 | 离线期间的推送积压 | 服务追加；UI 读取并推进游标 |
| `work/local/service-settings.json` | 服务 | 同步目录、方向、排除项等本地配置 | 仅服务读写，UI 经 `settings.*` 访问 |

写入一律「临时文件 + 原子替换」；token 与口令不写日志。

---

## 9. 实施阶段

### P0：抽模块，先不动传输

1. 新建 `:modules:local`（mingwX64 / linuxArm64 / linuxX64），迁入指标采集。
2. 实现 `LocalDispatcher` + `hello` / `metrics.get` / `version.get`。
3. `:modules:client` 增加 `LocalClient` 与进程内传输；`--embed` 改走进程内，删除 `EMBED_ENDPOINT_PATH`。
4. 验收：`--embed` 能显示本地指标；无发现文件、无监听端口；三目标编译通过。

### P1：loopback WS

1. `:modules:app-desktop` 增加 WS 适配器；`--service` 改用它。
2. 删除旧 `LocalService.kt` 的 Ktor routing 与手写信封；删除 `CoreLocalServiceApi` 的 GET 实现。
3. 验收：UI 与服务分进程时指标正常；杀掉服务后 UI 显示离线；重启后自动恢复。

### P2：设置与作业

1. `settings.get/set` 落地，服务拥有本地配置。
2. `JobManager` + `job.list` / `job.cancel`；用一次模拟任务验证进度事件与取消。
3. 验收：进度不阻塞 UI；断线重连后 `job.list` 能恢复状态。

### P3：Prompt 与通知

1. `ui.prompt` 反向请求、超时、多 UI 撤回。
2. `ui.toast` 事件接到现有通知层；在线时不写 spool。
3. 验收：服务发起确认时 UI 弹窗；超时按拒绝处理；关掉 UI 后服务不挂起。

### P4：同步能力（后续）

按白名单逐步开放 `sync.*`，每次新增方法都要有参数校验、取消语义与验收用例。本文不展开。

---

## 10. 迁移与删除清单

| 动作 | 对象 |
| --- | --- |
| 删除 | `:modules:server` 的 `localservice/LocalService.kt`（路由与手写信封） |
| 删除 | `:modules:client` 的 `CoreLocalServiceApi`（GET 实现） |
| 删除 | `Main.kt` 的 `EMBED_ENDPOINT_PATH` 与 `work/local-service.embed.json` |
| 迁移 | `SystemMetricsReader` → `:modules:local` |
| 保留 | `LocalServiceApi` 接口（`:modules:ui`）与 `LocalServiceDtos`（`:protocol`） |
| 保留 | `event-bus-events.jsonl` 作为离线兜底 |

---

## 11. 不做的事

- 不复用服务器 `@Controller` / KSP 路由：参数绑定与应答都绑在 `ApplicationCall` 上，为本地服务做跨传输抽象不划算。
- 不伪造 `ApplicationCall`。
- 不在本地服务引入数据库、RBAC、会话。
- 不做 HTTP 长轮询 / SSE + 回调端点：一套 WS 已经够用，多一套就多一份状态。
- 不让 UI 直接调用服务内部函数：必须经过 dispatcher，否则 embed 与分进程的行为会分叉。

---

## 12. 风险

| 风险 | 说明 | 处理 |
| --- | --- | --- |
| 单进程死锁 | UI 等待请求返回时，服务反向 prompt 需要 UI 应答 | 两侧消息泵，dispatcher 不阻塞接收协程 |
| 单进程崩溃 | 服务未捕获异常会连 UI 一起退出 | dispatcher 全量捕获并转错误帧 |
| 协议漂移 | 两种传输行为不一致 | 帧编解码与 dispatcher 只有一份；传输只搬字节 |
| WS 平台支持 | Ktor 服务端 WebSocket 在 native 上需确认 | P1 先做最小回环冒烟；不通则退回 HTTP + SSE + 显式 reply 端点，dispatcher 不变 |
| 发现文件残留 | 服务异常退出留下旧端口 / token | 连接失败即重读文件；服务启动时按 pid 判活 |
| 通知重复 | 在线事件与 spool 同时存在 | 在线只推事件，不写 spool；离线才落盘 |
