# ktor-template

洛洛希雅（Lolosia）的 Ktor 后端模板：**Kotlin + Ktor 3 服务端脚手架**，已从 JVM 单体迁移为 **Kotlin/Native 原生可执行文件**——没有 JVM、没有 fat jar，一个二进制就是全部（前端产物、SQL 脚本、配置文件都嵌在里面）。

- 技术栈：**Kotlin 2.4.0（KMP）+ Ktor 3.4.2（CIO）+ Koin 4.2.2 + sqlx4k（Rust sqlx 的 FFI）+ MariaDB**
- 目标平台：**`mingwX64`**（Windows 本地开发调试）与 **`linuxArm64`**（部署到开发板）；**不保留 JVM 目标**
- 自研件：**注解式路由插件**（注解 + KSP 代码生成）、**`classpath.zip` 资源中间层**、**多级配置**、**bcrypt**、**图形验证码**（PNG 编码 + 点阵算式，见 `util/image`、`util/captcha`）
- 旧 JVM 代码整体放在 [legacy/jvm](legacy/jvm)，不参与编译，仅作迁移参考

> 迁移方案与实测记录见 [doc/native-migration-plan.md](doc/native-migration-plan.md)，
> 会话交接（约定 / 状态 / 工作流 / 踩坑）见 [doc/开发记忆.md](doc/开发记忆.md)。

## ✨ 特性

- **注解式控制器**：`@Controller("/api") object XxxController` + `@GET/@POST/@PUT/@DELETE/@PATCH`，KSP 编译期生成路由注册（`generatedRoutes()`），不依赖任何反射（Native 也没有反射）
- **按类型自动注入**（Spring 风格）：形参类型标了 `@Injectable` 就**无需注解**直接注入，如 `fun whoami(ctx: Context)`、`fun config(config: AppConfig)`
- **参数注解名称可省略**：`@Path` / `@Query` / `@Header` / `@Form` / `@FilePart` 不写名字默认取形参名；`@Body` 直接反序列化 `@Serializable` 请求体
- **`classpath.zip` 中间层**：源集下的 `resources/**` 就是 classpath 根，构建期嵌入可执行文件，运行时 `classpath.read("static/home/index.html")` —— 写法与 Java 打资源文件一致
- **多级配置覆盖**：数据类默认值 < 内嵌 JSON < 磁盘 `application.json` < 环境变量 < 命令行端口
- **数据访问**：sqlx4k 的 `@Repository` + `@Query` 编译期代码生成（无 ORM、无反射），实体时间列统一 `kotlin.time.Instant`
- **自研 multipart 解析**：Ktor 3.4 在 Native 上未实现 `receiveMultipart()`，改为读原始字节自行解析
- **自研图形验证码**（`util/captcha`）：Native 上没有 `java.awt` / `javax.imageio`，于是自写 PNG 编码器（`util/image/Png.kt`：IHDR/IDAT/IEND + zlib STORED + CRC32/Adler-32）+ 5×7 点阵字形 + 算式生成，逐字对齐 hutool `LineCaptcha` / `MathGenerator`（100×40、150 条干扰线、`a+b=` 且结果可为负），零依赖出图
- **统一响应信封** `{code,msg,data}`：由 KSP 生成的注册代码调 `respondEnveloped(...)` 自动包装（不靠 send pipeline 拦截 —— Native 上没有反射，显式包装才能把 `typeInfo` 完整交给序列化器）
- **会话鉴权**：`Authorization: Bearer <sessionId>`（**token 就是会话 id，不是 JWT**）；拦截器链 `Traffic → Ctx → Auth`，`/api/**` 默认保护 + 白名单放行，失败回 `401` + 信封
- **静态资源 + SPA**：`staticClasspath` 支持 index、`ETag` + 304 协商缓存、history 路由回退
- **单文件部署**：Gradle 插件按 `platforms.json` 生成 `deploy<服务器>` 任务，走 systemd 拉起可执行文件（自动留 `.previous` 回滚文件）

## 🧱 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 / 构建 | Kotlin 2.4.0（Multiplatform）+ Gradle 9.5.1（Wrapper） |
| Web 框架 | Ktor 3.4.2（CIO 引擎，`embeddedServer`） |
| 序列化 | kotlinx.serialization 1.11.0（ContentNegotiation + JSON） |
| 路由 | 自研插件：[router-ksp](router-ksp)（KSP 处理器）+ [util/router](src/commonMain/kotlin/moe/lolosia/web/util/router)（运行时） |
| DI | Koin 4.2.2（容器实例显式传递，不用全局容器） |
| 数据访问 | sqlx4k 1.13.1（native 侧是预编译 Rust 静态库，无需 Rust 工具链） |
| 数据库 | MariaDB / MySQL |
| 时间 | kotlinx-datetime 0.8.0 + `kotlin.time.Instant`（写库一律 UTC，时区语义由类型保证） |
| IO / 压缩 | okio 3.9.1；`classpath.zip` 目前仅 STORED |
| 日志 | 自研（`Log.of("tag")`，级别 + 时间戳 + 异常 cause 链） |
| 密码 | 自研 bcrypt（移植 jBCrypt 0.4，兼容库里既有的 `$2a$10$...`） |

## 📁 项目结构

```
├── buildSrc/                     # 自定义 Gradle 插件 moe.lolosia
│   └── src/main/kotlin/moe/lolosia/gradle/
│       ├── task/                 # ViteJar / GenerateConstants / NativeClasspath / Deploy / GitArchive
│       ├── extension/            # lolosia { viteJar / classpath / deploy / docker } 配置
│       └── NativeClasspath.kt    # classpath.zip 合并 + windres/objcopy 嵌入 + 链接参数
├── router-ksp/                   # 自研路由的 KSP 处理器（JVM 件）：扫 @Controller → GeneratedRoutes.kt
├── src/
│   ├── commonMain/kotlin/moe/lolosia/web/
│   │   ├── ApiResult.kt          # {code,msg,data} 信封 + 默认端口 PORT = 7129
│   │   ├── config/               # AppConfig（@Serializable）+ 多级覆盖 ConfigLoader
│   │   ├── controller/           # Login / User / Role / UserRole / Menu（业务）+ Demo / Classpath（插件验证用）
│   │   ├── db/                   # Database（sqlx4k 驱动）+ 各 @Repository + db/model 实体
│   │   ├── di/                   # Koin 容器：appModule / serviceModule / registerInjectable
│   │   ├── dto/                  # 接口出参/入参 DTO（SystemDtos：用户/角色/绑定；LoginDtos：登录）
│   │   ├── interceptor/          # TrafficInterceptor（耗时日志）/ AuthInterceptor（会话鉴权 + 白名单）
│   │   ├── service/              # LoginService / SessionManager / CacheManager / SessionMap
│   │   │   └── system/           # UserService / RoleService / UserRoleService（业务服务）
│   │   └── util/                 # classpath / crypto(BCrypt) / image(PNG) / captcha / router / session / time / log
│   ├── commonMain/resources/     # application.json（内嵌默认配置）/ static/home/ / hello.txt
│   ├── nativeMain/kotlin/…/      # Bootstrap.kt（startServer）/ ZipClasspath / 平台 Env
│   ├── mingwX64Main/kotlin/…/    # Main.kt（入口 + platformModules）+ PE 资源读取
│   └── linuxArm64Main/kotlin/…/  # Main.kt（入口 + platformModules）+ objcopy 符号读取
├── classpath/mingw/classpath.rc  # Windows 资源脚本（windres 把 classpath.zip 编成 PE 资源）
├── sql/                          # 建表脚本（7 张表 + view_user_role 视图）
├── doc/                          # 迁移方案 + 开发记忆
├── legacy/jvm/                   # 旧 JVM 代码（不参与编译，仅参考）
├── ktor-template.service         # systemd 单元模板（部署插件据此改写 ExecStart）
└── platforms.json                # 【本地文件，不入库】前端工程路径 + 部署服务器列表
```

## 🚀 快速开始

### 环境要求

- JDK 21（Gradle 与 KSP 处理器运行用；产物本身不需要 JVM）
- MariaDB / MySQL
- 首次构建自动下载 Kotlin/Native 工具链到 `~/.konan`（约 2.1GB）
- 构建 `linuxArm64` 且宿主是 Windows 时：WSL2 + Ubuntu 24.04（只需 `aarch64-linux-gnu-objcopy`；编译与链接仍在 Windows 上跑）
- （可选）Node + vite 前端工程；`platforms.json` 里配 `webPath` 后由 `viteJar` 构建并并入

### 1. 建库导表

```sql
CREATE DATABASE `ktor-template` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

导入 `sql/` 下的脚本（**`view_user_role.sql` 依赖前面的表，必须最后导**）：

```bash
mysql -u root -p -D ktor-template < sql/sys_user.sql
mysql -u root -p -D ktor-template < sql/sys_role.sql
mysql -u root -p -D ktor-template < sql/sys_sessions.sql
mysql -u root -p -D ktor-template < sql/sys_cache.sql
mysql -u root -p -D ktor-template < sql/sys_user_roles.sql
mysql -u root -p -D ktor-template < sql/sys_logs.sql
mysql -u root -p -D ktor-template < sql/oss_user_file.sql
mysql -u root -p -D ktor-template < sql/view_user_role.sql   # 视图放最后
```

### 2. 本地配置（不入库）

- `platforms.json`（根目录，已 gitignore）：`webPath` 指向前端工程目录（`viteJar` 据此构建前端）、`deploy.servers` 描述部署目标。**缺了它 `viteJar` / `generateConstants` 不会注册**。
- 数据库等运行时配置不必改代码：写工作目录下的 `application.json`（见「配置」一节），或用环境变量。

### 3. 构建与启动（本机 mingwX64）

```powershell
gradlew linkReleaseExecutableMingwX64                       # 产物：build\bin\mingwX64\releaseExecutable\ktor-template.exe
build\bin\mingwX64\releaseExecutable\ktor-template.exe 7129  # 首参覆盖端口（可选）
```

服务默认监听 `0.0.0.0:7129`，静态首页在 `http://127.0.0.1:7129/home/`。冒烟：

```powershell
curl.exe -s http://127.0.0.1:7129/api/ping            # {"code":200,"msg":"success","data":"pong"}
curl.exe -s http://127.0.0.1:7129/api/config          # 生效配置（端口 / db / 日志级别）
curl.exe -s http://127.0.0.1:7129/api/db/ping         # sqlx4k 连通性：版本 / 库名 / 会话时区
$st = curl.exe -s http://127.0.0.1:7129/api/svc/selftest   # 服务层自检（13 步）+ 末尾给出 token=…
$st
$tk = ($st | Select-String -Pattern 'token=(\S+)').Matches.Groups[1].Value

# /api/** 默认要鉴权（Authorization: Bearer <sessionId>），无 token → 401 + {code:401,…}
curl.exe -s -o "$env:TEMP\a.json" -w "no-token=%{http_code}`n" -X POST http://127.0.0.1:7129/api/role/list
curl.exe -s -X POST http://127.0.0.1:7129/api/role/list -H "Authorization: Bearer $tk"
curl.exe -s -X POST http://127.0.0.1:7129/api/user/myInfo -H "Authorization: Bearer $tk"
curl.exe -s -X POST http://127.0.0.1:7129/api/logout -H "Authorization: Bearer $tk"

# 图形验证码 → verify（一次性消费）→ 注册（免鉴权）
$cap = curl.exe -s -X POST http://127.0.0.1:7129/api/captcha    # {"data":{"image":"<base64 png>","id":"<token>"}}
curl.exe -s -X POST http://127.0.0.1:7129/api/verify -H "Content-Type: application/json" -d '{"id":"<id>","code":"<算式结果>"}'
curl.exe -s -X POST http://127.0.0.1:7129/api/register -H "Content-Type: application/json" -d '{"userName":"alice","password":"123456","email":"alice@example.com"}'
```

## 🛠 构建任务

| 任务 | 说明 |
| --- | --- |
| `gradlew linkReleaseExecutableMingwX64` | Windows 本机可执行文件 |
| `gradlew linkReleaseExecutableLinuxArm64` | 开发板（aarch64）可执行文件 |
| `gradlew viteJar` | 构建 vite 前端工程（路径取自 `platforms.json` 的 `webPath`） |
| `gradlew generateConstants` | 依 vite 平台生成 `VITE_DEFAULT` 常量（值 = `contextPath`，避免把 `/home` 写死） |
| `gradlew classpathZip<目标>` | 把各源集 `resources` + vite 产物合并成 `classpath.zip`（link 任务自动依赖） |
| `gradlew deploy<服务器名>` / `deploy` | 按 `platforms.json` 部署（`deploy` 指向 `deploy.active` 那台）；配了 `abi` 走原生二进制，否则找 `bootJar` |
| `gradlew gitArchive` | 按 vite 平台导出 git 归档 |

## 🧭 写一个控制器

```kotlin
@Serializable
data class EchoReq(val name: String, val times: Int = 1)

@Controller("/api")
object DemoController {

    @GET("/ping")
    fun ping(): String = "pong"

    /** 形参类型标了 @Injectable → 无需注解，自动注入（Context / AppConfig / Database / Service / Repository 均可用） */
    @GET("/whoami")
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

约定（迁移前的 ktorfitx 控制器**只改 import** 即可复用）：

- `@Controller` 只能标在 `object` 上；方法返回值由生成代码调 `respondEnveloped(...)` 包成 `{code,msg,data}` 后写出
- 无法绑定的形参会**编译期报错**并给出提示：要么加参数注解，要么给类型标 `@Injectable`
- 支持的参数类型只有 `String / Int / Long / Double / Boolean / Uuid / 枚举`（不做嵌套泛型绑定，超范围请手写路由）

业务接口（路径与出参形状以前端工程为准）：`/api/login`、`/api/logout`、`/api/captcha`、`/api/verify`、`/api/register`、`/api/user/{list,searching,get,create,edit,delete,updatePassword,myInfo,myRole}`、`/api/role/{list,queryRoleByPage,create,update,destroy}`、`/api/userRole/getByUserId`、`/api/menu/config`。
这些接口（以及探针里的 `/api/classpath/*`）都走鉴权：`POST` + `Authorization: Bearer <sessionId>`；白名单只有 `/api/login`、`/api/logout`、`/api/captcha`、`/api/verify`、`/api/register`、`/api/user/updatePassword`、`/api/user/avatar` 与取件类前缀（`/api/oss/show/`、`/api/oss/get/`、`/api/user/avatar/get/`）。

当前模板自带的探针接口（**临时脚手架**，P0 收尾删除）：`/api/ping`、`/api/whoami`、`/api/config`、`/api/db/ping`、`/api/db/roles`、`/api/db/users`、`/api/cache/{put,get}`、`/api/session/{new,get/{id}}`、`/api/svc/selftest` 在开发期免鉴权（见 `AuthInterceptor.DEV_PROBES`）；`/api/classpath/{list,read}` 需鉴权。

## ⚙️ 配置

配置只有一个入口：[AppConfig](src/commonMain/kotlin/moe/lolosia/web/config/AppConfig.kt)（`@Serializable` 数据类）。
优先级由低到高，**按字段深合并**：

1. 数据类里的默认值
2. classpath 内嵌的 [application.json](src/commonMain/resources/application.json)（随二进制分发）
3. 磁盘 `application.json`（相对启动目录；systemd 下即 `/srv/ktor-template/`，可用 `KTOR_CONFIG` 指定其它路径）
4. 环境变量：`KTOR_HOST` / `KTOR_PORT` / `KTOR_LOG_LEVEL` / `KTOR_DB_URL` / `KTOR_DB_USERNAME` / `KTOR_DB_PASSWORD`
5. 命令行第一个参数（仅端口，如 `ktor-template.exe 8080`）

```json
{
  "server": { "host": "0.0.0.0", "port": 7129 },
  "datasource": {
    "url": "mysql://127.0.0.1:3306/ktor-template",
    "username": "root",
    "password": "123456",
    "maxConnections": 10
  },
  "logLevel": "INFO"
}
```

> 其它环境变量：`KTOR_CLASSPATH_DIR` 指向磁盘资源目录，开发期改前端/资源不必重编二进制。
> 加配置项 = 改 `AppConfig`（+ 必要时改 `envOverrides`），不要另起一套读取逻辑；磁盘 JSON 带 BOM 也能解析（解析失败会记 WARN 而不是静默忽略）。

## 🗄 数据层约定（sqlx4k）

- **实体**：`@Table("表名")` + `@Id`（库自增）或 `@Id(insert = true)`（应用给主键），列名自动按 snake_case 映射
- **仓库**：接口标 `@Repository` + `@Injectable`（后者必须，否则控制器形参注入报「无法绑定」），继承 `CrudRepository<T>`
  - 第一个参数必须是 `context: QueryExecutor`，方法必须 `suspend`，**每个方法都要带 `@Query`**（没有派生查询）
  - 方法名前缀白名单是精确匹配：`findAll` / `deleteAll` / `countAll`，或 `findAllBy*` / `findOneBy*` / `deleteBy*` / `countBy*` / `existsBy*` / `execute*`
  - **不支持 `IN (列表)`**（占位符在编译期固定），批量场景在服务层循环单条方法
  - MySQL 方言下**不要用生成的 `insert` / `update`**（会拼成多语句脚本，MariaDB 报 `1064`），写入用单语句 `@Query`
- **生成物位置**：实现类在目标源集（`moe.lolosia.web.db.generated`，KSP2 在 KMP 下没有 common 编译单元）→ 在 [Main.kt](src/mingwX64Main/kotlin/moe/lolosia/web/Main.kt) 的 `platformModules()` 里 `single { XxxRepositoryImpl }` 绑定，再到 `Bootstrap.configureApplication` 里 `koin.registerInjectable<XxxRepository>()`
- **时间列一律 `kotlin.time.Instant`**（业务用 `Clock.System.now()`），驱动按 UTC 往返；只有日志用本地时间展示

## 🚢 部署（systemd）

1. `platforms.json` 里给目标服务器配 `mode: "systemd"` 与 `abi: "arm64"`（`abi` 决定部署原生二进制，不配则走 `bootJar`）
2. `gradlew deploy<服务器名>`（或 `deploy`）→ 上传可执行文件 → `mv old old.previous` 再替换（避免覆盖运行中的文件报 `ETXTBSY`）→ 改写远端 `<名字>-native.service` 的 `ExecStart` 为可执行文件路径 → 重启服务
3. 首次部署会由脚本生成单元文件；想自定义 `User=` / 资源限制，就在**服务器上**改，插件不会覆盖已有单元

部署目录约定为 `/srv/ktor-template/`（磁盘 `application.json` 也放这里）。CIO 引擎无 HTTPS 与压缩，这两件事交给前置 Nginx。

## 🗺 迁移进度与路线图

| P0 项 | 状态 |
| --- | --- |
| 0.1 KMP 双目标骨架 + KSP2 多目标接线 | ✅ |
| 0.2 最小 CIO 服务（JSON + 信封 + StatusPages） | ✅ |
| 0.3 自研路由插件（注解 + KSP + 运行时 + multipart） | ✅ |
| 0.4 `classpath.zip` 中间层 | ✅ |
| 0.5 基础设施替换（日志 / 配置 / Koin / datetime / okio / Uuid） | ✅ |
| 0.6 sqlx4k 数据层 | ✅ 会话/缓存 + 用户/角色/绑定的读写与 Service 层（`/api/svc/selftest` 自检全绿） |
| 0.7 自研 bcrypt（`$2a$` cost 10） | ✅ 移植完成（官方向量锁定），已接进建用户/改密码/登录校验 |
| 0.8 鉴权拦截器链 + 登录 + 系统接口 | ✅ 信封改由 KSP 生成代码调 `respondEnveloped`；链 = Traffic → Ctx → Auth；`/api/login` `/api/logout` + 用户/角色/绑定/菜单控制器（**以前端工程契约为准**）。SSE 拦截器待做 |
| 0.9 部署（systemd 拉起原生二进制） | ✅ |
| 0.10 自研图形验证码 + `/captcha` `/verify` `/register` | ✅ 自写 PNG + 点阵算式验证码（复刻 hutool），验证码存 `sys_cache`（随机 token、5 分钟、一次性消费）；注册沿用前端两步流程（`roleId = 4` 学生） |

> 下一步（P0 收尾）：删掉 `DemoController` 与 `AuthInterceptor.DEV_PROBES` 这组临时探针，然后进 P1（OSS / 头像 / `sys_logs` / SSE）。

- **P1**：WebP 转换、OSS 上传/头像/菜单权限、SSE 事件总线实机回归、压缩（Nginx）、`classpath.zip` 改 DEFLATED
- **P2**：AI 相关按需改为纯 Ktor Client 直连 LLM HTTP API（不引入 AI 框架）

## 📜 License

[MIT](LICENSE) © 洛洛希雅Lolosia
