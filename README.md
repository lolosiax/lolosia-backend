<div align="center">

<img src=".github/assets/logo.png" width="128" alt="洛洛希雅" />

# lolosia-backend

**洛洛希雅的午后梦乡 · 后端**

Kotlin/Native 单文件服务端 —— 前端产物、SQL 脚本与默认配置均内嵌于可执行文件，部署产物为单个二进制

[快速开始](#快速开始) · [特性](#特性) · [接口](#接口与鉴权) · [配置](#配置) · [部署](#部署) · [文档](#延伸阅读)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotlin/Native](https://img.shields.io/badge/Kotlin%2FNative-3%20targets-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/native-overview.html)
[![Ktor](https://img.shields.io/badge/Ktor-3.4.2-087CFA?logo=ktor&logoColor=white)](https://ktor.io)
[![Koin](https://img.shields.io/badge/Koin-4.2.2-F9A03C)](https://insert-koin.io)
[![sqlx4k](https://img.shields.io/badge/sqlx4k-1.14.0-4B5563)](https://github.com/smyrgeorge/sqlx4k)
[![MariaDB](https://img.shields.io/badge/MariaDB-MySQL-003545?logo=mariadb&logoColor=white)](https://mariadb.org)
[![License](https://img.shields.io/badge/License-MIT-3DA639)](LICENSE)

</div>

- **目标平台**：`mingwX64` 用于 Windows 本地开发，`linuxArm64` 用于开发板，`linuxX64` 用于 x64 云主机；不提供 JVM 目标
- **自研组件**：KSP 代码生成的注解式路由、`classpath.zip` 资源中间层、多级配置、bcrypt、图形验证码、WebDAV、在线文件管理器、RBAC

> 代码风格与硬性约定见 [AGENTS.md](AGENTS.md) · 构建、部署与排障见 [doc/development-notes.md](doc/development-notes.md) · 第三方组件与许可声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)

## 特性

**路由与依赖注入**

- `@Controller("/api") object Xxx` 配合 `@GET/@POST/@PUT/@DELETE/@PATCH`，由 KSP 在编译期生成注册，运行期不依赖反射
- `@Injectable` 使路由形参按类型自动注入；`@Service` 自动生成 Koin 构造器绑定
- `@Path` / `@Query` / `@Header` / `@Form` / `@FilePart` 省略名称时采用形参名；无法绑定的形参将在编译期报错
- multipart 请求由自研解析器处理，因为 Ktor 在 Native 上未提供 `receiveMultipart()`

**请求与鉴权**

- 每个请求对应一个 `ctx`，提供 `ctx.session` / `ctx.user` / `ctx.db` / `ctx.transaction { }` / `ctx.hasPermission("a.b.c")`
- 统一响应信封 `{code,msg,data}` 由 `EnvelopeInterceptor` 按响应 MIME 包装；`text/plain`、文件流与 SSE 保持原样
- 会话鉴权使用 `Authorization: Bearer <sessionId>`；接口级授权使用 `@Permission("a.b.c")`
- 图形验证码采用点阵算式与自绘 PNG，不依赖系统字体

**资源与配置**

- `classpath.zip` 中间层：各源集的 `resources/**` 即 classpath 根，构建期嵌入可执行文件
- 多级配置覆盖：数据类默认值 < 内嵌 JSON < 磁盘配置 < 环境变量 < 命令行端口
- 静态站与 SPA：index、`ETag` / 304 协商缓存、history 路由回退

**文件与媒体**

- WebDAV：将 `webdav.root` 挂载到 `/dav`，使用 HTTP Basic 鉴权，覆盖 PROPFIND、含 Range 的 GET、PUT、COPY、MOVE、LOCK 等操作
- 在线文件管理器：目录树、上传、复制、移动、改名、删除、三档 WebP 缩略图、签名直链
- 媒体索引与标签、足迹聚类、番组订阅、仪表盘，设计见 [doc/](doc/)

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 / 构建 | Kotlin 2.4.20 Multiplatform + Gradle 9.5.1 |
| Web 框架 | Ktor 3.4.2，CIO 引擎 + `embeddedServer` |
| 序列化 | kotlinx.serialization 1.11.0，ContentNegotiation + JSON |
| 路由 | 自研插件：[router-ksp](modules/router-ksp) 提供 KSP 处理器，[util/router](modules/server/src/commonMain/kotlin/moe/lolosia/web/util/router) 提供运行时 |
| DI | Koin 4.2.2，容器实例显式传递，不使用全局容器 |
| 数据访问 | sqlx4k 1.14.0，native 侧为预编译 Rust 静态库，无需 Rust 工具链 |
| 数据库 | MariaDB / MySQL |
| 时间 | kotlinx-datetime 0.8.0 + `kotlin.time.Instant`，写库统一使用 UTC |
| IO / 压缩 | okio 3.9.1；`classpath.zip` 默认 DEFLATED，读取端通过 `platform.zlib` inflate，亦支持 STORED |
| 日志 | 自研 `Log.of("tag")`，包含级别、时间戳与异常 cause 链 |
| 密码 | 自研 bcrypt，`$2a$` 版本，cost 10 |
| 图像 | 内嵌 libpng、libjpeg-turbo、libwebp、giflib，通过 cinterop 静态链接 |

## 项目结构

```
├── buildSrc/                     # 自定义 Gradle 插件 moe.lolosia
│   └── src/main/kotlin/moe/lolosia/gradle/
│       ├── task/                 # ViteJar / GenerateConstants / Deploy / GitArchive
│       ├── extension/            # lolosia { viteJar / classpath / deploy / docker } 配置
│       └── NativeClasspath.kt    # classpath.zip 合并 + windres/objcopy 嵌入 + 链接参数
├── modules/
│   ├── protocol/                 # 跨端 DTO / EventSource 契约，无平台依赖
│   ├── core/                     # classpath / fs / log / time / platform / json / 出站 HTTP
│   ├── codecs/                   # 图像编解码、缩略图、EXIF、msgpack 通道，通过 cinterop
│   ├── local/                    # 本地服务：dispatcher / 传输 / 系统指标，不含 Ktor server
│   ├── router-ksp/               # 自研路由的 KSP 处理器，JVM 件
│   ├── server/                   # Ktor 服务端：controller / service / database / settings
│   ├── client/                   # UI 客户端：mvvm / RmlUi 宿主 nativeCpp / 事件订阅
│   ├── app-desktop/              # 桌面唯一入口 Main.kt 与三个可执行文件
│   └── app-android/              # Android 壳：独立 Gradle 构建，SDLActivity + JNI 宿主
├── thirdparty/                   # 第三方原生库独立构建：manifest + .def + 构建/发布脚本，产物不入库
├── build/thirdparty/out/         # 【生成物，不入库】下载的头文件与各目标 .a / .so
├── classpath/mingw/app.manifest  # 应用清单 RT_MANIFEST：将进程代码页设为 UTF-8，否则 okio 会丢弃中文文件名
├── sql/                          # 建表脚本与 view_user_role 视图，导入顺序见下文
├── doc/                          # 设计文档与排障笔记
├── lolosia-backend-native.service # systemd 单元模板，部署插件据此改写 ExecStart
└── platforms.json                # 【本地文件，不入库】前端工程路径与部署服务器列表
```

> 代码组织采用 Java 风格分包：一个 `.kt` 只放一个顶层 **public** 类型，嵌套类随宿主保留；`private` 顶层声明如日志器可就近放置。
> 与类型无关的 public 函数与常量统一放在同包的 `package.kt`。扩展**外部库类型**的函数按库分域到 `util/ktor`、`util/koin`、`util/json`，
> 域内再按 `<类型名>Ext.kt` 分文件；扩展**自研类型**的函数与类型同域，如 `util/classpath/ClasspathExt.kt`。
> 控制器专属的 DTO 作为该 `object` 的内部类；**中间件独占顶级包 `interceptor/`**，顺序由 `@Interceptor(order)` 声明，注册表由 KSP 生成。

## 快速开始

### 环境要求

- JDK 21，供 Gradle 与 KSP 处理器运行使用；产物本身无需 JVM
- MariaDB / MySQL
- 首次构建将自动下载 Kotlin/Native 工具链至 `~/.konan`，约 2.1GB
- 在 Windows 上构建 linux 目标时需要 WSL2 + Ubuntu 24.04，用于提供 `aarch64-linux-gnu-objcopy` 与 `x86_64-linux-gnu-objcopy`；编译与链接仍在 Windows 上执行
- 可选：Node 与 vite 前端工程。在 `platforms.json` 中配置 `webPath` 后，由 `viteJar` 构建并合并

### 1. 建库导表

```sql
CREATE DATABASE `lolosia-backend` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

`sql/` 下无外键依赖，脚本可按任意顺序导入，仅视图 `view_user_role.sql` 必须最后导入：

```powershell
# 表
Get-ChildItem sql\*.sql |
    Where-Object Name -notin @('view_user_role.sql', 'migrate_rbac_groups.sql') |
    ForEach-Object { & mysql -u root -p -D lolosia-backend -e "source $($_.FullName)" }

# 视图依赖上述表，需最后导入
& mysql -u root -p -D lolosia-backend -e "source $(Resolve-Path sql\view_user_role.sql)"
```

`sql/migrate_rbac_groups.sql` 仅用于已有数据库升级组模型，全新数据库不应导入。

### 2. 本地配置

以下两个文件均为本地文件，不纳入版本控制：

- `platforms.json` 位于根目录，已被 gitignore 忽略。`webPath` 指向前端工程目录，`viteJar` 依据该路径构建前端；`deploy.servers` 描述部署目标。**缺少该文件时 `viteJar` / `generateConstants` 不会注册**
- `application.json` 位于工作目录，用于覆盖数据库等运行时配置，详见「配置」一节；也可使用环境变量

### 3. 构建与启动

```powershell
gradlew runDebug                                                 # 本机调试启动；工作目录为 backend/，可读取磁盘上的 application.json
gradlew :modules:app-desktop:linkReleaseExecutableMingwX64       # release 可执行文件
modules\app-desktop\build\bin\mingwX64\releaseExecutable\lolosia-backend.exe 7129
```

服务默认监听 `0.0.0.0:7129`，静态首页位于 `http://127.0.0.1:7129/home/`。冒烟验证：

```powershell
# ⚠️ 返回值**按响应 MIME** 封装信封：JSON 接口 → {"code":200,"msg":"success","data":…}；裸 String 为 text/plain，原样输出
curl.exe -s -o "$env:TEMP\home.html" -w "home=%{http_code}`n" http://127.0.0.1:7129/home/   # 静态首页

# 注册接口免鉴权 → 登录获取会话 token
curl.exe -s -X POST http://127.0.0.1:7129/api/register -H "Content-Type: application/json" -d '{"userName":"alice","password":"123456","email":"alice@example.com"}'
$tk = (curl.exe -s -X POST http://127.0.0.1:7129/api/login -H "Content-Type: application/json" -d '{"userName":"alice","password":"123456"}' | ConvertFrom-Json).data.Authorization

# /api/** 默认需要鉴权，请求携带 Authorization: Bearer <sessionId>；未携带 token 时返回 401 + {code:401,…}
curl.exe -s -o "$env:TEMP\a.json" -w "no-token=%{http_code}`n" -X POST http://127.0.0.1:7129/api/role/list
curl.exe -s -X POST http://127.0.0.1:7129/api/role/list -H "Authorization: Bearer $tk"
curl.exe -s -X POST http://127.0.0.1:7129/api/user/myInfo -H "Authorization: Bearer $tk"
curl.exe -s -X POST http://127.0.0.1:7129/api/logout -H "Authorization: Bearer $tk"

# 图形验证码 → verify，验证码一次性消费
$cap = curl.exe -s -X POST http://127.0.0.1:7129/api/captcha    # {"code":200,"msg":"success","data":{"image":"<base64 png>","id":"<token>"}}
curl.exe -s -X POST http://127.0.0.1:7129/api/verify -H "Content-Type: application/json" -d '{"id":"<id>","code":"<算式结果>"}'   # data.message=success
```

## 构建任务

| 任务 | 说明 |
| --- | --- |
| `gradlew runDebug` / `gradlew runRelease` | 按宿主平台链接并启动；release 先执行 vite 构建 |
| `gradlew buildBinaries` | 桌面三目标汇总到 `build/bin/`；环境具备时一并构建 Android APK |
| `gradlew :modules:app-desktop:linkReleaseExecutable<目标>` | 单一目标：`MingwX64` / `LinuxArm64` / `LinuxX64` |
| `gradlew :modules:app-desktop:viteJar` | 构建 vite 前端工程，路径取自 `platforms.json` 的 `webPath` |
| `gradlew :modules:app-desktop:generateConstants` | 依 vite 平台生成 `VITE_DEFAULT` 常量，值等于 `contextPath`，避免硬编码 `/home` |
| `gradlew :modules:app-desktop:classpathZip<目标>` | 将各源集 `resources` 与 vite 产物合并为 `classpath.zip`，link 任务自动依赖 |
| `gradlew androidNative` | 使用 NDK 构建 x86_64 与 arm64-v8a 原生库，`-PandroidAbis=x86_64` 可只构建一份 |
| `gradlew androidApp` | 构建 Android debug APK，属于独立下游构建 |
| `gradlew deploy<服务器名>` / `deploy` | 按 `platforms.json` 部署，`deploy` 指向 `deploy.active` 对应的服务器；`abi` 决定是否产出原生二进制，`mode` 选择 systemd 或 docker |
| `gradlew :modules:app-desktop:gitArchive` | 按 vite 平台导出 git 归档 |
| `gradlew -p thirdparty thirdpartySync` | 拉取第三方原生库工件，未配置时回退到本地 |

## 编写控制器

```kotlin
@Controller("/api")
object ExampleController {

    /** 仅本控制器使用的 DTO：作为内部类，避免产生大量单行文件 */
    @Serializable
    data class EchoReq(val name: String, val times: Int = 1)

    @GET("/example/hello")
    fun hello(): String = "hello"

    /** 形参类型标注 @Injectable 后无需额外注解即可自动注入；Context / AppSettings / Database / Service / Repository 均可用 */
    @GET("/example/whoami")
    fun whoami(ctx: Context): String = "uri=${ctx.call.request.local.uri}"

    /** @Path / @Query 省略名称时采用形参名：id → {id}，verbose → ?verbose= */
    @GET("/user/get/{id}")
    fun getUser(@Path id: String, @Query verbose: Boolean?): RouteInfo = …

    @POST("/echo")
    fun echo(@Body req: EchoReq): EchoResp = …

    /** multipart：普通字段与文件 */
    @POST("/upload")
    fun upload(@Form("note") note: String?, @FilePart("file") file: UploadedFile): UploadInfo = …
}
```

约定：

- `@Controller` 只能标注在 `object` 上；返回值由生成代码通过 `call.respond(...)` 原样写出，信封交由 `EnvelopeInterceptor` 处理。直接返回 `String` 时会以 `text/plain` 发出且**不套用信封**；如需信封，应返回业务对象或 `dto.RawJson`
- 控制器专属的 DTO 声明为该 `object` 的内部类，KSP 生成的全限定名如 `…Controller.EchoReq`；仅当跨控制器共用时才独立成文件，或建立 `dto` 包
- 无法绑定的形参将**在编译期报错**并给出提示：需添加参数注解，或为类型标注 `@Injectable`
- 支持的参数类型仅有 `String / Int / Long / Double / Boolean / Uuid / 枚举`，不支持嵌套泛型绑定；超出范围时应手写路由

服务层约定：

- 请求域服务方法**首参一律为 `ctx: Context`**；数据库句柄与事务均从该对象获取，即 `ctx.db` / `ctx.transaction { tx -> … }`，服务**不持有连接池**
- 服务层**不应使用 `ctx.call`**；需要客户端 IP、UA 或请求头时，由控制器提取为普通参数传入
- 审计列 `created_by` / `updated_by` 的操作者**需显式传入 `operatorId`**，不应从 `ctx.userId` 隐式获取，因为注册链路没有登录态

## 接口与鉴权

接口均位于 `/api` 下，按控制器划分域：

| 域 | 控制器 | 内容 |
| --- | --- | --- |
| 登录 | `LoginController` | `/login` `/logout` `/register` `/captcha` `/verify` |
| 系统 | `UserController` `RoleController` `UserRoleController` `MenuController` | 用户、角色、绑定、菜单 |
| 权限 | `PermissionController` | 组规则、用户直授、当前用户生效权限、`@Permission` 节点目录 |
| 设置 | `SettingController` | 运行期配置读写 |
| 文件 | `OssController` `FileController` | OSS 上传取件、在线文件管理器的浏览 / 写操作 / 缩略图 / 直链 |
| 媒体 | `MediaController` `FootprintController` `AnimeController` | 媒体索引与标签、足迹地图、番组订阅 |
| 其它 | `DashboardController` `SseController` `ClasspathController` | 仪表盘、SSE 事件流、内嵌资源探针，用于运维排障 |

`/api/**` 默认均需鉴权：请求需携带 `Authorization: Bearer <sessionId>`，失败时返回 `401` 与信封；接口级授权由 `@Permission` 判定，失败时返回 `403`。免鉴权白名单如下：

- 精确路径：`/api/login`、`/api/captcha`、`/api/verify`、`/api/register`、`/api/user/updatePassword`、`/api/user/avatar`、`/api/sse/registry`
- 前缀：`/api/oss/show/`、`/api/oss/get/`、`/api/user/avatar/get/`、`/api/files/thumb`、`/api/files/raw`

前缀放行的接口用于 `<img src>`，因为浏览器原生标签无法携带 `Authorization`：

- `files/thumb` 与 `files/raw` 由控制器**自行校验 URL 签名**，签名由 `exp` 与 `sig` 组成，缺失签名一律返回 `403`
- `/api/sse/registry` 放行原因是 `EventSource` 同样无法携带自定义请求头，登录态通过建流之后的事件补绑

`/api/logout` **不在白名单内**，需携带有效 token。

WebDAV 挂载于 `/dav`，**不在 `/api` 下**：不受会话鉴权与响应信封影响，鉴权使用 HTTP Basic。

## 配置

配置只有一个入口，即 `@Serializable` 数据类 [AppSettings](modules/server/src/commonMain/kotlin/moe/lolosia/web/settings/AppSettings.kt)。
优先级由低到高，**按字段深合并**：

1. 数据类中的默认值
2. classpath 内嵌的 [application.json](modules/server/src/commonMain/resources/application.json)，随二进制分发
3. 磁盘上的 `application.json`，路径相对启动目录；使用 systemd 时即 `/srv/lolosia-backend/`，可通过 `KTOR_CONFIG` 指定其它路径
4. 环境变量：`KTOR_HOST` / `KTOR_PORT` / `KTOR_LOG_LEVEL` / `KTOR_DB_URL` / `KTOR_DB_USERNAME` / `KTOR_DB_PASSWORD` / `KTOR_WORK_DIR` / `KTOR_LLM_API_KEY` / `KTOR_ARTIFACT_*`
5. 命令行第一个参数，仅支持端口，如 `lolosia-backend.exe 8080`

段落：`server` / `datasource` / `ddns` / `webdav` / `files` / `dashboard` / `anime` / `artifact` / `llm`，另有 `workDir` 与 `logLevel`。示例省略了默认关闭的 `ddns` / `artifact` / `llm`：

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

> `webdav.password` 为空时**拒绝所有请求**，服务端不会退化为匿名访问，启动日志会输出一条 WARN。
> WebDAV 的鉴权方式为 **HTTP Basic**：Windows 内置客户端仅在 HTTPS 下发送 Basic 凭据，因此对外服务需经 TLS 反向代理，如 Caddy；
> 局域网内可直接使用 `http://<主机>:<端口>/dav`，手机等第三方客户端不受此限制。

> 在线文件管理器：`files.root` 留空时回退到 `webdav.root`；`files.signKey` 留空时在本次启动中随机生成，**重启后旧直链全部失效**，如需跨重启保持有效，应配置固定的随机字符串。
> `files.thumbnail.enabled` **在开发机与线上机之间只能开启一台** —— 两端同时写入同一份缓存会相互覆盖，并会拖慢机械盘两端的读写。

> 其它环境变量：`KTOR_CLASSPATH_DIR` 指向磁盘资源目录，开发期间修改前端与资源无需重新编译二进制。
> 新增配置项应修改 `AppSettings`，必要时修改 `envOverrides`，不应另建读取逻辑；磁盘 JSON 带 BOM 也可解析，解析失败会记录 WARN，不会静默忽略。

## 数据层约定

数据访问统一使用 sqlx4k：

- **实体**：`@Table("表名")` + `@Id` 表示数据库自增，或 `@Id(insert = true)` 表示由应用提供主键；列名自动按 snake_case 映射
- **仓库**：接口需标注 `@Repository` + `@Injectable`，后者必须标注，否则控制器形参注入会报「无法绑定」；接口继承 `CrudRepository<T>`
  - 第一个参数必须为 `context: QueryExecutor`，方法必须为 `suspend`，**每个方法都必须携带 `@Query`**，不提供派生查询
  - 方法名前缀白名单为精确匹配：`findAll` / `deleteAll` / `countAll`，或 `findAllBy*` / `findOneBy*` / `deleteBy*` / `countBy*` / `existsBy*` / `execute*`
  - **不支持 `IN` 列表**，因为占位符在编译期固定；批量场景应在服务层逐条调用单条方法
  - MySQL 方言下**不要使用生成的 `insert` / `update`**，生成器将拼接为多语句脚本，MariaDB 返回 `1064`；写入应使用单语句 `@Query`
- **连接池懒建**：`Database` 构造只保存配置、不连接数据库；启动后由后台协程周期性重连，失败仅记录日志，不会导致进程崩溃。数据库未就绪时**读库请求返回 `503 数据库暂不可用`**，静态资源与 SSE 不受影响，数据库恢复后自动可用。不要在启动路径上创建连接池，`mySQL(...)` 会阻塞直至超时
- **建池前鉴权预检**：`Database.connect()` 先执行 `MySqlAuthProbe`，即一次自写的裸 TCP 握手。按握手结果分为四种：
  - `Ok`：连接成功且登录通过，交由 sqlx4k 建池
  - `Unreachable`：主机不可达，直接判定失败，同时省去约 30 秒的阻塞
  - `Denied`：鉴权失败或数据库不存在，直接判定失败并交由后台重连
  - `Inconclusive`：协议无法识别或需要 TLS，回退到 sqlx4k
- **生成物位置**：实现类位于目标源集，包名为 `moe.lolosia.web.database.generated`；KSP2 在 KMP 下没有 common 编译单元。DI 绑定**无需手写**：`router-ksp` 扫描 `@Repository` / `@Injectable` / `@Service` 生成绑定，签名在 commonMain 的 [GeneratedRegistries.kt](modules/server/src/commonMain/kotlin/moe/lolosia/web/generated/GeneratedRegistries.kt) 中声明为 `expect`，生成物为 `actual`，由 [Main.kt](modules/app-desktop/src/commonMain/kotlin/moe/lolosia/web/Main.kt) 直接调用
- **时间列一律使用 `kotlin.time.Instant`**：业务使用 `Clock.System.now()`，驱动按 UTC 往返；仅日志使用本地时间展示

## 部署

1. 在 `platforms.json` 中为目标服务器配置 `abi` 与 `mode`。`arm64` / `aarch64` 对应 linuxArm64，`x64` / `amd64` / `x86_64` 对应 linuxX64
2. 执行 `gradlew deploy<服务器名>` 或 `deploy`，依次上传可执行文件、将旧文件 `mv` 为 `old.previous` 后再替换，避免覆盖运行中的文件导致 `ETXTBSY`，最后交由 systemd 或 docker 启动

**`mode: "systemd"`**：将远端 `<名字>-native.service` 的 `ExecStart` 改写为可执行文件路径后重启服务。首次部署由插件生成单元文件；如需自定义 `User=` 或资源限制，请在**服务器上**修改，插件不会覆盖已有单元。
仓库模板 `lolosia-backend-native.service` 已包含 `LimitNOFILE=524288`，**必须配置**：未配置时会继承 1024 的软限制，缩略图、索引与 WebDAV 并发升高后会在 `accept` 阶段因 `EMFILE` 崩溃。旧环境需手工补充，详见 [doc/lolosia-backend-notes.md](doc/lolosia-backend-notes.md) §4。

**`mode: "docker"`**：不构建镜像，产物以挂载方式注入基础镜像运行。

- 基础镜像配置在 `build.gradle.kts` 的 `binary { dockerImage }`，加速源配置在 `docker { image("ubuntu:24.04") { mirror } }`；国内网络直连 docker.io 可能无法拉取镜像，模板默认使用华为云 SWR 的 docker.io 代理
- 挂载：`/srv/lolosia-backend` → `/app`，可执行文件、配置与 `work/` 均在其中；另需挂载宿主机的 `/etc/localtime`、`/etc/timezone` 与 `/usr/share/zoneinfo`，基础镜像不包含 tzdata，缺少任一项时日志时间会整体落在 UTC
- 网络使用 `--network host`：应用直接绑定宿主端口，容器内的 `127.0.0.1` 即宿主，数据库地址按此配置
- 回滚：旧容器**先停止**再改名为 `<名字>-old`，确认新版本运行正常后才删除；失败时会打印容器日志并删除新容器

部署目录约定为 `/srv/lolosia-backend/`，磁盘上的 `application.json` 与 `application.yaml` 也放置于此。CIO 引擎不提供 HTTPS 与压缩，需交由前置 Nginx 处理；WebDAV 的 Basic 鉴权另需一条 TLS 反向代理，见 `doc/lolosia-backend-notes.md`。

## 延伸阅读

| 文档 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 代码风格与硬性约定，修改代码前应先阅读 |
| [doc/development-notes.md](doc/development-notes.md) | 构建、部署、冒烟、排障与环境依赖 |
| [doc/lolosia-backend-notes.md](doc/lolosia-backend-notes.md) | WebDAV、文件管理器、部署与网络实测 |
| [doc/rbac-design.md](doc/rbac-design.md) | RBAC：组、点分权限与用户直授 |
| [doc/media-index-design.md](doc/media-index-design.md) | 媒体索引：文件身份、标签、EXIF、评级与遮罩 |
| [doc/footprint-map-design.md](doc/footprint-map-design.md) | 足迹地图：聚类与区域 |
| [doc/anime-timetable-design.md](doc/anime-timetable-design.md) | 番组表：RSS 与 Bangumi 订阅 |
| [doc/local-service-design.md](doc/local-service-design.md) | 本地服务：dispatcher 与两种传输 |

## License

[MIT](LICENSE) © 洛洛希雅Lolosia
