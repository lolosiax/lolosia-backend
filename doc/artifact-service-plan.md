# 构建工件服务与第三方原生库管理方案

> 状态：**部分实施**。已落地：`moe.lolosia.thirdparty` 消费插件与源码回退、自建工件服务（`/repo`）、`gradlew -p thirdparty publishAll` 发布插件；
> 产物与下载的头文件统一落在 `build/thirdparty/out/`，不再入库。未完成：消费端 SHA-256 / `meta.json` 校验、`thirdparty/LICENSES` 与 `NOTICE`、公共源（GitHub Releases / Gitee）与 CI 发布、可选的历史重写。
> 范围：`thirdparty/` 的第三方 C 库产物与头文件、Gradle 解析链路、后续自建工件服务、Git 历史清理。
> 实施变更（2026-10）：`.a` / `.so` / 下载的 `.h` 已统一落到 `build/thirdparty/out/` 并从 Git 索引移除，
> `.gitignore` 覆盖 `thirdparty/{include,mingwX64,linuxArm64,linuxX64,androidJniLibs}/`；下文 §1 与 §4 保留的是决策时的历史现状与步骤。
> 现行工程约定以 [AGENTS.md](../AGENTS.md) 为准；模块化背景见 [modularization-plan.md](modularization-plan.md)。

---

## 0. 结论速览

1. **可行，但不建议把工件服务做成业务后端的一部分再暴露到公网**：
   先以「GitHub Releases / Gitee 附件 + Gradle 下载缓存」作为公共只读源；
   自建 Maven 工件服务用于**内部发布、国内镜像和源码 zip 托管**，独立域名或独立实例，只做只读分发 + 受控上传。
2. **仓库只保留**：`thirdparty/manifest.json`、构建配方（Gradle 插件里的声明）、`thirdparty/LICENSES/`。
   `.a` / `.so` 与第三方头文件移出 Git，构建时落到 `build/` 或用户级缓存。
3. **Gradle 侧做成插件而不是继续堆 ps1**：新增 `moe.lolosia.thirdparty` 插件（`buildSrc` 内），
   对外暴露 `thirdparty { }` 扩展与 `thirdpartySync` 任务；`cinterop` 的 `includeDirs` / `libraryPath`
   改为消费任务输出，配置来源优先级 `-P` > 环境变量 > `local.properties` > `platforms.json` > 内置默认。
4. **回退链**：本地缓存 → 工件源 → 源码镜像（我们托管的上游 zip）→ 上游原始地址 →
   从源码构建（可选，需要工具链）。任何一步都要 SHA-256 校验，失败给出明确错误而不是静默降级。
5. **历史清理**：先 `git rm --cached` 停止追踪（安全、可回滚）；开源前再决定是否用
   `git filter-repo` 重写历史（会改所有提交哈希，需要强推两个远端并通知协作者重新克隆）。

---

## 1. 现状与问题

### 1.1 当前入库内容（本地实测）

| 路径 | 体积 | 文件数 | 是否入库 | 说明 |
| --- | ---: | ---: | --- | --- |
| `thirdparty/include/` | 5.45 MB | 304 | 是 | RmlUi / SDL3 / FreeType / 各编解码库公共头 |
| `thirdparty/mingwX64/` | 15.32 MB | 12 | 是 | 静态库（含 6.07 MB `librmlui.a`、4.5 MB `libSDL3.a`） |
| `thirdparty/linuxArm64/` | 3.08 MB | 7 | 是 | 交叉编译静态库 |
| `thirdparty/linuxX64/` | 3.59 MB | 7 | 是 | 交叉编译静态库 |
| `thirdparty/androidJniLibs/` | 12 MB | — | **否** | 已被 `.gitignore` 忽略，脚本生成 |
| `.git/` | 10.8 MB | — | — | 压缩后体积，暂不构成限制 |

合计入库约 **27.5 MB / 336 个文件**。GitHub 没有“仓库总量 50 MB”的限制：
**单文件 50 MiB 是警告阈值，100 MiB 会被拒绝**，仓库建议控制在 1 GB 以内；
Gitee 免费仓库的容量档位更值得关注。当前体积不会触发任何限制，问题主要在长期趋势与工程规范。

### 1.2 现有构建链路

- 源码构建：`thirdparty/gradle/build-codecs.ps1`（zlib / libpng / libwebp / libjpeg-turbo / giflib / msgpack-c）、
  `thirdparty/sdl3/build.ps1`、`thirdparty/rmlui/build.ps1`、`thirdparty/freetype/build.ps1`、`thirdparty/gradle/build-android.ps1`；
  上游客服地址与版本固定，使用 Kotlin/Native 自带 clang + sysroot 构建。
- cinterop 消费点：
  - [modules/codecs/build.gradle.kts](../modules/codecs/build.gradle.kts)：6 个库，`thirdparty/include` + `thirdparty/<target>`；
  - [modules/client/build.gradle.kts](../modules/client/build.gradle.kts)：`lolosia` cinterop，`thirdparty/include` + `thirdparty/mingwX64`；
  - 根 [build.gradle.kts](../build.gradle.kts)：`androidNative` 输出 `thirdparty/androidJniLibs/<abi>`；
  - [modules/app-android/build.gradle.kts](../modules/app-android/build.gradle.kts)：`jniLibs` 直接指向同一目录。
- 自有原生门面 `liblolosia.a` / `liblolosia.so` 来自
  [modules/client/src/nativeCpp/](../modules/client/src/nativeCpp/)，**随业务代码变化，不能当成第三方工件**。

### 1.3 问题

1. 二进制进历史后只增不减，每次升级再叠约 20 MB；开源后所有克隆者都要承担。
2. 产物不可追溯：只有 `.a`，没有构建记录、Kotlin/Native 版本、编译参数。
3. 平台绑定：只提交了 mingw / linux / android 产物，其他平台贡献者拿不到。
4. 许可证合规：静态链接后分发二进制，BSD / MIT / FTL 类协议通常要求随附版权与许可声明。
   目前只有 `thirdparty/include/SDL3/SDL_copying.h` 与 RmlUi 的一个 LICENSE，
   FreeType / zlib / libpng / libwebp / libjpeg-turbo / giflib / msgpack-c 均缺。
5. 换机或 CI 必须依赖 GitHub 直连才能真正“从源码重建”，国内网络下不稳定。

---

## 2. 总体设计

### 2.1 三类资产要分开

| 类别 | 例子 | 归属 | 存储方式 |
| --- | --- | --- | --- |
| 第三方二进制 | `libz.a`、`libwebp.a`、`libSDL3.a`、`librmlui.a`、`libfreetype.a` | 外部依赖 | 工件库，按 bundle 版本发布 |
| 第三方头文件 | `build/thirdparty/out/include/**` | 外部依赖 | 随 bundle 下载或源码构建生成，不入库（`.gitignore` 已覆盖） |
| 自有原生门面 | `liblolosia.a`、`liblolosia.so` | 本仓库代码 | 由 CI/本机构建，只做任务输出缓存，不进工件库 |

`thirdparty/include` 是所有第三方库头文件的合集，**不要**把 `modules/client/src/nativeCpp/include`
（自有 `lolosia_ui.h`、SDL_image 包装头）混进来。

### 2.2 解析优先级

```text
1. 本地缓存命中（校验通过）
2. 工件源（Maven 仓库 / Release 资产）
3. 源码镜像（我们托管的源码 zip，按 sha256 校验）
4. 上游原始地址（GitHub / 官方站点）
5. 从源码构建（可选；需要 cmake/ninja/clang，默认关闭）
6. 失败：输出缺失清单、尝试过的地址、缓存路径与修复建议
```

配置项（`local.properties` / `-P` / 环境变量均可覆写）：

```properties
# 是否启用远端工件源；false 时只用本地缓存/源码构建
lolosia.artifact.enabled=true
# Maven 仓库根地址（也兼容普通 HTTP 目录）
lolosia.artifact.repo=https://artifacts.example.com/maven
# 逗号分隔的镜像（Release 资产或 OSS）
lolosia.artifact.mirrors=https://github.com/<org>/<repo>/releases/download/thirdparty-2026.10.0,https://gitee.com/<org>/<repo>/attach_files/...
# 第三方 bundle 版本（与 manifest.json 对应，不可变）
lolosia.artifact.thirdpartyVersion=2026.10.0
# 允许从源码构建（默认 false；CI 可显式开启）
lolosia.artifact.buildFromSource=false
# 缓存目录（默认 ~/.gradle/caches/lolosia/thirdparty）
lolosia.artifact.cacheDir=
# 私有源读 token（只进本地配置，禁止入库）
lolosia.artifact.readToken=
```

`platforms.json` 是部署配置且已 gitignore，适合放**部署机/CI 的镜像地址**；
`local.properties` 适合放**每台开发机**的开关与 token。两者都不入库，默认值写在插件里
（公共 Release 地址），保证 `git clone` 后不配置也能构建。

**不建议**把工件地址写死到各模块的 `build.gradle.kts`，否则每次换源都要改多个文件。

### 2.3 Bundle 规范

命名：`moe.lolosia.thirdparty:thirdparty-<target>:<bundleVersion>@zip`，例如
`moe.lolosia.thirdparty:thirdparty-mingwX64:2026.10.0@zip`。

zip 结构：

```text
thirdparty-mingwX64-2026.10.0.zip
├─ include/                 # 第三方头文件（二期才移出 Git）
├─ lib/                     # 外部静态库：zlib/png/webp/jpeg/gif/msgpack/SDL3/freetype/RmlUi...
├─ LICENSES/                # 各库许可证全文
└─ meta.json                # 见下
```

`meta.json` 必须包含：

```json
{
  "bundle": "2026.10.0",
  "target": "mingwX64",
  "kotlinNative": "2.4.20",
  "builtAt": "2026-10-22T12:00:00+08:00",
  "libraries": {
    "zlib": { "version": "1.3.1", "sha256": "..." },
    "SDL3": { "version": "3.x.y", "sha256": "..." }
  },
  "sourceRecipes": "thirdparty/manifest.json"
}
```

**静态库与 Kotlin/Native 版本强相关**。解析时必须比对 `meta.json.kotlinNative` 与
`gradle/libs.versions.toml` 的 kotlin 版本；不一致直接失败并提示重新发布 bundle，
不要冒险链接。

版本策略：bundle 版本一经发布**不可覆盖**（服务器返回 409），升级库＝发布新 bundle。

### 2.4 Gradle 插件设计

现在 `buildSrc` 里已有 `moe.lolosia` 聚合插件（部署 / classpath / vite / KSP 生成），
第三方工件解析应做成**独立的 `moe.lolosia.thirdparty`**，避免把下载逻辑塞进根构建脚本。

职责划分：

| 组件 | 位置 | 职责 |
| --- | --- | --- |
| `ThirdpartyExtension` | `buildSrc/.../extension/` | 配置源、版本、镜像、回退策略 |
| `ThirdpartySyncTask` | `buildSrc/.../task/` | 下载 / 校验 / 解压 / 原子落盘 |
| `ThirdpartyPlugin` | `buildSrc/.../` | 在根工程注册任务，向子工程暴露解析目录 Provider |
| 构建配方 | `thirdparty/manifest.json` + 插件内 DSL | 源码地址、版本、CMake/编译参数 |

对外 API（示意）：

```kotlin
// 根 build.gradle.kts
plugins { id("moe.lolosia.thirdparty") }

thirdparty {
    bundleVersion = "2026.10.0"
    repository {
        enabled = providers.gradleProperty("lolosia.artifact.enabled").orNull?.toBoolean() ?: true
        url = "..."
        mirrors = listOf("...")
    }
    fallback {
        sourceMirror = "https://artifacts.example.com/sources"
        buildFromSource = false
    }
}
```

子模块消费：

```kotlin
// modules/codecs/build.gradle.kts
val thirdparty = rootProject.extensions.getByType<ThirdpartyExtension>()
val nativeDeps = thirdparty.target("mingwX64") // Provider<Directory>

includeDirs.allHeaders(nativeDeps.dir("include").asFile)
extraOpts("-libraryPath", nativeDeps.dir("lib").asFile.absolutePath)
tasks.matching { it.name.startsWith("cinterop") }.configureEach {
    dependsOn(thirdparty.syncTaskFor("mingwX64"))
    inputs.files(nativeDeps)
}
```

要点：

- **不要在配置阶段解析下载**。用 `Provider<Directory>` + `dependsOn`，保证配置缓存（configuration cache）可用。
- 下载任务声明 `@Input`（版本、URL、sha256）与 `@OutputDirectory`（缓存目录），实现增量与离线命中。
- 解压必须防 Zip Slip（拒绝 `..` 与绝对路径），并限制解压后总大小。
- 校验失败要重试镜像，但**绝不**跳过校验。
- 缓存目录用户级（`~/.gradle/caches/lolosia/thirdparty/<bundleVersion>/<target>`），
  多个工作区共享；`local.properties` 可覆盖到 CI 的持久盘。

`k/N` 编译前由任务依赖保证目录存在；IDE 首次同步后执行一次
`./gradlew thirdpartySync` 即可让 cinterop 索引到头文件。

### 2.5 Android 独立构建

`modules/app-android` 是独立 Gradle 构建，拿不到根工程的 Provider。处理方式：

- 根构建把 Android 依赖解压到 `build/thirdparty/android/<abi>/`；
- `app-android` 通过**同一份 `local.properties`** 读取 `lolosia.artifact.cacheDir`，
  默认回退 `../../build/thirdparty/android`；
- `jniLibs.directories.add(...)` 指向该目录；
- `liblolosia.so` 由 `thirdparty/gradle/build-android.ps1` / 根 `androidNative` 产出，**不放进第三方 bundle**。

更彻底的做法是把 `app-android` 的 native 依赖也写成 `thirdpartySync` 的 Exec 调用，
但 Phase 1 先用共享路径，避免在两个构建之间传递 Provider。

---

## 3. 自建工件服务

### 3.1 是否放进业务后端

**可以，但要隔离**。把工件仓库挂在 `:modules:server` 下会增加：

- 存储与带宽压力（每版 bundle 约 20 MB，历史版本累积）；
- 公网暴露面（大文件下载、上传鉴权、路径遍历）；
- 部署耦合（开发板磁盘通常不大）。

建议：

1. **公共只读**：优先 GitHub Releases / Gitee 附件，不依赖自建服务可用性；
2. **自建服务**：独立子域或独立实例，承担私有工件、国内镜像、源码 zip 托管；
3. 若坚持同实例，至少使用独立路径（`/artifacts/**`）、独立存储卷、独立权限与限速，
   并避免让业务接口与工件下载共享同一个事件循环压力。

### 3.2 接口设计（Maven 兼容 + 原始上传）

```text
GET  /artifacts/maven/{group}/{artifact}/{version}/{file}
HEAD /artifacts/maven/{group}/{artifact}/{version}/{file}
PUT  /artifacts/maven/{group}/{artifact}/{version}/{file}
GET  /artifacts/api/list?group=...&artifact=...
```

- **读**：公开可匿名（开源工件）；私有模式要求 `Authorization: Bearer <token>`。
  直接 `call.respondFile` 流式返回，设置 `Content-Type`、`Content-Length`、`ETag`（即 sha256）。
- **写**：`@Permission("artifact.publish")` + token；**用原始 `PUT` + `application/octet-stream`**，
  不用 multipart——Ktor 3.4 在 Kotlin/Native 上 `receiveMultipart()` 未实现，
  仓库里已有相关绕行说明（[ApplicationCallExt.kt](../modules/server/src/commonMain/kotlin/moe/lolosia/web/util/ktor/ApplicationCallExt.kt)）。
- **上传流程**：流式写临时文件 → 计算 SHA-256 → 与 `X-Checksum-Sha256` 比对 →
  `ATOMIC_MOVE` 到最终路径 → 写 `.sha256` 与 `meta.json`；已存在则 409。
- **路径安全**：只接受 `[A-Za-z0-9._-]` 段，拒绝 `..`、绝对路径、符号链接。
- **Maven 兼容**：为每个 artifact 生成最小 `pom.xml`；
  固定版本不需要 `maven-metadata.xml`，如果要支持 `mvn` 浏览再补。
- **存储抽象**：`ArtifactStore` 接口，先实现 `LocalDirStore`（挂载卷），
  后续可加 `OssStore`（预签名 URL 直传/直下，减少后端带宽）。
- **数据库**：可选 `sys_artifact` 表记录 group/artifact/version/sha256/size/上传者/时间；
  服务文件本身不依赖数据库，避免库挂了连构建都做不了。

### 3.3 源码 zip 托管

同一套工件服务增加 `sources` 类型：

```text
moe.lolosia.thirdparty:zlib-source:1.3.1@tar.gz
moe.lolosia.thirdparty:RmlUi-source:6.x@tar.gz
```

`thirdparty/manifest.json` 里同时记录上游 URL、镜像 URL 与 sha256。
Gradle 先取镜像，失败再回上游；镜像命中后同样校验，避免“镜像被替换”。

### 3.4 最小实现顺序

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 | Release 资产 + `thirdpartySync` 下载缓存 | 新机器无 C 工具链可编译；断网走缓存 |
| P1 | 自建只读 Maven 仓库 + 受控上传 | CI 能 `PUT` 发布，Gradle 能按坐标拉取 |
| P2 | 源码 zip 镜像 + 回退链 | 屏蔽 GitHub 后仍能完成源码构建 |
| P3 | OSS 存储、版本列表 UI、审计与配额 | 大文件不占后端带宽 |

---

## 4. 从 Git 移除已有产物

### 4.1 只停止追踪（安全方案，推荐先做）

```powershell
# 1) 确认工作区干净并打备份标签
git status
git tag pre-thirdparty-purge

# 2) 停止追踪（保留本地文件，便于生成 bundle）
git rm -r --cached thirdparty/include thirdparty/mingwX64 thirdparty/linuxArm64 thirdparty/linuxX64

# 3) 更新 .gitignore
# thirdparty/include/
# thirdparty/mingwX64/
# thirdparty/linuxArm64/
# thirdparty/linuxX64/
# thirdparty/androidJniLibs/

git commit -m "build: 第三方原生库改由工件源提供，停止入库"
```

历史里的旧 blob 仍在（当前 `.git` 约 10.8 MB，影响可接受）。
如果头文件后续也要“只在 build 出现”，只需把 `thirdparty/include` 一并从索引移除，
并把 cinterop 的 `includeDirs` 指向 `thirdpartySync` 的输出目录。

### 4.2 重写历史（开源前可选）

会改变所有提交哈希，必须协调所有协作者与两个远端（Gitee + GitHub）。

```powershell
# 建议在全新克隆里操作，保留 mirror 备份
git clone --mirror https://gitee.com/lolosia/lolosia-project.git lolosia-backup.git
git clone https://gitee.com/lolosia/lolosia-project.git lolosia-rewrite
cd lolosia-rewrite
pip install git-filter-repo

git filter-repo --force --invert-paths `
  --path thirdparty/include `
  --path thirdparty/mingwX64 `
  --path thirdparty/linuxArm64 `
  --path thirdparty/linuxX64

git remote add origin https://gitee.com/lolosia/lolosia-project.git
git remote add github https://github.com/lolosiax/lolosia-backend.git

git push --force --all origin
git push --force --tags origin
git push --force --all github
git push --force --tags github
```

注意事项：

- 重写后所有本地克隆必须重新 clone（或 `git fetch` 后 `git reset --hard`），旧的 PR/MR 需要 rebase；
- 先在仓库设置里临时关闭分支保护，完成后再打开；
- GitHub/Gitee 可能仍可通过旧 commit SHA 访问旧对象，敏感信息场景才需要联系平台清理；
- 重写前确认没有其他人在推送；
- 这台机器上现有的 `dev/modularization` 未推送提交要先备份。

### 4.3 头文件去留

| 方案 | 优点 | 缺点 | 建议 |
| --- | --- | --- | --- |
| 一期保留 `thirdparty/include` | IDE 开箱可用、cinterop 少一步 | 多 5.45 MB | **Phase 0 采用** |
| 二期随 bundle 下载到 `build/` | 仓库最干净、头文件与库版本天然一致 | 首次同步需联网、IDE 需先跑 `thirdpartySync` | 工件链路稳定后切换 |

---

## 5. 构建配方：从 ps1 收敛到插件

现有脚本继续保留为“配方实现细节”，但**入口统一到 Gradle**：

- `thirdparty/manifest.json` 声明每个库的版本、源码 URL、镜像 URL、sha256、构建方式；
- 插件注册 `thirdpartyBuildFromSource`（仅显式开启时执行），内部按平台调用 CMake/编译命令，
  Windows 用 K/N 自带 clang，Linux 用系统 clang/gcc（CI 镜像固定）；
- `build-*.ps1` 要么删除，要么退化为调试用的薄包装，不再作为文档中的主入口；
- CI 用矩阵（`mingwX64` / `linuxArm64` / `linuxX64` / Android ABI）构建 bundle、跑一次
  cinterop 冒烟编译，再上传工件服务与 Releases；
- 每次发布同时更新 `thirdparty/manifest.json` 的 sha256，并提交到仓库。

**不要**让“源码构建”成为默认路径：它依赖工具链且耗时，会破坏“新机器直接能编”的体验；
默认走工件源，源码构建只作为回退和 CI 产出手段。

---

## 6. 许可证与合规

在 `thirdparty/LICENSES/` 下按库存放许可证全文，并生成 `NOTICE`：

| 库 | 许可证（以源码包为准） |
| --- | --- |
| SDL3 | zlib |
| RmlUi | MIT |
| FreeType | FTL / GPLv2（二选一，按使用的条款） |
| zlib | zlib |
| libpng | libpng-2.0 |
| libwebp | BSD-3-Clause |
| libjpeg-turbo | BSD-3-Clause / IJG / zlib |
| giflib | MIT |
| msgpack-c | BSL-1.0 |

bundle 的 `LICENSES/` 与部署产物一同分发；若客户端有“关于/许可”页面，列出这些组件。

---

## 7. 实施阶段与验收

### Phase 0：工件下载链路（不依赖自建服务）

1. 生成当前 target 的 bundle zip 与 sha256，上传 GitHub Releases；
2. 新增 `moe.lolosia.thirdparty` 插件与 `thirdpartySync`；
3. 改 `codecs` / `client` / 根 `androidNative` / `app-android` 四处路径；
4. 停止追踪 `.a`（头文件可暂留），更新 `.gitignore`；
5. 补 `LICENSES` / `NOTICE`。

验收：

- 全新克隆 + 空缓存 + 有网：`gradlew :modules:app-desktop:compileKotlinMingwX64` 通过；
- 断开 GitHub、只留镜像：仍然通过；
- 完全断网 + 已填充缓存：`--offline` 通过；
- `git ls-files thirdparty` 中不再出现 `.a` / `.so`；
- Android `assembleDebug` 能找到 `libSDL3.so`。

### Phase 1：自建工件服务

- 只读 Maven 路径 + 原始 PUT 上传 + RBAC `artifact.publish`；
- CI 发布 bundle 与源码 zip；
- 验收：关闭 GitHub 后，仅靠自建服务完成“清空缓存 → 编译”。

### Phase 2：开源前整理

- 头文件移出 Git（如决定）；
- 视需要执行历史重写；
- 文档更新：[AGENTS.md](../AGENTS.md) §7/§10、[development-notes.md](development-notes.md) 的构建命令；
- 轮换任何曾出现在配置示例中的 token。

---

## 8. 风险与规避

| 风险 | 影响 | 规避 |
| --- | --- | --- |
| 工件源不可用 | 新机器无法构建 | 多镜像、用户级缓存、源码回退 |
| bundle 与 K/N 版本不匹配 | 链接期诡异错误 | `meta.json` 校验 K/N 版本，不匹配直接失败 |
| 下载内容被替换 | 供应链风险 | 强制 SHA-256，镜像同样校验 |
| Zip Slip | 写到工作区外 | 解压前规范化路径并限制根目录 |
| 版本被覆盖 | 构建不可复现 | 发布版本不可变，409 拒绝覆盖 |
| 配置缓存不兼容 | 构建变慢/失败 | 任务只用 `Provider`/`@Input`，执行期不碰 `Project` |
| 历史重写打乱协作 | 提交哈希全变 | 备份 mirror、临时关保护、通知重新克隆 |
| 自建服务占用业务资源 | 下载影响 API | 独立实例/子域/存储卷，限速与配额 |
| 本地构建配方漂移 | 产物不一致 | manifest 固定版本与 sha256，CI 统一产出 |

---

## 9. 待决策

1. 公共只读源用 **GitHub Releases** 还是 **Gitee 附件**（或两者都发）？
2. 自建工件服务是否独立部署（推荐），还是挂在现有后端 `/artifacts`？
3. 头文件是 Phase 0 就移出 Git，还是 Phase 2 再移？
4. 是否在开源前重写 Git 历史（会改哈希并需要强推）？
5. 工件是否需要签名（GPG / minisign），还是 SHA-256 + HTTPS 足够？
