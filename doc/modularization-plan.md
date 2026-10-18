# 多模块化与 Gradle 根整理方案（Phase 2）

> 状态：**Stage 0–2 已实施**（分支 `dev/modularization`），**Stage 3 首版已跑通**（x86_64 模拟器上 Activity 打开现有 `probe.rml` 面板；KMP 模块的 `androidTarget()` 与实际业务接入仍待补）。实际偏差见 §3.2 与 §8。
> 范围：只动 `backend/`。工作区根目录（`web/`、`ftp/`、`doc/`、根 `AGENTS.md` 等）**不动**。
> 结论：采用 Phase 2 —— **Gradle 根只做聚合与发布，代码全部进 `backend/modules/`，根下不再保留 `src/`**。
> 现行工程约定仍以 [AGENTS.md](../AGENTS.md) 为准；本方案落地后需要同步修订该文件的文件地图与命令清单。

---

## 1. 五条硬性决策

| # | 决策 | 落点 |
| --- | --- | --- |
| 1 | 工作区根目录不动 | 只改 `backend/`；不移动 `web/`、`ftp/`、根 `doc/`、根 `AGENTS.md`，不新增根 `.gitignore` |
| 2 | 使用 Phase 2，Gradle 根下不再保留 `src/` | 现有 `src/`、`router-ksp/` 全部迁入 `backend/modules/`；根只留构建脚本、资源仓库与发布产物 |
| 3 | `platforms.json` 留在 `backend/` 根 | 根构建脚本读取一次，经 `rootProject.extra` 交给 `:app-desktop`（vite）与根 `deploy`（SSH）；模块内不放副本 |
| 4 | `deploy` 只在根注册 | 根应用 `moe.lolosia` 并配置 `deploy`；子模块不配置 `deploy { server(...) }`，不产生 `deploy*` 任务 |
| 5 | 根新增 `runDebug` / `runRelease` / `buildBinaries` | `run*` 按宿主平台指向对应可执行文件；`buildBinaries` 汇总全部二进制到 `backend/build/bin/`，统一命名 |

---

## 2. 最终目录结构

```
backend/                              # Gradle 根：聚合、发布、共享资源；无 src/
├─ settings.gradle.kts
├─ build.gradle.kts                   # 根任务：deploy / runDebug / runRelease / buildBinaries
├─ gradle.properties
├─ gradlew / gradlew.bat
├─ buildSrc/                          # 构建插件 moe.lolosia（不参与运行时代码）
├─ gradle/
│  ├─ libs.versions.toml
│  └─ wrapper/
├─ classpath/mingw/                   # windres 资源：classpath.rc / app.manifest
├─ thirdparty/                        # 预编译 C 静态库 + 头文件 + 一次性重建脚本
├─ sql/                               # 建表脚本
├─ deploy/                            # 部署辅助（如 anime-relay）
├─ doc/                               # 设计文档
├─ tools/ui-smoke/                    # UI 冒烟脚本（不进 Gradle）
├─ platforms.json                     # 机器相关、gitignored；根唯一读取点
├─ application.yaml                   # 本机运行配置、gitignored
├─ lolosia-backend-native.service     # systemd 单元模板（rootDir 下）
├─ Dockerfile / docker_shell.sh       # 仅 JVM docker 形态需要；原生形态不使用
├─ modules/
│  ├─ router-ksp/                     # KSP 处理器（原根下 router-ksp/）
│  ├─ protocol/                       # 跨端 DTO / 契约
│  ├─ core/                           # 平台基础设施 + 共用出站传输
│  ├─ codecs/                         # 图像编解码 / msgpack / 缩略图
│  ├─ server/                         # Ktor 服务端、数据库、控制器、服务
│  ├─ client/                         # 同步 / 推送 / ViewModel / RmlUi 宿主
│  ├─ app-desktop/                    # 桌面唯一入口、模式分发、classpath 嵌入
│  └─ app-android/                    # Android 壳（Phase C，条件 include）
└─ build/
   └─ bin/                            # buildBinaries 输出（release/debug 的 exe/kexe/apk）
```

**根目录的边界**：根只保留「不能属于某一个模块」的东西 —— 构建插件、共享 C 库、共享嵌入资源、SQL、部署脚本、文档、机器相关配置，以及最终发布产物。任何 Kotlin 源码都不再放在 `backend/src/`。

---

## 3. 模块职责与依赖图

### 3.1 职责表

| 模块 | 职责 | 从现有目录迁入 | 目标 |
| --- | --- | --- | --- |
| `:modules:protocol` | 跨端可序列化契约 | `dto/` 中不依赖 `:server` 实体的纯 DTO、`ApiResult` / `ApiException`、SSE 事件负载、API 路径常量（`SystemDtos` / `OssDtos` 先留 `:server`，见 §4.5） | native + android |
| `:modules:core` | 平台基础设施与共用出站传输 | `util/classpath/`、`util/fs/`、`util/log/`、`util/time/`、`util/platform/`、`util/json/`、`api/` 的通用传输部分、各平台 actual（EmbeddedClasspath / Mkdir / Env / Termination） | native + android |
| `:modules:codecs` | 图像与二进制编解码 | `util/image/`（expect + actual）、`nativeMain/.../thirdparty/`、`util/xhrdata/`、`service/files/{ThumbnailGenerator,AutoCrop}`、`nativeInterop/cinterop/{zlib,png,webp,jpeg,gif,msgpack}.def` | native + android |
| `:modules:server` | 服务端 | `configuration/`、`controller/`、`database/`、`interceptor/`、`service/`（除 codecs 部分）、`settings/`、`generated/`、server-only `util/{captcha,crypto,exif,koin,ktor,llm,media,proc,router,session,sse,system,tokenizer}`、`resources/{application.json,permissions.yaml,media-rules.yaml,static,hello.txt}`、平台 actual（PlatformModule / Shell / SystemMetricsReader） | native only |
| `:modules:client` | 客户端、同步、推送、UI | `ui/`（mvvm / experiment / UiExperiment）、`resources/ui/`、`nativeCpp/`、`nativeInterop/cinterop/lolosia.def`、`api/` 的客户端部分、`linuxMain` / `mingwX64Main` 下的 `ApiClient` 与 `UiExperiment` actual | native + android |
| `:modules:app-desktop` | 桌面唯一入口 | `Main.kt`、目标可执行文件声明、classpath / vite 配置、桌面模式分发 | native only |
| `:modules:app-android` | Android 壳 | 新增：Activity、前台 Service、JNI 桥、assets | android |
| `:modules:router-ksp` | KSP 处理器 | 原 `router-ksp/` | JVM（build-only） |

**包名策略**：Stage 2 **先保持现有包名不变**，只换模块。例如 `dto/` 进 `:protocol` 后仍是 `moe.lolosia.web.dto`，`:server` 与 `:client` 的 import 不需要改。新模块的新代码可以使用 `moe.lolosia.client` / `moe.lolosia.app.*`，但不要为了「模块名好看」在同一笔改动里批量改包名 —— 那会把一次结构迁移变成全仓 import 重写。

代价是会出现跨模块的 split package（例如 `moe.lolosia.web.util.image` 同时存在于 `:codecs`，`moe.lolosia.web.util.classpath` 存在于 `:core`）。Kotlin/Native 允许这样做，但长期不利于阅读。建议在 Stage 2 构建转绿后，单独做一次「包名归位」：`:protocol` → `moe.lolosia.protocol.*`、`:core` → `moe.lolosia.core.*`、`:codecs` → `moe.lolosia.codecs.*`、`:client` → `moe.lolosia.client.*`，改完只跑编译与冒烟，不夹带其他逻辑改动。

### 3.2 依赖图

```
                         ┌──────────────┐
                         │ :router-ksp  │  (KSP 处理器，build-only)
                         └──────┬───────┘
                                │ ksp<target>
┌──────────────┐        ┌───────▼──────┐
│ :protocol    │◄───────│ :core        │
└──────┬───────┘        └───────┬──────┘
       │                        │
       │                ┌───────▼──────┐
       │                │ :codecs      │
       │                └──┬────────┬──┘
       │                   │        │
┌──────▼───────────────────▼──┐  ┌──▼─────────────────────┐
│ :server                     │  │ :client                │
└──────────────┬──────────────┘  └───────┬────────────────┘
               │                         │
        ┌──────▼─────────────────────────▼──────┐
        │ :app-desktop                          │
        └───────────────────────────────────────┘

              ┌─────────────────┐
              │ :app-android    │  (:client)
              └─────────────────┘
```

约束：

- **箭头无环**，实际方向是 `:protocol` ← `:core` ← `:codecs` ← `:server` / `:client` ← `:app-desktop`。
- `:protocol` 不依赖任何业务模块，只带 `kotlinx-serialization-json`。
- `:core` 只依赖 `:protocol`：`ZipClasspath` 用 Kotlin/Native 内置的 `platform.zlib`，不需要 `:codecs`；出站传输的 `xhrRequest`（需要 `decodeXhrData`）已下沉到 `:server`，因此 `:core` 不反向依赖 `:codecs`。
- `:codecs` 依赖 `:core`（`ExifReader` 使用 `:core` 的 `Log`；`Image` / `ThumbnailGenerator` 的 expect/actual 仍全在本模块）。
- `:server` 与 `:client` 互不依赖；两者都 `api(project(":modules:protocol"))` + `api(project(":modules:core"))` + `api(project(":modules:codecs"))`。
- `:app-desktop` 是唯一同时依赖 `:server` 与 `:client` 的模块；它只做装配，不承载业务。
- `:app-android` 只依赖 `:client`（及传递依赖），**不能**依赖 `:server`。

> 与最初方案图的偏差：原先设想 `:core` 依赖 `:codecs`。实际 `ZipClasspath` 走内置 `platform.zlib`，无需 codecs；而 `ExifReader` 需要 `:core` 的 `Log`，所以依赖方向反过来。`xhrRequest` 从 `:core` 移到 `:server`，把「通用传输」与「通道编解码」解耦。

### 3.3 一次改动的落点

| 改动类型 | 需要碰的模块 |
| --- | --- |
| 服务端接口 / 业务逻辑 | `:server` |
| 客户端同步 / 推送 / UI | `:client` |
| 新增跨端 DTO 或改契约 | `:protocol` → `:server` / `:client`（两端各自适配） |
| 新增平台能力（Windows / Linux / Android） | 对应 `:app-*`，必要时加 `:core` 的 actual |
| 图像编解码 / 缩略图 | `:codecs` |
| 构建 / 部署 / 产物命名 | 根 `build.gradle.kts` + `buildSrc/` |

日常绝大多数改动只落在 `:server` 或 `:client`。端到端新增一个接口会碰 `:protocol` + `:server` + `:client` 三个模块，这是跨端契约的固有成本；像「改个按钮文案」这种只动 `:client`。不要为了避免三个模块而把 `:client` 再拆成 `:sync` + `:ui` —— 目前同步、推送、UI 共享同一套客户端状态，拆开只会增加跨模块跳转。

---

## 4. 源集、资源与 C 库归属

### 4.1 源集规则

- **expect/actual 必须在同一个模块内**，不能跨模块配对；平台接口与它的各平台实现同理。因此：
  - `Image`、`ThumbnailGenerator`、`XhrData` 的 expect/actual 全部放 `:codecs`；
  - `Classpath`、`FileStore` 的接口与各平台实现，以及 `Env`、`Termination` 的 expect/actual 全部放 `:core`；
  - `UiExperiment` 的 expect/actual 全部放 `:client`；
  - `PlatformModule`、`Shell`、`SystemMetricsReader` 的 actual 放 `:server`。
- `:server` / `:core` / `:codecs` 都声明 `mingwX64`、`linuxArm64`、`linuxX64`，即使它们只产出 klib、没有可执行文件。
- `:client` 同样声明这三个 native 目标；Android 阶段再给 `:protocol` / `:core` / `:codecs` / `:client` 增加 `androidTarget()`。
- `-Xexpect-actual-classes` 加在 `:codecs`（`Image` 是 expect class）；其他模块如有 expect class 同理。

### 4.2 资源归属

| 资源 | 归属模块 | 进入 classpath 的方式 |
| --- | --- | --- |
| `application.json`、`permissions.yaml`、`media-rules.yaml` | `:server/src/commonMain/resources` | 插件自动收集项目依赖的源集资源 |
| `static/home/index.html`（debug 占位） | `:server/src/commonMain/resources` | 同上；release 时被 vite 产物覆盖 |
| `ui/probe.rml`、`ui/sample.png` | `:client/src/commonMain/resources` | 插件自动收集项目依赖的源集资源 |
| 平台特有资源（如某目标专用字体） | `:app-desktop/src/<target>Main/resources` | 插件自动扫描 applying project 的目标源集，无需额外配置 |

关键点：`NativeClasspath` **自动遍历项目依赖图**，把每个依赖模块中与该目标相关的源集（`commonMain` / `nativeMain` / `<target>Main`）资源并入 `classpath.zip`。`:app-desktop` 依赖 `:server` / `:client` 后，构建系统会自动合并它们的资源，**不需要手写 `classpath.resources(...)`**。合并顺序仍是「先入者优先」：applying project 自己的资源在前，依赖模块的资源按声明顺序追加在后。

### 4.3 第三方 C 库与 nativeCpp

- `thirdparty/` 继续留在 `backend/` 根，作为三个 native 目标的共享产物仓库；`:codecs` 与 `:client` 的 cinterop 都通过 `rootProject.layout.projectDirectory.dir("thirdparty")` 定位，**不要再写 `layout.projectDirectory.dir("thirdparty")`**。
- `thirdparty/build-*.ps1` 留在根，继续从 `$PSScriptRoot` 定位自身。
- `src/nativeCpp/`（`liblolosia` 门面 + RmlUi 后端胶水）迁到 `modules/client/src/nativeCpp/`。`build.ps1` 需要把 `backend` 根显式算出来：
  ```powershell
  $backendRoot = Resolve-Path "$PSScriptRoot/../../../.."   # modules/client/src/nativeCpp -> backend
  $thirdparty  = Join-Path $backendRoot "thirdparty"
  ```
  输出仍落 `thirdparty/<目标>/liblolosia.a`，RmlUi 源码树仍从 `backend/build/thirdparty/tree/...` 取。
- `lolosia.def` 迁到 `modules/client/src/nativeInterop/cinterop/lolosia.def`；`:client` 负责 cinterop 与静态库，`:app-desktop` 的 mingw 可执行文件继续补 `uiStackLinkerOpts`（SDL3 的 Windows 系统库清单）—— cinterop 只带 `staticLibraries`，系统库不会自动传递到最终链接命令。
- `:app-desktop` 的 `entryPoint` 保持 `moe.lolosia.web.main`（Stage 2 不改包名）；`main()` 仍只有一份，模式分发（`ui` / `--service` / `--daemon-console` 等）全部在这里决定。
- 图像编解码的 6 个 `.def` 迁到 `modules/codecs/src/nativeInterop/cinterop/`；cinterop 任务必须把 `thirdparty/<目标>/*.a` 声明为输入（现有 `build.gradle.kts` 的 `thirdpartyArchives` 逻辑原样搬过去），否则重建静态库后 cinterop 不会重跑。
- `ktor-client-curl` 的 `libcrypto.a` 补链逻辑（现有 `unpackCurlStaticLibs<Target>` + `linkerOpts(curlCryptoLib(target))`）必须跟**最终可执行文件**的 link 任务在同一个项目（`:app-desktop`）；klib 配置从实际声明 `ktor-client-curl` 的模块（`:core` 或 `:client`）读取。不要把它留在 `:server` 的旧脚本里，否则 linux 链接会缺 OpenSSL 符号。

### 4.4 KSP

- `ksp<目标>` 的处理器依赖属于 `:server`，跟着服务端代码走：
  ```kotlin
  listOf("MingwX64", "LinuxArm64", "LinuxX64").forEach { target ->
      add("ksp$target", project(":modules:router-ksp"))
      add("ksp$target", libs.sqlx4k.codegen)
  }
  ```
- `ksp { arg("output-package", "moe.lolosia.web.database.generated"); arg("dialect", "mysql") }` 也留在 `:server`。
- `generated/GeneratedRegistries.kt` 的 expect 签名留在 `:server/src/commonMain`；生成物仍落各目标源集。
- `:client` / `:core` / `:codecs` 不引入 KSP。

### 4.5 需要先处理的跨模块耦合

这几处如果不先拆，模块依赖会成环，或者把服务端类型带进 `:client`：

| 现状 | 问题 | 处理 |
| --- | --- | --- |
| `util/fs/FileStore.kt` 标了 `@Injectable`（来自 `util/router/Annotations.kt`） | `FileStore` 进 `:core` 后，`:core` 会反向依赖 `:server` 才能看到注解 | **实际落点**：去掉 `FileStore` 的 `@Injectable`，在 `:server` 的 `PlatformModule`（nativeMain）里手写绑定 `PosixFileStore` |
| `api/package.kt` 的形参 `ctx: Context` | `Context` 是服务端请求上下文，`:core` / `:client` 不能依赖 `:server` | `:core` 的通用传输改为 `SessionIdProvider`（`val sessionId: String?`）；`:server` 的 `Context` 实现它。**实际落点**：`xhrRequest`（需要 `decodeXhrData`）整体移到 `:server` 的 `api/XhrRequest.kt`，`:core` 只保留 `requestBytes` / `post` / `get` / `sse` / `ws` |
| `util/sse/` 同时有 `EventSource`（业务接口）与 `SseContent` / `KtorEventSource`（Ktor Server 实现） | 客户端也需要事件接口，但不能依赖 Ktor Server | `EventSource` 进 `:protocol`；`SseContent` / `KtorEventSource` / `SseService` 留 `:server`；`:client` 用自己的 SSE 消费实现 |
| `settings/AppSettings.kt` 是服务端运行配置 | 客户端需要的是服务器地址、token、同步根目录，语义不同 | `AppSettings` 留 `:server`；`:client` 新建自己的 `ClientSettings`，不要复用服务端配置类 |
| `util/koin/KoinExt.kt` 依赖 `RouterInjection` | `RouterInjection` 是服务端路由注入实现，`:core` 不能依赖它 | `util/koin/` 留 `:server`，不进 `:core` |
| `ThumbnailGenerator` 依赖 `AutoCrop` | 两者必须在同一模块，否则 `:codecs` 反向依赖 `:server` | `AutoCrop` 与 `ThumbnailGenerator` 一起进 `:codecs` |
| `XhrData.native.kt` 的 `decodeXhrData` / `XHR_DATA_CONTENT_TYPE` 是 `internal` | `internal` 只在模块内可见，`:core` 的通用传输用不到 | 改成 `public`，或由 `:codecs` 暴露一个 public 门面；不要用 `@PublishedApi` 绕过 |
| 其他跨模块使用的 `internal` 声明 | 迁移后同样不可见 | 统一搜一遍 `internal`，只把确实跨模块的改成 `public`，其余保持 |
| `dto/SystemDtos.kt`、`dto/OssDtos.kt` 直接 import `database.model.*` 实体 | 实体与 sqlx4k 注解属于 `:server`，DTO 进 `:protocol` 会成环 | 只把不依赖实体的 DTO 迁 `:protocol`；这两个文件先留 `:server`，或在 `:protocol` 定义无实体的镜像 DTO、由 `:server` 做映射 |
| `dto/FileDtos.kt` import `service.files.DEFAULT_MIME` | 服务端常量 | 把 `DEFAULT_MIME` 下沉到 `:protocol`，或让 `FileDtos` 自包含该常量 |

依赖声明按需补齐：`:protocol` 若承载 `EventSource` 需要 `kotlinx-coroutines-core`；`:core` 的通用传输需要 Ktor Client、Okio、序列化；`:codecs` 需要序列化与 6 个 cinterop；不要把这些依赖继续堆在 `:server`。

---

## 5. Gradle 根的任务与脚本

### 5.1 `settings.gradle.kts`

```kotlin
rootProject.name = "lolosia-backend"   // 不要改：部署路径 / unit 名都依赖它

include(":modules:router-ksp")
include(":modules:protocol")
include(":modules:core")
include(":modules:codecs")
include(":modules:server")
include(":modules:client")
include(":modules:app-desktop")

// Android 阶段再打开；没有 SDK 的机器不能因此构建失败
if (startParameter.projectProperties["withAndroid"] == "true") {
    include(":modules:app-android")
}
```

`rootProject.name` 保持 `lolosia-backend`。`DeployTask` 的默认远端目录是 `/srv/${rootProject.name}`，默认 unit 名是 `<rootProject.name>-native.service`；改名会让线上路径和 systemd 单元一起变。

### 5.2 根 `build.gradle.kts` 的职责

根**不应用** KMP 插件，只应用 `moe.lolosia`（为了 `deploy` 与 SSH）并注册发布任务：

```kotlin
plugins {
    id("moe.lolosia")
}

group = "moe.lolosia"
version = "1.0.0-SNAPSHOT"   // 单一版本来源；子模块按需引用 rootProject.version

// platforms.json 只在根解析一次；子模块通过 extra 读取，避免多处解析漂移
val platformsFile = rootProject.file("platforms.json")
@Suppress("UNCHECKED_CAST")
val platformConfig: Map<String, Any?> =
    if (platformsFile.exists()) JsonMapper().readValue(platformsFile, Map::class.java) as Map<String, Any?>
    else emptyMap()

extra["lolosia.platforms"] = platformConfig
```

`platforms.json` 中的 `webPath` / `webContextPath` 由 `:app-desktop` 与 `:server` 从 `rootProject.extra["lolosia.platforms"]` 取；`deploy` 段由根本身使用。**任何模块都不再直接 `File("platforms.json")`。**

### 5.3 `deploy`（只在根）

根配置部署服务器，并显式指定产物来自 `:app-desktop`：

```kotlin
@Suppress("UNCHECKED_CAST")
val deployConfig = platformConfig["deploy"] as? Map<String, Any?>

lolosia {
    deploy {
        artifactProject = ":modules:app-desktop"

        activate = deployConfig?.get("active") as? String
        // ... 与现有 build.gradle.kts 相同的 server(...) 解析 ...
        binary {
            buildType = "release"
            dockerImage = "ubuntu:24.04"
        }
    }

    docker {
        image("ubuntu:24.04") {
            mirror = "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/ubuntu:24.04"
        }
    }
}
```

子模块不配置 `deploy`，因此不会注册 `deploy*` 任务。`DeployTask` 仍在根执行，`project.rootDir` / `rootProject.name` 的语义不变，`/srv/...`、unit 文件、`application.yaml` 的查找位置全部保持现状。

### 5.4 `runDebug` / `runRelease`

根按宿主平台选择目标，工作目录固定为 `backend/` 根：

```kotlin
import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

// 根脚本要读子模块的 link 任务输出，必须先让该模块完成配置，
// 否则根 build.gradle.kts 求值时 link*Executable* 还没注册。
// ⚠️ 这一行必须放在 extra["lolosia.platforms"] 赋值之后：
//    强制求值 :app-desktop 时，它的 build.gradle.kts 会立刻读取这个 extra。
evaluationDependsOn(":modules:app-desktop")

val desktop = project(":modules:app-desktop")
val hostTarget = when {
    Os.isFamily(Os.FAMILY_WINDOWS) -> "MingwX64"
    System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "LinuxArm64"
    else -> "LinuxX64"
}

val appArgs = (findProperty("appArgs") as String?)
    ?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

fun registerRun(name: String, buildType: String) {
    val link = desktop.tasks.named(
        "link${buildType}Executable$hostTarget",
        KotlinNativeLink::class.java,
    )
    tasks.register<Exec>(name) {
        group = "application"
        dependsOn(link)
        workingDir = rootDir
        doFirst {
            executable = link.get().outputFile.absolutePath
            args.clear()
            args.addAll(appArgs)
        }
    }
}

registerRun("runDebug", "Debug")
registerRun("runRelease", "Release")
```

约定：

- `runDebug` 走 debug 链路：不跑 `vite build`，前端由 `pnpm dev` 提供。
- `runRelease` 走 release 链路：会先跑 `viteJar`，再嵌入前端产物。
- 工作目录是 `backend/`，所以磁盘 `application.yaml`、`work/`、`log/` 的位置与现在一致。**不要用 KGP 默认的 `runDebugExecutable<目标>` 作为根任务的依赖并直接执行** —— 它的默认工作目录是模块目录，会让运行时配置退回内嵌默认值。
- 传参用 `-PappArgs="ui"`、`-PappArgs="--service"` 等；参数按空格切分。
- Windows 宿主用 `mingwX64`；Linux 宿主按 `os.arch` 选 `linuxArm64` / `linuxX64`。macOS 没有目标，直接报错。

### 5.5 `buildBinaries`

> 以下沿用 5.4 的 `desktop` 与 `evaluationDependsOn(":modules:app-desktop")`；同一个根脚本里不要重复声明。

目标：一次构建全部桌面二进制；Android 模块存在时一并构建 APK；输出统一落到 `backend/build/bin/`。

命名格式：`<项目名称>-<平台>-<类型>-<版本>.<扩展名>`

| 目标 | 平台 | 类型 | 扩展名 | 示例 |
| --- | --- | --- | --- | --- |
| `mingwX64` | `windows-x64` | `release` / `debug` | `exe` | `lolosia-backend-windows-x64-release-1.0.0-SNAPSHOT.exe` |
| `linuxArm64` | `linux-arm64` | `release` / `debug` | `kexe` | `lolosia-backend-linux-arm64-release-1.0.0-SNAPSHOT.kexe` |
| `linuxX64` | `linux-x64` | `release` / `debug` | `kexe` | `lolosia-backend-linux-x64-release-1.0.0-SNAPSHOT.kexe` |
| `app-android` | `android` | `release` / `debug` | `apk` | `lolosia-backend-android-release-1.0.0-SNAPSHOT.apk` |

根脚本用一个 `Sync` 任务汇总，避免多目标分别 `Copy` 到同一目录时互相覆盖、以及旧版本文件残留：

```kotlin
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Sync

val binDir = layout.buildDirectory.dir("bin")
val buildType = (findProperty("buildType") as String? ?: "release").lowercase()
val cap = buildType.replaceFirstChar { it.uppercaseChar() }

val desktopTargets = buildList {
    // mingwX64 只在 Windows 宿主上构建；Linux 宿主跳过，避免整个任务失败
    if (Os.isFamily(Os.FAMILY_WINDOWS)) add(Triple("MingwX64", "windows-x64", "exe"))
    add(Triple("LinuxArm64", "linux-arm64", "kexe"))
    add(Triple("LinuxX64", "linux-x64", "kexe"))
}

val buildBinaries = tasks.register<Sync>("buildBinaries") {
    group = "build"
    description = "构建全部桌面二进制${if (project.findProject(":modules:app-android") != null) "与 APK" else ""}"
    into(binDir)

    desktopTargets.forEach { (target, platform, ext) ->
        val link = desktop.tasks.named(
            "link${cap}Executable$target",
            KotlinNativeLink::class.java,
        )
        dependsOn(link)
        from(link.map { it.outputFile }) {
            rename {
                "${rootProject.name}-$platform-$buildType-${rootProject.version}.$ext"
            }
        }
    }

    // Android 阶段由 :app-android 暴露 stage<BuildType>Apk 或 releaseApk 输出
    project.findProject(":modules:app-android")?.let { android ->
        dependsOn(":modules:app-android:stage${cap}Apk")
        from(android.layout.buildDirectory.dir("outputs/staged")) {
            include("*.apk")
        }
    }
}
```

约定：

- 默认 `release`；`-PbuildType=debug` 切到 debug 链路。
- 版本号直接进文件名；若包含 Windows 非法字符（`:`、`/`、`\`、`*`、`?`、`"`、`<`、`>`、`|`），先在脚本里替换成 `-`。
- `Sync` 会清掉 `build/bin/` 中不属于本次构建的旧文件，避免 `1.0.0-SNAPSHOT` 与旧版本混在一起。
- **Windows 是完整构建宿主**：可同时产出 `exe` + 两个 `kexe`。Linux 宿主只构建本机能交叉编译的目标；`mingwX64` 在 Linux 上不保证可构建，脚本应跳过并打印提示，而不是让整个任务失败。
- Android APK 的 `stage${cap}Apk` 由 `:app-android` 自己实现（它最清楚 AGP 的输出路径与 ABI split）。根只负责 `dependsOn` 与从 `build/outputs/staged/` 收集。若未启用 ABI split，就产出一个 universal APK；启用 split 时在该目录按 ABI 放多个文件，命名追加 ABI。
- `buildBinaries` **不参与 `deploy`**：`deploy` 仍直接用 `:app-desktop` 的 `linkReleaseExecutable*` 原始输出，避免把「发布目录整理」和「部署上传」耦合在一起。

### 5.6 二进制与进程模型（与任务的关系）

- 每个 native 目标只产出一个可执行文件；`main()` 按参数分发模式，**不拆成 `server.exe` / `ui.exe`**。
- **Windows**：同一个 exe 以 `--service` 由 SCM 拉起（session 0），用户登录后再以默认模式或 `--ui` 拉起 UI 进程；两者通过 loopback API 通信。`runDebug` 默认跑前台服务 / daemon，`-PappArgs=ui` 才跑 UI。
- **Linux**：同一个 kexe 由 systemd 以 `--service` 拉起；需要 UI 时另起一个进程，用参数区分。
- **Android**：一个 APK，daemon 进程 + Activity；`buildBinaries` 只收集最终 APK。
- 因此 `buildBinaries` 的命名表只有 exe / kexe / apk 三类，不出现 `server` / `ui` 后缀。

---

## 6. `buildSrc` 插件改造清单

| 文件 | 现状 | 需要的改动 |
| --- | --- | --- |
| `buildSrc/.../NativeClasspath.kt` | `layout.projectDirectory.dir("classpath/mingw")` | 改为 `rootProject.layout.projectDirectory.dir("classpath/mingw")`；`options` 不变 |
| `buildSrc/.../NativeClasspath.kt` | 只收集 applying project 自己的源集资源 | 自动遍历项目依赖图，合并依赖模块对应目标的源集资源（见 6.3） |
| `buildSrc/.../NativeClasspath.kt` | 自动覆盖 applying project 的全部 native 目标 | 保留；`:server` 通过 `classpath.enabled = false` 跳过 |
| `buildSrc/.../extension/ClasspathExtension.kt` | 无开关；`resources()` 对所有目标生效 | 增加 `enabled: Boolean = true`；增加 `resourcesFor(target, vararg paths)`，用于平台特有资源 |
| `buildSrc/.../LolosiaPlugin.kt` | deploy 在 applying project 里找 `link*Executable*` | 增加 `DeployExtension.artifactProject`；跨项目解析 link 任务与 `bootJar` |
| `buildSrc/.../LolosiaPlugin.kt` | `configureGenerateDiModule` / `configureGenerateRouteModule` 直接 `getByType<SourceSetContainer>()` | 改为 `findByType<SourceSetContainer>() ?: return`，避免 Android / 非 Java 模块被误应用时抛异常 |
| `buildSrc/.../LolosiaPlugin.kt` | `configureGenerateConstants` 只从 `viteJar.platforms` 取上下文路径 | 增加 `lolosia.constants { platform(name, contextPath) }`；`:server` 用它，`:app-desktop` 设 `enabled = false`，避免两个模块生成同名常量 |
| `buildSrc/.../task/ViteJarTask.kt` | `project.file(config.dir!!)` | 改为 `project.rootProject.file(config.dir!!)`，相对路径统一按 `backend/` 解析 |
| `buildSrc/.../task/GitArchiveTask.kt` | `project.file(config.dir!!)` | 同上，改为 `project.rootProject.file(config.dir!!)` |
| `buildSrc/.../LolosiaPlugin.kt` | 无条件 `apply("org.hidetake.ssh")` | 只在根应用（`project == project.rootProject`）；deploy 只在根注册，子模块不需要 SSH |
| `buildSrc/.../LolosiaPlugin.kt` | `gitArchive` 在 platforms 为空时也会注册 | platforms 为空时跳过注册，避免子模块出现空任务 |
| `:app-desktop/build.gradle.kts` | 无 | 显式设置 `baseName = rootProject.name`，否则产物名会变成 `app-desktop.exe` |
| `:server/build.gradle.kts` | 无 | `classpath { enabled = false }`；`constants { platform("default", contextPath) }`（`contextPath` 取自 `platforms.json`） |
| `:app-desktop/build.gradle.kts` | 无 | `constants { enabled = false }`；依赖 `:server` / `:client` 即可，资源由插件自动合并 |

### 6.1 `artifactProject` 的必要性

`LolosiaPlugin.resolveNativeArtifact()` 当前在 **applying project** 里找 `linkReleaseExecutable<目标>`。Phase 2 后，link 任务在 `:modules:app-desktop`，而 `deploy` 在根；不跨项目解析就找不到产物。实现方式：

```kotlin
val artifactProject = lolosiaExt.deploy.artifactProject
    ?.let { project.findProject(it) ?: throw GradleException("artifactProject '$it' 不存在") }
    ?: project

val linkTask = artifactProject.tasks.findByName(taskName)
...
files = artifactProject.files(linkTask.outputFile)
```

`DeployTask` 本身不用改：它在根执行，`project.rootDir`、`rootProject.name`、`rootDir.resolve(unit)` 都仍然指向 `backend/`。

### 6.2 `constants` 与 `VITE_DEFAULT`

`VITE_DEFAULT` 是服务端挂静态资源时用的编译期常量，来源是 `platforms.json` 的 `webContextPath`（默认 `home`）。Phase 2 后：

- `:server` 应用 `moe.lolosia`，从 `platforms.json` 的 `webContextPath`（缺省 `home`）配置 `constants { platform("default", contextPath) }`，生成到自己的 `commonMain`；
- `:app-desktop` **关闭** constants 生成（`constants { enabled = false }`），因为它依赖 `:server`，会看到同一份生成代码；`configureGenerateConstants` 必须先判 `enabled`，为 `false` 直接 return，不能继续回退到 `viteJar`；
- `:app-desktop` 只配置 `viteJar`，`contextPath` 同样取 `webContextPath`，保证「前端产物前缀」与「服务端常量」一致。

如果暂时不想引入 `constants` 扩展，最小替代是让 `:server` 配置 `viteJar { platform("default") { contextPath = "home" } }` 并关闭 classpath；但这样会注册一个永远不会被执行的 `viteJar` 任务，语义不如 `constants` 清楚。

### 6.3 资源合并与 `resourcesFor`

**原则：依赖模块的源集资源由构建系统自动合并，不在各模块里手写路径。**

`NativeClasspath` 在 `projectsEvaluated` 阶段遍历 applying project 的项目依赖（含传递依赖），对每个依赖模块：

1. 找到与当前目标同名的 `KotlinNativeTarget`；
2. 用 `resourceSourceSetsOf(target)` 按「越具体越优先」取出 `commonMain` / `nativeMain` / `<target>Main`；
3. 把这些源集的 `resources` 目录追加到 `classpath.zip` 的输入里。

因此 `:app-desktop` 只需要正常声明依赖：

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":modules:server"))
            implementation(project(":modules:client"))
        }
    }
}
```

`classpath` 配置里只保留真正属于「本模块额外注入」的内容：

```kotlin
lolosia {
    viteJar {
        val cfg = rootProject.extra["lolosia.platforms"] as? Map<String, Any?> ?: emptyMap()
        val webPath = cfg["webPath"] as? String
        val contextPath = cfg["webContextPath"] as? String ?: "home"
        if (!webPath.isNullOrBlank()) {
            platform("default") {
                this.contextPath = contextPath
                dir = webPath
            }
        }
    }

    classpath {
        compressed = true
        // 依赖模块的资源自动合并，不需要 resources(...)
        // 仅当某个生成任务产出额外资源目录时才用 resources(...)
        // 平台特有资源用 resourcesFor("mingwX64", ...)
    }

    constants { enabled = false }
}
```

优先级仍是「先入者优先」：vite 产物 > `:app-desktop` 目标源集 > `:app-desktop` 中间源集 > `:app-desktop` common > 依赖模块目标源集 > 依赖模块中间源集 > 依赖模块 common > 显式注入资源。`:server` 与 `:client` 的资源路径不重叠，顺序只影响极端情况下的覆盖行为。

同一模块内若存在同名资源，由 applier 自己的资源胜出；跨模块按依赖声明顺序，先声明者胜出。实现细节见 `NativeClasspath.kt` 的 `dependencyClasspathResources()`。

---

## 7. `platforms.json` 的读取与安全

`platforms.json` 继续放在 `backend/` 根，且保持 gitignored。建议字段：

```json
{
  "webPath": "E:\\path\\to\\lolosia-web",
  "webContextPath": "home",
  "deploy": {
    "active": "<server-name>",
    "servers": [
      {
        "name": "<server-name>",
        "host": "<host>",
        "port": 22,
        "user": "<user>",
        "password": "<password>",
        "mode": "systemd",
        "abi": "arm64",
        "args": []
      }
    ]
  }
}
```

读取链路：

| 使用方 | 读取内容 | 方式 |
| --- | --- | --- |
| 根 `deploy` | `deploy.active` / `deploy.servers` | 根 `build.gradle.kts` 解析 `rootProject.file("platforms.json")` |
| `:app-desktop` | `webPath` / `webContextPath` | `rootProject.extra["lolosia.platforms"]` |
| `:server` | `webContextPath` | 同上 |
| `ViteJarTask` | `dir` | 插件改为 `rootProject.file(dir)`，相对路径按 `backend/` 解析 |

安全注意：

- 文件含 SSH 明文口令，**不得入库、不得打进二进制、不得写进日志**。本方案与文档中只出现占位符。
- `rootProject.extra` 会把该 Map 留在 Gradle 配置内存里；不要打印整个 Map，也不要把它传给会输出配置的日志。
- 若启用 Gradle configuration cache，`.gradle/` 本地缓存可能包含这些值；`backend/.gitignore` 已忽略 `.gradle/`，不要提交缓存目录。

---

## 8. 迁移步骤

> 建议按阶段落地，每阶段都能独立编译。不要一次性把「目录移动 + 模块拆分 + Android」全做完再验证。

### Stage 0：先改插件（保持现有结构可编译）

1. `ClasspathExtension` 增加 `enabled` 与 `resourcesFor`。
2. `NativeClasspath` 的 `classpath/mingw` 改为根相对。
3. `DeployExtension` 增加 `artifactProject`，`LolosiaPlugin` 支持跨项目解析。
4. 增加 `ConstantsExtension`；`configureGenerateConstants` 支持 `constants`。
5. `ViteJarTask` 的相对路径改为 `rootProject.file`。
6. `SourceSetContainer` 改 `findByType`；`org.hidetake.ssh` 只在根应用；空 `gitArchive` 跳过。
7. 验证现有三目标仍可编译、链接、部署。

### Stage 1：机械迁移，根下不再有 `src/`

1. `src/` → `modules/server/src/`。
2. `router-ksp/` → `modules/router-ksp/`。
3. 当前 `build.gradle.kts` 的 KMP / cinterop / KSP / lolosia 配置整体搬到 `modules/server/build.gradle.kts`，只做路径修正：
   - `thirdpartyDir = rootProject.layout.projectDirectory.dir("thirdparty")`
   - `lolosia.def` / `nativeCpp` 路径按新位置
   - `viteJar` / `classpath` 按新位置
4. 根 `build.gradle.kts` 变成聚合器：解析 `platforms.json`、配置 `deploy`、注册 `runDebug` / `runRelease` / `buildBinaries`，产物来源暂时指向 `:modules:server`。
5. 根 `settings.gradle.kts` 改为 `include(":modules:server")`、`include(":modules:router-ksp")`。
6. 验证：
   ```powershell
   gradlew :modules:server:compileKotlinMingwX64 :modules:server:compileKotlinLinuxArm64 :modules:server:compileKotlinLinuxX64
   gradlew buildBinaries
   gradlew runDebug
   gradlew deploy
   ```

Stage 1 结束时，根目录已经满足「无 `src/`」，但 `:server` 仍是单体可执行模块。如果只想整理根目录，可以停在这里。

### Stage 2：拆出 `:protocol` / `:core` / `:codecs` / `:client` / `:app-desktop`（已完成）

1. 新建 `:protocol`，迁 `dto/` 与纯契约。
2. 新建 `:core`，迁 classpath / fs / log / time / platform / 通用出站传输，保留现有包名。
3. 新建 `:codecs`，迁 `Image` / `ThumbnailGenerator` / `AutoCrop` / `thirdparty` 门面 / `XhrData` / 图像 `.def`，配置 6 个 cinterop。
4. `:server` 改为库模块：去掉 `main()`、可执行文件声明、`nativeCpp`、UI；保留 Ktor / DB / controller / service / resources / KSP。
5. 新建 `:client`，迁 `ui/`、`resources/ui/`、`src/nativeCpp/`、`lolosia.def`、`ApiClient` / `UiExperiment` actual；新增同步 / 推送 / 本地 API 客户端代码。
6. 新建 `:app-desktop`：唯一 `main()`、三个 native 可执行文件、classpath / vite 配置、模式分发。
7. 根 `deploy` 的 `artifactProject` 改为 `:modules:app-desktop`；根 `run*` / `buildBinaries` 也改为指向它。
8. 验证（实际执行）：
   ```powershell
   gradlew :modules:protocol:compileKotlinMingwX64
   gradlew :modules:core:compileKotlinMingwX64
   gradlew :modules:codecs:compileKotlinMingwX64      # 6 个 cinterop
   gradlew :modules:client:compileKotlinMingwX64      # lolosia cinterop
   gradlew :modules:server:compileKotlinMingwX64      # KSP
   gradlew :modules:app-desktop:linkDebugExecutableMingwX64
   gradlew :modules:app-desktop:linkReleaseExecutableLinuxArm64
   gradlew buildBinaries
   gradlew runDebug
   gradlew deploy --dry-run
   ```

### Stage 3：Android（首版已跑通，业务接入待续）

1. 首版没有给 `:protocol` / `:core` / `:codecs` / `:client` 加 `androidTarget()`：目标只是「Activity 打开现有 RmlUi 面板」，
   加 Android 目标会连带补 `Image` / `XhrData` / `Classpath` 等一批 JVM actual，收益低于成本。
   当前的做法是 JNI 级宿主：`modules/client/src/nativeCpp/android/LolosiaUiAndroid.cpp` 直接驱动 `ll_ui_*`，
   数据模型初值与动作与桌面版 `UiProbeViewModel` 对齐。
2. 原生库：`thirdparty/build-android.ps1 -Abi x86_64` 用 NDK r27c 交叉编译 SDL3（共享库）、FreeType / RmlUi（静态库）与
   `liblolosia.so`，产物落 `thirdparty/androidJniLibs/x86_64/`。
3. `modules/app-android` 是**独立 Gradle 构建**（自己的 `settings.gradle.kts`），因为 AGP 9 与 buildSrc 里的 KGP 2.4.20
   共享 classpath 时会创建不兼容的 `KotlinAndroidTarget`。构建命令：`gradlew -p modules/app-android assembleDebug`。
   两个方向的编排（单向、无回环）：
   - 根 `buildBinaries` → `androidApp` → `:androidNative` → 下游 `assembleDebug`；
   - 下游单独构建 → `preBuild` → 根 `:androidNative`（叶子任务）；
   - 上游拉起下游时传 `-PlolosiaAndroidFromRoot=true`，下游跳过回调，避免嵌套根构建等根项目锁。
4. `MainActivity` 继承 SDL3 的 `SDLActivity`，加载 `libSDL3.so` + `liblolosia.so`；
   assets 直接引用 `modules/client/src/commonMain/resources`（`probe.rml` / `sample.png`），字体用系统 Noto CJK，
   图片解码走 `BitmapFactory` + `libjnigraphics`。
5. 后续（真正 Stage 3 的业务目标）：
   - 给 `:protocol` / `:core` / `:codecs` / `:client` 增加 `androidTarget()`，补齐 `androidMain` actual；
   - 把 JNI 宿主里的 probe 数据模型换回复用 `:client` 的 `UiProbeViewModel`（JNI 回调 / 共享状态）；
   - `:app-android` 增加前台 Service + 本地 loopback API；
   - `buildBinaries` 汇总 APK，并支持 arm64-v8a（x86_64 模拟器与 API 36 真机均已验证）。
6. 首版验证：
   ```powershell
   powershell -ExecutionPolicy Bypass -File thirdparty/build-android.ps1 -Abi x86_64
   gradlew -p modules/app-android assembleDebug
   adb install -r modules/app-android/build/outputs/apk/debug/lolosia-android-debug.apk
   adb shell am start -n moe.lolosia.android/.MainActivity
   ```
   在 `Medium_Phone_API_35`（x86_64）上确认面板渲染、中文与图片正常、`+1` 后计数与「最近动作」更新。

---

## 9. 验证矩阵

| 检查项 | 命令 | 期望 |
| --- | --- | --- |
| 三目标编译 | `gradlew :modules:app-desktop:compileKotlinMingwX64 :modules:app-desktop:compileKotlinLinuxArm64 :modules:app-desktop:compileKotlinLinuxX64` | 全部通过（传递编译全部依赖模块） |
| 桌面 debug 链接 | `gradlew :modules:app-desktop:linkDebugExecutableMingwX64` | 产出 debug exe |
| 桌面 release 链接 | `gradlew :modules:app-desktop:linkReleaseExecutableMingwX64` | 产出 release exe，含 vite 产物 |
| 本机调试运行 | `gradlew runDebug` | 工作目录为 `backend/`，能读到磁盘 `application.yaml` |
| 本机 release 运行 | `gradlew runRelease` | 先跑 `vite build`，再启动 |
| 全部二进制 | `gradlew buildBinaries` | `build/bin/` 下按命名表产出 exe / kexe；APK 由独立的 `modules/app-android` 构建产出 |
| 部署 | `gradlew deploy` | 根任务读取根 `platforms.json`，上传 `:app-desktop` 的 release 二进制 |
| 子模块无 deploy | `gradlew :modules:server:tasks --all`、`:modules:app-desktop:tasks --all` | 不应出现 `deploy` / `deploy<Server>` |
| classpath 资源 | 启动后访问 `static/home`、`/ui` 资源 | `:server` 与 `:client` 的资源都在内嵌 zip 中 |
| 根目录整洁 | `Get-ChildItem backend` | 无 `src/`、无 `router-ksp/` |

---

## 10. 风险与规避

| 风险 | 规避 |
| --- | --- |
| 跨模块拆 expect/actual | 每对 expect/actual 固定在同一个模块；`Image` / `ThumbnailGenerator` 进 `:codecs`，`Classpath` / `Env` / `FileStore` 进 `:core`，`UiExperiment` 进 `:client` |
| KSP 生成物只在目标源集 | `:server` 保留三套 `ksp<目标>` 配置；`GeneratedRegistries.kt` 的 expect 签名留在 `:server` |
| 两个模块生成同名 `VITE_DEFAULT` | 只有 `:server` 配置 `constants`；`:app-desktop` 设 `constants { enabled = false }` |
| 依赖模块资源没进 zip | 插件自动遍历项目依赖图，按目标合并源集资源（见 6.3） |
| 平台特有资源串台 | 平台资源放 `:app-desktop/src/<target>Main/resources`，或用新增的 `resourcesFor(target, ...)` |
| `thirdparty` / `classpath` 相对路径失效 | 插件与各模块统一用 `rootProject.layout.projectDirectory`；`nativeCpp/build.ps1` 用 `$PSScriptRoot` 反推 `backend` 根 |
| 根 `deploy` 找不到 link 任务 | 增加 `DeployExtension.artifactProject`，跨项目解析 `KotlinNativeLink` 与 `bootJar` |
| SSH 插件污染所有模块 | `org.hidetake.ssh` 只在根应用；子模块不配置 `deploy` |
| `run*` 工作目录错 | 根任务显式 `workingDir = rootDir`，不要直接依赖 KGP 的 `run*Executable*` 默认行为 |
| Linux 宿主构建不出 mingw 产物 | `buildBinaries` 按宿主能力构建；Windows 作为完整发布宿主 |
| Android 模块拖垮桌面构建 | `modules/app-android` 是独立 Gradle 构建；根构建完全不知道 AGP 的存在 |
| `platforms.json` 口令泄漏 | 文件 gitignored、只在根解析、不打印 Map；文档与提交里只用占位符 |
| 改 `rootProject.name` 破坏部署 | 保持 `lolosia-backend`；需要改名时显式设置 `deploy.binary.name` / `unitName` 并同步远端目录 |
| `build/bin` 残留旧版本 | `buildBinaries` 用 `Sync`，不是多个 `Copy` |

---

## 11. 验收清单

- [ ] `backend/` 根下不再有 `src/`、`router-ksp/`。
- [ ] `settings.gradle.kts` 只 include 桌面/服务端模块；Android 壳是独立构建（`gradlew -p modules/app-android`）。
- [ ] `platforms.json` 仍在 `backend/` 根，且仍被 `.gitignore` 覆盖。
- [ ] 根 `deploy` 任务存在；`modules/server` 与 `modules/app-desktop` 下没有 `deploy*` 任务。
- [ ] 根 `runDebug` / `runRelease` 按宿主平台执行正确目标，工作目录为 `backend/`。
- [ ] 根 `buildBinaries` 将 exe / kexe / apk 统一输出到 `backend/build/bin/`，命名符合 `<项目名称>-<平台>-<类型>-<版本>.<扩展名>`。
- [ ] `:app-desktop` 的 release 二进制包含 vite 产物；debug 二进制不包含。
- [ ] `:app-desktop` 的 classpath 同时包含 `:server` 与 `:client` 的资源。
- [ ] `:server` 仍能生成 `VITE_DEFAULT`，且没有重复常量。
- [ ] 三目标编译、release 链接、`deploy` 冒烟全部通过。
- [ ] `backend/AGENTS.md` 的文件地图、`§9` 命令、`§10` 目录树已同步更新。
- [ ] 工作区根目录（`web/`、`ftp/`、根 `doc/`、根 `AGENTS.md`）未发生改动。

---

## 12. 落地后需要同步的文档

| 文档 | 需要改的内容 |
| --- | --- |
| [AGENTS.md](../AGENTS.md) | §0 模块列表；§2 入口与 expect/actual 位置；§7 `nativeCpp` / `thirdparty` 路径；§9 构建命令；§10 文件地图 |
| [development-notes.md](./development-notes.md) | 构建 / 部署 / 冒烟命令改为根任务（`runDebug` / `buildBinaries` / `deploy`） |
| [native-migration-plan.md](./native-migration-plan.md) | 仅在其历史说明处补一句「后续已多模块化」，不改历史内容 |
| 本文件 | 实施完成后把「方案」状态改为「已实施」，并记录实际偏差 |
