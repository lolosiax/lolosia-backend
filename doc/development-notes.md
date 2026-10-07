# 开发笔记（ktor-template）

> **运行层面**的记录：构建 / 部署 / 冒烟 / 排障 / 环境依赖 / 工程形态。
> **规范性的项目风格与硬性约定在根目录 [AGENTS.md](../AGENTS.md)** —— 本文不重复那些约定，读代码前请先读它。
> 迁移详设与实测记录见 [native-migration-plan.md](./native-migration-plan.md)。
> 分支：`native`。所有内容以**实测**为准，未经验证的推断不入此表。

## 1. 当前状态（P0）

| P0 项 | 状态 | 备注 |
| --- | --- | --- |
| 0.1 KMP 双目标骨架 + KSP2 多目标 | ✅ | `kspMingwX64` / `kspLinuxArm64` |
| 0.2 最小 CIO 服务（JSON + 信封 + StatusPages） | ✅ | |
| 0.3 自研路由插件（注解 + KSP + 运行时） | ✅ | 含 multipart 自研解析 |
| 0.4 `classpath.zip` 中间层 | ✅ | 构建期嵌入，双平台实测 |
| 0.5 基础设施（日志 / datetime / okio / Uuid / Base64） | ✅ | 日志自研（`util/log`）、配置系统（`settings/`）、Koin（`configuration/`） |
| 0.6 sqlx4k 数据层 | ✅ | 会话/缓存 + 用户/角色/绑定的数据访问层与 Service 层；本机 MariaDB 实测 12/12 通过 |
| 0.7 bcrypt（吃 `$2a$` cost 10） | ✅ | 移植 jBCrypt 0.4，官方向量逐字命中；已接进建用户 / 改密码 / 登录校验 |
| 0.8 鉴权拦截器链 + 登录 + 系统接口 | ✅ | 链顺序由 `@Interceptor(order)` 经 KSP 生成；已挂 Traffic(-9) / Ctx(-5) / Auth(-3) / Envelope(-2)；`/api/login` `/api/logout` + 用户/角色/绑定/菜单控制器 |
| 0.9 部署（systemd 拉起原生二进制） | ✅ | 见 §3 |
| 0.10 图形验证码 + `/captcha` `/verify` `/register` | ✅ | 验证码自研（`util/image` + `util/captcha`），存 `sys_cache`，5 分钟一次性消费 |

**P0 之后的架构收敛（当前形态）**：启动流程已全部落在 commonMain —— `main` 在 `Main.kt`，Ktor 装配在 `configuration/AppConfigure.kt`，
各目标源集不再有入口；平台差异只剩 `installPlatformClasspath` / `envOrNull` / `platformModule` 三处 `expect/actual`；
DI 注册表（仓库 / 服务 / 注入 / 中间件 / 路由）改由 KSP 生成 `actual`、commonMain 手写 `expect` 签名后直接调用。详见 [AGENTS.md](../AGENTS.md) §2。

**下一步**：P0 收尾 —— 删掉 `DemoController` 与 `AuthInterceptor.DEV_PROBES` 这组临时探针，然后进 P1（OSS / 头像上传 / `sys_logs` 落库 / SSE 实机回归 / `classpath.zip` 改 DEFLATED）。

## 2. 构建 / 验证工作流

### 构建

```powershell
gradlew linkReleaseExecutableMingwX64     # Windows 本地产物
gradlew linkReleaseExecutableLinuxArm64   # 开发板产物（aarch64）
gradlew deploy<服务器名>                   # 构建 + 部署到开发板（见 §3）
```

**提交前自检**（防「文件在磁盘上但被 `.gitignore` 吃掉」）：

```powershell
# 磁盘上有、git 里没有的源文件（.kt/.kts）
$disk = Get-ChildItem -Recurse -Include *.kt,*.kts -File | Where-Object { $_.FullName -notmatch "\\build\\" } |
        ForEach-Object { $_.FullName.Replace("$PWD\","").Replace("\","/") }
$tracked = git -c core.quotepath=false ls-files "*.kt*"   # 必须关掉 quotepath，否则中文路径比较会误报
$disk | Where-Object { $tracked -notcontains $_ }
# 或者直接看被忽略的文件里有没有源码：
git ls-files --others --ignored --exclude-standard | Select-String "\.(kt|kts|sql|json|ts|vue)$"
```

- **Windows 上编译与链接 linuxArm64 都能直接跑**，不需要整包丢给 WSL。唯一缺位的是 `objcopy`（konan 自带的 aarch64 objcopy 是 ELF 二进制、mingw 的 objcopy 不支持 `elf64-littleaarch64`、essentials 版 LLVM 无 `llvm-objcopy`），所以 `objcopyClasspath<目标>` 在 Windows 上**只把这一步借道 WSL**。可用 `classpath { wslDistro / wslUser }` 指定发行版与用户，留空用默认值。
- WSL 侧需要 `aarch64-linux-gnu-objcopy`（Ubuntu-24.04 已装，在 `/usr/bin`）。
- 路径转换用 `toWslPath()` 显式转 `/mnt/<盘符>/…`。**不要依赖工作目录传递** —— 从 Windows 进程拉起的 `wsl` 未必能翻译 cwd（实测把 `F:\Develop\…` 传成了 `F:Develop…`）。

### 冒烟（本机 mingw）

```powershell
$exe = "build\bin\mingwX64\releaseExecutable\ktor-template.exe"
$p = Start-Process $exe -ArgumentList 7160 -PassThru
Start-Sleep 4
# ⚠️ 0.8 起返回值**按响应 MIME**包信封：JSON 才包成 {code,msg,data}；探针返回裸 String 是 text/plain，原样输出
curl.exe -s http://127.0.0.1:7160/api/ping          # 裸文本 pong
curl.exe -s http://127.0.0.1:7160/api/whoami        # 裸文本 uri=/api/whoami（ctx 自动注入）
curl.exe -s http://127.0.0.1:7160/api/config        # 裸文本 port=7160, db=..., logLevel=INFO（配置 + Koin→注入链路）
curl.exe -s http://127.0.0.1:7160/api/db/ping       # 裸文本 db=10.11.6-MariaDB / ktor-template（sqlx4k FFI）
curl.exe -s "http://127.0.0.1:7160/api/user/get/42?verbose=true"
curl.exe -s -X POST http://127.0.0.1:7160/api/echo -H "Content-Type: application/json" -d '{"name":"a","times":3}'
curl.exe -s -X POST http://127.0.0.1:7160/api/upload -F note=hello -F file=@src/commonMain/resources/hello.txt
curl.exe -s "http://127.0.0.1:7160/api/db/roles"                              # 5 个角色
curl.exe -s "http://127.0.0.1:7160/api/db/users"                              # view_user_role 分页
$st = curl.exe -s http://127.0.0.1:7160/api/svc/selftest                      # 服务层自检全绿 + 末尾 token=
$st
$tk = ($st | Select-String -Pattern 'token=(\S+)').Matches.Groups[1].Value    # 自检留下的会话 id

# ---- 鉴权链路（0.8 起）----
curl.exe -s -o "$env:TEMP\a.json" -w "no-token=%{http_code}`n" -X POST http://127.0.0.1:7160/api/role/list   # 401
curl.exe -s -X POST http://127.0.0.1:7160/api/role/list -H "Authorization: Bearer $tk"                        # 200 + 角色数组
'{"pageNo":1,"pageSize":3,"keys":"lolosia"}' | Set-Content -Encoding ascii "$env:TEMP\q.json"
curl.exe -s -X POST http://127.0.0.1:7160/api/user/list -H "Authorization: Bearer $tk" -H "Content-Type: application/json" -d "@$env:TEMP\q.json"
curl.exe -s -X POST http://127.0.0.1:7160/api/menu/config -H "Authorization: Bearer $tk"                     # {"default":true}
curl.exe -s -X POST http://127.0.0.1:7160/api/logout -H "Authorization: Bearer $tk"                           # 会话立即失效 → 再调 401

# ---- 图形验证码 / 注册（0.10 起）----
# 答案落在 sys_cache（path=/system/login/captcha、tag=id），本机查库就能拿到"标准答案"
$mm = 'D:\Program Files\MariaDB\bin\mysql.exe'
function CapAnswer($id) { ((& $mm --host=127.0.0.1 --port=3306 --user=root --password=123456 -D ktor-template -N -e "select value from sys_cache where path='/system/login/captcha' and tag='$id';") | Out-String).Trim() }
$c = (curl.exe -s -X POST http://127.0.0.1:7160/api/captcha | ConvertFrom-Json).data
$ans = CapAnswer $c.id
'{"id":"' + $c.id + '","code":"' + $ans + '"}' | Set-Content -Encoding ascii "$env:TEMP\v.json"
curl.exe -s -X POST http://127.0.0.1:7160/api/verify -H "Content-Type: application/json" -d "@$env:TEMP\v.json"   # data.message=success
curl.exe -s -X POST http://127.0.0.1:7160/api/verify -H "Content-Type: application/json" -d "@$env:TEMP\v.json"   # 400 已过期（一次性消费）
'{"userName":"smoke-reg","email":"smoke-reg@example.com","password":"pass-123"}' | Set-Content -Encoding ascii "$env:TEMP\v.json"
curl.exe -s -X POST http://127.0.0.1:7160/api/register -H "Content-Type: application/json" -d "@$env:TEMP\v.json"  # 200 + roleId=4

curl.exe -s -o "$env:TEMP\curl.out" -w "%{http_code} %{size_download}`n" http://127.0.0.1:7160/home/          # 200 + 前端首页
Stop-Process -Id $p.Id
```
> 期望：日志首行 `classpath 已装载：<N> 个条目（来源：classpath://）`（磁盘回退时是 `file://<目录>`，空实现是 `empty://`），
> 格式为 `[时间] [级别] [tag] 消息`（含 `[Traffic] POST /api/xxx 1ms`），且 `Uncaught Kotlin exception` 计数为 0。
> 注意 `curl -o $null` 在 PowerShell 里不会真的丢弃输出（会打到控制台），要落盘就写 `"$env:TEMP\curl.out"`。
> 中文 Body 别用 `Set-Content -Encoding ascii`（会变 `???`），要么 `-Encoding utf8`，要么直接单行 `-d`。

### 配置覆盖自测（多级优先级）

```powershell
$cfg = "$env:TEMP\ktor-cfg-test"; New-Item -ItemType Directory -Force $cfg | Out-Null
# 故意用带 BOM 的写法（PS 5.1 的 -Encoding UTF8 会写 BOM），验证 BOM 容忍
Set-Content -Path "$cfg\application.json" -Value '{"logLevel":"DEBUG","server":{"port":7161},"datasource":{"url":"mysql://127.0.0.1:3399/override-db"}}' -Encoding UTF8
$p = Start-Process $exe -WorkingDirectory $cfg -PassThru; Start-Sleep 4
curl.exe -s http://127.0.0.1:7161/api/config          # 磁盘配置生效：port=7161, logLevel=DEBUG, override-db
Stop-Process -Id $p.Id -Force
$env:KTOR_PORT = "7162"
$p = Start-Process $exe -WorkingDirectory $cfg -PassThru; Start-Sleep 4
curl.exe -s http://127.0.0.1:7162/api/config          # 环境变量赢磁盘：port=7162
Stop-Process -Id $p.Id -Force
$p = Start-Process $exe -ArgumentList 7163 -WorkingDirectory $cfg -PassThru; Start-Sleep 4
curl.exe -s http://127.0.0.1:7163/api/config          # 命令行赢环境变量：port=7163
Stop-Process -Id $p.Id -Force; Remove-Item Env:KTOR_PORT
```

### 冒烟（linuxArm64，不开板子）

用 WSL + qemu 跑 Windows 产出的 kexe（依赖：`qemu-user-static`、`libc6-arm64-cross`、`libcrypt.so.1`）：

```bash
KEXE=/mnt/f/Develop/Kotlin/ktor-template/build/bin/linuxArm64/releaseExecutable/ktor-template.kexe
setsid qemu-aarch64-static -L /usr/aarch64-linux-gnu "$KEXE" 7150 > /tmp/ktor.log 2>&1 < /dev/null &
```
> 后台跑**必须** `setsid … < /dev/null &`，否则父脚本退出会连带杀掉 qemu，日志里表现为 `JobCancellationException: LazyStandaloneCoroutine is cancelling`，很像协程 Bug 其实不是。

> ⚠️ **接了 sqlx4k 之后，qemu 这条路不能用了**：新版 aarch64 产物在 qemu 下启动即 `SIGSEGV`（`-strace` 显示崩在进程极早期，连一次 `open` 都没有），但**同一个二进制在真机 aarch64 上完全正常**。判定为 qemu-user 模拟问题，不是产物问题。**aarch64 的验证以真机为准**。

### 本地库准备（本机 MariaDB）

```powershell
$exe = 'D:\Program Files\MariaDB\bin\mysql.exe'
& $exe --host=127.0.0.1 --port=3306 --user=root --password=123456 -e "CREATE DATABASE IF NOT EXISTS ``ktor-template`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
$sqlDir = 'f:\Develop\Kotlin\ktor-template\sql'
$files = @(Get-ChildItem $sqlDir -Filter '*.sql' | Where-Object { $_.Name -ne 'view_user_role.sql' } | Sort-Object Name)
$files += @(Get-ChildItem $sqlDir -Filter 'view_user_role.sql')   # 视图必须最后导入
foreach ($f in $files) { & $exe --host=127.0.0.1 --port=3306 --user=root --password=123456 --default-character-set=utf8mb4 -D ktor-template -e "source $($f.FullName -replace '\\','/')" }
```
> 建完是 **8** 个表/视图（7 表 + `view_user_role`），`sys_user` 里有 `lolosia`（bcrypt `$2a$10$…`）。
> 默认配置（`src/commonMain/resources/application.json`）指向本机 `127.0.0.1:3306/ktor-template`。
> `/api/svc/selftest` 会留下**软删**的 `selftest-*` 用户与 `type='selftest'` 角色（视图已过滤）；要硬清：
>
> ```sql
> delete from sys_user_roles where user_id in (select id from sys_user where user_name like 'selftest-%');
> delete from sys_user where user_name like 'selftest-%';
> delete from sys_role where type = 'selftest';
> ```

### 临时二进制验机（真机 aarch64，不动 systemd）

把产物丢到板子 `/tmp` 用**另一个端口**跑，验完删掉 —— 不碰 `ktor-template-native.service`（7130）与现网 JVM（7129）：

```powershell
Set-Content -Path "$env:TEMP\askpass.cmd" -Value "@echo <密码>" -Encoding ASCII
$env:SSH_ASKPASS = "$env:TEMP\askpass.cmd"; $env:SSH_ASKPASS_REQUIRE = "force"; $env:DISPLAY = "localhost:0"
scp -P 6023 -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL build\bin\linuxArm64\releaseExecutable\ktor-template.kexe lolosia@127.0.0.1:/tmp/ktor-test.kexe
ssh -n -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -p 6023 lolosia@127.0.0.1 "chmod +x /tmp/ktor-test.kexe; cd /tmp; (setsid ./ktor-test.kexe 7131 > /tmp/ktor-test.log 2>&1 < /dev/null &); sleep 6; curl -s http://127.0.0.1:7131/api/db/ping; echo; grep 'Caused by' /tmp/ktor-test.log | head -3; pkill -f ktor-test.kexe"
```
> 板子上 `root/123456` **连不上** MariaDB（`1698 Access denied`，root 的密码登录被禁），所以 `/api/db/ping` 会返回 500 + cause；能拿到这个 cause 本身就说明 **FFI 与 MySQL 握手在 aarch64 上是通的**。

### vite 前端的增量行为

- `viteJar` 的 `inputs` 由 `lolosia { viteJar { platform(...) { cache { } } } }` 的 `dirs` / `files` / `filePatterns` 声明，**增量是好的**。
- **改 `buildSrc` 会让所有 task 失效**（Gradle 把任务实现的 classloader hash 算进 up-to-date 判定），表现为「每次构建都重跑 vite」。属正常现象，不是 bug。

## 3. 部署与开发板

- 板子：`lckfb`（RK3566，aarch64），跑 MariaDB 10.6 + nginx；部署目录 **`/srv/ktor-template`**。
- 现网 JVM 版与原生版**并行**，可对照/回滚：

  | | 单元 | 端口 | 产物 |
  | --- | --- | --- | --- |
  | JVM（现网） | `ktor-template.service` | 7129 | `ktor-template-1.0.0-SNAPSHOT-fat.jar` |
  | 原生（新） | `ktor-template-native.service` | 7130 | `ktor-template`（+ `.previous` 回滚文件） |

- `platforms.json`（**已 gitignore，不提交**）配 `webPath` 与服务器；原生部署靠服务器上的 `"abi": "arm64"` 开启（不配 = 走 JVM fat jar）。
- **`host` 必须写 `127.0.0.1`，不要写 `localhost`**：Windows 上 `localhost` 会先解析到 `::1`，而隧道只绑 IPv4。
  > 原生服务的 `host = "0.0.0.0"` 只绑 IPv4，从外部用板子的 **IPv6** 探 7130 会失败（JVM/Netty 默认双栈，所以 7129 探得通）。要 IPv6 直连就把监听地址改成 `::`。

### 两条到板子的路

**A. 隧道（默认）**

```powershell
cd F:\Develop\Python\quic-socket
.\.venv\Scripts\python.exe client.py --config "<配置 yaml>"
```
- QUIC 代理客户端，转发 `127.0.0.1:6023 → localhost:22`、`127.0.0.1:3307 → localhost:3306`。
- 配置可取自 GUI 存的 profile：`QuicSocketClient\bin\Debug\net10.0-windows\work\servers\<board-host>.json`（schema 与 `client.py` 一致，可直接当 yaml 用）。
- 隧道带宽较低，传产物明显慢。

**B. 直连板子公网 IPv6（部署提速用）**

> 板子入口域名与账号一律不写进仓库；下面用 `<board-host>` 占位，真实值放本地 `platforms.json` / 隧道 profile。

```powershell
curl.exe -s http://<board-host>/ipv6        # 返回板子的 `ip addr`，取 eth0 的全球 IPv6
```
临时把 `platforms.json` 的 `host` 改成该 IPv6、`port` 改成 `22`，部署完**记得改回 `127.0.0.1:6023`**。
> 板子的 IPv6 会变（多个 SLAAC 地址），不要写死在文档/代码里。

### 在 Windows 侧带密码 SSH（调试用）

```powershell
Set-Content -Path "$env:TEMP\askpass.cmd" -Value "@echo <密码>" -Encoding ASCII   # 密码见本地 platforms.json
$env:SSH_ASKPASS = "$env:TEMP\askpass.cmd"; $env:SSH_ASKPASS_REQUIRE = "force"; $env:DISPLAY = "localhost:0"
ssh -n -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -p 6023 <user>@127.0.0.1 "systemctl is-active ktor-template-native.service"
```
> WSL 里的 `sshpass` **够不到 Windows 侧的隧道**（NAT 模式 + 防火墙拦入站）；WSL 也没有 IPv6 路由。

## 4. 历史踩坑速查

| 症状 | 根因 / 处理 |
| --- | --- |
| 编译通过但接口全 404 | KSP 聚合没传源文件 → `Dependencies(aggregating = true, *sourceFiles)` |
| 改签名后 `actual` 与 `expect` 对不上 | 生成注册表的签名手写在 commonMain 的 `generated/GeneratedRegistries.kt`，`router-ksp` 生成 `actual`；**这是有意为之**（编译器强制配对，见 [AGENTS.md](../AGENTS.md) §2） |
| `Unresolved reference 'JvmName'` | `@file:JvmName` 不能进 commonMain → `GenerateConstantsTask` 加 `jvmTarget` 开关 |
| Gradle 报 `input file does not exist` | Gradle 9 对缺失的 `inputs.dir/file` 直接校验失败 → 改 `inputs.files(fileTree(...))` |
| 阿里云镜像缺 Ktor / sqlx4k 产物 | `settings.gradle.kts` 里 `excludeGroupByRegex("io\\.ktor")` / `io\\.github\\.smyrgeorge` 强制走 mavenCentral |
| `Failed SFTP MKDIR: …/releaseExecutable` | link 任务的 `outputs.files` 是**输出目录**不是文件 → 用 `KotlinNativeLink.outputFile` |
| `A problem occurred starting process 'aarch64-linux-gnu-objcopy'` | Windows 上没有可用的 aarch64 objcopy → 借道 WSL（§2） |
| `The plugin is already on the classpath with an unknown version` | buildSrc 引了 KGP 后，主工程必须**无版本**应用 Kotlin 插件 |
| buildSrc 看不到版本目录 | `buildSrc/settings.gradle.kts` 里 `create("libs").from(files("../gradle/libs.versions.toml"))` |
| classpath 里混进垃圾条目 | `ViteJarTask` 产出多一个根目录 `MANIFEST.MF`，并入时排除 |
| JS 上传的字段值带前导 CRLF | multipart 头部结束位置要**逐行扫到空行**，不能用第一个 CRLF |
| Native 上 `receiveMultipart()` 抛异常 | Ktor 3.4 在 Native 未实现 multipart → 自研纯字节解析器（`util/router/Multipart.kt`） |
| 覆盖正在运行的可执行文件失败 | `ETXTBSY` → 先 `mv old old.previous` 再 `mv new` |
| 后台脚本退出后服务跟着死 | `setsid … < /dev/null &`（见 §2） |
| PowerShell 跑 `.ps1` 中文乱码/语法错 | PS 5.1 按 ANSI 读脚本 → 脚本里只用 ASCII，或改文件编码为 UTF-8 BOM |
| PowerShell 里 `$home` 赋值报只读错误 | `$home` 是内置只读变量，换名 |
| `curl -o NUL` 在 Windows 建出名为 `NUL` 的文件 | 用 `-o $null` 或交给 WSL `rm` 删 |
| 磁盘 `application.json` 看着完全没生效 | PowerShell `Set-Content -Encoding UTF8` / 记事本会写 **BOM** → 已在解析前剥 BOM，解析失败会记 WARN（不再静默吞） |
| Koin 4 编译报 `Unresolved reference 'GlobalContext'` / `'koinApplication'` | 这俩在 Koin 4 里**不是公开 API** → 只用 `startKoin { }.koin` 并把容器实例显式往下传 |
| 冒烟时 `curl` 返回 7（连不上）但进程活着 | 端口优先级是「命令行 > 环境变量 > 磁盘配置 > 内嵌默认」，先用 `/api/config` 看实际生效端口 |
| 加 sqlx4k 后 `Clock.System` / `monthNumber` 编译不过 | sqlx4k 把 kotlinx-datetime 顶到 **0.8.0**：`Clock` 迁到 stdlib `kotlin.time.Clock`、`monthNumber/dayOfMonth` 弃用（改 `month.ordinal + 1` / `day`）。用 `gradlew dependencyInsight` 确认版本 |
| 只看到 `Could not create instance for '[Singleton: …]'`，看不到真正的错 | Koin 的 `InstanceCreationException` 把 cause 包住了，而 `Throwable.toString()` 不带 cause → 日志已改为渲染整条 cause 链（`Caused by: …`） |
| aarch64 产物在 qemu 里启动即 `SIGSEGV` | qemu-user 模拟问题，同一二进制真机正常 → 改在真机验证（§2） |
| MariaDB 报 `1064 ... near '; select id from ...'` | sqlx4k 在 MySQL 方言下把生成的 `insert` / `update` 拼成多语句脚本 → 写入改用单语句 `@Query` |
| 写进库的时间比本地时间差 8 小时 | 实体时间字段用了 `LocalDateTime`（驱动会话时区是 `+00:00`）→ **一律改 `kotlin.time.Instant`** |
| KSP 报 `Invalid repository method name` / `first parameter must be named 'context'` / `Suspend function cannot override non-suspend` | sqlx4k codegen 的三条硬规则（见 [AGENTS.md](../AGENTS.md) §5） |
| KSP 报 `Invalid repository method name 'findAllActive'` / `'countAllByKeys'` | 前缀白名单是**精确匹配**（**没有 `countAllBy`**）→ 改名（如 `countByKeys`） |
| 控制器形参报「`XxxRepository` 无法绑定」 | 仓库接口除了 `@Repository` 还要标 `@Injectable` |
| `in (:ids)` 运行时报 `Illegal parameter data types varchar and row for operation '='` | `@Query` 占位符编译期固定，**不支持 `IN (列表)`** → 批量在服务层循环单条 |
| 想用「库里既有的 bcrypt 哈希」验收 bcrypt，但没有匹配明文 | 库里哈希对应的是**站点登录口令**，不是 SSH/数据库口令 → 用 **jBCrypt 官方测试向量**锁死算法，再用真实账号端到端验收 |
| 源码文件不进库，而 `git status` **毫无提示**（最阴的坑） | `.gitignore` 里通用目录名漏了前导斜线：`log/` 会匹配**任意层级**的 `log` 目录，整层源码被忽略 → 运行目录收紧为 `/log/`、`/work/`，并在末尾加 `!src/**` 兜底；提交前跑 §2 的自检 |
| `executeInsert` 拿不到新插入行的自增 id | `execute*` 返回的是**受影响行数** → 需要新 id 时**插入后回查** |
| 更新后 `updated_by` 变成 NULL | 读-改-写里写了 `updatedBy = operatorId`，未接登录态时 `operatorId == null` 覆盖了原值 → 一律 `operatorId ?: current.updatedBy` |
| 前端读 `data.rows` / `data.roleId` / `row.isUse === 1` 全是 undefined | 配套前端与 legacy 后端已漂移 → 已拍板**以前端为准**（见 [AGENTS.md](../AGENTS.md) §8） |
| `Syntax error: Unclosed comment`，报错行在文件末尾 | KDoc 里写了 `/db/*`：Kotlin 块注释**可嵌套** → 注释里别出现 `/*` |
| `import io.ktor.util.getOrNull` 报 `Unresolved reference` | `Attributes.getOrNull` 是**成员函数**不是顶层函数 → 删掉 import |
| `ApiResult.err(code, cause.message)` 报 `String?` 与 `String` 不匹配 | `Throwable.message` 是 `String?` → `cause.message ?: "…"` |
| 冒烟脚本里 `(curl … \| ConvertFrom-Json).data` 报 `Invalid JSON primitive` | 探针返回的是**裸 `String`**（`text/plain`），信封按 MIME 判定故不包（见 §2 冒烟注释） |
| 中文 Body 存进去变成 `???` | 冒烟脚本用了 `Set-Content -Encoding ascii` → 换 `-Encoding utf8` 或直接单行 `-d` |
| 验证码图里一个数字都没有、只剩干扰线 | 点阵字形判据写错，`fillRect` 一次没调 → **「整幅图缺一整个图层」的排查法：造一张只含该图层的图** |
| 常量/查表初始化顺序 | 对象属性按**声明顺序**初始化：`CRC_TABLE` 引用声明在后的 `CRC_POLY` 会算出全 `0` 的表（静默错）→ 被引用者声明在前 |
| 前端把验证码 `id` 标注成 `number`，实际是随机 token | `VerifyReq.id` 挂自定义 `CaptchaIdSerializer`（数字/字符串都收） |
| KSP 抛 `FileAlreadyExistsException` | 处理器首轮 `deferred` 会让 KSP 重跑同一轮，而 sqlx4k 的 processor 没有去重标志 → **首轮就把生成物落盘**，下一轮再回头校验 |
| 前端在调试器里关掉「明文 JSON」后所有接口都不通 | `utils/request.ts` 会把 POST body 用 **msgpack + gzip** 发（`application/vnd.lolosia.xhr.data`），开关是 `debuger` store 的 `network.plainJson`，**默认明文** → 别关 |
| `pnpm install` 警告 `Ignored build scripts: core-js, esbuild, vue-demi` | pnpm 10 默认不放行依赖的构建脚本；实测 `esbuild --version` 正常、`pnpm build` 可用，**不必** `pnpm approve-builds` |

## 5. 待办 / 已知缺口

- **P0 收尾**：删掉 `DemoController` 与 `AuthInterceptor.DEV_PROBES`，然后进 P1。
- **P1 缺口**（前端已经在调、本项目还没有的端点）：`/menu/list|create|update|destroy|navigate`、`/user/getStudentInfoByStudentId`、`/api/sse` + `/sse/registry`；另外 `sys_logs` 落库（日志拦截器）与 OSS / 头像上传、SSE 实机回归。
- **前端工程**：`ktor-template-web`（`F:\Develop\Node\ktor-template-web`，`origin` 待补）尚未与契约副本 `lolosia-web` 对齐，属 P1；`viteJar.contextPath` 仍是 `home`，要改得两边同时改。
- **bcrypt 的尾巴**：`gensalt` 的随机源是 Kotlin 默认 PRNG（`Random.Default.nextBytes`），**非密码学强度** → 若要更严，用平台 CSPRNG（Linux `getrandom` / Windows `BCryptGenRandom`）做 expect/actual 替换。
- **`RoleService.create` 的自增 id 靠回查**（按 `type + roleName` 取最大 id）；若要更稳可考虑角色 id 改应用侧生成（需评估既有 1~5 号角色引用）。
- **三处多语句写还没包事务**：`UserService.create` / `update` / `delete` 目前是两条独立语句，中途失败会留中间态。`ctx.transaction { }` 已就位，包上属**行为变更**，待拍板。
- **`UserInfo`（`ctx.user`）目前零调用方**：能力已备但没有接口走到它，属未验证代码。
- **测试**：native 侧 `commonTest` 只有依赖声明、**零测试**；验证全靠临时冒烟脚本（§2）。`legacy/jvm/test/kotlin/ServerTest.kt` 未迁移。
- **`classpath.zip` 只支持 STORED**（未实现 DEFLATED），release 里约 5MB 是这个 zip；改 DEFLATED 需补 `platform.zlib` inflate。属可选优化。
- **产物体积**：mingwX64 release 约 14.5 MiB（Koin / okio / kotlinx-datetime / ktor-client 的 klib 代码）——属预期代价，暂不优化。
- 前端 `vite*.ts` 会连 `vitest.config.ts` / `vitest.setup.ts` 一起匹配，改测试配置也会触发一次 vite 构建；收紧可写 `vite.config*.ts`。

## 6. 环境依赖清单

| 依赖 | 用途 |
| --- | --- |
| JDK 21 + Gradle Wrapper | 构建（Gradle 9.5.1；Gradle 与 KSP 处理器运行用，产物本身不需要 JVM） |
| Kotlin/Native 工具链 | 首次构建自动下载到 `~/.konan`（约 2.1GB） |
| WSL2 + Ubuntu-24.04（含 `aarch64-linux-gnu-objcopy`） | Windows 上构建 linux 目标时的 objcopy 步骤 |
| `qemu-user-static` + `libc6-arm64-cross`（+ `libcrypt.so.1`） | 不开板子验证旧 aarch64 产物（接了 sqlx4k 后已失效，见 §2） |
| Node 24 + pnpm 10.22 + 前端工程 `F:\Develop\Node\ktor-template-web` | `viteJar` 构建前端产物 |
| QUIC 客户端 `F:\Develop\Python\quic-socket` | SSH 隧道到开发板 |
| MariaDB / MySQL | 数据库 |

## 7. 工程形态：三处位置 + Trae 工作区

同一份代码分三处存放，Trae 工作区用 **junction** 串成一个工作区：

| 内容 | 路径 | 仓库 |
| --- | --- | --- |
| 后端（本仓库） | `F:\Develop\Kotlin\ktor-template` | `gitee.com/lolosia/ktor-template`，分支 `native` |
| 前端 | `F:\Develop\Node\ktor-template-web` | 派生自一份非公开的前端基座工程，分支 `master`；**`origin` 待补** |
| 工作区 | `F:\Develop\Trae\ktor-template` | 不是仓库（只有 junction 与两份文件） |

```
ktor-template/                                     ← Trae 工作区
├── backend/      → F:\Develop\Kotlin\ktor-template     （junction）
├── web/          → F:\Develop\Node\ktor-template-web   （junction）
├── temp/
└── AGENT.md      项目概述 + 前后端代码规范
```

> 后端仓库内现在也有自己的 [AGENTS.md](../AGENTS.md)（规范性约定）与 `doc/`（运行层面记录、迁移详设），
> 与工作区根部的 `AGENT.md` 各管一层：仓库内的这份随代码走。
>
> 上游那份非公开的前端基座工程，`utils/request.ts` 里带着 **msgpack + gzip** 的请求体通道（默认关，见 §4）；
> `lolosia-web` 是它的业务派生版，已被剥掉该通道，是本项目原契约副本。
