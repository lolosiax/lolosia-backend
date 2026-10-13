# 开发笔记（ktor-template）

> **运行层面**的记录：构建 / 部署 / 冒烟 / 排障 / 环境依赖 / 工程形态。
> **规范性的项目风格与硬性约定在根目录 [AGENTS.md](../AGENTS.md)** —— 本文不重复那些约定，读代码前请先读它。
> 迁移详设与实测记录见 [native-migration-plan.md](./native-migration-plan.md)。
> 分支：`native`。所有内容以**实测**为准，未经验证的推断不入此表。

## 1. 当前状态（P0）

| P0 项 | 状态 | 备注 |
| --- | --- | --- |
| 0.1 KMP 多目标骨架 + KSP2 多目标 | ✅ | `kspMingwX64` / `kspLinuxArm64` / `kspLinuxX64` |
| 0.2 最小 CIO 服务（JSON + 信封 + StatusPages） | ✅ | |
| 0.3 自研路由插件（注解 + KSP + 运行时） | ✅ | 含 multipart 自研解析 |
| 0.4 `classpath.zip` 中间层 | ✅ | 构建期嵌入，双平台实测 |
| 0.5 基础设施（日志 / datetime / okio / Uuid / Base64） | ✅ | 日志自研（`util/log`）、配置系统（`settings/`）、Koin（`configuration/`） |
| 0.6 sqlx4k 数据层 | ✅ | 会话/缓存 + 用户/角色/绑定的数据访问层与 Service 层；本机 MariaDB 实测 12/12 通过 |
| 0.7 bcrypt（吃 `$2a$` cost 10） | ✅ | 移植 jBCrypt 0.4，官方向量逐字命中；已接进建用户 / 改密码 / 登录校验 |
| 0.8 鉴权拦截器链 + 登录 + 系统接口 | ✅ | 链顺序由 `@Interceptor(order)` 经 KSP 生成；已挂 Traffic(-9) / Ctx(-5) / Auth(-3) / Envelope(-2)；`/api/login` `/api/logout` + 用户/角色/绑定/菜单控制器 |
| 0.9 部署（原生二进制：systemd / docker 两种 mode） | ✅ | 见 §3 |
| 0.10 图形验证码 + `/captcha` `/verify` `/register` | ✅ | 验证码自研（`util/image` + `util/captcha`），存 `sys_cache`，5 分钟一次性消费 |

**P0 之后的架构收敛（当前形态）**：启动流程已全部落在 commonMain —— `main` 在 `Main.kt`，Ktor 装配在 `configuration/AppConfigure.kt`，
各目标源集不再有入口；平台差异只剩 `installPlatformClasspath` / `envOrNull` / `platformModule` 三处 `expect/actual`；
DI 注册表（仓库 / 服务 / 注入 / 中间件 / 路由）改由 KSP 生成 `actual`、commonMain 手写 `expect` 签名后直接调用。详见 [AGENTS.md](../AGENTS.md) §2。

**下一步**：P0 已收尾（临时探针 `DemoController` / `AuthInterceptor.DEV_PROBES` 已删，保留 `ClasspathController`），进入 P1。

## 2. 构建 / 验证工作流

### 构建

```powershell
gradlew linkReleaseExecutableMingwX64     # Windows 本地产物
gradlew linkReleaseExecutableLinuxArm64   # 开发板产物（aarch64）
gradlew linkReleaseExecutableLinuxX64     # x64 云主机产物
gradlew deploy<服务器名>                   # 构建 + 部署到开发板 / 云主机（见 §3）
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

- **Windows 上编译与链接 linuxArm64 / linuxX64 都能直接跑**，不需要整包丢给 WSL。唯一缺位的是 `objcopy`（konan 自带的 aarch64/x86_64 objcopy 是 ELF 二进制、mingw 的 objcopy 不支持 `elf64-littleaarch64` / `elf64-x86-64`、essentials 版 LLVM 无 `llvm-objcopy`），所以 `objcopyClasspath<目标>` 在 Windows 上**只把这一步借道 WSL**。可用 `classpath { wslDistro / wslUser }` 指定发行版与用户，留空用默认值。
- WSL 侧需要 `aarch64-linux-gnu-objcopy` 与 `x86_64-linux-gnu-objcopy`（Ubuntu-24.04 已装，在 `/usr/bin`）。
- 路径转换用 `toWslPath()` 显式转 `/mnt/<盘符>/…`。**不要依赖工作目录传递** —— 从 Windows 进程拉起的 `wsl` 未必能翻译 cwd（实测把 `F:\Develop\…` 传成了 `F:Develop…`）。

### 第三方 C 静态库（`thirdparty/`）

图像编解码（WebP / PNG / JPEG / GIF）用 5 个 C 库：libwebp、libpng、zlib、libjpeg-turbo、giflib。
**源码不入库、产物入库**：`thirdparty/<目标>/` 放 6 个 `.a`（`libwebp.a` 已并入编码侧的私有依赖 SharpYuv，见下），
`thirdparty/include/` 放绑定需要的公共头文件，`thirdparty/build-libs.ps1` 是一次性重建脚本。目标有 `mingwX64` / `linuxArm64` / `linuxX64` 三份。

日常 `gradlew` 构建完全不碰 C 工具链 —— `build.gradle.kts` 只把 `.def` 与 `-libraryPath` 交给 cinterop，
`.def` 里 `staticLibraries` 声明的 `.a` 会打进 klib、在链接期随可执行文件静态链入（与 ktor 的 curl klib 同一机制）。

只在换库版本 / 改编译选项时重建：

```powershell
.\thirdparty\build-libs.ps1                                # 默认重建 mingwX64 + linuxArm64 + linuxX64
.\thirdparty\build-libs.ps1 -Targets mingwX64 -Force       # 只重建一个目标（默认工作目录 build/thirdparty，已 gitignore）
```

- **不需要额外安装任何工具链**：脚本用 `~/.konan` 里 Kotlin/Native 自带的 **llvm-21 clang** 交叉编译，
  sysroot 也用 konan 自带的 `msys2-mingw-w64-x86_64-2`（mingwX64）、`aarch64-unknown-linux-gnu-…`（linuxArm64）与 `x86_64-unknown-linux-gnu-…`（linuxX64）——
  与 `konan.properties` 的 `targetSysRoot` 完全一致，ABI / glibc 版本与链接期对齐。
- ⚠️ 别用 konan 自带 msys2 里的 `cc1.exe` 做编译器：它缺运行库 DLL，一执行就是 `0xC0000135 STATUS_DLL_NOT_FOUND`。
- libpng 只认 `find_package(ZLIB)`：摆一个 zlib prefix 后传 `-DZLIB_ROOT=<prefix>`（旧的 `PNG_BUILD_ZLIB` 已废弃）；
  zlib 的真 `zconf.h` 在**构建目录**里（源码树那份被 CMake 改名成 `zconf.h.included`）。
- libjpeg-turbo 只构建静态库目标（`jpeg-static` / `turbojpeg-static`）：`all` 会连带构建 CLI 可执行文件，Linux 目标链接不过。
- ⚠️ **libwebp 1.6 起编码侧的 SharpYuv 拆成独立归档 `libsharpyuv.a`，脚本会把它合并回单个 `libwebp.a`**（`.def` 里只声明 `libwebp.a`）。分成两个归档时，K/N 的 ELF 链接（ld.gold）按归档逐个解析，顺序被重排后 `libwebp.a` 里对 `SharpYuv*` 的引用会解析不到 —— arm64 与 x64 都会 `linkReleaseExecutable<目标>` 失败（mingw 的 PE 链接器宽容，才没暴露）。
- 绑定：`.def` 在 `src/nativeInterop/cinterop/`，门面在 `src/nativeMain/kotlin/moe/lolosia/thirdparty/<库名>/`，
  包名一律 `moe.lolosia.thirdparty.<库名>`。`gradle.properties` 开了 `kotlin.mpp.enableCInteropCommonization=true`，
  让 cinterop 声明对 `nativeMain` 可见（`kotlinx.cinterop` 的指针 API 本身仍只在 native 源集可用）。
- 目标相关的类型宽度要当心：`size_t` 三个目标都是 64 位可以安全传递，`unsigned long` 不是（mingw 32 位 / linux 64 位），
  跨目标代码不要写出具体宽度。
- 对 commonMain 暴露的统一入口是 `moe.lolosia.web.util.image.Image`（`expect` 在 commonMain、`actual` 在 nativeMain）：
  `Image(bytes)` 按文件头识别并解码，`toByteArray(ImageFormat)` 编码（PNG / JPEG / WEBP；GIF 不编码），
  属性 `width / height / size / format / colorType`，draw 用 `0xAARRGGBB` 的 `fill / drawPoint / drawLine / drawRect / draw(source, x, y)`
  （前四者原值覆盖，`draw` 做 source-over 合成）；companion 上 `typeOf / sizeOf / colorTypeOf` 只读文件头、不解码。
  四个门面各提供一个 `probe(bytes): ImageInfo?`：libpng 借 `begin_read` 后的 `image.format` 读源色彩类型、
  libjpeg-turbo 读 `TJPARAM_COLORSPACE`、libwebp 用 `WebPGetFeatures`、giflib 手解逻辑屏幕描述符。

`?webp` 冒烟（本机起服务后，注意 PS 5.1 的 `curl` 是 `Invoke-WebRequest` 的别名，要写 `curl.exe`）：

```powershell
curl.exe "http://127.0.0.1:7131/res/resources/test.png?webp" -o out.webp   # 开头应是 RIFF…WEBP，响应头 Content-Type: image/webp
# 缓存落在 work/cache/webp/{源文件内容 md5}.webp；不支持的格式或转换失败回退原图
```

实测（2026-10）：png / jpg / gif 三种源图经 `?webp` 转换后用 Pillow 独立读回，四象限颜色逐一比对通过；
带 alpha 的 PNG 与带透明色的 GIF 转出的 WebP 保留了 alpha；`?webp` 打在 `.txt` 上回退 `text/plain` 原文；
`If-None-Match` 命中 ETag 返回 304。

`Image` 的独立实测（2026-10-07，临时探针 route + Pillow 比对，验完已删）：
`typeOf` / `sizeOf` / `colorTypeOf` 对 PNG(RGBA/RGB/GRAY/GRAY_ALPHA/PALETTE/16bit灰) / JPEG(RGB) / GIF(调色板) / WEBP 判定全部正确，
bmp 回 `UNKNOWN`；三种源图 → png（无损，逐像素 0 误差）/ webp / jpeg 的四象限中心颜色比对通过；
`fill / drawPoint（含半透明原值覆盖）/ drawRect（填充·描边）/ drawLine / draw(a, x, y)`（不透明覆盖、alpha=0 跳过、半透明 source-over 定点混合）逐点比对通过；
`/res/resources/rgb.bmp?webp` 仍回退原图字节。

### 冒烟（本机 mingw）

```powershell
$exe = "build\bin\mingwX64\releaseExecutable\ktor-template.exe"
$p = Start-Process $exe -ArgumentList 7160 -PassThru
Start-Sleep 4
#> P0 收尾后**不再有免鉴权探针**：冒烟一律走真实链路（注册 → 登录 → 带 token）。
# ---- 静态页 / 存活 ----
curl.exe -s -o "$env:TEMP\home.html" -w "home=%{http_code} size=%{size_download}`n" http://127.0.0.1:7160/home/

# ---- 注册（`/api/register` 在免鉴权白名单里；本轮不吃图形验证码，可直接调）----
$name = "smoke-$([DateTimeOffset]::Now.ToUnixTimeSeconds())"
'{"userName":"' + $name + '","email":"' + $name + '@example.com","password":"pass-123"}' | Set-Content -Encoding utf8 "$env:TEMP\reg.json"
curl.exe -s -X POST http://127.0.0.1:7160/api/register -H "Content-Type: application/json" -d "@$env:TEMP\reg.json"   # 200 + roleId=4（学生）

# ---- 图形验证码（答案落 sys_cache，本机查库取"标准答案"）----
$mm = 'D:\Program Files\MariaDB\bin\mysql.exe'
function CapAnswer($id) { ((& $mm --host=127.0.0.1 --port=3306 --user=root --password=123456 -D ktor-template -N -e "select value from sys_cache where path='/system/login/captcha' and tag='$id';") | Out-String).Trim() }
$c = (curl.exe -s -X POST http://127.0.0.1:7160/api/captcha | ConvertFrom-Json).data
$ans = CapAnswer $c.id
'{"id":"' + $c.id + '","code":"' + $ans + '"}' | Set-Content -Encoding utf8 "$env:TEMP\v.json"
curl.exe -s -X POST http://127.0.0.1:7160/api/verify -H "Content-Type: application/json" -d "@$env:TEMP\v.json"   # data.message=success
curl.exe -s -X POST http://127.0.0.1:7160/api/verify -H "Content-Type: application/json" -d "@$env:TEMP\v.json"   # 400 已过期（一次性消费）

# ---- 登录 → 拿会话 token ----
'{"userName":"' + $name + '","password":"pass-123"}' | Set-Content -Encoding utf8 "$env:TEMP\login.json"
$tk = (curl.exe -s -X POST http://127.0.0.1:7160/api/login -H "Content-Type: application/json" -d "@$env:TEMP\login.json" | ConvertFrom-Json).data.Authorization
"token=$tk"

# ---- 鉴权链路（无 token → 401 / 带 token → 200）----
curl.exe -s -o "$env:TEMP\a.json" -w "no-token=%{http_code}`n" -X POST http://127.0.0.1:7160/api/user/list -H "Content-Type: application/json" -d '{"pageNo":1,"pageSize":3}'   # 401
curl.exe -s -X POST http://127.0.0.1:7160/api/user/myInfo -H "Authorization: Bearer $tk"                         # 200 + 当前用户
curl.exe -s -X POST http://127.0.0.1:7160/api/role/list -H "Authorization: Bearer $tk"                           # 200 + 角色数组
curl.exe -s -X POST http://127.0.0.1:7160/api/menu/config -H "Authorization: Bearer $tk"                         # {"default":true}
curl.exe -s "http://127.0.0.1:7160/api/classpath/read?path=hello.txt" -H "Authorization: Bearer $tk"             # 内嵌资源探针

# ---- SSE（匿名建流 + 首帧 flush）----
$up = curl.exe -s -X POST http://127.0.0.1:7160/api/sse -H "x-upgrade: event-source" | ConvertFrom-Json
curl.exe -s -N --max-time 3 "http://127.0.0.1:7160/api/sse?eventSourceId=$($up.data)"   # 应见 id: 1 / event: status / data: "connect successful"

curl.exe -s -X POST http://127.0.0.1:7160/api/logout -H "Authorization: Bearer $tk"                              # 会话立即失效 → 再调 401
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
# `/api/config` 探针已随 P0 收尾删除，这里改看**监听端口**：连得上即该层配置生效
$p = Start-Process $exe -WorkingDirectory $cfg -PassThru; Start-Sleep 4
curl.exe -s -o "$env:TEMP\home.html" -w "disk-cfg=%{http_code}`n" http://127.0.0.1:7161/home/     # 200 → 磁盘配置的 port 生效
Stop-Process -Id $p.Id -Force
$env:KTOR_PORT = "7162"
$p = Start-Process $exe -WorkingDirectory $cfg -PassThru; Start-Sleep 4
curl.exe -s -o "$env:TEMP\home.html" -w "env-wins=%{http_code}`n" http://127.0.0.1:7162/home/      # 200 → 环境变量赢磁盘
Stop-Process -Id $p.Id -Force
$p = Start-Process $exe -ArgumentList 7163 -WorkingDirectory $cfg -PassThru; Start-Sleep 4
curl.exe -s -o "$env:TEMP\home.html" -w "cli-wins=%{http_code}`n" http://127.0.0.1:7163/home/      # 200 → 命令行赢环境变量
Stop-Process -Id $p.Id -Force; Remove-Item Env:KTOR_PORT
```
> `logLevel` / `datasource.url` 的覆盖改从**启动日志**（首行级别）与后续请求是否报库错来观察 —— 不再有 `/api/config` 可读。

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
$sqlDir = 'e:\Dev\Kotlin\ktor-template\sql'
$files = @(Get-ChildItem $sqlDir -Filter '*.sql' | Where-Object { $_.Name -ne 'view_user_role.sql' } | Sort-Object Name)
$files += @(Get-ChildItem $sqlDir -Filter 'view_user_role.sql')   # 视图必须最后导入
foreach ($f in $files) { & $exe --host=127.0.0.1 --port=3306 --user=root --password=123456 --default-character-set=utf8mb4 -D ktor-template -e "source $($f.FullName -replace '\\','/')" }
```
> 建完是 **8** 个表/视图（7 表 + `view_user_role`），`sys_user` 里有 `lolosia`（bcrypt `$2a$10$…`）。
> 默认配置（`src/commonMain/resources/application.json`）指向本机 `127.0.0.1:3306/ktor-template`。
> `/api/svc/selftest` 探针已随 P0 收尾删除；现在 §2 冒烟会注册 `smoke-*` 用户（学生），跑多了要清理：
>
> ```sql
> delete from sys_user_roles where user_id in (select id from sys_user where user_name like 'smoke-%');
> delete from sys_user where user_name like 'smoke-%';
> ```

### 临时二进制验机（真机 aarch64，不动 systemd）

把产物丢到板子 `/tmp` 用**另一个端口**跑，验完删掉 —— 不碰现网 `ktor-template-native.service`（监听 7129）。

> 临时实例**自带可用的配置最好**（能连库就能跑完整链路）；但就算配置里的库连不上也不会崩了 ——
> `Database` 是懒建连接池，启动只后台重连并记日志，库没连上时读库的请求返回 `503 数据库暂不可用`。
> 不过现网配置里 `ddns.enabled: true`，务必关掉再跑（免得起第二个 DDNS 更新器重复推 Cloudflare）。
> 做法：把现网 `application.yaml` 拷到临时目录、把 `enabled` 改 false，再用命令行首参覆盖端口。连接信息见平台配置（`platforms.json`）。

```powershell
Set-Content -Path "$env:TEMP\askpass.cmd" -Value "@echo <password>" -Encoding ASCII
$env:SSH_ASKPASS = "$env:TEMP\askpass.cmd"; $env:SSH_ASKPASS_REQUIRE = "force"; $env:DISPLAY = "localhost:0"
scp -P 22 -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL build\bin\linuxArm64\releaseExecutable\ktor-template.kexe <user>@<host>:/tmp/ktor-test.kexe

$remote = 'mkdir -p /tmp/ktortest; sed "s/enabled: true/enabled: false/" /srv/ktor-template/application.yaml > /tmp/ktortest/application.yaml; chmod +x /tmp/ktor-test.kexe; cd /tmp/ktortest; (setsid /tmp/ktor-test.kexe 7131 > /tmp/ktor-test.log 2>&1 < /dev/null &); sleep 8; curl -s -o /dev/null -w "home=%{http_code}\n" http://127.0.0.1:7131/home/; tail -6 /tmp/ktor-test.log'
ssh -n -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -p 22 <user>@<host> $remote
```

清理（**别用 `pkill -f ktor-test.kexe`** —— 它会把承载命令的 shell 自己也匹配上、导致后续命令不执行；按 PID kill）：

```bash
kill -9 <pid>; rm -f /tmp/ktor-test.kexe; rm -rf /tmp/ktortest
```

### 本地实时调试（起后端 + 浏览器自动化）

改前端 / 后端时**不必每次都 `deploy` 上板子** —— 本地把服务与前端 dev server 起起来，用 **Trae 的浏览器自动化 SKILL**（插件「浏览器控制」，技能 `TRAE-browseruse`）直接开页面点，实时看接口响应、静态页与 SSE 推送，边改边验。

```powershell
# 1) 后端：编译并运行本机产物（默认端口 7129）
gradlew runReleaseExecutableMingwX64                       # 一条命令编译 + 运行
# 或先 linkReleaseExecutableMingwX64，再直接跑：
.\build\bin\mingwX64\releaseExecutable\lolosia-backend.exe 7129   # 首参覆盖端口，不传用配置里的

# 2) 前端：vite dev server（端口 5005）
cd <工作区>/web
pnpm dev
```

- **配置来源**：启动目录下的 `application.yaml → yml → json` 里**第一个存在的**；`KTOR_CONFIG` 显式指定文件、`KTOR_PORT` / 首个命令行参数覆盖端口（优先级 命令行 > 环境变量 > 配置文件 > 内置默认）。内置默认见 `src/commonMain/resources/application.json`。
- **前端请求指向哪**：`web/.env.serve-dev` 里 `VITE_APP_BASE_MODE=port` + `VITE_APP_BASE_PORT=7129`，`utils/request.ts` 据此把 API 拼成 `http://<host>:7129/api/`。所以**本地后端跑在 7129 即可与 dev server（5005）互通**，不必额外配代理。
- **前端要被后端内嵌时**才需要构建：`viteJar` 是 `link*` 的依赖，`gradlew link...` / `deploy` 会顺带跑（见下条「vite 前端的增量行为」）；纯调前端用 dev server 就够。
- **用浏览器 SKILL 做多步验收**：登录 → 打开番组表 → 关注番剧 / 发布组 → 点「立即拉取」，即可当场看到列表刷新与 `anime.release` 的站内 / 桌面通知。板子上同理，只是换成板子地址。

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

- `platforms.json`（**已 gitignore，不提交**）配 `webPath` 与服务器；原生部署靠服务器上的 `"abi"` 开启（`arm64`/`aarch64` → linuxArm64，`x64`/`amd64`/`x86_64` → linuxX64；不配 = 走 JVM fat jar）。x64 云主机同样只需在其 server 项里配 `"abi": "x64"`。
- 原生还分两种 `"mode"`，**同一个 `abi` 都能用**：

  | mode | 机制 | 适用 |
  | --- | --- | --- |
  | `systemd` | 可执行文件落 `/srv/<名字>/`，插件改 `<名字>-native.service` 的 `ExecStart` 后重启 | 板子等能直接跑产物的机器 |
  | `docker` | 可执行文件与配置以挂载方式注入基础镜像（**不构建镜像、不需要 Dockerfile**） | 云主机；只想要个可管理的运行环境 |

- **`host` 必须写 `127.0.0.1`，不要写 `localhost`**：Windows 上 `localhost` 会先解析到 `::1`，而隧道只绑 IPv4。
  > 原生服务的 `host = "0.0.0.0"` 只绑 IPv4，从外部用板子的 **IPv6** 探 7130 会失败（JVM/Netty 默认双栈，所以 7129 探得通）。要 IPv6 直连就把监听地址改成 `::`。

### 默认部署目标：`platforms.json` 的 `active`（主服务器）

- `gradlew deploy`（**不带服务器名**）等价于 `gradlew deploy<active>` —— 目标由 `platforms.json` 的 `deploy.active` 决定，**当前是 `tsp-1m`（板子，主服务器）**。日常部署后端就是它。
- `deploy.servers` 里的**其他项（如 `shiyan`）当前不部署后端** —— 预留作 **CDN** 使用，`gradlew deploy` 不会碰它们，可以忽略；要单独发某台才显式 `gradlew deploy<名字>`。
- ⚠️ **单实例后台任务只在主服务器开**：番组表 RSS 轮询（`anime.enabled`）、DDNS（`ddns.enabled`）等一旦在多台机器同时开启就会重复推送（SSE 重复广播 / DNS 重复更新）。CDN 节点配置里这些开关保持 `false`。

### mode=docker（原生）细节

- 基础镜像配在 `build.gradle.kts`：`binary { dockerImage = "ubuntu:24.04" }`；镜像只需要有 glibc 与动态链接库
  （产物 `NEEDED` 里是 libc / libz / libcrypt / libgcc_s / libm / libpthread / libresolv / libutil / librt，符号版本最高 `GLIBC_2.18`）。
- **国内直连 docker.io 拉不动**（实测 `dial tcp …:443: i/o timeout`），走 `docker { image("ubuntu:24.04") { mirror = "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/ubuntu:24.04" } }`：
  插件先拉 mirror 再 `docker tag` 回原名，远端运行用的始终是 `ubuntu:24.04`。
- 运行形态（一次 `docker run -d`，全部靠挂载，无镜像层）：

  ```bash
  docker run -d --restart=always --name <名字> \
    --network host -w /app \
    -v /srv/<名字>:/app \
    -v /etc/localtime:/etc/localtime:ro \
    -v /etc/timezone:/etc/timezone:ro \
    -v /usr/share/zoneinfo:/usr/share/zoneinfo:ro \
    ubuntu:24.04 /app/<名字> [启动参数…]
  ```
  - `--network host`：应用直接绑宿主端口（无需按端口做 `-p` 映射）；容器里的 `127.0.0.1` 就是宿主，
    所以 `application.yaml` 里的库地址照常写就行；DDNS 取网卡 IPv6 也才看得到宿主网卡。
  - 时区挂**三个**路径，少一个日志就整片落在 UTC（原理见 §4 表里对应那条）。
- 回滚：旧容器**先停**再 `docker rename` 成 `<名字>-old`；新版起来后回查 `docker ps` 状态，
  只有 `Up` 才算成功（`Restarting` 一律当失败），成功后删掉 `-old`，失败则打印容器日志、删掉新容器并提示怎么切回旧容器。
- 目录布局与 systemd 形态共用：`/srv/<名字>/` 下就是 `可执行文件` + `application.yaml` + `work/` + `可执行文件.previous`。
  首次部署若远端没有 `application.yaml`，插件会弹对话框让你填（与 JVM 的 docker 流程一致）。

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
| `A problem occurred starting process 'aarch64-linux-gnu-objcopy'` | Windows 上没有可用的 aarch64/x86_64 objcopy → 借道 WSL（§2） |
| `linkReleaseExecutable<linux目标>` 报 `libwebp.a(…picture_csp_enc.c.o): … SharpYuv* 未定义` | libwebp 1.6 把 SharpYuv 拆成 `libsharpyuv.a`，两个归档的解析顺序被重排 → 用 `thirdparty/build-libs.ps1` 重建（已合并回单个 `libwebp.a`，§2） |
| `The plugin is already on the classpath with an unknown version` | buildSrc 引了 KGP 后，主工程必须**无版本**应用 Kotlin 插件 |
| buildSrc 看不到版本目录 | `buildSrc/settings.gradle.kts` 里 `create("libs").from(files("../gradle/libs.versions.toml"))` |
| classpath 里混进垃圾条目 | `ViteJarTask` 产出多一个根目录 `MANIFEST.MF`，并入时排除 |
| JS 上传的字段值带前导 CRLF | multipart 头部结束位置要**逐行扫到空行**，不能用第一个 CRLF |
| Native 上 `receiveMultipart()` 抛异常 | Ktor 3.4 在 Native 未实现 multipart → 自研纯字节解析器（`util/router/Multipart.kt`） |
| 覆盖正在运行的可执行文件失败 | `ETXTBSY` → 先 `mv old old.previous` 再 `mv new` |
| `deploy` 报「容器 … 未能存活（状态：Restarting (…)）」 | 新容器起来就崩，`docker logs` 会一并打出来。`139` = SIGSEGV（多半是缺库）；若是 `tcpBind` 的 `Address already in use`，多半端口被别的容器占着（例如更早失败留下的 `<名字>-old` 还在跑）→ `ss -lntp \| grep <端口>` |
| 容器里应用日志时间是 UTC | 基础镜像不带 tzdata，而 kotlinx-datetime 在 Linux 上认时区只有两条路：`/etc/localtime` 是指向 `/usr/share/zoneinfo` 的**软链**，或 `/etc/timezone` + `/usr/share/zoneinfo` 且 `/etc/localtime` 内容与之一致（Debian 系的 `/etc/localtime` 是拷贝而非软链）。所以三个都要挂；**给 `TZ` 环境变量没用**（该实现不读它） |
| `docker pull` 卡住或 `dial tcp …:443: i/o timeout` | docker.io 直连不通（国内常见）→ 在 `docker { image("镜像名") { mirror = "…" } }` 里配镜像源，如华为云 SWR 的 `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/…` |
| 后台脚本退出后服务跟着死 | `setsid … < /dev/null &`（见 §2） |
| PowerShell 跑 `.ps1` 中文乱码/语法错 | PS 5.1 按 ANSI 读脚本 → 脚本里只用 ASCII，或改文件编码为 UTF-8 BOM |
| PowerShell 里 `$home` 赋值报只读错误 | `$home` 是内置只读变量，换名 |
| `curl -o NUL` 在 Windows 建出名为 `NUL` 的文件 | 用 `-o $null` 或交给 WSL `rm` 删 |
| 磁盘 `application.json` 看着完全没生效 | PowerShell `Set-Content -Encoding UTF8` / 记事本会写 **BOM** → 已在解析前剥 BOM，解析失败会记 WARN（不再静默吞） |
| Koin 4 编译报 `Unresolved reference 'GlobalContext'` / `'koinApplication'` | 这俩在 Koin 4 里**不是公开 API** → 只用 `startKoin { }.koin` 并把容器实例显式往下传 |
| 冒烟时 `curl` 返回 7（连不上）但进程活着 | 端口优先级是「命令行 > 环境变量 > 磁盘配置 > 内嵌默认」，按各层端口逐个探测 `/home/` 判断实际生效端口 |
| 加 sqlx4k 后 `Clock.System` / `monthNumber` 编译不过 | sqlx4k 把 kotlinx-datetime 顶到 **0.8.0**：`Clock` 迁到 stdlib `kotlin.time.Clock`、`monthNumber/dayOfMonth` 弃用（改 `month.ordinal + 1` / `day`）。用 `gradlew dependencyInsight` 确认版本 |
| 只看到 `Could not create instance for '[Singleton: …]'`，看不到真正的错 | Koin 的 `InstanceCreationException` 把 cause 包住了，而 `Throwable.toString()` 不带 cause → 日志已改为渲染整条 cause 链（`Caused by: …`） |
| aarch64 产物在 qemu 里启动即 `SIGSEGV` | qemu-user 模拟问题，同一二进制真机正常 → 改在真机验证（§2） |
| 库没起来时接口返回 `503 数据库暂不可用`，静态页正常 | 这是**预期行为**：`Database` 懒建连接池，启动不连库、后台每 10s 重连；库恢复后自动可用。若要排查，看启动日志里 `[Database]` 那条 `数据库连接失败` 的 cause |
| 账号密码写错时进程**启动即崩**（Rust panic / `exit -1073740791`） | 根因在 `sqlx4k` 1.13.1 的 Rust 封装：它把非 `Database` 类错误全部 `panic!`（鉴权失败落到 `Error::Protocol`），而该 crate 以 `panic = "abort"` 构建、导出又是 `extern "C"`，于是 abort 整个进程、Kotlin 侧接不住。**1.14.0 已改成回 `ERROR_DATABASE`**（本工程已升级，实测鉴权失败时抛可捕获的 `SQLError`）。`MySqlAuthProbe` 预检保留，作快速失败与友好报错 |
| 升级 `sqlx4k` 后 KSP 报 `NoSuchMethodError: ...UtilsKt.validate$default(...)` | `sqlx4k-codegen` 与其编译所用的 KSP 版本必须同档：1.14.0 编译在 KSP **2.3.12** 上，插件仍是 2.3.11 就撞签名 → `libs.versions.toml` 里把 `ksp` 同步升到 **2.3.12**（升级 sqlx4k 时要一起动） |
| `pkill -f xxx` 之后同一行后续命令没执行 | `pkill -f` 会把承载命令的 shell 自身也匹配上 → 按 PID kill（见 §2 清理） |
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

- **P0 收尾已完成**：`DemoController` 与 `AuthInterceptor.DEV_PROBES` 已删（`ClasspathController` 保留作运维探针）。
- **`legacy/jvm/` 已移除**（2026-10）：可迁移的部分都已落进 `commonMain`，旧 JVM 实现不再留在工作区（它本来也不参与编译）。
  真要翻旧实现时从 git 历史取回：引入它的那次提交是 `378fbee`。
- **数据库健壮性已完成**：`Database` 改**懒建连接池 + 后台重连**（启动不建池、失败只记日志，库恢复自动可用）；库未就绪时读库请求返回 `503`。并在 `mySQL(...)` 建池前加 `MySqlAuthProbe` **裸 TCP 握手预检**（`Ok` 才建池 / `Unreachable`·`Denied` 提前判失败 / `Inconclusive` 回退 sqlx4k），挡掉 sqlx4k 在账号密码错时 panic 崩进程的问题。三情形本机实测通过。
- **P1 进度**（以当前前端 `E:\Dev\Node\lolosia-web` 的实际调用为准）：
  - **已完成**：图形验证码 `/captcha` `/verify` `/register`；头像上传/读取 `/user/avatar`；SSE `/api/sse` + `/sse/registry`（**开发板 aarch64 实机回归通过**：握手拿 id、匿名建流、首帧 `event: status` 及时 flush）；OSS 文件服务 `/api/oss/{list,getMetas,fileCheck,upload,get/{user}/{md5}}`（本机端到端冒烟通过）；`classpath.zip` 改 **DEFLATED**（读取端 `platform.zlib` inflate，Windows 与 aarch64 均实测，`/home/` 仍 200/395B）。
  - 前端已路由页面调用的接口：**全部具备**；`/user/getStudentInfoByStudentId` 属其它工程带过来的残留、并未被调用，**不迁**。
  - **仍未做**（当前前端未调用 / 可选）：`sys_logs` 落库、菜单管理 `/menu/{list,create,update,destroy,navigate}`（`menuManagement` / `roleManagement` 视图未挂路由，已弃用）、`textAi`（P2）。
- **WebP 转换已完成**（2026-10）：`/res/resources/**?webp` 走 `WebpCache` + `util/image/Image.kt`（原 `NativeImageTranscoder` 已并入 `Image`），见 §2「第三方 C 静态库」。
  两点已知差异：旧 JVM 版能转的 **bmp / tiff 没有对应解码库，一律回退原图**；`moe.lolosia.thirdparty.zlib` 目前只有 cinterop 绑定、没有 Kotlin 门面（libz.a 已由 libpng 的 `.def` 链入，绑定留着备查）。
- **前端工程**：契约以前端 `E:\Dev\Node\lolosia-web` 为准（`viteJar.contextPath` 仍是 `home`，要改得两边同时改）。
- **bcrypt 的尾巴**：`gensalt` 的随机源是 Kotlin 默认 PRNG（`Random.Default.nextBytes`），**非密码学强度** → 若要更严，用平台 CSPRNG（Linux `getrandom` / Windows `BCryptGenRandom`）做 expect/actual 替换。
- **`RoleService.create` 的自增 id 靠回查**（按 `type + roleName` 取最大 id）；若要更稳可考虑角色 id 改应用侧生成（需评估既有 1~5 号角色引用）。
- **三处多语句写还没包事务**：`UserService.create` / `update` / `delete` 目前是两条独立语句，中途失败会留中间态。`ctx.transaction { }` 已就位，包上属**行为变更**，待拍板。
- **`UserInfo`（`ctx.user`）目前零调用方**：能力已备但没有接口走到它，属未验证代码。
- **测试**：native 侧 `commonTest` 只有依赖声明、**零测试**；验证全靠临时冒烟脚本（§2）。
- **`classpath.zip` 已改 DEFLATED**（`classpath { compressed = true }`，读取端 `ZipClasspath` 用 `platform.zlib` 做 raw inflate）：本机 zip 由 ~5MB 降到 ~2.5MB，Windows 与 aarch64 均实测可读。
- **产物体积**：mingwX64 release 约 15.7 MiB（Koin / okio / kotlinx-datetime / ktor-client 的 klib 代码 + 内嵌的图像编解码静态库 libwebp·libpng·libjpeg-turbo·giflib·libz）——属预期代价，暂不优化。
- 前端 `vite*.ts` 会连 `vitest.config.ts` / `vitest.setup.ts` 一起匹配，改测试配置也会触发一次 vite 构建；收紧可写 `vite.config*.ts`。

## 6. 环境依赖清单

| 依赖 | 用途 |
| --- | --- |
| JDK 21 + Gradle Wrapper | 构建（Gradle 9.5.1；Gradle 与 KSP 处理器运行用，产物本身不需要 JVM） |
| Kotlin/Native 工具链 | 首次构建自动下载到 `~/.konan`（约 2.1GB） |
| WSL2 + Ubuntu-24.04（含 `aarch64-linux-gnu-objcopy` 与 `x86_64-linux-gnu-objcopy`） | Windows 上构建 linux 目标时的 objcopy 步骤 |
| `qemu-user-static` + `libc6-arm64-cross`（+ `libcrypt.so.1`） | 不开板子验证旧 aarch64 产物（接了 sqlx4k 后已失效，见 §2） |
| Node 24 + pnpm 10.22 + 前端工程 `E:\Dev\Node\lolosia-web` | `viteJar` 构建前端产物 |
| QUIC 客户端 `F:\Develop\Python\quic-socket` | SSH 隧道到开发板 |
| （可选）`~/.konan` 自带的 clang + sysroot | 只在重建第三方 C 静态库时用（`thirdparty/build-libs.ps1`），日常构建不需要 |
| MariaDB / MySQL | 数据库 |
| 目标机的 Docker（仅 `mode=docker` 时需要） | 原生产物挂进基础镜像运行，不构建镜像；基础镜像与国内加速源见 §3 |

## 7. 工程形态：三处位置 + Trae 工作区

同一份代码分三处存放，Trae 工作区用 **junction** 串成一个工作区（下表路径以**当前开发机**为准，换机请各自核对）：

| 内容 | 路径 | 仓库 |
| --- | --- | --- |
| 后端（本仓库） | `E:\Dev\Kotlin\ktor-template` | `gitee.com/lolosia/ktor-template`，分支 `native` |
| 前端 | `E:\Dev\Node\lolosia-web` | 派生自一份非公开的前端基座工程，分支 `master`；**`origin` 待补** |
| 工作区 | 随机器而异（本机 `doc/` 在 `E:\Dev\Trae\lolosia`） | 不是仓库 |

```
ktor-template/                                     ← Trae 工作区（junction 串起 backend / web）
├── backend/      → E:\Dev\Kotlin\ktor-template     （junction）
├── web/          → E:\Dev\Node\lolosia-web          （junction）
├── temp/
└── AGENT.md      项目概述 + 前后端代码规范
```

> 后端仓库内现在也有自己的 [AGENTS.md](../AGENTS.md)（规范性约定）与 `doc/`（运行层面记录、迁移详设），
> 与工作区根部的 `AGENT.md` 各管一层：仓库内的这份随代码走。
>
> 上游那份非公开的前端基座工程，`utils/request.ts` 里带着 **msgpack + gzip** 的请求体通道（默认关，见 §4）；
> `lolosia-web` 是它的业务派生版，已被剥掉该通道，是本项目原契约副本。
