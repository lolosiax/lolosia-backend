# ktor-template → Kotlin/Native 迁移方案

> 本文档只做评估与规划，**不含代码改动**。对应分支：`native`。
> 结论先行：**可行，但不是"迁移"，基本等同于"保留 Web 骨架 + 重写全部基础设施"**。
> 状态：用户 **9 项决策**已确认（§12）；关键链路**已实测验证**（§2.1）：WSL 交叉编译 `linuxArm64` ✅、资源嵌入并在开发板读回 ✅。

---

## 1. 背景与目标

### 1.1 现状

当前项目是一个 **JVM 单体 Ktor 服务端模板**：

| 维度 | 现状 |
| --- | --- |
| 语言/运行时 | Kotlin 2.4.0 / JDK 21 |
| Web 引擎 | Ktor 3.4.2 + **Netty** |
| 启动方式 | `EngineMain` + `resources/application.yaml` |
| 路由 | **ktorfitx** 注解控制器（KSP 编译期生成） |
| DI | Koin 4.2.2（`@Service` 扫描 + 自研 Gradle 任务生成注册代码） |
| ORM | **Ebean 19.4.0** + 项目内 `:orm` querybean 模块 + kapt 字节码增强 |
| 数据库 | MariaDB（JDBC `mariadb-java-client`） |
| 序列化 | kotlinx.serialization（主）+ Jackson（`JsonMapper.toBundle` 映射） |
| 日志 | Logback + SLF4J + 自研 `%clr` 转换器 |
| 其他 | spring-security-crypto(BCrypt)、hutool 图形验证码、webp-imageio、commons-collections4、snakeyaml、Koog(AI) |
| 打包 | `bootJar`（Spring Boot 插件仅用于打 fat jar）+ vite 前端并入 |

### 1.2 目标

- 迁移到 **Kotlin/Native 目标**（`linuxArm64` / `mingwX64` 等），产出无需 JVM 的原生可执行文件。
- **只保留基础 Web 能力**：路由、JSON、会话/鉴权、SSE、静态资源。
- 数据库能力保留，但 **Ebean 必须移除**（依赖 JVM 反射 + 字节码增强 + JDBC），替换为 KMP 生态的库。
- 编译不过的功能（大量依赖 `java.*` / JVM 反射的部分）**允许直接删除**。

### 1.3 参照资料

- 参考技能 `create-project` 中的后端模板结构（本项目即其 backend 的 Ktor 变体）。
- 参考技能 `浏览器`：可用内置浏览器做端到端验证（登录、SSE、静态资源），但本次改造以 **HTTP 黑盒测试 / MOCK 测试** 为主即可。
- **关键参考实现（本机已有可运行代码）**：`F:\Develop\Games\VRCBitmapLed\BitmapLedJava\installer` —— 一个 Kotlin/Native `mingwX64` 可执行程序，演示了：
  - 用 `.rc` + `windres` 把任意二进制嵌进 PE（§3.1）；
  - `FindResourceW` 取指针 + **内存 zip 读取器** `ZipCollection`（`zip.kt`）—— 这正是 §3.2 `classpath.zip` 的直接参考；
  - JVM 侧同资源复用的 `WinResourceClassLoader`。
  （`F:\Ventures\Kotlin\patent-analysis\installer` 是同一份代码的副本。）

---

## 2. 可行性结论（TL;DR）

**可行，但工作量接近重写。** 原因：

1. **Ktor Server 本身支持 Kotlin/Native**，官方文档明确支持，但有硬限制：只能用 **CIO 引擎**、只能 **`embeddedServer`**、**无 HTTPS**。
2. 项目里 **约 90% 的基础设施依赖 JVM 专有 API 或 JVM 反射**：
   - Ebean（反射 + 字节码增强 + JDBC）、`:orm` 模块（Java 源码）
   - Jackson、snakeyaml、commons-collections4
   - `java.util.UUID`、`java.sql.Timestamp`、`java.time.*`、`java.io.File`、`java.nio.file.*`、`java.security.MessageDigest`、`java.util.Base64`
   - `java.lang.reflect.Proxy`（`LazyDatabase`）、`kotlin.reflect.full.*`（`createInstance` / `memberProperties` / `supertypes` / `javaField`）
   - `javax.imageio` / `java.awt`（验证码、WebP）
   - SLF4J / Logback
3. **Kotlin/Native 反射极弱**（`kotlin.reflect` 在 Native 上只有极少数能力，没有 `createInstance`、`memberProperties`、`supertypes`、`javaField`）。项目中大量"用反射做通用映射"的代码（`Bundle` ↔ 实体、`query<T>()` / `createModel<T>()`、配置系统）**必须改写为编译期生成或手写**。

结论：**核心 Web 骨架（Ktor + Koin + kotlinx.serialization + SSE + 拦截器链）可以保留；ORM / 配置 / 工具库 / AI / 图片处理整层替换或删除。**

### 2.1 环境实测结论（2026-09-21 实测，非推断）

**结论：开发 → 构建 → 部署三段链路已全部打通，且比原设想更省事（不需要 Docker）。**

| 环节 | 实测结果 |
| --- | --- |
| WSL | Ubuntu 24.04.4 LTS，**x86_64**，内核 6.6.114.1-microsoft-standard-WSL2；已装 git/curl/gcc/make/python3/gpg；缺 JDK（可 `apt` 装，WSL root 免密） |
| **交叉编译** | ✅ **WSL(x86_64) 可直接交叉编译出 `linuxArm64` 产物**，无需 Docker、也无需在开发板上编译。实测产物：`ELF 64-bit LSB executable, ARM aarch64, for GNU/Linux 4.9.156` |
| **资源嵌入** | ✅ **已端到端验证**：WSL 内 `objcopy` 把资源转 `.o` → cinterop + linker 链入 → 上传到开发板**真实运行并成功读回资源**（详见 §3.1） |
| 开发板 | 主机名 `lckfb`，**aarch64**，Ubuntu 22.04.5 LTS，内核 6.1.141，**4 核 / 2GB RAM**，磁盘 12G（余 5.4G），glibc 2.35；有 `gcc`/`make`/`java`/`objcopy`/`zip`/`unzip`，**无 Docker** |
| 访问方式 | 本机经 `quic-socket` QUIC 隧道：`localhost:6023 → 开发板:22`（SSH）、`localhost:3307 → 开发板:3306`（MariaDB）；服务端 `<board-host>`（Cloudflare，443）。实测握手成功、**RTT ≈ 29ms** |
| 数据库 | 开发板上 **MariaDB 10.6.23**（`mariadb.service` active）；真实业务库是 **`lolosia-backend`**（`ktor-template` 库是空的），表含 `sys_user` / `sys_role` / `sys_user_roles` / `sys_sessions` / `sys_cache` / `sys_logs` / `oss_user_file` / `view_user_role` |
| 现有部署 | `/srv/ktor-template/` 下是 **JVM fat jar（67.6MB）**，由 `ktor-template.service` 以 `-Xms128m -Xmx512m` 跑在 **7129** 端口；同机另有 nginx / cloudflared / quic-socket-server |
| 密码哈希 | 库中实际值为标准 bcrypt **`$2a$10$...`**（结论见 §6.1） |

> 实测脚本与产物：交叉编译 `hello_arm64.kexe`；资源嵌入 `embed_arm64.kexe`（709,168 字节，含嵌入资源，开发板上输出 `运行时读回嵌入资源 (len=88)`）。

---

## 3. Kotlin/Native 的硬性约束（决定一切）

| 约束 | 影响 |
| --- | --- |
| 只有 **CIO** 引擎，且必须 `embeddedServer` | 移除 Netty；移除 `EngineMain` + `application.yaml` 的启动方式，改为代码内配置 |
| **无 HTTPS**（需反代） | TLS 必须由 Nginx / Caddy 终止 |
| **无 `java.*` 生态** | 上述所有 JVM API 全部要替换 |
| **反射受限** | 所有反射式通用映射改为编译期代码生成（KSP）或手写 |
| **无 kapt** | Ebean 增强、`kotlin-querybean-generator` 全部不可用 |
| **无内置 classpath 资源 API** | `staticResources()` / `environment.classLoader.getResource()` 不可用 —— **但 classpath 本质就是个 zip**：自造 `classpath.zip` 嵌入二进制 + `Classpath` 中间层即可完全等价（见 §3.2，底层嵌入机制见 §3.1） |
| **只有 KMP 库可用** | 所有依赖必须是 multiplatform 且发布了 native 产物 |
| **不能从 Windows 直接交叉编译 Linux** | Windows 宿主只能编 `mingwX64`；`linuxArm64`/`linuxX64` 需 Linux 宿主 —— **已实测：用本机 WSL 即可，无需 Docker**（见 §2.1 / §7.1） |
| **KSP2 需按目标声明处理器** | `ksp(...)` 的 catch-all 已废弃，需写 `add("kspLinuxArm64", ...)` 等 |

### 3.1 关于"资源加载"：Native 下的等价方案（已在本机项目中验证）

> 更正：早期版本此处写作"无 classpath 资源加载"，表述不准确。准确说法是：Kotlin/Native **没有内置的资源 API**（没有 classloader、没有 `getResource`），但**可以把任意文件在构建期嵌入二进制、运行时读回**，等价于 classpath 资源。

**已验证案例：`VRCBitmapLed/BitmapLedJava/installer`**（Kotlin/Native `mingwX64` 可执行程序；`F:\Ventures\Kotlin\patent-analysis\installer` 是同一份代码的副本）

| 环节 | 实现 | 位置 |
| --- | --- | --- |
| 声明资源 | RC 中自定义类型：`IDR_JAR1 JARFILE "installer.jar"`（`JARFILE` 是**自定义类型名**，非系统类型） | [installer.rc](file:///f:/Develop/Games/VRCBitmapLed/BitmapLedJava/installer/resources/installer.rc#L1-L9) |
| 编译资源 | 调 Kotlin/Native **自带** mingw 工具链的 `windres installer.rc -O coff -o x.res`；工具链在 `~/.konan/dependencies/msys2-mingw-w64-x86_64-2/bin`，**无需另装 MinGW** | [build.gradle.kts](file:///f:/Develop/Games/VRCBitmapLed/BitmapLedJava/installer/build.gradle.kts#L84-L111) |
| 链接进二进制 | `linkTaskProvider.dependsOn(windresTask)` + `linkerOpts(outFile.toString())` | 同上 |
| 构建期灌入产物 | `copyInstallerJar` 任务把 jar 复制到 `resources/installer.jar`（角色相当于本项目的 `viteJar`） | [build.gradle.kts](file:///f:/Develop/Games/VRCBitmapLed/BitmapLedJava/installer/build.gradle.kts#L113-L120) |
| 运行时读回 | `FindResourceW(null, name, "JARFILE")` → `SizeofResource` → `LoadResource` → `LockResource`，得到 `COpaquePointer + size` | [jar.kt](file:///f:/Develop/Games/VRCBitmapLed/BitmapLedJava/installer/src/nativeMain/kotlin/top/lolosia/installer/jar.kt#L34-L51) |
| 双目标共用同一资源 | JVM 侧自定义 `WinResourceClassLoader`（`URLClassLoader` 子类 + `WinResourcesJarURLConnection`）直接把同一份 RC 资源当 classpath 用 | `src/javaMain/java/moe/lolosia/loader/WinResourceClassLoader.java` |

要点：

1. **资源类型名任意**（`JARFILE` 只是自己起的名字），所以能塞任意二进制：前端 dist、模板、SQL 脚本、证书、另一个 jar。
2. 该实现是 **Windows 专用**（依赖 `platform.windows.*` 与 PE 资源段）。
3. 同项目里的 `-include-binary` 是**另一件事**——它把 `.a` 静态库（minizip 系列）交给 linker，**不是**通用数据嵌入接口。

**跨平台等价机制**（`linuxArm64` 没有 RC 资源）：

| 机制 | 适用 | 说明 | 状态 |
| --- | --- | --- | --- |
| RC + `windres` + `linkerOpts(.res)` | Windows | 本机 `VRCBitmapLed` 已有可运行案例 | 高（已验证） |
| **`objcopy -I binary -O elf64-littleaarch64 -B aarch64 res.bin res.o` + cinterop + linker** | **Linux** | **已端到端实测**（见下方配方） | **高（已在开发板实测）** |
| 汇编 `.incbin` | 任意（需汇编器） | 手写 `.S/.asm` 内联数据并导出符号，再 cinterop 引用 | 中 |
| 构建期生成 Kotlin 源码（`ByteArray` / Base64 字面量） | 任意 | 零工具链依赖、最可移植；大文件会拖慢编译并膨胀源码 | 高 |
| 磁盘目录 + `staticFiles()` | 任意 | 最简单；前端可独立更新，不必重编二进制 | 高 |

**linuxArm64 实测配方（2026-09-21，WSL 构建 → 开发板运行通过）**

```bash
# 1) 任意资源 -> 目标文件（符号名由文件名推导：_binary_<name>_start/_end/_size）
aarch64-linux-gnu-objcopy -I binary -O elf64-littleaarch64 -B aarch64 hello.txt hello_res.o

# 2) C 包装：把符号暴露成普通函数，避免在 Kotlin 里做指针减法
#    res_wrap.c:  extern const unsigned char _binary_hello_txt_start[];  ... 
#                 const unsigned char *res_ptr(void);  long res_len(void);
aarch64-linux-gnu-gcc -c -O2 res_wrap.c -o res_wrap.o
aarch64-linux-gnu-ar rcs libres.a hello_res.o res_wrap.o

# 3) cinterop 生成 klib（注意：-o 产出的是「文件」不是目录）
cinterop -def res.def -o res.klib -target linux_arm64 -compiler-option -I<header 所在目录>
#    res.def:  headers = res_wrap.h
#              package = probe.res

# 4) 编译并链接
kotlinc-native -target linux_arm64 -o embed_arm64 \
    -library res.klib -linker-option libres.a main.kt
```

踩坑记录：
1. cinterop 的 `includeDirs` 写在 `.def` 里**未生效**，必须用命令行 `-compiler-option -I<dir>`。
2. cinterop 的 `-o res.klib` 产出是**文件**（Kotlin 2.x），不是目录，判断存在性别用 `-d`。
3. `objcopy` 的符号名跟**输入路径**走（`hello.txt` → `_binary_hello_txt_start`；带目录会变成 `_binary_dir_file_txt_start`），建议先 `cd` 到资源目录再用纯文件名。
4. 交叉工具链是 `binutils-aarch64-linux-gnu`（`objcopy`/`ar`）+ `gcc-aarch64-linux-gnu`，`apt` 直接装。

**本项目做法**（对应决策 7 + 8）：底层嵌入机制用上面这套，**上层统一收敛到一个 `classpath.zip`**，见 §3.2。

### 3.2 「classpath.zip」：自造一个可执行文件内的 classpath

> 对应决策 8。**classpath 本质就是一个 zip**，所以不必"放弃 classpath 模型"，只需把它从"JVM 自动提供的路径"换成"我们自己打包并嵌入的一个 zip"。

**思路**（参考本机 `VRCBitmapLed/BitmapLedJava/installer`：RC 里塞 `installer.jar`，运行时 `FindResourceW` 取指针 → `ZipCollection.open(ptr, size)` 直接读内存 zip，**全程不落盘**）：

```
构建期：资源目录 ──zip──▶ classpath.zip ──嵌入──▶ 可执行文件
                                                 ├─ Windows: PE 资源段（windres + linkerOpts）
                                                 └─ Linux:   .rodata（objcopy   + linkerOpts）
运行期：Classpath.read("static/home/index.html")  ← 直接读二进制映像内的 zip 条目
```

**关键约定：`classpath.zip` 内部布局与现在的 jar 布局保持一致**（`static/home/**`、`sql/**`、`templates/**` …）。这样业务代码里的资源路径**一个字都不用改**，跨项目复制也几乎无摩擦。

**运行期接口（放 `commonMain`，两端共用）**

```kotlin
interface Classpath {
    fun read(path: String): ByteArray?        // 不存在返回 null
    fun exists(path: String): Boolean
    fun list(prefix: String): Sequence<String>
    companion object {
        fun open(image: COpaquePointer, size: Int): Classpath   // 指向二进制内的零拷贝视图
    }
}
```

- 启动时只解析一次 zip 的 **Central Directory**，建 `Map<String, Entry(offset, size, method)>` 索引；
- 只在读取某个条目时才解压 → 内存占用 = 索引 + 单条目，**不会全量展开**；
- 数据源是二进制映像内的指针（Windows `LockResource` / Linux `_binary_*_start`），**零拷贝**。

**三种实现选择**

| 方案 | 说明 | 评价 |
| --- | --- | --- |
| **A. `platform.zlib` + 自写 zip 解析**（推荐） | zip 的 Central Directory 是定长结构，自己解析不足 200 行；deflate 用 Kotlin/Native **自带**的 `platform.zlib`（已确认 `linux_arm64` 与 `mingw_x64` 都存在 `org.jetbrains.kotlin.native.platform.zlib`） | **零第三方依赖**、不带 `.a` 静态库、两目标写法一致 |
| B. minizip（installer 的做法） | cinterop + 5 个 `.a`（minizip / bzip2 / lzma / zstd / z-ng） | 功能全但我们只用 deflate，依赖过重 |
| C. `zip -0`（store 模式） | 不压缩 → 连 inflate 都不需要，纯偏移切片，实现最简单 | 二进制变大；前端产物本就不大，**可作 A 的兜底** |

**上层适配（真正落实"classpath 全走这个中间层"）**

| 现在 | 改法 |
| --- | --- |
| `staticResources("/home", "static/home")` | 自研 `Route.staticClasspath(classpath, "/home", "static/home")`：路径解析 + 按扩展名判 Content-Type + `fallback {}` |
| `environment.classLoader.getResource(path)` | `classpath.read(path)` |
| `index.openStream()` | `classpath.read(path)!!`（内存读，不再需要 `withContext(Dispatchers.IO)`） |
| Webjars | 删；若确需 jquery 之类，当普通静态资源打进 `classpath.zip` |
| 后续新增（模板/SQL/配置/字体） | 统一走 `classpath.read(...)`，不再有"读文件还是读资源"的分叉 |

**开发期回退**：同一接口提供第二个实现 `DiskClasspath(root)`（直接读目录）。开发时优先读磁盘，改前端不必重编二进制；切换靠配置。

**打包接线**：在现有 `viteJar` 之后追加一个 `zipClasspath`（Gradle `Zip`，`compressionMethod` 选 `DEFLATED` 或 `STORED`），产出 `classpath.zip` 后接 §3.1 的嵌入流程（Windows 走 `.rc`，Linux 走 `objcopy`）。

---

## 4. 依赖评估总表

### 4.1 可保留 / 可直接替换

| 依赖 | 用途 | Native | 处置 |
| --- | --- | --- | --- |
| `ktor-server-core` | 核心 | ✅ | 保留 |
| `ktor-server-cio` | CIO 引擎 | ✅ | **替换 Netty** |
| `ktor-server-content-negotiation` | 内容协商 | ✅ | 保留 |
| `ktor-serialization-kotlinx-json` | JSON | ✅ | 保留 |
| `ktor-server-cors` | CORS | ✅（应可用） | 保留，实测确认 |
| `ktor-server-conditional-headers` | 304 协商 | ✅（官方标注） | 保留 |
| `ktor-server-forwarded-header` | 反代头 | ✅（官方标注） | 保留 |
| `ktor-server-default-headers` | 默认头 | ✅（应可用） | 保留，实测确认 |
| `ktor-server-status-pages` | 全局异常 | ✅（应可用） | 保留，实测确认 |
| `ktor-server-auth` | 认证框架 | ⚠️ 需确认 | 当前只装了 `basic` 占位，**建议直接删掉**，认证走自研拦截器 |
| `ktor-server-test-host` | 测试 | ✅（nativeTest） | 保留 |
| `kotlinx.serialization` | 序列化 | ✅ | 保留（**成为唯一序列化方案**） |
| `koin-ktor` / `koin-core` | DI | ✅（Koin 全平台支持，`koin-ktor` 已 KMP 化） | 保留 |
| ktorfitx（`server-core` / `server-annotation` / `server-ksp`） | 注解式路由 | ❌ **已查证：服务端无 native 产物**（`server-core` 的 POM 直接依赖 `ktor-server-core-jvm`，Maven 上也没有 `server-*-linuxarm64/mingwx64`；只有客户端 `multiplatform-*` 才带 native 变体） | **删除**，改用**自研路由插件**（决策 9，见 §5.4）：注解同名同义，只换 import |
| `io.github.smyrgeorge:sqlx4k` | 数据访问 | ✅ **已查证**：Maven 上有 `sqlx4k-linuxarm64` / `sqlx4k-linuxx64` / `sqlx4k-mingwx64`，以及 `sqlx4k-mysql-*` 同名变体 | **新增**，见 §6 方案 A |
| KSP | 代码生成 | ✅（支持 native 目标） | 保留 |
| `kotlinx.coroutines` | 协程 | ✅ | 保留 |
| `kotlin("test")` | 测试 | ✅ | 保留 |
| `kotlin.uuid.Uuid`（stdlib） | UUID | ✅（Kotlin 2.4 起 Stable） | **替换 `java.util.UUID`** |
| `kotlin.io.encoding.Base64` | Base64 | ✅ | **替换 `java.util.Base64`** |
| okio / kotlinx-io | 文件 IO、哈希 | ✅ | **替换 `java.io.File` / `java.nio.file` / `MessageDigest`** |
| kotlinx-datetime | 日期时间 | ✅ | **替换 `java.time` / `java.sql.Timestamp`** |

### 4.2 必须删除（Native 不可用）

| 依赖 | 不可用原因 | 处置 |
| --- | --- | --- |
| `ktor-server-netty` | Netty 仅 JVM | 删，换 CIO |
| `ktor-server-webjars` + `org.webjars:jquery` | 官方标注 **Native ✗**（依赖 classpath 资源 API） | 删；静态资源改走自造 `classpath.zip`（§3.2） |
| `ktor-server-call-logging` | 官方标注 **Native ✗**（SLF4J） | 删（当前仅声明未安装，影响小） |
| `ktor-server-compression` | 官方标注 **Native ✗**（java.util.zip） | 删；如需压缩另找 KMP gzip 库或交给反代 |
| `ktor-server-config-yaml` | YAML 配置面向 JVM（EngineMain） | 删，改代码内配置 |
| `ktor-server-serialization-jackson` | Jackson 仅 JVM | 删 |
| `io.ebean:ebean` / `ebean-platform-mariadb` / `kotlin-querybean-generator` + `io.ebean` Gradle 插件 | JVM 反射 + 字节码增强 + kapt | **删，整体换库（见 §6）** |
| `org.mariadb.jdbc:mariadb-java-client` | JDBC | 删，换 KMP 驱动 |
| `:orm` 模块（Java + typequery） | Java + Ebean | 删该模块 |
| `kotlin-reflect`（`kotlin("reflect")`） | Native 反射极弱 | 删，改编译期生成/手写 |
| `com.fasterxml.jackson.*`（databind/core/annotations） | JVM | 删，全改 kotlinx.serialization |
| `org.apache.commons:commons-collections4` | JVM | 删（仅配置系统用 `DualHashBidiMap`） |
| `org.yaml:snakeyaml` | JVM | 删，改 kotlinx.serialization 或简易解析 |
| `spring-security-crypto`（BCrypt） | JVM | 删，换 KMP BCrypt 实现（见 §9） |
| `commons-logging` | JVM | 删 |
| `cn.hutool:hutool-captcha` | 依赖 `java.awt` / Swing | **删图形验证码**（或自绘 PNG） |
| `org.sejda.imageio:webp-imageio` | `javax.imageio` + JNI | **删 WebP 转换** |
| `ch.qos.logback:logback-classic` + SLF4J | JVM | 删，换 KMP 日志（`log4k` / `kotlin-logging` native / 自写） |
| `ai.koog:koog-ktor` | 官方定位 "JVM 生态"，KMP 只覆盖 JS/Wasm/Android/iOS，**不含 linux native** | **删 Koog / AI 模块** |
| `org.springframework.boot` Gradle 插件 | 仅用于 `bootJar` | 删，改产 native 可执行文件 |
| `io.insert-koin:koin-logger-slf4j` | SLF4J | 删，换 `printLogger()` |

---

## 5. 代码分层改造清单

### 5.1 可以基本不动

| 文件 | 说明 |
| --- | --- |
| [annotation/](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/annotation) 全部 | 纯注解声明 |
| [ApiResult.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/dto/response/ApiResult.kt) | 纯 kotlinx.serialization |
| [JsonUtils.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/dto/response/JsonUtils.kt) | `AnySerializer` + `toJsonObject`，纯 kotlinx |
| [TreeElementMap.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/bundle/TreeElementMap.kt) | 纯 Kotlin 集合 |
| [EventBus.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/event/EventBus.kt) 等 event 包 | 纯 Kotlin（`KClass` 仅作 map key，Native 可接受） |
| [CtxInterceptor.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/interceptor/CtxInterceptor.kt) | 纯 Ktor |
| [EnvelopeInterceptor.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/interceptor/EnvelopeInterceptor.kt) | 纯 Ktor + kotlinx |
| [SseInterceptor.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/interceptor/SseInterceptor.kt) | 纯 Ktor（`ByteWriteChannel`）；需实测 CIO 下 flush 行为 |
| [SerializationConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/SerializationConfig.kt) | 去掉 Jackson 相关即可 |
| [AuthInterceptor.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/interceptor/AuthInterceptor.kt) | 逻辑可保留，依赖 `SessionManager` 改造 |
| `sql/*.sql` 建表脚本 | 库仍是 MariaDB，脚本可继续用（视图 `view_user_role` 也保留） |

### 5.2 需要改写

| 文件 | 问题 | 改法 |
| --- | --- | --- |
| [KtorApplication.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/KtorApplication.kt) | `EngineMain` | 改 `embeddedServer(CIO, port)`，显式调用 `configureApplication()` |
| [SConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/SConfig.kt) | snakeyaml + `java.io` + `java.nio.file` | 重写：配置改用 kotlinx.serialization(JSON) 或环境变量/常量 |
| `util/config/*`（[NodeChainConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/config/NodeChainConfig.kt)、[ChainConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/config/ChainConfig.kt) 等） | snakeyaml + `kotlin.reflect.full.*` + `javaField` + `enumConstants` + commons-collections4 | **整体重写或删除**，改为简单配置数据类 |
| [ServerConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/ServerConfig.kt) / [HostConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/HostConfig.kt) / [OpenAiConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/OpenAiConfig.kt) / [ParentConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/ParentConfig.kt) | 继承配置链 + 反射 | 改为 `@Serializable` 数据类 |
| [DatabaseConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/DatabaseConfig.kt) | Ebean `DataSourceBuilder` | 改为 sqlx4k `MySQL(...)` 连接池（见 §6） |
| `util/ebean/*`（[AbstractModel](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/ebean/AbstractModel.kt)、[LazyDatabase](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/ebean/LazyDatabase.kt)、[async.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/ebean/async.kt)、[context.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/ebean/context.kt)） | Ebean + `java.lang.reflect.Proxy` + `kotlin.reflect.full.createInstance/createType` | **整层删除**，改为 sqlx4k Repository |
| [Context.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/session/Context.kt) / [ApplicationCallContext.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/session/ApplicationCallContext.kt) / [UserInfo.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/session/UserInfo.kt) / [SSEContext.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/session/SSEContext.kt) | `java.util.UUID`、Ebean query bean 扩展（`ctx.query<T>()`） | `Uuid`；DB 访问改走 Repository |
| [SessionManager.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/manager/SessionManager.kt) | Jackson `JsonMapper`、`UUID`、Ebean query、`Date` | kotlinx.serialization 序列化会话 JSON；`Uuid`；sqlx4k Repository |
| [CacheManager.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/manager/CacheManager.kt) | Ebean query、`java.sql.Timestamp`、`Duration` | sqlx4k Repository + kotlinx-datetime |
| [EventBusSSEManager.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/manager/EventBusSSEManager.kt) / [EventSourceManager.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/manager/EventSourceManager.kt) | `java.util.UUID` | 改 `Uuid` |
| [LoginService.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/service/LoginService.kt) | hutool 验证码 + `java.awt.Color` + `Calculator`、BCrypt、Jackson、Ebean | **删验证码**；BCrypt 换 KMP 实现；JsonMapper 改手写映射 |
| [UserService.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/service/system/UserService.kt) | Ebean query/`PagedList`/`asMapKey`、`java.nio.file.Path`、BCrypt | 重写为 sqlx4k 查询 + okio 路径 |
| [RoleService.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/service/system/RoleService.kt) / [UserRoleService.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/service/system/UserRoleService.kt) | Ebean query + Jackson | 同上 |
| [UserFileService.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/service/oss/UserFileService.kt) | `java.nio.file.Files`、`MessageDigest`、`java.io.InputStream` | okio 文件 IO + okio 哈希 |
| [ResourcesService.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/service/ResourcesService.kt) | `java.io.File`、`javax.imageio.ImageIO`（WebP）、`MessageDigest` | **删 WebP 转换**；文件读写改 okio |
| [HomeController.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/controller/HomeController.kt) | **`staticResources()`（classpath）**、`environment.classLoader.getResource()` | 改为 `classpath.read(...)` + 自研 `staticClasspath(...)` 路由（§3.2）；SPA fallback 用 `fallback {}` |
| [ResourcesController.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/controller/ResourcesController.kt) | `java.io.File` | okio `Path` |
| [OssController.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/controller/OssController.kt) / [UserController.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/controller/system/UserController.kt) | `java.io.File`、Jackson `JsonMapper` | okio + 手写映射 |
| [RoleController.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/controller/system/RoleController.kt) | Jackson | 手写映射 |
| [model/\*](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/model) 全部实体 | JPA/Ebean 注解（`jakarta.persistence`、`io.ebean.annotation`）、`java.sql.Timestamp`、`UUID`、`@JvmField` | 改为 sqlx4k `@Table`/`@Id`/`@Column` 的 `data class`，时间用 kotlinx-datetime |
| [logging/](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/logging)（ClrConverter / WhitespaceThrowableProxyConverter / CoreLogging） | Logback API | **整包删除**，换 KMP 日志 |
| [util/package.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/package.kt) | `LoggerFactory`、`Throwable().stackTrace`、`java.io.File`、`ZonedDateTime`、`respondFile` | 换 KMP logger / okio / kotlinx-datetime；`collectFileItems` 逻辑可保留 |
| [util/kotlin/package.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/kotlin/package.kt) | commons-collections4、SLF4J、`java.sql.Timestamp`、`java.time`、`String.format` | 大裁剪（多数函数在本项目未被使用） |
| [util/bundle/package.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/bundle/package.kt) | `ObjectMapper.toBundle()`、`jvmName` | 删 Jackson 扩展，改手写/编译期映射；`jvmName` 改 `qualifiedName` |
| [util/property/\*](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/util/property) | 使用 `kotlin.reflect` 属性委托体系 | 检查后裁剪：配置系统重写后此包多半可删 |
| [config/web/HttpConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/HttpConfig.kt) | `Compression` | 删 `install(Compression)` |
| [config/web/SecurityConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/SecurityConfig.kt) | `ktor-server-auth` basic 占位 | 建议整文件删除 |
| [config/web/WebjarsConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/WebjarsConfig.kt) | Webjars | **整文件删除** |
| [config/web/KoogConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/KoogConfig.kt) | Koog | **整文件删除** |
| [config/web/DiConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/DiConfig.kt) | `JsonMapper`、`BCryptPasswordEncoder`、`slf4jLogger` | 换 KMP 对应实现 |
| [config/web/ErrorConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/ErrorConfig.kt) | logger | 换 KMP logger |
| [config/web/RoutingConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/RoutingConfig.kt) | 依赖生成的 `generateRoutes`/`manualRoutes` | 保留；改为并列挂载 **`generatedRoutes`（自研路由插件生成）+ `manualRoutes`（现有 `@Route`）**，见 §5.4 |
| [ServerTest.kt](file:///f:/Develop/Kotlin/ktor-template/src/test/kotlin/ServerTest.kt) | 引用了不存在的 `/greet` 接口（当前应已失败） | 重写为真实接口的 nativeTest |
| `buildSrc/`（LolosiaPlugin 等） | 跑在 Gradle(JVM) 上，**本身不受 Native 限制** | 保留 `generateDiModule` / `generateRouteModule`（纯文本扫描，可用）；`spring-boot`/`bootJar`/`viteJar`/`deploy` 相关需按新产物调整 |

### 5.3 直接删除

- `orm/` 模块（Java + Ebean typequery）
- `logging/` 包
- `config/web/WebjarsConfig.kt`、`KoogConfig.kt`、`SecurityConfig.kt`
- `util/config/`、`util/property/`、`util/ebean/`（配合重写）
- `ktor-template.service`、`docker_shell.sh`（部署方式变化，需重写）
- 前端 `viteJar` 并入 fat jar 的能力 —— **不是废弃，而是换形态**：改为先打成 `classpath.zip` 再嵌入二进制（见 §3.2），`static/` 布局保持不变

### 5.4 自研路由插件（替代 ktorfitx，对应决策 9）

**为什么自己造**：ktorfitx 服务端只有 JVM 产物（§4.1 已查证），而它的用法极轻——本项目实际只用到 8 个注解。自己实现一个"刚好够用"的 KSP 处理器，比改写成手写路由更划算，而且**控制器代码几乎不用动**。

**现状盘点：本项目实际用到的 ktorfitx 注解**（全量，共 8 个；涉及 7 个 `@Controller`）

| 注解 | 用途 | 出现位置 |
| --- | --- | --- |
| `@Controller(prefix)` | 标在 `object` 上，路由前缀 | 全部 7 个控制器 |
| `@GET(path)` / `@POST(path)` | HTTP 方法 + 路径模板（含 `{id}`） | 同上 |
| `@Body` | JSON 请求体 → `@Serializable` 参数 | User/Role/Login/Oss/Home/EventBusSSE |
| `@Path(name)` | URL 路径参数 | User/Home |
| `@Query(name)` | Query 参数 | Home |
| `@Attribute(name)` | 从 Ktor `call.attributes` 注入（主要是 `ctx`） | User/Role/Login/Oss/EventBusSSE |

> 也就是说：**只要把这 8 个注解复刻出来，现有控制器代码 100% 不用改方法体。**

**设计**

```
lolosia-ktor-router/
├── router-annotations   （KMP commonMain，纯注解、零依赖）
│     @Controller @GET @POST @PUT @DELETE @PATCH
│     @Body @Path @Query @QueryName @Header @Attribute
│     @Form @File            ← 表单 / 文件（用户点名要的）
├── router-ksp           （JVM，编译期运行）
│     扫描 @Controller object → 生成路由注册代码
└── router-runtime       （KMP commonMain/nativeMain）
      参数绑定小工具（path/query/header/attribute 取值与类型转换、multipart 流式读取）
```

**1) 注解：同名同义，只是换包**

新注解放在 `moe.lolosia.web.util.router`（本 web 工程根包 `moe.lolosia.web` 之下，见 §13.2-5），**类名与 ktorfitx 完全一致**。于是跨项目/跨版本迁移的改动量 = 把

```kotlin
import cn.ktorfitx.server.annotation.Controller
```

换成

```kotlin
import moe.lolosia.web.util.router.Controller
```

本仓库共 30 行 import（前面已统计），**方法体一行不动**。这正是"跨项目复制代码时改动尽可能小"的落点。

**2) KSP 处理器：编译期生成注册代码**

对每个 `@Controller` 生成一份注册函数，例如 [UserController.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/controller/system/UserController.kt#L45-L52) 的 `@POST("/user/list")` 生成：

```kotlin
// build/generated/ksp/<target>/kotlin/.../UserControllerRoutes.kt
fun Routing.userControllerRoutes() {
    post("/api/user/list") {
        val ctx = call.attributes[ContextKey]
        val param = call.receive<ListFn>()
        call.respond(UserController.list(ctx, param))
    }
}
```

再汇总一个 `generatedRoutes(routing)`，在 `RoutingConfig` 里与现有的 `manualRoutes` 并列调用。

**生成规则（只做需要的，不做通用的）**

| 参数形式 | 生成代码 |
| --- | --- |
| `@Body x: T`（`T` 是 `@Serializable`） | `call.receive<T>()`（kotlinx.serialization，已有） |
| `@Path("id") id: String/Int/Long/Uuid/枚举` | `call.parameters["id"]` + 按目标类型转换；不可空则缺失时报 400 |
| `@Query("x") x: T` | `call.request.queryParameters["x"]` + 转换 |
| `@Header("x") x: String` | `call.request.headers["x"]` |
| `@Attribute("ctx") ctx: Context` | `call.attributes[ContextKey]`（属性 key 表在 runtime 里注册） |
| `@Form("f") f: String` / `@File("f") file: UploadedFile` | `call.receiveMultipart()` 流式读取（复用现有 `collectFileItems` 的思路，见 [UserController.avatarUpload](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/controller/system/UserController.kt#L144-L156)） |
| 可空参数 | 缺失传 `null` |
| 有默认值的参数 | 缺失用默认值 |
| 返回值 | `call.respond(result)`；`suspend` 方法直接 `await` |

**明确不做**（超出范围就手写 `@Route`）：任意类型转换器、嵌套泛型参数绑定、文件上传以外的复杂 multipart 组合、内容协商的多格式（只做 JSON）。

**3) 与现有 `@Route` 机制的关系**

- `@Route` **原样保留**（纯文本扫描的 `GenerateRouteModuleTask` 不动）——继续承载三类场景：`staticClasspath` 静态资源、multipart 流式、SSE 等需要直接操作 `call` 的路由；
- 新插件的 `generatedRoutes` 与老的 `manualRoutes` **并列注册**，互不干扰；
- 换句话说：**注解路由覆盖常规 CRUD，`@Route` 覆盖长尾**。两者都在 `RoutingConfig` 里挂一次。

**4) 用 KSP 还是沿用"纯文本扫描"**

现有 `GenerateRouteModuleTask` 是纯文本扫描，够用是因为它只需要 `类名 + 方法名`。但路由插件要生成 `call.receive<ListFn>()`，**必须解析参数类型**（拿到类型名与可空性），文本扫描会非常脆弱。因此**用 KSP**——正好把 §9-2 的 "KSP2 + KMP 多目标接线" 风险一并在 P0 解决。

**5) 跨项目复用的打包方式**

把三块做成**独立的 Gradle 插件 + 库**（如 `lolosia-ktor-router`），其他项目只要：

```kotlin
plugins { id("moe.lolosia.ktor.router") }
dependencies { implementation("moe.lolosia:router-annotations") }
```

即可获得同款注解路由。**这是本方案里唯一值得独立发布的组件**，也为后续其它 Ktor 项目复用铺路。

---

## 6. 数据库方案（重点）

Ebean 依赖 **JVM 反射 + 运行时字节码增强 + JDBC**，在 Kotlin/Native 上完全不可用，且 Native **没有通用反射**，因此任何"运行时 ORM"都不可行，只能选 **编译期代码生成** 或 **手写 SQL**。

### 方案 A（推荐）：**sqlx4k + MariaDB/MySQL**

`io.github.smyrgeorge:sqlx4k` 是一个 **coroutine-first 的 KMP SQL 工具库**（非 ORM），native 侧基于 Rust `sqlx` 通过 FFI 实现，官方声明支持：

- 数据库：**PostgreSQL / MySQL / MariaDB / SQLite**
- 目标：`jvm`、`linuxX64`、`linuxArm64`、`mingwX64`、`macosArm64`、`iosArm64`、`androidNative*`

关键能力（与现有 Ebean 用法高度对应）：

| Ebean 现有用法 | sqlx4k 对应 |
| --- | --- |
| `@Entity` / `@Table` / `@Id` / `@Column` | `@Table` / `@Id` / `@Column`（`data class`） |
| QueryBean（`QSysUserEntity`） | `@Repository` 接口 + `@Query` 方法（KSP 编译期生成实现，**语法校验在编译期**） |
| `findOne` / `findList` / `findPagedList` / `exists` | 生成方法 `findOneBy…` / `findAll` / `countAll` / `existsBy…` |
| `insert` / `update` / `delete` / `saveAll` | `CrudRepository.insert/update/delete/save` |
| `transaction { }` | `db.transaction { }` / `TransactionContext`（协程上下文传播） |
| `DataSourceConfig`（连接池） | `Driver.Pool.Options`（`maxConnections` 等） |
| Ebean DDL 迁移 | `db.migrate(path = "./db/migrations")` |

**优点**：可继续使用现有 MariaDB 库与 `sql/*.sql` 脚本（含 `view_user_role` 视图），数据层改动语义清晰，编译期即校验 SQL。

**限制/注意**：

- MySQL **不支持** `batchInsert` / `batchUpdate`（无 `RETURNING`），批量写入需拆成多次 execute 或手写多值 INSERT。
- `@Query` 的语法校验基于 JSqlParser（ANSI 子集），个别 MySQL 专有语法可能误报，可用 `checkSyntax = false` 关闭。
- 复杂 JOIN / 视图查询需要手写 SQL（原 `view_user_role` 的联表逻辑改为手写 SELECT）。
- 依赖 Rust 运行时链接（Maven 已发布预编译产物），构建环境需注意目标匹配。

### 方案 B：SQLDelight + SQLite

- 成熟、类型安全；但 **native 只支持 SQLite**（JDBC 驱动仅在 JVM）。
- 需把 MariaDB 表结构迁移为 SQLite：`AUTO_INCREMENT` / `UUID` / 视图 / 时间类型都要调整；丧失与其它项目共享同一数据库的能力。
- 适合"单机自带数据文件"的部署形态。

### 方案 C：保留 JVM，仅做 KMP 抽层

- 保留 Netty/Ebean，只把公共代码抽到 `commonMain`。**不满足"迁移到 Kotlin/Native"的目标**，仅作兜底记录。

> **建议：方案 A**。若后续发现 sqlx4k 在目标平台上不稳定，可降级为"方案 A 的手写 SQL 变体"（只用 sqlx4k 的 `execute/fetchAll`，不用其代码生成）。

### 6.1 密码算法：换实现后旧密码还兼容吗（回答用户第 5 问）

**答：兼容，前提是"换实现"而不是"换算法"。**

依据：

1. 本项目用的是 Spring Security 的 `BCryptPasswordEncoder(10)`（[DiConfig.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/config/web/DiConfig.kt#L42) 与 [LoginService.kt](file:///f:/Develop/Kotlin/ktor-template/src/main/kotlin/moe/lolosia/web/service/LoginService.kt#L63)），产出的是**标准 bcrypt 哈希**。
2. 开发板 MariaDB 中 `lolosia-backend.sys_user` 的实际值形如：
   `$2a$10$hpCjmzrKxTbIxzOB4.V2DeejXcWAueej571KUbCZzA9SYhVzUqvlu`
   —— 即标准 `$2a$` + cost `10`，`password` 列 `varchar(255)`。
3. bcrypt 是**自描述格式**：`$2a$10$<22字符salt><31字符hash>`，salt 与 cost 都编码在哈希串里。任何遵循标准的 bcrypt 实现都能校验它。

因此：**只要新的 KMP bcrypt 库实现的是标准 bcrypt，并能接受 `$2a$` 前缀，现有库中的密码可原样继续登录，无需重置。**

选型时必须确认两点：

| 检查项 | 说明 |
| --- | --- |
| **接受 `$2a$` 前缀** | 部分实现（Go/某些 Rust crate）默认只认 `$2b$`/`$2y$`，遇到 `$2a$` 直接报错 —— 这类库要么配置接受前缀，要么先做前缀归一化 |
| **cost 10 可校验** | 校验时 cost 从哈希串读取，无需与配置一致 |

> 若改用 **Argon2**（KMP 生态更活跃），则**不兼容**：bcrypt 与 Argon2 是不同算法，哈希串无法互认。此时只能做"登录时迁移"：用 bcrypt 校验通过后，再用 Argon2 重新哈希并回写。建议 P0 直接换标准 bcrypt 实现（零迁移成本），把 Argon2 留到以后。

**同时注意**：`spring-security-crypto` 需要删除，但**编码/校验的语义要保持一致**（cost=10），否则新注册/改密的用户与老用户会落在不同 cost 上（功能上没问题，只是不整齐）。

---

## 7. 目标平台与构建/部署

### 7.1 目标平台

按用户决策：**只出 Windows `.exe` 与 Linux 二进制，不做 JVM 目标。**

| 目标 | 用途 | 备注 |
| --- | --- | --- |
| `linuxArm64` | **部署到开发板**（`lckfb`，RK3566 级 aarch64） | Ktor / sqlx4k 均覆盖；**在 WSL 中交叉编译，已实测可行** |
| `mingwX64` | Windows 本机开发调试 | Ktor 支持；sqlx4k 有 `mingwX64`（注意其已知 lld-link 问题，见库官方 issue #18） |
| ~~`linuxX64`~~ | 可选，仅用于 WSL 内快速冒烟 | 非交付产物；若要跑单测可临时加，代价是构建配置变复杂 |
| ~~`jvm`~~ | **不保留**（用户决策 6） | 不作为 Java-KMP 项目实现 |

### 7.2 构建形态

**构建环境（已实测）**：本机 Windows 写代码 → **WSL Ubuntu 24.04 里跑 Gradle** 产出 `linuxArm64`，Windows 侧产出 `mingwX64`。不需要 Docker。

- WSL 侧需装：JDK（`apt install openjdk-21-jdk-headless`，root 免密）+ Kotlin/Native 工具链（Gradle 会自动下载，或预置 `KOTLIN_NATIVE_PREBUILT`）。若走 `objcopy` 嵌入资源，另装 `binutils-aarch64-linux-gnu`、`gcc-aarch64-linux-gnu`。
- **网络提示（实测）**：`download.jetbrains.com` / GitHub Releases 在本机可能超时；Java 侧依赖可走阿里云镜像，Kotlin/Native 发行包可从 Maven Central（`org.jetbrains.kotlin:kotlin-native-prebuilt:2.4.0:<target>@tar.gz`）或阿里云镜像获取（实测 12MB/s）。建议在 `gradle.properties` / 环境变量里预先指好镜像。
- 移除 `org.springframework.boot` 插件与 `bootJar`。
- KMP + `binaries { executable { entryPoint = "main" } }` 产出 `.exe` / 原生二进制（`.kexe`）。
- 前端静态资源：按决策 7 **嵌入二进制**（Windows 用 `.rc` + `windres`，Linux 用 `objcopy`，见 §3.1）；建议保留磁盘目录回退以便调试。
- `buildSrc` 中的 `viteJar` 改为"把前端产物喂给资源嵌入步骤"；`deploy` 任务需从"上传 fat jar"改为"上传原生二进制 + 重启 systemd"。`docker_shell.sh` / Dockerfile 相关可删（开发板无 Docker）。
- **部署目标现状**：`/srv/ktor-template/`（`ktor-template.service`，端口 7129）。迁移后 `ExecStart` 直接指向原生二进制即可；建议保留 `ktor-template.previous.jar` 的回滚能力。

### 7.3 反向代理

由于 Ktor/Native **不支持 HTTPS**，生产必须前置 Nginx/Caddy 做 TLS 终止；开发板上**已有 nginx**，天然满足。`ForwardedHeaders` / `XForwardedHeaders` 可保留（Native ✅）。

---

## 8. 实施路线（P0 / P1 / P2）

> 按用户给定的节奏重新组织：**P0 只验证基础 HTTP 能力，P1 补扩展库，P2 再谈 AI。**

### P0 —— 跑通"能用的最小服务"（目标：替代当前 JVM 版）

| # | 内容 | 验收标准 |
| --- | --- | --- |
| 0.1 | KMP 工程结构改造：`commonMain` + `nativeMain` + 两个目标（`mingwX64` / `linuxArm64`）；**同时把 KSP2 多目标接线跑通** | `linkDebugExecutableMingwX64` 与 `linkReleaseExecutableLinuxArm64` 都能出产物，KSP 生成的源码在两端都被编译到 |
| 0.2 | 启动骨架：`embeddedServer(CIO, port=7129)` + JSON 内容协商 + `{code,msg,data}` 信封 + StatusPages | `mingwX64` 本机启动，`GET /api/ping` 返回 JSON |
| 0.3 | **自研路由插件**（决策 9，§5.4）：`router-annotations` + `router-ksp` + `router-runtime`；注解同名同义，控制器只改 import | 现有 7 个控制器**方法体零改动**编译通过；`/api/**` 全部可访问；含 `@Body`/`@Path`/`@Query`/`@Attribute`/表单/文件 |
| 0.4 | **`classpath.zip` 中间层**（决策 8，§3.2）：`Classpath` 接口 + `platform.zlib` 版 zip 解析 + `DiskClasspath` 回退 + `staticClasspath` 路由；打包走 `zipClasspath` → 嵌入 | 前端页面从**二进制内**的 `classpath.zip` 提供；开发期可切磁盘目录 |
| 0.5 | 基础设施替换：KMP 日志、`kotlin.uuid.Uuid`、`kotlin.io.encoding.Base64`、okio 文件与哈希、kotlinx-datetime；删 Jackson / snakeyaml / commons-collections4 / kotlin-reflect | 编译通过，无残留 `java.*` 引用 |
| 0.6 | 数据库：sqlx4k 接 MariaDB（**库名 `lolosia-backend`**）、连接池、实体改 `@Table`/`@Id`，重写 `SessionManager` / `CacheManager` / `UserService` / `RoleService` | 会话落库、登录链路可用 |
| 0.7 | 密码：换标准 bcrypt KMP 实现（**必须接受 `$2a$`、cost 10**，见 §6.1） | 用库里现存的 `$2a$10$...` 哈希能直接登录成功 |
| 0.8 | 鉴权拦截器链：Traffic → Ctx → SSE → Auth → Envelope | 白名单外的接口无 session 时返回 401 |
| 0.9 | 部署：`systemd`（开发板无 Docker），产物替换 `/srv/ktor-template/` 下的 fat jar | `linuxArm64` 产物在开发板上启动，外网可访问 |

> 顺序建议：**0.1 → 0.2 → 0.3 → 0.4** 是"骨架四件套"，先把它们跑通，后面 0.5~0.9 才是搬业务。

**P0 明确不做**：验证码、WebP、OSS 上传、菜单/头像、AI、SSE 细节打磨。

### P1 —— 扩展库与功能补齐

- **图形验证码**：调研 KMP/C 方案（用户提示：hutool 的源码 jar 在 Gradle 目录下，可自行移植）；
- WebP 转换（找 C 库 + cinterop，或直接用 `libwebp`）；
- OSS 文件上传/下载、头像、菜单权限；
- SSE 事件总线的实机回归（CIO 下 flush 行为）；
- 压缩（gzip/br，KMP 库或交给 Nginx）；
- 若 sqlx4k 出现方言问题，按需降级为"driver + 手写 SQL"。

### P2 —— AI

- 删除 Koog（用户明确：原本用 SpringAI，Koog 压根没用过）；
- 如确有需要，改为纯 Ktor Client 直连 LLM HTTP API（不依赖任何 AI 框架）。

---

## 9. 风险与待验证项（已按实测更新）

| # | 风险 | 状态 | 应对 |
| --- | --- | --- | --- |
| 1 | ~~ktorfitx server 端 native 支持未明确~~ | **已确认不成立**：`server-core` POM 依赖 `ktor-server-core-jvm`，Maven 无 `server-*-linuxarm64/mingwx64` | 删除 ktorfitx，改用**自研路由插件**（§5.4）；注解同名同义，控制器只改 import |
| 2 | **KSP2 在 KMP 下需按目标声明处理器**（`add("kspLinuxArm64", ...)`），`buildSrc` 生成的源码目录要挂到正确源集 | 待验证；**现在同时是路由插件的前置条件** | P0-0.1 就把 KSP 接线跑通 |
| 3 | **sqlx4k 的成熟度**（较新社区库），MySQL 不支持 batch、`@Query` 方言校验可能误报 | 产物存在性**已确认**；行为待验证 | P0-0.6 先做最小 CRUD 验证；保留"只用其 driver + 手写 SQL"降级路径 |
| 4 | **Ktor/Native 无 Compression** | 确认 | 交给 Nginx 做 gzip/br |
| 5 | **Ktor/Native 无 HTTPS** | 确认（开发板上已有 nginx，天然满足） | Nginx 终止 TLS |
| 6 | **Kotlin/Native 反射极弱** | 确认 | 通用映射改 KSP 生成或手写 `RowMapper` |
| 7 | **静态资源模型变化** | 已有方案（**Linux 侧嵌入已实测**，§3.1；中间层设计见 §3.2） | 自研 `classpath.zip` + `Classpath` 中间层 + `DiskClasspath` 开发期回退 |
| 8 | ~~跨目标编译限制~~ | **已实测打通**：WSL(x86_64) 可直接产出 `linuxArm64` | 构建在 WSL 里跑，无需 Docker |
| 9 | **bcrypt 实现需接受 `$2a$` 前缀** | 待选型 | 见 §6.1；选型时先验证能校验库中现存哈希 |
| 10 | **Koog 无 linux native** | 确认 | 按用户决策删除（P2 再议 AI） |
| 11 | **SSE 在 CIO 下的 flush 行为** | 待实机验证 | P1 回归；`SseInterceptor` 已有 flush 处理 |
| 12 | **二进制体积 / 编译耗时 / GC 表现** | 待观察 | 增量编译；先用 `mingwX64` 快速迭代，`linuxArm64` 在 WSL 里编 |
| 13 | **sqlx4k 依赖 Rust 运行时** | 待确认 | 优先用 Maven 预编译产物；若需源码构建则在 WSL 装 Rust |
| 14 | **开发板资源有限**（4 核 / 2GB / 余 5.4G 磁盘） | 注意 | 新产物比 67.6MB fat jar 小得多；注意 JVM 版并行存在时的内存占用（迁移期可先停旧服务） |
| 15 | **`mingwX64` 与 `linuxArm64` 行为差异** | 待观察 | P0 双目标都要跑；CI 用 WSL 跑 `linuxArm64` 冒烟 |
| 16 | **自研路由插件是"自己造的轮子"**：KSP 生成代码的调试、注解语义与 ktorfitx 的细微差异 | 新增成本（可控：只做 8 个注解 + 6 类参数绑定） | 先只覆盖现有 7 个控制器的用法；生成代码可读、可断点；超范围一律回退 `@Route` |
| 17 | **自研 zip 解析**（Central Directory / zip64 / 编码） | 低（我们只打包自己产出的 zip，可控） | 明确不支持的边界：不处理 zip64（`classpath.zip` 不会超 4GB）、文件名统一 UTF-8 |

---

## 10. 保留的最小 Web 能力范围（裁剪结论）

**保留**：

- Ktor(CIO) + **自研路由插件**（注解同名同义，替代 ktorfitx，§5.4）+ `@Route` 长尾兜底
- kotlinx.serialization JSON + `{code,msg,data}` 响应信封
- 拦截器链：流量日志 → ctx 绑定 → SSE → 鉴权 → 信封
- 会话系统（`SessionManager`，落库 MariaDB）+ `AuthInterceptor` 白名单鉴权
- SSE 事件总线（`EventBus` + `EventSource` + `EventBusSSEManager`）
- **`classpath.zip` 中间层（自研，嵌入二进制 + `DiskClasspath` 回退）**
- Koin DI（`@Service` 扫描生成）
- MariaDB 数据访问（sqlx4k）
- OSS 文件上传/下载（okio 重写，MD5 用 okio 哈希）

**删除**：

- ktorfitx（server 端无 native 产物）
- Ebean / `:orm` / JDBC 驱动
- Jackson / snakeyaml / commons-collections4 / kotlin-reflect 相关通用映射
- Logback / SLF4J / `logging` 包
- Webjars / Compression / CallLogging / Netty / EngineMain / YAML 配置
- Koog（AI）→ P2 再议
- 图形验证码（hutool + AWT）→ P1 再议
- WebP 图片转换（imageio）→ P1 再议
- `util/config` / `util/property` 反射式配置体系

---

## 11. 结论

1. **技术上可行，且链路已实测打通**：WSL 交叉编译 `linuxArm64` ✅、资源嵌入并在真实开发板读回 ✅、目标机上 MariaDB 10.6 与 nginx 现成 ✅。
2. **代价是"重写"而非"迁移"**：ORM、配置、日志、工具类、映射层需整体替换；Native 的反射缺失是最大设计约束。
3. **原"最大不确定项"已定论**：ktorfitx 服务端**只有 JVM 产物**。应对不是"全部改手写路由"，而是**自研一个只覆盖需要的路由插件**（决策 9，§5.4）——注解与 ktorfitx 同名同义，现有 7 个控制器只改 import、方法体零改动，兼顾"跨项目复制改动最小"。
4. **"无 classpath"不是死路**：**自造 `classpath.zip` 嵌入二进制 + `Classpath` 中间层**（决策 8，§3.2），并让所有资源访问统一收敛到该中间层；资源路径与现有 jar 布局保持一致，业务代码不用改路径。
5. **数据库确定用 sqlx4k 保留 MariaDB**（`lolosia-backend` 库）；SQLite 仅作兼容备选。
6. **密码迁移零风险**：库中现存 `$2a$10$` 标准 bcrypt 哈希，换实现即可，无需重置用户密码。
7. 用户决策已全部确认，见 §12。

---

## 12. 已确认决策（用户 2026-09-21 答复）

| # | 问题 | 决策 | 落实要点 |
| --- | --- | --- | --- |
| 1 | 目标平台 | **保留 `mingwX64` 供 Windows 开发**，加 `linuxArm64` 部署 | 双目标都要跑通；见 §7.1 |
| 2 | 数据库 | **尽可能保留 MariaDB**，SQLite 作为兼容 | 主用 §6 方案 A（sqlx4k + MariaDB / `lolosia-backend`）；SQLite 作备选 |
| 3 | 验证码等扩展库 | 查 KMP / C 库，或**按 Gradle 目录下的源码自行编译**（hutool 有源码 jar）；**可留到 P1** | 见 §8 P1；P0 不做 |
| 4 | AI / Koog | **暂时移除，留到 P2**（原本用 SpringAI，Koog 从未用过） | 见 §8 P2 |
| 5 | 密码算法兼容性 | —— 已在 §6.1 给出结论：**换实现则兼容，换算法（Argon2）则不兼容** | 选型必须接受 `$2a$` 前缀 |
| 6 | JVM 目标 | **不保留**，只生成 exe 与 Linux 二进制 | 见 §7.1 |
| 7 | 资源分发 | **嵌入到二进制，自己实现一套资源加载器** | 见 §3.1 实测配方 |
| 8 | classpath 模型 | **自造 `classpath.zip` 打包进可执行文件**（classpath 本质就是 zip，参考 installer 模块与 zip 加载器），**所有 classpath 访问统一走这个中间层** | 见 §3.2；`Classpath` 接口 + `staticClasspath` 路由 + `DiskClasspath` 回退 |
| 9 | 路由注解 | **自己造 Gradle 插件轮子**，只实现需要的部分（表单、文件、Body、Path、Query 等）；**目标：跨项目复制代码时改动尽可能小** | 见 §5.4；注解与 ktorfitx **同名同义**，控制器只改 import；`@Route` 保留作长尾兜底 |

---

## 13. P0 实施进度与实测记录（native 分支）

> 本节记录**已落地代码**与实测结果，配套提交见 git 历史（`native` 分支）。
> **会话交接请先读 [开发记忆.md](./开发记忆.md)**（硬性约定、构建/部署/调试工作流、踩坑速查）。

### 13.1 已完成

| P0 项 | 状态 | 说明 |
| --- | --- | --- |
| 0.1 KMP 工程结构 | ✅ | 根工程改为 `kotlin("multiplatform")`，目标 `mingwX64` + `linuxArm64`；旧 JVM 代码整体移入 `legacy/`（仅作移植参考，不参与编译）；移除 `:orm` 与全部 JVM 插件/依赖 |
| 0.2 最小 CIO 服务 | ✅ | `embeddedServer(CIO, 7129)` + `ContentNegotiation(JSON)` + `StatusPages` + `{code,msg,data}` 信封 |
| 0.3 自研路由插件 | ✅ | `:router-ksp`（KSP 处理器）+ `moe.lolosia.web.util.router`（注解 + 运行时）；`GeneratedRoutes.kt` 由 KSP 生成 |
| 0.4 `classpath.zip` 中间层 | ✅ | 见 §13.5：Gradle 原生 `resources` 目录按源集合并打包 + 构建期嵌入；`Classpath` 统一访问接口 + `DiskClasspath` 回退 + `staticClasspath` 路由；Windows/Linux 双平台实测通过 |
| 0.5 基础设施替换 | ⬜ | |
| 0.6 sqlx4k 数据层 | ⬜ | |
| 0.7 bcrypt | ⬜ | |
| 0.8 鉴权拦截器链 | ⬜ | 已有 `CtxInterceptor` 雏形（`@Injectable Context`） |
| 0.9 部署 | ✅ | 插件侧支持原生产物（`deploy { binary { } }`）+ **真机跑通**，见 13.6 |

**实测数据**

| 指标 | mingwX64（Windows 本机） | linuxArm64（开发板 lckfb / RK3566） |
| --- | --- | --- |
| 产物大小 | release **10.7 MB** / debug 19.4 MB | release **9.4 MB** / debug 12.8 MB（含 vite 前端） |
| 构建方式 | Windows 直接构建 | **Windows 直接构建**（`objcopy` 一步借道 WSL，见 13.3-18）；增量 ≈35s |
| 冒烟测试 | 10/10 通过 | qemu 全绿 + **开发板真机 `deploy1m` 6s 部署成功** |
| 运行内存 | — | **RSS ≈ 34 MB**（现网 JVM 版实测 ≈238 MB，`-Xmx512m`） |

冒烟覆盖：`/`、`/api/ping`、**`ctx` 无注解按类型自动注入**、`@Path`/`@Query` 省略名、可空参数、`@Path` 显式名、`@Body` JSON、multipart 上传、参数转换失败→400、未知路由→404。

### 13.2 与设计的偏差（实施后修正）

| # | 原设计 | 实际做法与原因 |
| --- | --- | --- |
| 1 | 注解放 `moe.lolosia.web.annotation`（与 `@Route` 同包） | 改为 **`moe.lolosia.web.util.router`**（包名规整见 13.2-5）；注解与运行时同包，`@Route` 仍留在 `moe.lolosia.web` 层 |
| 2 | `@Attribute("ctx")` 注入上下文 | **去掉该注解**（用户指出字符串前缀属冗余），改为 **`@Injectable` 标注类型 + 按类型自动注入**，形参写 `ctx: Context` 即可。`@Path/@Query/@Header/@Form/@FilePart` 的名称也改为**可省略，默认取形参名** |
| 3 | `main()` 在 `commonMain` | 必须放**各目标源集**：KSP2 无 common/metadata 编译单元，生成代码落在目标源集，而 common 代码看不到 platform 声明 |
| 4 | Ktor 自带 multipart | **Ktor 3.4 在 Native 上未实现 multipart**，被迫自研解析器（见 13.3-1） |
| 5 | 包名规整 | 本 web 工程 `src/` 下的代码统一归入 **`moe.lolosia.web`**：`moe.lolosia.classpath` → `moe.lolosia.web.util.classpath`，`moe.lolosia.router` → `moe.lolosia.web.util.router`。仅**独立 Gradle 模块** `:router-ksp` 保持 `moe.lolosia.router.ksp`（其生成代码所用的注解全限定名常量已同步改为 `moe.lolosia.web.util.router`） |
| 6 | classpath 流水线写在 `build.gradle.kts` | 迁入 **`buildSrc`**（`ClasspathExtension` + `NativeClasspath.kt`），`lolosia { }` 扩展重新启用并接管 vite 前端产物；根脚本从 187 行降到 106 行（见 13.5） |

### 13.3 实施中发现的坑（重要，后续必读）

1. **Ktor 3.4 在 Kotlin/Native 上没有 multipart**
   平台侧实现为 `error("Multipart is not supported in native")`，`receiveMultipart()` 会直接抛 `ContentTransformationException`。
   → 已自研纯字节扫描的 `multipart/form-data` 解析器（`moe.lolosia.web.util.router.Multipart`，零第三方依赖），`@Form`/`@FilePart` 因此可用；文件字节与本地真实文件逐字节一致。
   → 启示：凡是"JVM 上理所当然"的 Ktor 功能，在 Native 上都要先查平台侧 actual 实现。

2. **KSP2 + KMP 没有 common/metadata 编译单元**
   `tasks` 里只有 `kspKotlinMingwX64` / `kspKotlinLinuxArm64`，没有 `kspCommonMainMetadata`；加 `kspCommonMainMetadata` 依赖也不会生成任务。
   → 生成代码落在目标源集；而 **common 代码不能引用 platform 声明**（已用最小探针验证），所以入口 `main()` 必须在目标源集。
   → 架构上的写法：`commonMain` 提供 `startServer(port, installRoutes)`，各目标 `main()` 传入 `{ generatedRoutes() }`。

3. **KSP 聚合输出必须声明 `Dependencies(aggregating = true, *源文件)`**
   只写 `aggregating = true` 而不传源文件时，KSP 增量处理只扫"脏文件"，会把未改动文件里的 `@Controller` 全部漏掉，**静默生成空路由表**（编译通过、接口全 404，极具迷惑性）。

4. **阿里云镜像缺 Ktor 的 native 产物**
   `ktor-server-core-mingwx64.klib` 等在阿里云 `public` 仓库同步不全 → 需在 `settings.gradle.kts` 里 `excludeGroupByRegex("io\\.ktor")` 强制走 mavenCentral。

5. **WSL 交叉编译可行，且不需要 Docker**
   `download.jetbrains.com` 与 Maven Central 在 WSL 中均可达；Gradle 9.5.1 + JDK 21 + Kotlin/Native 工具链全自动就绪。

6. **`scp` 指定端口是大写 `-P`**（小写 `-p` 是保留时间戳），踩过一次。

7. **Windows 上链接产物被运行中的进程占用会导致 link 失败** —— 改代码前先停掉正在跑的 exe。

8. **`CPointer.rawValue` 的类型随 Kotlin 版本变化**
   Linux 侧要靠 `_binary_..._end - _binary_..._start` 算映像大小，但 `rawValue` 返回的 `NativePtr`
   在本版本里不是普通 `Long`，直接 `size <= 0` 会报
   `'operator' modifier is required on 'fun <T> Comparable<T>.compareTo(other: T): Int'`。
   统一走 `rawValue.toLong()`（封装成 `rawAddress()`）。
   **验证技巧**：把这段平台无关的算术放进 `nativeMain`，就能在 Windows 上几秒内验证，不必等 30 分钟的 Linux 全量构建。

9. **Linux 上 objcopy 的符号默认进不了动态符号表**
   需要 `-Wl,--export-dynamic`（已加），之后 `_binary_classpath_zip_start/_end` 才会出现在 `.dynsym`，
   `dlsym` 才能查到。
   **注意**：`readelf --dyn-syms` 不加 `-W` 会**截断长符号名**，导致 `grep classpath_zip` 误判为"没有"——
   排查时务必用 `readelf --dyn-syms -W`。

10. **WSL 构建目录用覆盖式同步会残留已删除的文件**
    用 `tar` 覆盖同步不会删除源端已删的文件，结果 `PORT` 出现两份定义报重载歧义。
    → 同步前先 `rm -rf` 目标源码目录（或改用 `rsync --delete`）。

11. **在 x86_64 上跑 aarch64 产物验证**：`qemu-user-static` + `libc6-arm64-cross`，并需手动补
    `libcrypt.so.1`（从 `ports.ubuntu.com` 取**与发行版匹配**的版本，装太新会报 `GLIBC_2.43 not found`）。
    这条能省掉每次"打包→传板子"的往返。
    **注意**：后台启动 qemu 要用 `setsid ... < /dev/null &`，且**单独放一个脚本文件**跑；
    放进 `{ ... } > log` 这类整体重定向的复合语句里也会被连带取消。现象是日志里出现
    `Uncaught Kotlin exception: JobCancellationException: LazyStandaloneCoroutine is cancelling`
    —— 看着像协程 Bug，实际是测试脚本的进程管理问题（同一二进制连跑 3 次均正常）。

12. **Gradle `from(x) { ... }` 闭包形式会新建"子 spec"，遍历顺序永远排在根 `from()` 之后**
    想用 `duplicatesStrategy = EXCLUDE`（先入者优先）来定资源优先级时，若把 vite 产物写成
    `from(zipTree(jar)) { exclude(...) }`，即使写在最前面也**一定输给后面的 `from(sourceSet.resources)`**——
    因为子 spec 的遍历顺序排在根 spec 的所有 source 之后。
    → 过滤要用 `zipTree(f).matching { exclude(...) }` 返回 `FileTree`，再 `from(fileTree)` 才是根级 source。

13. **buildSrc 里引入 KGP 后，主工程必须"无版本"应用 Kotlin 插件**
    `implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:X")` 让 KGP 上了 buildSrc 的 classloader，
    此时主工程再写 `alias(libs.plugins.kotlin.multiplatform)` 会直接失败：
    `The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version`。
    → 主工程改为 `id("org.jetbrains.kotlin.multiplatform")`（不写版本），版本由 buildSrc 的依赖单点决定。
    → buildSrc 的构建脚本默认**看不到主工程的版本目录**，需在 `buildSrc/settings.gradle.kts` 里
    `create("libs").from(files("../gradle/libs.versions.toml"))` 挂载，避免 Kotlin 版本两处维护而漂移。

14. **`ViteJarTask` 产出里会多出一个根目录 `MANIFEST.MF`**
    Gradle 的 `Jar` 除了写 `META-INF/MANIFEST.MF`，还会把内容里的同名文件落到根目录。
    并入 classpath 时必须一并排除，否则 `classpath.list("")` 里会混进一个垃圾条目。

15. **Gradle 9 对缺失的 `inputs.dir()` / `inputs.file()` 直接判定校验失败**
    `LolosiaPlugin` 原先用 `inputs.dir(baseDir.resolve("typings"))` 这类写法给 vite 源码打指纹，
    但前端工程里 `typings`、`public` 未必存在；在 WSL 交叉编译时前端目录（`F:\...`）更是根本不存在，
    于是 `:viteJar` 直接配置校验失败（`input file does not exist`），整个 link 起不来。
    → 改为 `inputs.files(project.fileTree(path))`：目录不存在时只是空的 `FileTree`，不报错，
      且文件级指纹比目录级更精确。

16. **`generateConstants` 要生成到 commonMain 必须去掉 `@file:JvmName`**
    `@file:JvmName("Constants")` 在 JVM 目标上没问题，但放进 `commonMain` 会直接编译失败：
    `Unresolved reference 'JvmName'`（它不是 `@OptionalExpectation`，普通 common 代码看不到）。
    → `GenerateConstantsTask` 增加 `jvmTarget` 开关（JVM 工程仍生成），KMP 工程置 false。
    → 另外 Gradle 不允许 `@Optional` 标注**基本类型**属性（`Properties of primitive type cannot be optional`），
      所以 `var jvmTarget: Boolean` 只能带 `@Input`。

17. **KGP 的 link 任务把「输出目录」声明成 output，不能直接喂给 SFTP `put`**
    `linkReleaseExecutableLinuxArm64.outputs.files` 是 `build/bin/linuxArm64/releaseExecutable/`（**目录**），
    而不是里面的 `ktor-template.kexe`。
    hidetake-ssh 的 `put(from: File, into: String)` 遇到目录会走递归分支：把本地目录名压进
    `directoryStack` 再逐个 mkdir、上传 —— 于是远端报
    `Failed SFTP MKDIR: 1m:/srv/ktor-template/ktor-template.new/releaseExecutable`（SFTP 的 MKDIR 不建父目录）。
    → 改用 KGP 的 `KotlinNativeLink.outputFile`（`RegularFileProperty`）定位真正的可执行文件。

18. **Windows 宿主上「编译 + 链接」都能跑，唯独 objcopy 缺位 —— 借道 WSL 只做这一步**
    实测（Kotlin 2.4 / Kotlin-Native）：`compileKotlinLinuxArm64` 与 `linkReleaseExecutableLinuxArm64`
    在 Windows 上都能成功产出合法的 ELF64 AArch64 可执行文件，**不支持交叉编译的传言不适用于这一版**。
    真正卡住的只有 `objcopy -I binary -O elf64-littleaarch64` 这一步：
    - `~/.konan/dependencies/aarch64-unknown-linux-gnu-gcc-*/bin/aarch64-unknown-linux-gnu-objcopy`
      是 **ELF 二进制**（Linux 用），Windows 执行不了（`A problem occurred starting process`）；
    - `~/.konan/dependencies/msys2-mingw-w64-*/bin/objcopy.exe` 的 `--info` 只有 `pe-x86-64 / elf64-x86-64`，
      不认 `elf64-littleaarch64`；
    - konan 自带的 LLVM 是 `llvm-21-x86_64-windows-**essentials**`，**不含 `llvm-objcopy`**；
    - 想从 Windows 反向够 WSL 也不行：NAT 模式下 WSL 访问宿主走 `172.x.x.1`，而入站被 Windows 防火墙拦
      （连宿主已监听 `0.0.0.0` 的 `mysqld:3306` 都连不上）。
    → 在 `objcopyClasspath<目标>` 里判断宿主为 Windows 时改为拉起 `wsl -- bash -c "cd <wsl路径> && aarch64-linux-gnu-objcopy …"`。
      路径用 `toWslPath()` 显式转成 `/mnt/<盘符>/…` —— **不要依赖工作目录传递**：从 Windows 进程拉起的 wsl
      未必能正确翻译 cwd（实测把 `F:\Develop\…` 传成了 `F:Develop…`）。
      发行版 / 用户可用 `classpath { wslDistro / wslUser }` 指定，留空就用 WSL 默认值。
    这样 **Windows 侧一条 `gradlew deploy1m` 就能跑完全流程**，WSL 只在 objcopy 这一步被拉起来一次。

### 13.5 classpath.zip 中间层（P0-0.4）落地说明

**效果**：与 Java 打资源文件一致 —— `src/<sourceSet>/resources/**` 就是 classpath 根，
运行时 `classpath.read("static/home/index.html")` 即可读到，**路径不需要改**。

**访问接口**（`moe.lolosia.web.util.classpath`）：

```kotlin
val bytes = classpath.require("static/home/index.html")   // 必须有
val text  = classpath.readText("hello.txt")               // 可空
val list  = classpath.list("static/")                     // 前缀列举
val all   = classpath.entries                             // 全部条目
```

**打包与嵌入**（实现位于 `buildSrc/src/main/kotlin/moe/lolosia/gradle/NativeClasspath.kt`）：

| 环节 | mingwX64 | linuxArm64 |
| --- | --- | --- |
| 打包 | 标准 Gradle `Zip` 任务 `classpathZipMingwX64` | `classpathZipLinuxArm64` |
| 合并来源 | 各源集的 `KotlinSourceSet.resources`，按「目标源集 > 中间源集 > commonMain」优先级，重名由更具体者胜 | 同左 |
| 注入扩展 | `lolosia.classpath.resources(...)`（其它 task 的资源目录在打包阶段一并合并） | 同左 |
| 嵌入 | `windresClasspathMingwX64`：用 Kotlin/Native 自带 mingw `windres` 把 `.rc` 编成 `.res` 后链接 | `objcopyClasspathLinuxArm64`：`aarch64-linux-gnu-objcopy -I binary` 转 `.o` 后链接，配 `-Wl,--export-dynamic` |
| 运行时取像 | `FindResourceW` → `LoadResource` → `LockResource` | `dlsym("_binary_classpath_zip_start/_end")` |

**构建配置（`lolosia { }` 扩展）**：把原先散在根脚本里的近百行流水线收进 `buildSrc` 的 `moe.lolosia` 插件后，
根 `build.gradle.kts` 只剩声明式配置：

```kotlin
lolosia {
    packageName = "moe.lolosia.web"
    projectName = "洛洛希雅的Ktor后端"
    deploy { /* 读 platforms.json */ }      // 需 bootJar，非 JVM 工程自动跳过并告警
    viteJar { platform("default") { contextPath = "home"; dir = webPath } }
    classpath {
        // targets 留空 = 覆盖工程内全部 native 目标；includeViteJar 默认 true
        // resources("build/xxx") 可再注入其它 task 的资源目录
    }
}
```

**vite 前端产物并入 classpath**：`viteJar` 任务原本只服务于 JVM 工程的 `bootJar`/`jar`；
非 JVM 打包工程（本项目）没有 jar，于是 `LolosiaPlugin` 改为把 `viteJar` 的产物交给 classpath 流水线消费，
zip 内前缀沿用 `static/<contextPath>`（即 `static/home/`），**与旧 JVM 版的资源路径完全一致**。

合并优先级（先入者优先）：**vite 前端产物 > 目标源集 resources > 中间源集 > commonMain > 注入资源**。
即构建出的前端会覆盖 `src/*/resources` 里的同名占位文件（例如占位 `index.html`），
不会再出现"真实前端被仓库内占位页挡住"的情况。

**前端路径常量（`generateConstants`）**：每个 `viteJar.platform(<名>)` 生成一个
`VITE_<平台名大写>` 常量（值即 `contextPath`），服务端挂载静态资源时引用常量而不是写死字符串。
配多个前端就生成多个常量，KMP 工程把生成目录挂到 `commonMain`（去掉 `@file:JvmName`）。

```kotlin
// build/generated/source/vite/main/moe/lolosia/web/constants.kt
package moe.lolosia.web
const val PROJECT_NAME = "洛洛希雅的Ktor后端"
const val VITE_DEFAULT = "home"
```

`Bootstrap.kt` 中（与旧 JVM 版 `HomeController` 的写法一致）：

```kotlin
staticClasspath("/$VITE_DEFAULT", "static/$VITE_DEFAULT") { index = "index.html"; /* … */ }
get("/") { call.respondRedirect("/$VITE_DEFAULT/", permanent = true) }
get("/$VITE_DEFAULT") { call.respondRedirect("/$VITE_DEFAULT/", permanent = true) }
```

**实现要点**：
- `ZipClasspath` 只解析**中央目录**建索引一次，读取时才拷贝单条目；打开时把映像拷入内存数组，
  换取解析逻辑与平台无关（几 MB 一次性开销）。
- 目前只支持 **STORED**（不压缩）条目；要改 DEFLATED 需在 `read()` 里补 inflate
  （可用 Kotlin/Native 自带的 `platform.zlib`），或把 `lolosia.classpath.compressed` 打开后补齐读取端。
- `Route.staticClasspath(remotePath, zipPrefix)` 把 classpath 挂成静态资源路由，自动
  `Content-Type` / `Cache-Control` / `ETag`，含 `index` 与 SPA `fallback`，配合 `ConditionalHeaders` 支持 304。
- 开发期可用环境变量 `KTOR_CLASSPATH_DIR` 指向磁盘目录，**改前端资源不必重编二进制**。

**实测**（两平台各自的 `target.txt` 内容不同，用来验证按目标源集区分）：

| 检查项 | mingwX64 | linuxArm64（WSL 交叉编译 + qemu） |
| --- | --- | --- |
| 构建 | ✅ BUILD SUCCESSFUL | ✅ BUILD SUCCESSFUL（`_binary_classpath_zip_*` 3 个符号在 `.dynsym`） |
| `generateConstants` | ✅ `VITE_DEFAULT = "home"` 生成到 commonMain | ✅ 同样生成（WSL 里也按 platforms.json 生成） |
| classpath 装载 | 99 条目（含 vite 产物；前端工程在本机可达） | 3 条目（WSL 里访问不到 `F:\...\lolosia-web`，vite 跳过，属预期） |
| `hello.txt`（commonMain） | ✅ | ✅ |
| `target.txt`（目标源集） | ✅ 返回 mingwX64 那份 | ✅ 返回 linuxArm64 那份 |
| `/home/` | ✅ 200 text/html **395B = vite 产物**（覆盖了仓库里 745B 的占位页） | ✅ 200 text/html 745B = 占位页（前端未参与构建） |
| `/home/static/js/index.js`、`/home/favicon.ico` | ✅ 200，来自 vite 构建 | —（无前端产物） |
| `/` 301 跳转 / SPA 回退 / 前缀过滤 / 缺失资源 | ✅ | ✅ |
| 资源路由 / ctx 注入 / @Path / @Query / multipart / 参数错误→400 | ✅ | ✅（0 未捕获异常） |
| Cache-Control + ETag + If-None-Match→304 | ✅ | — |
| `KTOR_CLASSPATH_DIR` 磁盘覆盖 | ✅ | — |

### 13.6 原生形态部署（systemd）

旧 `DeployTask` 从里到外都是「JVM fat jar」假设，原生二进制会踩三处：

| 位置 | 问题 |
| --- | --- |
| `prepareSlimJar` / `repackageJar` / `uploadLibsWithCache` | 把 fat jar 拆成 `slim.jar` + 按 sha256 增量补 `BOOT-INF/lib`；原生是自包含单文件，整套无意义 |
| `patchServiceExecStart` | sed 规则是替换 `ExecStart=` 里最后一个 `.jar` token；换成 `ktor-template.kexe` 匹配不上，直接 `PATCH_MISS` 抛错 |
| `.service` 的 `ExecStart=/usr/bin/java -Xms… -Xmx… -jar …` | 需要整行换成可执行文件路径，且原生不需要 `unzip/zip`（`prepareSystemdDeployDir` 里的 apt 安装） |

**做法**：给部署产物加一个「形态」维度，而不是往 `mode` 里塞值 —— `mode` 回答「怎么跑」（docker / systemd），
形态回答「跑什么」，两者正交。**而「跑哪个架构」是每台机器自己的属性，写在服务器配置里**：
同一个项目完全可能既部署到 arm64 开发板、又部署到 x64 云主机。

`platforms.json`（不提交，本地配置）：

```json
{
  "servers": [
    { "name": "shiyan", "host": "203.0.113.10", "mode": "systemd" },
    { "name": "1m", "host": "127.0.0.1", "port": 6023, "mode": "systemd",
      "abi": "arm64", "args": ["7130"] }
  ]
}
```

> 隧道转发时 `host` 要写 `127.0.0.1` 而**不能写 `localhost`**：`localhost` 在 Windows 上会先解析到 `::1`，
> 而隧道（以及多数转发工具）只绑 IPv4 的 `127.0.0.1`，握手会直接 `Connection refused`。

```kotlin
lolosia {
    deploy {
        servers?.forEach { server ->
            server(server["name"] as String) {
                …
                mode = server["mode"] as? String ?: "docker"
                abi = server["abi"] as? String                                  // 配了就是原生，不配就是 jar
                args = (server["args"] as? List<*>)?.map { it.toString() } ?: emptyList()
            }
        }
        binary {                     // 只有跨服务器共享的构建/单元命名；全部有默认值
            buildType = "release"
        }
    }
}
```

产物解析规则（逐服务器）：

| server 配置 | 部署产物 | Kotlin/Native 目标 |
| --- | --- | --- |
| `abi = "arm64"` | 原生可执行文件 | `linuxArm64` |
| `abi = "x64"` | 原生可执行文件 | `linuxX64` |
| 无 `abi` | JVM `bootJar` | — |

- ABI 别名：`arm64` / `aarch64` 等价，`x64` / `amd64` / `x86_64` 等价。
- ABI 不认识、或工程没声明对应目标（如没写 `linuxX64 { }`）→ 告警并**跳过该服务器**，不影响其它服务器。
- 服务器既没配 `abi`、工程又确实没有 `bootJar`（纯原生工程）→ 跳过（`info` 级，不吵）。
- 被跳过的服务器**不会**注册 `deploy<名字>`，聚合 `deploy` 任务也不会指向它。

`DeployTask` 新增 `artifactKind`（jar / binary）与 `artifact`（`@InputFiles`，由插件注入 link 任务输出），
binary 分支的处理：

1. 传到 `<pwd>/<name>.new` → `chmod +x` → `mv -f <name> <name>.previous` → `mv -f <name>.new <name>`；
   **必须先改名挪走旧 inode**，直接覆盖正在运行的可执行文件会 `ETXTBSY`。
2. 单元文件：远端没有就创建。优先用项目根目录同名的 `<unitName>`（可自定义 `User=`、资源限制），
   否则按二进制形态生成模板 —— `ExecStart` 指向可执行文件，**没有 `java -jar` 前缀**。
3. `ExecStart` **整行替换**：`sed -i -E 's|^ExecStart=.*|ExecStart=<绝对路径> [启动参数…]|'`。
   jar 形态仍旧只替换 `.jar` token，以保留 `-Xms/-Xmx` 之类的 JVM 参数。
4. 跳过 `application.yaml` 交互与 apt 装 `unzip/zip`（原生不用 config-yaml，也不用拆包）。
5. docker 模式 + binary 形态直接报错提示：需要另外写以该二进制为入口的 Dockerfile。

**与 JVM 版并行验证**：默认单元名 `<rootProject.name>-native.service`，与现网 `<rootProject.name>.service`
互不干扰，`args` 里换个端口即可（本模板：JVM 7129 / 原生 7130）。验证通过后再统一端口、退役 JVM 服务。

**首次真机部署实测（2026-09-21，开发板 lckfb / RK3566，aarch64）**：

| 检查项 | 结果 |
| --- | --- |
| 构建宿主 | Windows 一条 `gradlew deploy1m` 跑完（objcopy 借道 WSL，见 13.3 坑 18） |
| 部署耗时 | 单次 6s（直连板子公网 IPv6；走 QUIC 隧道传 9.4MB 明显更慢） |
| 单元文件 | `/srv/ktor-template/ktor-template-native.service`，`enabled` + `active` |
| `ExecStart` | `/srv/ktor-template/ktor-template 7130` —— systemd 视角 `argv[]=/srv/ktor-template/ktor-template 7130`，**无 `java -jar`** |
| 端口 | `0.0.0.0:7130` ← 原生服务（pid 193017）；`*:7129` ← JVM 服务（pid 152437），两者并存 |
| 自测 | `curl 127.0.0.1:7130/api/ping` → `pong`；`/home/` → `HTTP 200 395B`（vite 产物） |
| 内存 | 原生 **≈34MB RSS** vs JVM `-Xmx512m` 实测 ≈238MB RSS |
| 回滚 | `/srv/ktor-template/ktor-template.previous` 为上一个可执行文件 |

> 注意：原生服务的 `host = "0.0.0.0"` 只绑 IPv4，外部用板子的 **IPv6** 探 `7130` 会失败
> （JVM/Netty 默认双栈，所以 `7129` 的 IPv6 探得通）。需要 IPv6 直连就把监听地址改成 `::`。

### 13.7 建议的下一步

1. **0.5 基础设施替换**：日志（现为 `println`）、`java.time`→`kotlinx-datetime`、UUID/Base64→标准库、文件 IO→okio。
2. **0.6~0.8**：按 §5 清单把 `legacy/` 里可移植的部分逐个迁到 `commonMain`（`sqlx4k` 数据层、bcrypt 密码校验、鉴权拦截器链），JVM 专有部分按表删除/替换。
3. ~~**0.9 部署**~~ ✅ 已完成（见 13.6）：插件侧与真机部署均已跑通，
   `/srv/ktor-template/ktor-template` 已由 `ktor-template-native.service` 拉起（7130），与现网 7129 的 JVM 服务并行。
   验证无问题后再统一端口、退役 JVM 服务。
4. 可选优化：`classpath.zip` 改 DEFLATED 压缩（需补 `platform.zlib` inflate），进一步缩小二进制与内存占用。
