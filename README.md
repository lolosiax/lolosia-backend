# lolosia-backend

洛洛希雅（Lolosia）的小网站后端：**Kotlin/Native 单文件服务端** —— 前端产物、SQL 脚本与默认配置都嵌在可执行文件里，部署时只有一个二进制。

- 技术栈：**Kotlin 2.4.20（KMP）+ Ktor 3.4.2（CIO）+ Koin 4.2.2 + sqlx4k + MariaDB**
- 目标平台：**`mingwX64`**（Windows 本地开发）、**`linuxArm64`**（开发板）、**`linuxX64`**（x64 云主机）；没有 JVM 目标
- 自研件：注解式路由（KSP 代码生成）、`classpath.zip` 资源中间层、多级配置、bcrypt、图形验证码、WebDAV、在线文件管理器、RBAC

> 代码风格与硬性约定见 [AGENTS.md](AGENTS.md)；构建 / 部署 / 排障见 [doc/development-notes.md](doc/development-notes.md)；
> 第三方组件与许可声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## 特性

**路由与依赖注入**

- `@Controller("/api") object Xxx` + `@GET/@POST/@PUT/@DELETE/@PATCH`，KSP 编译期生成注册，运行期无反射
- `@Injectable` 让路由形参按类型自动注入；`@Service` 自动生成 Koin 绑定（构造器注入）
- `@Path` / `@Query` / `@Header` / `@Form` / `@FilePart` 省略名称时取形参名；无法绑定的形参编译期报错
- 自研 multipart 解析（Ktor 在 Native 上没有 `receiveMultipart()`）

**请求与鉴权**

- 一次请求一个 `ctx`：`ctx.session` / `ctx.user` / `ctx.db` / `ctx.transaction { }` / `ctx.hasPermission("a.b.c")`
- 统一响应信封 `{code,msg,data}`，由 `EnvelopeInterceptor` 按响应 MIME 包装（`text/plain`、文件流、SSE 原样放行）
- 会话鉴权 `Authorization: Bearer <sessionId>`；接口级授权用 `@Permission("a.b.c")`
- 图形验证码（点阵算式 + 自绘 PNG，不依赖系统字体）

**资源与配置**

- `classpath.zip` 中间层：各源集 `resources/**` 就是 classpath 根，构建期嵌入可执行文件
- 多级配置覆盖：数据类默认值 < 内嵌 JSON < 磁盘配置 < 环境变量 < 命令行端口
- 静态站 + SPA：index、`ETag` / 304 协商缓存、history 路由回退

**文件与媒体**

- WebDAV：把 `webdav.root` 挂到 `/dav`，HTTP Basic 鉴权，覆盖 PROPFIND / GET(含 Range) / PUT / COPY / MOVE / LOCK 等
- 在线文件管理器：目录树、上传、复制 / 移动 / 改名 / 删除、三档 WebP 缩略图、签名直链
- 媒体索引与标签、足迹聚类、番组订阅、仪表盘（设计见 [doc/](doc/)）

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 / 构建 | Kotlin 2.4.20（Multiplatform）+ Gradle 9.5.1（Wrapper） |
| Web 框架 | Ktor 3.4.2（CIO 引擎，`embeddedServer`） |
| 序列化 | kotlinx.serialization 1.11.0（ContentNegotiation + JSON） |
| 路由 | 自研插件：[router-ksp](modules/router-ksp)（KSP 处理器）+ [util/router](modules/server/src/commonMain/kotlin/moe/lolosia/web/util/router)（运行时） |
| DI | Koin 4.2.2（容器实例显式传递，不用全局容器） |
| 数据访问 | sqlx4k 1.14.0（native 侧是预编译 Rust 静态库，无需 Rust 工具链） |
| 数据库 | MariaDB / MySQL |
| 时间 | kotlinx-datetime 0.8.0 + `kotlin.time.Instant`（写库一律 UTC） |
| IO / 压缩 | okio 3.9.1；`classpath.zip` 默认 DEFLATED（读取端 `platform.zlib` inflate，STORED 也支持） |
| 日志 | 自研（`Log.of("tag")`，级别 + 时间戳 + 异常 cause 链） |
| 密码 | 自研 bcrypt（`$2a$`，cost 10） |
| 图像 | 内嵌 libpng / libjpeg-turbo / libwebp / giflib（cinterop 静态链接） |

## 项目结构

```
├── buildSrc/                     # 自定义 Gradle 插件 moe.lolosia
│   └── src/main/kotlin/moe/lolosia/gradle/
│       ├── task/                 # ViteJar / GenerateConstants / Deploy / GitArchive
│       ├── extension/            # lolosia { viteJar / classpath / deploy / docker } 配置
│       └── NativeClasspath.kt    # classpath.zip 合并 + windres/objcopy 嵌入 + 链接参数
├── modules/
│   ├── protocol/                 # 跨端 DTO / EventSource 契约（无平台依赖）
│   ├── core/                     # classpath / fs / log / time / platform / json / 出站 HTTP
│   ├── codecs/                   # 图像编解码、缩略图、EXIF、msgpack 通道（cinterop）
│   ├── local/                    # 本地服务：dispatcher / 传输 / 系统指标（不含 Ktor server）
│   ├── router-ksp/               # 自研路由的 KSP 处理器（JVM 件）
│   ├── server/                   # Ktor 服务端：controller / service / database / settings
│   ├── client/                   # UI 客户端：mvvm / RmlUi 宿主（nativeCpp）/ 事件订阅
│   ├── app-desktop/              # 桌面唯一入口 Main.kt + 三个可执行文件
│   └── app-android/              # Android 壳（独立 Gradle 构建，SDLActivity + JNI 宿主）
├── thirdparty/                   # 第三方原生库独立构建：manifest + .def + 构建/发布脚本（产物不入库）
├── build/thirdparty/out/         # 【生成物，不入库】下载的头文件 + 各目标 .a / .so
├── classpath/mingw/app.manifest  # 应用清单（RT_MANIFEST）：把进程代码页切成 UTF-8，否则中文文件名会被 okio 丢弃
├── sql/                          # 建表脚本 + view_user_role 视图（导入顺序见下）
├── doc/                          # 设计文档与排障笔记
├── lolosia-backend-native.service # systemd 单元模板（部署插件据此改写 ExecStart）
└── platforms.json                # 【本地文件，不入库】前端工程路径 + 部署服务器列表
```

> 代码组织（Java 风格分包）：一个 `.kt` 只放一个顶层 **public** 类型（嵌套类随宿主保留），`private` 顶层声明（如日志器）就近放置；
> 与类型无关的散装 public 函数/常量放同包的 `package.kt`；**扩展外部库类型**的函数按库分域
> （`util/ktor`、`util/koin`、`util/json`，域内再按 `<类型名>Ext.kt` 分文件），扩展自研类型的函数与类型同域
> （`util/classpath/ClasspathExt.kt`）；控制器专属的 DTO 作为该 `object` 的内部类；
> **中间件独占顶级包 `interceptor/`**，顺序由 `@Interceptor(order)` 声明、注册表由 KSP 生成。

## 快速开始

### 环境要求

- JDK 21（Gradle 与 KSP 处理器运行用；产物本身不需要 JVM）
- MariaDB / MySQL
- 首次构建会自动下载 Kotlin/Native 工具链到 `~/.konan`（约 2.1GB）
- 构建 linux 目标且宿主是 Windows 时：WSL2 + Ubuntu 24.04（提供 `aarch64-linux-gnu-objcopy` / `x86_64-linux-gnu-objcopy`；编译与链接仍在 Windows 上跑）
- （可选）Node + vite 前端工程；`platforms.json` 配 `webPath` 后由 `viteJar` 构建并并入

### 1. 建库导表

```sql
CREATE DATABASE `lolosia-backend` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

`sql/` 下没有外键依赖，脚本顺序随意，只有视图 `view_user_role.sql` 必须最后导入：

```powershell
# 表
Get-ChildItem sql\*.sql |
    Where-Object Name -notin @('view_user_role.sql', 'migrate_rbac_groups.sql') |
    ForEach-Object { & mysql -u root -p -D lolosia-backend -e "source $($_.FullName)" }

# 视图（依赖前面的表）
& mysql -u root -p -D lolosia-backend -e "source $(Resolve-Path sql\view_user_role.sql)"
```

`sql/migrate_rbac_groups.sql` 只用于已有库升级组模型，全新库不要导入。

### 2. 本地配置（不入库）

- `platforms.json`（根目录，已 gitignore）：`webPath` 指向前端工程目录（`viteJar` 据此构建前端）、`deploy.servers` 描述部署目标。**缺了它 `viteJar` / `generateConstants` 不会注册**。
- 数据库等运行时配置不必改代码：写工作目录下的 `application.json`（见「配置」一节），或用环境变量。

### 3. 构建与启动（本机 mingwX64）

```powershell
gradlew runDebug                                                 # 本机调试启动（工作目录 backend/，能读到磁盘 application.json）
gradlew :modules:app-desktop:linkReleaseExecutableMingwX64       # release 可执行文件
modules\app-desktop\build\bin\mingwX64\releaseExecutable\lolosia-backend.exe 7129
```

服务默认监听 `0.0.0.0:7129`，静态首页在 `http://127.0.0.1:7129/home/`。冒烟：

```powershell
# ⚠️ 返回值**按响应 MIME**包信封：JSON 接口 → {"code":200,"msg":"success","data":…}；裸 String 是 text/plain，原样输出
curl.exe -s -o "$env:TEMP\home.html" -w "home=%{http_code}`n" http://127.0.0.1:7129/home/   # 静态首页

# 注册（免鉴权）→ 登录拿会话 token
curl.exe -s -X POST http://127.0.0.1:7129/api/register -H "Content-Type: application/json" -d '{"userName":"alice","password":"123456","email":"alice@example.com"}'
$tk = (curl.exe -s -X POST http://127.0.0.1:7129/api/login -H "Content-Type: application/json" -d '{"userName":"alice","password":"123456"}' | ConvertFrom-Json).data.Authorization

# /api/** 默认要鉴权（Authorization: Bearer <sessionId>），无 token → 401 + {code:401,…}
curl.exe -s -o "$env:TEMP\a.json" -w "no-token=%{http_code}`n" -X POST http://127.0.0.1:7129/api/role/list
curl.exe -s -X POST http://127.0.0.1:7129/api/role/list -H "Authorization: Bearer $tk"
curl.exe -s -X POST http://127.0.0.1:7129/api/user/myInfo -H "Authorization: Bearer $tk"
curl.exe -s -X POST http://127.0.0.1:7129/api/logout -H "Authorization: Bearer $tk"

# 图形验证码 → verify（一次性消费）
$cap = curl.exe -s -X POST http://127.0.0.1:7129/api/captcha    # {"code":200,"msg":"success","data":{"image":"<base64 png>","id":"<token>"}}
curl.exe -s -X POST http://127.0.0.1:7129/api/verify -H "Content-Type: application/json" -d '{"id":"<id>","code":"<算式结果>"}'   # data.message=success
```

## 构建任务

| 任务 | 说明 |
| --- | --- |
| `gradlew runDebug` / `gradlew runRelease` | 按宿主平台链接并启动；release 先跑 vite |
| `gradlew buildBinaries` | 桌面三目标汇总到 `build/bin/`；环境具备时顺带构建 Android APK |
| `gradlew :modules:app-desktop:linkReleaseExecutable<目标>` | 单一目标：`MingwX64` / `LinuxArm64` / `LinuxX64` |
| `gradlew :modules:app-desktop:viteJar` | 构建 vite 前端工程（路径取自 `platforms.json` 的 `webPath`） |
| `gradlew :modules:app-desktop:generateConstants` | 依 vite 平台生成 `VITE_DEFAULT` 常量（值 = `contextPath`，避免把 `/home` 写死） |
| `gradlew :modules:app-desktop:classpathZip<目标>` | 把各源集 `resources` + vite 产物合并成 `classpath.zip`（link 任务自动依赖） |
| `gradlew androidNative` | 用 NDK 构建 x86_64 + arm64-v8a 原生库（`-PandroidAbis=x86_64` 可只建一份） |
| `gradlew androidApp` | 构建 Android debug APK（独立下游构建） |
| `gradlew deploy<服务器名>` / `deploy` | 按 `platforms.json` 部署（`deploy` 指向 `deploy.active` 那台）；`abi` 决定产出原生二进制，`mode` 选 systemd / docker |
| `gradlew :modules:app-desktop:gitArchive` | 按 vite 平台导出 git 归档 |
| `gradlew -p thirdparty thirdpartySync` | 拉取（或本地回退）第三方原生库工件 |

## 写一个控制器

```kotlin
@Controller("/api")
object ExampleController {

    /** 仅本控制器使用的 DTO：作为内部类，避免散落一堆单行文件 */
    @Serializable
    data class EchoReq(val name: String, val times: Int = 1)

    @GET("/example/hello")
    fun hello(): String = "hello"

    /** 形参类型标了 @Injectable → 无需注解，自动注入（Context / AppSettings / Database / Service / Repository 均可用） */
    @GET("/example/whoami")
    fun whoami(ctx: Context): String = "uri=${ctx.call.request.local.uri}"

    /** @Path / @Query 省略名称时取形参名（id → {id}，verbose → ?verbose=） */
    @GET("/user/get/{id}")
    fun getUser(@Path id: String, @Query verbose: Boolean?): RouteInfo = …

    @POST("/echo")
    fun echo(@Body req: EchoReq): EchoResp = …

    /** multipart：普通字段 + 文件 */
    @POST("/upload")
    fun upload(@Form("note") note: String?, @FilePart("file") file: UploadedFile): UploadInfo = …
}
```

约定：

- `@Controller` 只能标在 `object` 上；返回值由生成代码 `call.respond(...)` 原样写出，信封交给 `EnvelopeInterceptor`（返回裸 `String` 会按 `text/plain` 发出、**不会被包**，要信封就返回业务对象或 `dto.RawJson`）
- 控制器专属的 DTO 声明为该 `object` 的内部类（KSP 生成的全限定名如 `…Controller.EchoReq`）；跨控制器共用时才独立成文件（或建 `dto` 包）
- 无法绑定的形参会**编译期报错**并给出提示：要么加参数注解，要么给类型标 `@Injectable`
- 支持的参数类型只有 `String / Int / Long / Double / Boolean / Uuid / 枚举`（不做嵌套泛型绑定，超范围请手写路由）

服务层约定：

- 请求域服务方法**首参一律 `ctx: Context`**；DB 句柄与事务都从它取（`ctx.db` / `ctx.transaction { tx -> … }`），服务**不持有连接池**
- 服务层**不要用 `ctx.call`**：需要客户端 IP / UA / 请求头时由控制器抽成普通参数传下去
- 审计列的操作者（`created_by` / `updated_by`）**显式传 `operatorId`**，不从 `ctx.userId` 隐式取 —— 注册链路没有登录态

## 接口与鉴权

接口都在 `/api` 下，按控制器分域：

| 域 | 控制器 | 内容 |
| --- | --- | --- |
| 登录 | `LoginController` | `/login` `/logout` `/register` `/captcha` `/verify` |
| 系统 | `UserController` `RoleController` `UserRoleController` `MenuController` | 用户 / 角色 / 绑定 / 菜单 |
| 权限 | `PermissionController` | 组规则、用户直授、当前用户生效权限、`@Permission` 节点目录 |
| 设置 | `SettingController` | 运行期配置读写 |
| 文件 | `OssController` `FileController` | OSS 上传取件、在线文件管理器（浏览 / 写操作 / 缩略图 / 直链） |
| 媒体 | `MediaController` `FootprintController` `AnimeController` | 媒体索引与标签、足迹地图、番组订阅 |
| 其它 | `DashboardController` `SseController` `ClasspathController` | 仪表盘、SSE 事件流、内嵌资源探针（运维排障用） |

`/api/**` 默认都要鉴权（`Authorization: Bearer <sessionId>`，失败回 `401` + 信封），接口级授权由 `@Permission` 判定（失败 `403`）。免鉴权白名单：

- 精确路径：`/api/login`、`/api/captcha`、`/api/verify`、`/api/register`、`/api/user/updatePassword`、`/api/user/avatar`、`/api/sse/registry`
- 前缀：`/api/oss/show/`、`/api/oss/get/`、`/api/user/avatar/get/`、`/api/files/thumb`、`/api/files/raw`

前缀放行的这几个是给 `<img src>` 用的（浏览器原生标签带不了 `Authorization`）：`files/thumb` 与 `files/raw` 由控制器**自己校验 URL 签名**（`exp` + `sig`），拿不到签名一律 `403`；`/api/sse/registry` 放行是因为 `EventSource` 也带不了自定义请求头，登录态靠建流之后的事件补绑。`/api/logout` **不在白名单里**，需带有效 token。

WebDAV 挂在 `/dav`（**不在 `/api` 下**）：不受会话鉴权与响应信封影响，鉴权走 HTTP Basic。

## 配置

配置只有一个入口：[AppSettings](modules/server/src/commonMain/kotlin/moe/lolosia/web/settings/AppSettings.kt)（`@Serializable` 数据类）。
优先级由低到高，**按字段深合并**：

1. 数据类里的默认值
2. classpath 内嵌的 [application.json](modules/server/src/commonMain/resources/application.json)（随二进制分发）
3. 磁盘 `application.json`（相对启动目录；systemd 下即 `/srv/lolosia-backend/`，可用 `KTOR_CONFIG` 指定其它路径）
4. 环境变量：`KTOR_HOST` / `KTOR_PORT` / `KTOR_LOG_LEVEL` / `KTOR_DB_URL` / `KTOR_DB_USERNAME` / `KTOR_DB_PASSWORD` / `KTOR_WORK_DIR` / `KTOR_LLM_API_KEY` / `KTOR_ARTIFACT_*`
5. 命令行第一个参数（仅端口，如 `lolosia-backend.exe 8080`）

段落：`server` / `datasource` / `ddns` / `webdav` / `files` / `dashboard` / `anime` / `artifact` / `llm`，另有 `workDir` 与 `logLevel`。示例（省略了默认关闭的 `ddns` / `artifact` / `llm`）：

```json
{
  "server": { "host": "0.0.0.0", "port": 7129 },
  "datasource": {
    "url": "mysql://127.0.0.1:3306/lolosia-backend",
    "username": "root",
    "password": "123456",
    "maxConnections": 10
  },
  "webdav": {
    "enabled": true,
    "path": "/dav",
    "root": "/mnt/hdd1",
    "username": "lolosia",
    "password": "",
    "realm": "lolosia-webdav"
  },
  "files": {
    "root": "",
    "signKey": "",
    "signTtlSeconds": 900,
    "thumbnail": {
      "enabled": false,
      "sizes": [256, 512, 1024],
      "quality": 80,
      "scanIntervalSeconds": 600,
      "maxSourceBytes": 67108864
    }
  },
  "logLevel": "INFO"
}
```

> `webdav.password` 为空时**拒绝一切请求**（服务端不会退化到匿名），启动日志会打一条 WARN。
> WebDAV 的鉴权是 **HTTP Basic**：Windows 内置客户端只在 HTTPS 上才肯发 Basic，所以对外要经 TLS 反代（如 Caddy）；
> 局域网里直接用 `http://<主机>:<端口>/dav` 即可（手机等第三方客户端不受这条限制）。

> 在线文件管理器：`files.root` 留空回退 `webdav.root`；`files.signKey` 留空则本次启动随机生成（**重启后旧直链全部失效**，要跨重启就配一个固定随机串）；
> `files.thumbnail.enabled` **开发机与线上机只能开一台** —— 两边同时写同一份缓存会互相覆盖，也会把机械盘两头都拖慢。

> 其它环境变量：`KTOR_CLASSPATH_DIR` 指向磁盘资源目录，开发期改前端/资源不必重编二进制。
> 加配置项 = 改 `AppSettings`（+ 必要时改 `envOverrides`），不要另起一套读取逻辑；磁盘 JSON 带 BOM 也能解析（解析失败会记 WARN 而不是静默忽略）。

## 数据层约定（sqlx4k）

- **实体**：`@Table("表名")` + `@Id`（库自增）或 `@Id(insert = true)`（应用给主键），列名自动按 snake_case 映射
- **仓库**：接口标 `@Repository` + `@Injectable`（后者必须，否则控制器形参注入报「无法绑定」），继承 `CrudRepository<T>`
  - 第一个参数必须是 `context: QueryExecutor`，方法必须 `suspend`，**每个方法都要带 `@Query`**（没有派生查询）
  - 方法名前缀白名单是精确匹配：`findAll` / `deleteAll` / `countAll`，或 `findAllBy*` / `findOneBy*` / `deleteBy*` / `countBy*` / `existsBy*` / `execute*`
  - **不支持 `IN (列表)`**（占位符在编译期固定），批量场景在服务层循环单条方法
  - MySQL 方言下**不要用生成的 `insert` / `update`**（会拼成多语句脚本，MariaDB 报 `1064`），写入用单语句 `@Query`
- **连接池懒建**：`Database` 构造只存配置、不连库；启动由后台协程周期重连（失败只记日志，不崩进程）。库未就绪时**读库的请求返回 `503 数据库暂不可用`**（静态资源、SSE 不受影响），库恢复后自动可用；别在启动路径上建池（`mySQL(...)` 会阻塞到超时）
- **建池前鉴权预检**：`Database.connect()` 先跑 `MySqlAuthProbe`（自写裸 TCP 握手）——能连且登录成功（`Ok`）才建池；主机不可达（`Unreachable`，顺带省掉约 30s 阻塞）或鉴权失败 / 库不存在（`Denied`）直接判失败并交给后台重连；协议不认识 / 需 TLS（`Inconclusive`）才回退 sqlx4k
- **生成物位置**：实现类在目标源集（`moe.lolosia.web.database.generated`，KSP2 在 KMP 下没有 common 编译单元）。DI 绑定**不用手写**：`router-ksp` 扫 `@Repository` / `@Injectable` / `@Service` 生成绑定，签名在 commonMain 的 [GeneratedRegistries.kt](modules/server/src/commonMain/kotlin/moe/lolosia/web/generated/GeneratedRegistries.kt) 声明为 `expect`、生成物是 `actual`，[Main.kt](modules/app-desktop/src/commonMain/kotlin/moe/lolosia/web/Main.kt) 直接调用
- **时间列一律 `kotlin.time.Instant`**（业务用 `Clock.System.now()`），驱动按 UTC 往返；只有日志用本地时间展示

## 部署

1. `platforms.json` 里给目标服务器配 `abi`（`arm64` / `aarch64` → linuxArm64，`x64` / `amd64` / `x86_64` → linuxX64）与 `mode`
2. `gradlew deploy<服务器名>`（或 `deploy`）→ 上传可执行文件 → `mv old old.previous` 再替换（避免覆盖运行中的文件报 `ETXTBSY`）→ 交给 systemd / docker 拉起

**`mode: "systemd"`**：改写远端 `<名字>-native.service` 的 `ExecStart` 为可执行文件路径后重启服务。首次部署由插件生成单元文件；想自定义 `User=` / 资源限制，就在**服务器上**改，插件不会覆盖已有单元。
仓库模板 `lolosia-backend-native.service` 已带 `LimitNOFILE=524288`（**必须给**：不写会继承软限制 1024，缩略图 / 索引 / WebDAV 并发一高就 `EMFILE` 崩在 `accept`）；老机器需手工补，详见 [doc/lolosia-backend-notes.md](doc/lolosia-backend-notes.md) §4。

**`mode: "docker"`**：不构建镜像，产物以挂载方式注入基础镜像运行。

- 基础镜像配在 `build.gradle.kts` 的 `binary { dockerImage }`，加速源配在 `docker { image("ubuntu:24.04") { mirror } }`；国内直连 docker.io 拉不动，模板默认走华为云 SWR 的 docker.io 代理
- 挂载：`/srv/lolosia-backend` → `/app`（可执行文件、配置、`work/` 都在其中），另挂宿主 `/etc/localtime` `/etc/timezone` `/usr/share/zoneinfo`（基础镜像不带 tzdata，少挂一个日志就整片落在 UTC）
- 网络用 `--network host`：应用直接绑宿主端口，容器里的 `127.0.0.1` 就是宿主（库地址按这个配）
- 回滚：旧容器**先停**再改名成 `<名字>-old`，新版确认活着才删；失败会打印容器日志并删掉新容器

部署目录约定为 `/srv/lolosia-backend/`（磁盘 `application.json` / `application.yaml` 也放这里）。CIO 引擎不带 HTTPS 与压缩，这两件事交给前置 Nginx（WebDAV 的 Basic 鉴权另需一条 TLS 反代，见 `doc/lolosia-backend-notes.md`）。

## 延伸阅读

| 文档 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 代码风格与硬性约定（改代码前先读） |
| [doc/development-notes.md](doc/development-notes.md) | 构建 / 部署 / 冒烟 / 排障 / 环境依赖 |
| [doc/lolosia-backend-notes.md](doc/lolosia-backend-notes.md) | WebDAV、文件管理器、部署与网络实测 |
| [doc/rbac-design.md](doc/rbac-design.md) | RBAC：组 + 点分权限 + 用户直授 |
| [doc/media-index-design.md](doc/media-index-design.md) | 媒体索引：文件身份、标签、EXIF、评级与遮罩 |
| [doc/footprint-map-design.md](doc/footprint-map-design.md) | 足迹地图：聚类与区域 |
| [doc/anime-timetable-design.md](doc/anime-timetable-design.md) | 番组表：RSS 与 Bangumi 订阅 |
| [doc/local-service-design.md](doc/local-service-design.md) | 本地服务：dispatcher 与两种传输 |

## License

[MIT](LICENSE) © 洛洛希雅Lolosia
