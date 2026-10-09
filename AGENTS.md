# AGENTS.md —— 项目风格与硬性约定

> 在本仓库动手前先读本文。它是**规范性**文档（约定，不是教程）：写代码、改结构、加依赖都按这里来。
> 与代码冲突时以代码为准，并顺手回来修本文。
> 深度材料（构建/部署/冒烟脚本、历史踩坑、迁移详设）见 [`doc/`](doc/)。
>
> 面向对象：AI agent 与人类协作者一视同仁 —— 约定就是约定。

## 0. 项目定位

- **Kotlin/Native 全栈模板**：Kotlin 2.4.20（KMP）+ Ktor 3.4.2（CIO）+ Koin 4.2.2 + sqlx4k（Rust sqlx 的 FFI）+ MariaDB。
- 目标平台有 **`mingwX64`**（Windows 本地开发）、**`linuxArm64`**（部署到开发板）与 **`linuxX64`**（部署到 x64 云主机）；**没有 JVM 目标**。两个 linux 目标共享 `src/linuxMain/` 下的平台实现。
- 产物是**单个原生可执行文件**：前端产物、SQL、默认配置都以 `classpath.zip` 形式内嵌，无 fat jar、无外部资源目录。
- 图像编解码（WebP / PNG / JPEG / GIF）走**预编译的 C 静态库**，随可执行文件静态链入；库产物随仓库提交，日常构建不需要 C 工具链（`thirdparty/`，见 §7 / §10）。
- 根包 **`moe.lolosia.web`**；唯一的独立 Gradle 模块是 `:router-ksp`（插件域，包名 `moe.lolosia.router.ksp` 不受根包约束）。

## 1. 代码风格

1. **不写 `public`**：Kotlin 默认即 public，显式写视为冗余。只有 `internal` / `private` 才显式标注。
2. **import 用通配**：`import io.ktor.client.*`，不逐条展开；同包内不写 import。
3. **注释只写正经 KDoc**：用途 / 参数 / 不变量 / 真实约束。
   - **不要**写「改动历史」「迁移自哪里」「参照方案第几节」这类叙述 —— 那是 git 的事。
   - 注释里**不要出现 `/*`**（Kotlin 块注释可嵌套，`/db/*` 会把后面的代码吞掉并报 `Unclosed comment`）。
4. **一个 `.kt` 只放一个顶层类型**（class / object / interface / enum），嵌套类与内部类随宿主保留。
   - 唯一例外：`util/router/Annotations.kt`（13 个注解集中一处）。
   - `private` 顶层声明不受此限：`private val log = Log.of("Tag")`、单行常量、只服务本文件的辅助函数，就近放使用处，不必为它开文件，也不必塞进 `package.kt`。
5. **散装函数/常量放同包 `package.kt`**（与类型无关的 public 顶层声明）。
6. **扩展函数的归属**：
   - 扩展**外部库**类型 → 按被扩展的库分域到 `util/<库名>`，域内按 `<接收者类型名>Ext.kt` 分文件（`util/ktor/RouteExt.kt`、`util/koin/KoinExt.kt`、`util/json/JsonObjectExt.kt`）。
   - 扩展**自研**类型 → 与类型同域（`util/classpath/ClasspathExt.kt`）。
   - 只服务单个文件的 `private` 扩展留在原文件。
7. **中间件独占顶级包 `moe.lolosia.web.interceptor`**：一层一个文件，public 的 `Application.installXxx()` 扩展。
8. **控制器专属 DTO 作为该 `object` 的内部类**；跨控制器共用才提成独立文件（或进 `dto/` 包）。
9. 纯函数模块保留语义文件名：`SettingsLoader.kt` / `Multipart.kt` / `AppConfigure.kt` / `Main.kt` / `Time.kt` / `Env.kt`。

## 2. 启动流程与依赖注入

**入口与装配（2026-10 收敛后）**

- `main()` 在 **commonMain** 的 `Main.kt`，唯一入口；`entryPoint = "moe.lolosia.web.main"`。
- Ktor 的 `Application` 装配在 `configuration/AppConfigure.kt` 的 `Application.configureApplication(koin)`。
- **各目标源集不再有 `main`，也不再有 `startServer`**：不要新增「由各目标入口传入 lambda」的写法。

**平台差异只有五处 `expect/actual`**：`installPlatformClasspath()`、`envOrNull()`、`platformModule()`，以及图像能力 [`Image`](src/commonMain/kotlin/moe/lolosia/web/util/image/Image.kt) 与缩略图生成 [`ThumbnailGenerator`](src/commonMain/kotlin/moe/lolosia/web/service/files/ThumbnailGenerator.kt)（这两个要绑内嵌的 C 静态库，`actual` 只能落在 nativeMain）。

**KSP 生成的注册表跨源集可见**

KSP2 在 KMP 下没有 common 编译单元，生成物只能落到各目标源集。因此约定：

- 签名以 `expect` 手写在 commonMain 的 [`generated/GeneratedRegistries.kt`](src/commonMain/kotlin/moe/lolosia/web/generated/GeneratedRegistries.kt)；
- 各目标由 `router-ksp` 生成 `actual`；二者由**编译器强制配对**，签名对不上直接编译报错（不会有静默漂移）；
- common 代码**直接调用**它们，不要再当参数层层传递：

```kotlin
repositoryModules()                    // @Repository 接口 → sqlx4k 生成实现 的 Koin 绑定
serviceModule()                        // @Service 类型的单例绑定（构造器注入）
koin.registerInjectables()             // 容器实例 → 路由「按类型注入」
configureInterceptors(koin)            // @Interceptor(order) 升序注册
generatedRoutes()                      // @Controller 路由批量注册（含 @Permission 校验）
generatedPermissions()                 // @Permission 节点目录（给管理界面做权限选择器）
```

**Koin**

- 容器实例**显式传递**（`startAppKoin(config, cp, modules)` 返回 `Koin` 往下传），**不要碰全局容器**。
  ⚠️ Koin 4 里 `org.koin.core.context.GlobalContext` 与 `koinApplication` **不是公开 API**（native 上直接 Unresolved），只有 `startKoin { }.koin` 可用。
- 两种注解各管一件事：
  - `@Service` → 生成 `single { X(get(), …) }`，**容器绑定**（构造器注入）；
  - `@Injectable` → **路由形参按类型注入**（控制器/仓库形参靠它，否则报「无法绑定」）；
  - 两者都需要时**都标**。
- 不能用注解的服务（需要外部实例、条件绑定等）手写进 `configuration/AppModule.kt` 的 `serviceModule()`。
  ⚠️ 它与生成的 `serviceModule()` **同名不同包**，改的是 `configuration` 包那一个。

## 3. 路由与控制器

- 控制器：`@Controller("/api") object XxxController` + `@GET/@POST/@PUT/@DELETE/@PATCH`；KSP 编译期生成路由注册，**无反射**。
- `@Controller` 只能标在 `object` 上。
- **注解参数名可省略**：`@Path` / `@Query` / `@Header` / `@Form` / `@FilePart` 不写名字时取形参名；`@Body` 直接反序列化 `@Serializable` 请求体。
- 支持的参数类型只有 `String / Int / Long / Double / Boolean / Uuid / 枚举`（不做嵌套泛型绑定，超范围请手写路由）。
  例外：`@Form` 支持 `List<String>`（同名多值字段，如 OSS 上传的 `reference`），`@FilePart` 支持 `List<UploadedFile>`（同名多文件）。
- 无法绑定的形参**编译期报错**：要么加参数注解，要么给类型标 `@Injectable`。
- 返回值直接写业务对象即可，**信封交给 `EnvelopeInterceptor`**：
  - 裸 `String` 会被当 `text/plain` 发出、**不包信封**；要包一段 JSON 文本就返回 `dto.RawJson`。
- **接口授权用 `@Permission("a.b.c")`**（可标函数或 `object`，函数上的覆盖 `object` 的；多个值是 **OR**）：
  `RouterProcessor` 在生成的路由体首行插入 `call.requirePermission(...)`，未通过抛 **403**；
  **不标 = 只要求登录**（与不引入本机制时一致）。权限模型、命名规范与判定口径见 [doc/rbac-design.md](doc/rbac-design.md)。
- 跨项目复用控制器时，目标是**只改 import**（注解同名同义，方法体零改动）。

## 4. 请求上下文 `ctx` 与服务层

- 一次请求一个 `Context`（挂在 `call.attributes`，请求结束随 `call` 释放），四面：
  - 请求面 `ctx.call`（**仅控制器用**）、`ctx.data`（请求期共享数据区）；
  - 用户面 `ctx.session` / `ctx.userId` / `ctx.user`（当前用户与角色懒查一次并缓存）；
  - 执行面 `ctx.db` / `ctx.transaction { }`；
  - **权限面 `ctx.hasPermission("a.b.c")`**：`@Permission` 生成的路由校验最终落到它。规则 = 组链（自身组 + 沿 `sys_role.parent_id` 的祖先组，库里没规则时回退 `permissions.yaml` 默认值）+ 用户直授；判定见 [doc/rbac-design.md](doc/rbac-design.md)。
- **服务层方法首参一律 `ctx: Context`**（与旧 JVM 族同位置）；服务**不持有连接池**（容器里的 `Database` 只给基础设施与 `CtxInterceptor`）。
- 服务层**禁用 `ctx.call`**：需要 IP / UA / 请求头时由控制器抽成普通参数传下去。
- 审计列的操作者**显式传 `operatorId: String?`**，不从 `ctx.userId` 隐式取（注册链路没有登录态）。
- `null` = 不修改 → 实现是**读-改-写**；审计列写 `operatorId ?: 现有值`，别用裸 `operatorId`（会把已有值抹成 NULL）。
- 批量只能**循环单条**（`@Query` 不支持 `IN`）。
- 出参一律 `@Serializable` DTO，**永不带 `password`**。

## 5. 数据层（sqlx4k）

- **连接池懒建**：`Database` 构造只存配置、不连库；由 `startReconnectLoop` 挂在应用作用域后台重连（失败只记日志、不崩溃）。**别在启动路径上建池** —— `mySQL(...)` 会阻塞到连上为止（主机不可达约 30s 后抛 `PoolTimedOut`）。
- **建池前先跑 `MySqlAuthProbe`（裸 TCP 握手预检）**：`connect` 在调 `mySQL(...)` 之前先用 [MySqlAuthProbe] 发一次 `HandshakeResponse41`：`Unreachable`（不可达，顺带省掉 sqlx4k 约 30s 的阻塞）/ `Denied`（鉴权失败、库不存在等确定性错误）直接判失败，`Ok` 才建池，`Inconclusive`（协议不认识 / 需 TLS / 未知插件）才回退给 sqlx4k。目的是**快速失败 + 明确报错**（sqlx4k 1.13.1 的 Rust 封装在这些情形会 `panic!`，叠加 `panic = "abort"` 直接崩进程；**1.14.0 起已改为回错误**）。改数据源相关的排障从日志里的 `[Database]` 行入手。
- 库未就绪时 `ctx.db` 抛 `ApiException(503, "数据库暂不可用…")`（前端据此报错，属预期）；库恢复后后台重连成功即自动可用。
- **实体**：`@Table("表名")` + `@Id`（库自增）或 `@Id(insert = true)`（应用给主键）；列名自动 snake_case。
- ⚠️ **建表别给列加 `_bin` 排序规则或 `ascii` / `binary` 字符集**：sqlx4k 的 MySQL 驱动把这类列当二进制，
  回给 Kotlin 的是**十六进制字符串**（`image` → `696d616765`），而且只填 `value` 不填 `bytes`，
  `asString()` 也照抄（其 `ResultSet.Column` 的既定契约，`ffi.kt` 侧确实如此实现）。枚举 / 短标识列
  一律跟表默认的 `utf8mb4_unicode_ci` 走 —— 唯一键顺带变成大小写不敏感，对标签这类数据正是想要的。
- **仓库**：接口标 `@Repository` + `@Injectable`，继承 `CrudRepository<T>`。
  - 首参必须是 `context: QueryExecutor`，方法必须 `suspend`，**每个方法都要带 `@Query`**（没有派生查询）。
  - 方法名前缀白名单是**精确匹配**：`findAll` / `deleteAll` / `countAll`，或 `findAllBy*` / `findOneBy*` / `deleteBy*` / `countBy*` / `existsBy*` / `execute*`。
    返回类型由前缀决定：`findAllBy*→Result<List<T>>`、`findOneBy*→Result<T?>`、`countBy*→Result<Long>`、`execute*→Result<Long>`。
  - ⚠️ **不支持 `IN (列表)`**：占位符编译期就固定成一个 `?`，传 `List` 会被当单值绑定并运行期报错 → 批量在服务层循环单条。
  - ⚠️ **`@Query` 的具名参数别撞 SQL 保留字**：KSP 用自己的 SQL 语法解析整条语句，`:sample` 会被词法切成
    `:` + `SAMPLE`（`TABLESAMPLE` 的关键字）直接编译失败（`Invalid SQL syntax ... Encountered: <DOUBLE_COLON>`）→ 换个名字，如 `:sampleHex`。
  - ⚠️ **MySQL 方言下不要用 `CrudRepository` 自带的 `insert` / `update`**：生成器拼成 `insert …; select …` 多语句脚本，MariaDB 预处理直接 `1064`。写入用单语句 `@Query`（如 `executeInsert`）；`delete` 是单语句，可用。
  - 拿不到自增 id：`execute*` 只回受影响行数 → 需要新 id 时**插入后回查**。
- ⚠️ **`@Query` 的主表必须与实体 `@Table` 一致**（codegen 会校验，否则报 `targets table 'x' but the repository entity is mapped to table 'y'`）：
  跨表取数把**本表当驱动表**再 `join` 别的表，不要从别的表 `join` 回来。
- **权限规则只在 `PermissionService`（管理侧读写）/ `PermissionResolver`（判定侧解析）里碰**：规则串语法 `[+/-]a.b.c` / `a.b.*` / `*`，写入前必须过 `Permissions.canonical()` 校验（非法串拒 `400`）；
  保存是**全量替换**（软删 + 插入，跑在同一事务里）。判定口径：合并**组链**（自身组 + 沿 `sys_role.parent_id` 的祖先组）与用户直授 → **负号优先** → 未命中拒绝，`owner` 组直接放行。
- **内置组的默认权限在内嵌资源 [`permissions.yaml`](src/commonMain/resources/permissions.yaml)，不在 SQL 里**：某组在 `sys_role_permission` 一条规则都没有时回退用它，库里有规则就完全以库为准。
  `extends` 由启动时的 `service.permission.syncRegistryGroups` 同步进 `sys_role.parent_id`（只改继承，不建组、不写规则）；组本身仍由 `sql/sys_role.sql` 建（其 id 被代码引用）。
- **生成物位置**：实现类是 `object XxxImpl`，在目标源集包 `moe.lolosia.web.database.generated`，**只存在于目标源集**。
  绑定**不手写** —— 由 `router-ksp` 扫 `@Repository` 生成 `repositoryModules()`（见 §2）。
- **时间列一律 `kotlin.time.Instant`**（业务用 `Clock.System.now()`），**不要用 `LocalDateTime`**：驱动全程按 UTC 往返，时区语义由类型系统保证；只有日志用 `nowLocal()` 展示本地时间。

## 6. 中间件（拦截器链）

- **顺序由注解声明**：`@Interceptor(order)`（`Int`，越小越靠外），`InterceptorProcessor` 按 order 升序生成 `configureInterceptors(koin)`；**不要手写顺序表**，order 重复由 KSP 报错。
- 拦截器**形参即依赖**，由 KSP 生成 `koin.get()` 注入；**不要用 `GlobalContext`**。
- ⚠️ **注册位置**：整条链必须**晚于 `install(ContentNegotiation)`** —— 信封按响应 MIME 判定，要等内容协商把响应转成带 `Content-Type` 的 `OutgoingContent` 后才认得出 JSON。
- **响应信封规则**（`EnvelopeInterceptor`）：只作用于 `/api/` 前缀，**按响应 MIME 判定** ——
  `application/json` / `text/json` / `*+json` 包成 `{code,msg,data}`；`text/plain` **不包**；文件流 / SSE 不处理；
  顶层已含 `code`/`msg`/`data` 三键的响应（`ApiResult`、StatusPages 的错误响应）**跳过**，避免双重包裹；
  必须原样的响应（如 SSE 握手）给 call 打 `EnvelopeSkipKey`。
- **鉴权分两层，别混为一谈**：`AuthInterceptor`（`-3`）只管「**是否登录**」，「**这个接口你能不能调**」由路由级的 `@Permission`（§3）负责。
  白名单只免掉「没登录就 401」，**不免权限** —— 白名单里的接口若标了 `@Permission`，会因无会话而判失败。
- 白名单里的接口可以**自己校验另一种凭据**，文件管理器就是这么做的：`/api/files/{thumb,raw}` 是给 `<img src>` 直接访问的
  （浏览器原生标签带不了 `Authorization`），于是放进白名单、改为校验 **URL 签名**（`exp` + `sig`，HMAC 覆盖「相对路径 + 过期时间」，
  由列表接口下发，见 `service/files/FileSigner.kt`）。同类先例是 `/api/user/avatar` 自己判登录态 —— 加白名单前先想清楚「那谁来把关」。
- **WebDAV 挂在 `/api` 之外**（默认 `/dav`，见 `util/ktor/WebDavRouting.kt`）：既不经会话鉴权、也不套信封，鉴权走 HTTP Basic ——
  WebDAV 客户端（Windows「映射网络驱动器」）要的是原样状态码与 207 多状态 XML。**不要**把它挪进 `/api`，也不要给它套信封。

## 7. 资源、配置、日志、密码、图像

- **资源一律走 `classpath` 中间层**，不要用文件系统路径：`src/<源集>/resources/**` 就是 classpath 根，运行时 `classpath.read("static/home/index.html")`。
  - `Classpath` 的来源标识：`scheme`（`file` / `classpath` / `empty`）与 `origin`（供日志展示）。
  - 内嵌 `classpath.zip` 默认 **DEFLATED**（`classpath { compressed = true }`），读取端 `ZipClasspath` 用 `platform.zlib` 做 inflate；STORED 也支持。
  - ⚠️ **改了 `resources/**` 必须重新链接**：`buildSrc/NativeClasspath.kt` 已把 `classpath.res` / `classpath.o` 声明为链接任务的输入，只改资源内容也会触发 `classpathZip → windres/objcopy → link`；若发现改动没生效，先看 link 任务是不是被判了 UP-TO-DATE。
- **Windows 进程代码页**：`classpath/mingw/app.manifest`（`activeCodePage=UTF-8`）由 `classpath.rc` 以 `RT_MANIFEST` 引用，随 `classpath.res` 一起链进 exe。
  没有它，okio 走的 CRT 窄字符 API 会把中文文件名解错而**直接丢弃** —— 症状是目录里「看着少文件」，排查时先怀疑代码页。
- **默认权限注册表**是内嵌资源 `permissions.yaml`（`classpath` 根下），由 `loadPermissionRegistry` 在 `appModule` 里解析成 `PermissionRegistry` 单例；改它同样要重新编译。
- **运行期资源目录**（不在二进制内）：`/res/resources/**` → `work/resources/**`，用 `FileStoreClasspath` 把 `FileStore` 的子目录挂给 `staticClasspath`（前端 landing 页背景图走这条）。
  带 `?webp` 时按旧版语义转 WebP 并落盘缓存 `work/cache/webp/{源文件内容md5}.webp`；不支持解码的格式（bmp / tiff）或转换失败一律回退原图（`service/WebpCache.kt` 落盘，转码走 `util/image/Image.kt`）。
- **WebDAV**：`AppSettings.webdav`（`enabled` / `path` / `root` / `username` / `password` / `realm`）把 `root` 目录（如挂载的机械盘 `/mnt/hdd1`）挂到 `path` 下；
  协议层在 `util/ktor/WebDavRouting.kt`、存储层在 `service/dav/`。挂载点在 `/api` 之外（规则见 §6），鉴权用 HTTP Basic。
  ⚠️ `DavStore` 会**跳过一切 `.` 开头的段**（解析与列举都跳）—— 这是为了让文件管理器的缩略图缓存（`<root>/.cache`）在 WebDAV 客户端里彻底不可见，代价是用户自己建的隐藏目录/文件也看不到。
- **在线文件管理器**：`AppSettings.files`（`root` 留空回退 `webdav.root`、`signKey`、`signTtlSeconds`、`thumbnail.*`）把 `root` 以**只读**方式列成目录树并出缩略图；
  接口在 `controller/FileController.kt`，实现全在 `service/files/`。`thumb` / `raw` 走 URL 签名而非会话（见 §6），其余接口挂 `lolosia.files.read`（默认只给 `admin` 及以上）。
  - 缩略图缓存落在 `<root>/.cache/lolosia/thumb/v2`（`CACHE_DIR` + `THUMBNAIL_CACHE_VERSION`），版本号一改旧缓存整体作废。
  - 生成是**单工作协程串行**的（机械盘并发解码只会更慢），`files.thumbnail.enabled` **只能在一台机器上开**，否则两边同时写同一份缓存。
  - `FileEntry` 的 `hasChildren`（是否为空）与 `hasSubdirs`（是否有子目录）是**两个独立标志位**，前者由 `files/list` 与 `files/dirs` 填、后者只有 `files/dirs` 填（判定它要多一轮 stat 扫描）。
- **第三方 C 库**（图像编解码）固定在仓库里：`thirdparty/<目标>/*.a`（预编译静态库）+ `thirdparty/include/**`（公共头文件）+ `src/nativeInterop/cinterop/*.def`。
  - **产物入库、源码不入库**；日常 Gradle 构建只做 cinterop 解析 + 链接，**不需要任何 C 工具链**。重建/升级用 `thirdparty/build-libs.ps1`（用法见 [doc/development-notes.md](doc/development-notes.md)）。
  - 绑定包名 **`moe.lolosia.thirdparty.<库名>`**；门面只以 `ByteArray` / `RgbaImage` 收发，不把 C 指针泄漏给调用方。
  - 一律选**不依赖 `setjmp/longjmp` 的接口**（libpng 用 `png_image` 简化接口、libjpeg-turbo 用 `tj3*`、libwebp / giflib 本身回错误码），坏图只回 `null`，不会把进程带走。
  - ⚠️ C 库只保留调用期间有效的裸指针（libpng 的 `png_image_begin_read_from_memory` 就是典型）：`usePinned` 必须覆盖到真正的解码调用为止，中途解 pin 只会得到「不报错但像素全错」。
  - ⚠️ `size_t` 三个目标都是 64 位可安全传递；`unsigned long` 不是（mingw 32 位 / linux 64 位），跨目标代码别写出具体宽度。
  - ⚠️ libwebp 1.6 起编码侧的 SharpYuv 拆成独立归档，`build-libs.ps1` 已把它合并回单个 `libwebp.a`：K/N 的 ELF 链接（ld.gold）按归档逐个解析，分成两个归档时 `SharpYuv*` 会解析不到（arm64 / x64 都失败）。
- **前端路径不许写死**：用 `generateConstants` 产出的 `VITE_DEFAULT`（值 = viteJar 的 `contextPath`）拼路由。
- **配置只有一个入口** [`settings/AppSettings.kt`](src/commonMain/kotlin/moe/lolosia/web/settings/AppSettings.kt)（`@Serializable`）。
  优先级（低→高，**按字段深合并**）：数据类默认值 < 内嵌 `application.json` < 磁盘 `application.json` < 环境变量 < 命令行端口。
  加配置项 = 改数据类（+ 必要时 `envOverrides`），**不要另起一套读取逻辑**。
- **日志一律 `Log.of("tag")`**，不要 `println`；级别由配置 `logLevel` 控制。
- **密码用自研 bcrypt**（`util/crypto/BCrypt.kt`，移植 jBCrypt 0.4）：保持 `$2a$` 与 cost=10 语义，能直接校验库里既有的哈希；
  改动算法后**先跑 jBCrypt 官方测试向量**。
- **Native 上没有 `java.awt` / `javax.imageio`**：通用图像编解码走内嵌的 C 静态库（见上一条），commonMain 的统一入口是 [`util/image/Image.kt`](src/commonMain/kotlin/moe/lolosia/web/util/image/Image.kt)（`expect class Image`：bytes ↔ 像素、探测格式/尺寸/色彩类型、基础 draw）；
  纯自研的只剩验证码那条链路 —— `util/captcha/`（5×7 点阵字，无字体依赖）直接画在 `Image` 的空白画布上再编码 PNG。不要去 Maven 找图像库。

## 8. 接口契约

- **路径与出参形状以前端工程为准**；旧 JVM 后端已从工作区移除（只在 git 历史里），不要再拿它对照。
- 改契约前先在前端源码里 grep 一遍调用点。

## 9. 验证与提交

- **编译自检**（改了代码至少跑这个）：

```powershell
gradlew compileKotlinMingwX64 compileKotlinLinuxArm64 compileKotlinLinuxX64
```

- **本机跑起来**（改完代码冒烟用）：
  - **建库导表**：本机 MariaDB 直接导 `sql/` 里的脚本即可（库 `lolosia-backend`）。客户端不在 `PATH`（本机在 `D:\Program Files\MariaDB 12.1\bin\`），且 `cmd /c` 重定向会被工具策略拦，
    改用客户端自带的 `source`：`& "<...>\bin\mysql.exe" -u root -p<弱口令> -e "source <脚本的绝对路径>.sql"`。
  - **启动后端**：`gradlew runReleaseExecutableMingwX64`，**cwd 必须是 `backend` 根目录** —— 磁盘 `application.yaml` 按工作目录找，换个 cwd 就退回内嵌默认配置。
  - **重启**：先杀旧的 `lolosia-backend.exe`（`Get-Process lolosia-backend | Stop-Process -Force`）再跑；
    否则 `linkReleaseExecutableMingwX64` 会因 exe 被旧进程占用报 `AccessDeniedException`。

- 产物：`gradlew linkReleaseExecutableMingwX64` / `linkReleaseExecutableLinuxArm64` / `linkReleaseExecutableLinuxX64`；
  部署：`gradlew deploy<服务器名>`（配置见 `platforms.json`；`abi` 决定架构、`mode` 选 `systemd` / `docker`，细节见 [doc/development-notes.md](doc/development-notes.md)）。
- **第三方 C 库只在升级时重建**：`thirdparty/build-libs.ps1`（Windows 上借 Kotlin/Native 自带的 clang + sysroot 交叉编译，不用额外装工具链）；改 `.def` 或换库后至少跑一次 `linkReleaseExecutable<目标>` 验证能链上。
- **没有自动化测试**（`commonTest` 只有依赖声明）；行为验证目前靠临时冒烟脚本（命令清单见 `doc/development-notes.md`）。
- KSP 聚合输出**必须带源文件**：`Dependencies(aggregating = true, *sourceFiles)`。只写 `aggregating = true` 会漏扫未改动文件，症状是「**编译通过但接口全 404**」。
- ⚠️ KSP 处理器**不要在首轮 `deferred`**（会让 KSP 重跑同一轮，而 sqlx4k 的 processor 没有去重标志，重复 `createNewFile` 直接抛 `FileAlreadyExistsException`）。
- **线上 / 共享环境的凭据一律不入库**（域名、公网 IP、SSH 与线上库口令，含本文档与 `doc/`）；用占位符。
  唯一例外是本机开发库的弱口令（换了环境即失效，方便冒烟脚本直接跑）。
- 提交前自检源码没被 `.gitignore` 误吞（历史踩过：`log/` 匹配到任意层级，整层源码不入库）。

## 10. 文件地图

```
src/commonMain/kotlin/moe/lolosia/web/
  Main.kt                        唯一入口：装 classpath → 读配置 → 起 Koin → 起 CIO 引擎
  configuration/AppConfigure.kt  Application.configureApplication(koin)：插件 / 中间件 / 路由 / DDNS
  configuration/AppModule.kt     Koin：appModule / serviceModule（手写逃生口）/ startAppKoin / platformModule(expect)
  generated/GeneratedRegistries.kt  6 个生成注册表的 expect 签名（actual 由 router-ksp 生成）
  api/package.kt                 对外 HTTP 客户端（Ktor Client；引擎按目标选 curl / winhttp），含 sse / ws / streamingRequest
  controller/                    LoginController / SseController / OssController / FileController / Classpath（运维探针）/ system/{User,Role,UserRole,Menu,Permission}
  database/                      Database（懒建连接池 + 后台重连 + MySqlAuthProbe 预检）+ repository/（@Repository 接口）+ model/（@Table 实体）
  dto/                           ApiResult（信封）/ RawJson / SystemDtos / LoginDtos / OssDtos / FileDtos / PermissionDtos
  interceptor/                   Traffic → Ctx → SSE → Auth → Envelope（顺序由 @Interceptor 生成）+ package.kt
  service/                       SessionManager / SessionMap / CacheManager / LoginService / SseService / WebpCache / ddns/ / oss/ / dav/（WebDAV 存储层）/ permission/（RBAC：PermissionRegistry · PermissionResolver · PermissionService）/ files/（在线文件管理器：FileBrowser · FileSigner · FileTypes · ThumbnailCache·Generator·Service）
  service/system/                UserService / RoleService / UserRoleService / QuerySupport
  settings/                      AppSettings（@Serializable）+ SettingsLoader（多级覆盖）
  util/captcha/                 自研图形验证码（点阵字 + 算式）
  util/classpath/               Classpath 接口 / ClasspathExt / ClasspathHolder / StaticClasspathConfig / FileStoreClasspath / package.kt
  util/crypto/BCrypt.kt         自研 bcrypt
  util/fs/FileStore.kt          文件存储抽象（native 实现 PosixFileStore）
  util/image/                   Image.kt（expect：bytes ↔ 像素、格式/尺寸/色彩类型探测、draw）+ ImageFormat / ImageColorType / ImageSize / ImageCodecException
  util/json/ koin/ ktor/        外部库类型扩展（JsonObject / Koin / Route·ApplicationCall；WebDAV 路由 `webDav` 在 ktor/）
  util/log/                     Log / LogLevel / Logger（自研）
  util/platform/Env.kt          envOrNull（expect）
  util/proc/Shell.kt            子进程
  util/router/                  注解 + 参数绑定（含 List<String> / List<UploadedFile>）+ multipart 解析 + RouterInjection
  util/session/                 Context / UserInfo / ApplicationCallContext / package.kt
  util/sse/                     EventSource / KtorEventSource / SseContent
  util/time/Time.kt             nowLocal（仅日志）/ nowEpochMs
src/commonMain/resources/       application.json（内嵌默认配置）/ permissions.yaml（默认权限注册表）/ static/home/ / hello.txt
src/nativeMain/kotlin/…/        ZipClasspath / DiskClasspath / EmbeddedClasspath(Image) / EmptyClasspath、
                                installPlatformClasspath(actual) / Env.kt(actual) / platformModule(actual) / fs/Mkdir·PosixFile*、
                                util/image/Image.kt(actual)、service/files/ThumbnailGenerator.kt(actual)、thirdparty/（C 库门面：RgbaImage + png / webp / jpeg / gif）
src/nativeInterop/cinterop/     第三方 C 库的绑定定义（zlib / png / webp / jpeg / gif 各一个 .def）
src/linuxMain/kotlin/…/          两个 linux 目标共享的平台实现（.linux.kt）：
                                api/ApiClient / classpath/EmbeddedClasspath / fs/Mkdir / proc/Shell
src/mingwX64Main/kotlin/…/      Windows 平台实现（.mingw.kt）：ApiClient / EmbeddedClasspath / Mkdir / Shell（无入口）
thirdparty/                     预编译 C 静态库与头文件（入库产物，按 mingwX64 / linuxArm64 / linuxX64 三份）+ build-libs.ps1（一次性重建脚本）
router-ksp/src/main/kotlin/…/   KSP 处理器：RouterProcessor（@Controller）/ InterceptorProcessor（@Interceptor）/
                                InjectionProcessor（@Repository·@Injectable·@Service）
buildSrc/src/main/kotlin/…/     Gradle 插件 moe.lolosia：task/（ViteJar / GenerateConstants / Deploy / GitArchive）、
                                NativeClasspath.kt（zip 合并 + windres/objcopy 嵌入 + 链接参数）、extension/
sql/                            建表脚本（9 表，含 sys_role.parent_id 与权限规则两表；无种子规则 —— 默认值在 permissions.yaml）+ migrate_rbac_groups.sql（历史库一次性迁移）
```

## 11. 深度材料

| 文档 | 内容 |
| --- | --- |
| [doc/lolosia-backend-notes.md](doc/lolosia-backend-notes.md) | **本派生项目**自身的差异与运行事实（更名 / WebDAV / 在线文件管理器 / 部署 / 网络实测）；上游模板那份 md 不动，差异都记在这里 |
| [doc/rbac-design.md](doc/rbac-design.md) | RBAC 权限体系：组 + 点分权限（`±通配`）+ 用户直授 + `@Permission` 的设计、判定口径与已实现落点 |
| [doc/media-index-design.md](doc/media-index-design.md) | 媒体库索引：文件身份（path / quick_id / sha256 三层）、标签体系与来源优先级、EXIF 等元数据抽取、评级与 NSFW 遮罩（直链签名折 flags）、embedding 与语义检索 |
| [doc/development-notes.md](doc/development-notes.md) | 构建 / 部署 / 冒烟 / 排障 / 环境依赖 / 工程形态（运行层面） |
| [doc/native-migration-plan.md](doc/native-migration-plan.md) | JVM → Kotlin/Native 迁移详设与实测记录（历史） |
