# lolosia-backend 项目笔记

> 本文件只记**本派生项目**相对上游模板的差异，以及这个项目**实际是怎么跑的**（实测为准）。
>
> 上游模板自带的 [development-notes.md](./development-notes.md) 与 [native-migration-plan.md](./native-migration-plan.md) **保持原样**，
> 以免与上游合并时冲突；规范性约定见 [AGENTS.md](../AGENTS.md)。`README.md` / `AGENTS.md` 无法回避，已就地同步。

## 1. 项目更名（ktor-template → lolosia-backend）

| 位置 | 旧 | 新 |
| --- | --- | --- |
| Gradle 根工程名 | `ktor-template` | `lolosia-backend` |
| group | `moe.lolosia.web` | `moe.lolosia` |
| 可执行文件 | `ktor-template` / `ktor-template.kexe` | `lolosia-backend` / `lolosia-backend.kexe` |
| systemd 单元 | `ktor-template.service` | `lolosia-backend-native.service` |
| 部署目录 | `/srv/ktor-template` | `/srv/lolosia-backend` |
| 数据库名 | `ktor-template` | `lolosia-backend` |

部分文件里仍有旧名残留，均来自模板且不影响运行，**暂不改**（改了会与上游冲突）：
`src/commonMain/kotlin/moe/lolosia/web/Main.kt` 的 KDoc、`src/commonMain/resources/static/home/index.html` 的标题、
`sql/*.sql` 顶部的导出注释头、`AppSettings.DatasourceConfig.url` 的默认库名。

## 2. WebDAV

### 2.1 配置

```yaml
webdav:
  enabled: true
  path: "/dav"
  root: "/mnt/hdd1"
  username: "lolosia"
  password: "<自定；为空则拒绝一切请求>"
  realm: "lolosia-webdav"
```

- 挂载点在 `/api` 之外：**不套 `{code,msg,data}` 信封、也不走会话鉴权**，鉴权是 HTTP Basic（客户端要原样状态码与 207 XML）。
- 存储层 `service/dav/DavStore.kt` 把 URL 分段逐段校验（拒 `.` / `..` / 空段 / 含分隔符与 NUL 的段，**以及一切以 `.` 开头的段**）后落盘，因此请求必然落在 `root` 内。
  - 拒 `.` 开头（解析与列举都跳）是为了让在线文件管理器的缩略图缓存 `<root>/.cache`（见 §3）在 WebDAV 客户端里彻底不可见，不会混进机械盘的正常目录树；**代价是用户自己建的隐藏目录 / 隐藏文件也一并看不到了**。
- 覆盖 `OPTIONS / PROPFIND / PROPPATCH / GET(含 Range→206) / HEAD / PUT / DELETE / MKCOL / COPY / MOVE / LOCK / UNLOCK`；`Depth: infinity` 直接 403。

### 2.2 端口与对外暴露（板子实测）

| 监听 | 进程 | 用途 |
| --- | --- | --- |
| `0.0.0.0:7129` | `lolosia-backend` | Ktor 本体（**明文 HTTP**） |
| `*:8443` | `caddy` | HTTPS（TLS 证书）→ `127.0.0.1:7129`，给 Windows 内置客户端用 |
| `0.0.0.0:80` | `nginx` | 兜底反代 → `127.0.0.1:7129`（Cloudflare 隧道入口） |

客户端 URL：

- Windows「映射网络驱动器」：`\\<board-host>@SSL@<tls端口>\dav` —— Basic 只肯走 HTTPS，所以对外必须经 TLS 反代（Caddy）。
- 局域网直连（手机 / 第三方 App）：`http://<板子局域网IP>:7129/dav` —— 明文，**不要映射到公网**。

### 2.3 Windows 内置客户端的已知行为（实测）

- PROPFIND **永远发空 body**（等价 `allprop`），从不显式索要 RFC 4331 配额属性。
- 即便服务端回了 `DAV:quota-available-bytes` / `DAV:quota-used-bytes` 且状态 200，资源管理器显示的仍是**本地系统盘（C:）的容量**（`DriveInfo` 实测数值等于 C 盘）。
- 结论：**Windows 内置客户端忽略 RFC 4331，改服务端无效**（当时的临时探针已完整回滚）。

## 3. 在线文件管理器

把与 WebDAV **同一块机械盘**（`files.root`，留空回退 `webdav.root`）以**只读**方式做成网页端的目录浏览 + 缩略图，挂在 `/api/files/*`。
和 WebDAV 的分工：WebDAV 是给系统文件管理器用的读写协议；在线文件管理器只读，是给「在网页上翻相册」用的。

### 3.1 配置与权限

```yaml
files:
  root: ""              # 留空回退 webdav.root
  signKey: ""           # 留空则每次启动随机生成 —— 重启后页面上已下发的直链全部失效
  signTtlSeconds: 900
  thumbnail:
    enabled: false      # ⚠️ 开发机与线上机只能开一台
    sizes: [256, 512, 1024]
    quality: 80
    scanIntervalSeconds: 600
    maxSourceBytes: 67108864
```

- 权限节点 `lolosia.files.read` **只给 `admin` 及以上**：浏览的是整块机械盘，不对普通用户开放。前端菜单按角色隐藏，直连 `/files` 对无权用户落 404。
- 缩略图缓存落在 `<root>/.cache/lolosia/thumb/v1`（`CACHE_DIR` + `THUMBNAIL_CACHE_VERSION`，版本号一改旧缓存整体作废）。**该目录对 WebDAV 隐藏**，见 §2.1。
- `signKey` 未配置时启动日志会打一条 WARN。线上建议显式配一个固定随机串，否则每次重启后旧直链失效（不影响功能，前端重新列目录会拿到新签名）。
- `thumbnail.enabled` 只能在一台机器上开：两台同时写同一份缓存会互相覆盖，也会把机械盘两头都拖慢。

### 3.2 直链为什么走 URL 签名而不是会话

`<img src>` 由浏览器原生发起，**带不了 `Authorization` 头**。于是 `/api/files/{thumb,raw}` 放进 `AuthInterceptor`
白名单，改为校验 URL 签名：`exp` + `sig` 由 `files/list` / `files/detail` 下发，
`sig = HMAC-SHA256(key, "相对路径|过期时间")`（okio 自带 `hmacSha256` / `hex`，没有自研加密）。

白名单只免掉「没登录就 401」，鉴权责任因此落回控制器自己 —— 这与 `/api/user/avatar` 自己判登录态是同一类做法。

实测（本机 mingw + 本地库，真实走链路）：

| 请求 | 结果 |
| --- | --- |
| `thumb?path=…&size=256&exp=…&sig=…`（合法、未过期） | `200`，`image/webp`，7790 字节 |
| 同一条 URL 篡改 `sig` | `403` |
| 只带 `path`、不带 `exp`/`sig` | `403` |
| 完全不带任何请求头 | 不是 401（证明确实在白名单里，是签名在把关） |

SSE 事件 `files:thumb` 的负载里带的是 `path` + **新签发的签名**，所以只推给有 `lolosia.files.read` 的连接
（`SseService.broadcast` 增加授权判定，匿名连接一律不推）—— 否则等于把机械盘的读权限白送给所有人。

### 3.3 Windows 中文文件名：进程代码页

mingwX64 下 okio 走的是 **CRT 窄字符 API**：进程代码页不是 UTF-8 时，**中文文件名会被解错并直接丢弃**。
症状很隐蔽 —— 目录里「看着少文件」，且只在含中文的路径上出现。

修法：`classpath/mingw/app.manifest`（`activeCodePage=UTF-8`）由 `classpath.rc` 以 `RT_MANIFEST` 引用，
随 `classpath.res` 一起链进 exe（`NativeClasspath.kt` 已把 `app.manifest` 声明为 windres 任务的输入，改它同样会触发重新链接）。

### 3.4 目录列举的实测性能

`files/list` / `files/dirs` 对每个**子目录**都要多一次目录枚举：前端得知道「里面有没有东西」（`hasChildren`）与
「有没有子目录」（`hasSubdirs`）—— 后者是**独立标志位**，判定它还要再过一遍子项的 stat。这些都是纯元数据操作、
不碰文件内容，但串行在 WebDAV 映射盘上就是「子目录个数」次往返。

| 被列的目录 | 子目录数 | 耗时 |
| --- | --- | --- |
| 根 | 2 | ~35 ms |
| 下一层 | 2 | ~80 ms |
| 再下一层 | 14 | ~700 ms（并发改造前 **1348 ms**） |

**14 个子目录那档是差得最明显的**：每个子目录一次往返，串行就是 14 次累加。改法是让 `list` / `dirs`
变成 `suspend`，内部 `withContext(Dispatchers.Default) + coroutineScope + async/awaitAll`（native 上没有
`Dispatchers.IO`），于是只等最慢的那一个。

## 4. 部署要点（systemd）

- 服务**以 `lolosia` 用户运行**：远端 `/srv/lolosia-backend/lolosia-backend-native.service` 的 `[Service]` 段手工加了 `User=lolosia` / `Group=lolosia`。
  - 原因：WebDAV 若以 root 运行，它新建的目录会属 `root:root 755`，SMB 侧（`lolosia`）写不进去 —— 表现为「局域网里往共享复制文件失败」。
  - 部署插件**不覆盖远端已存在的 unit**（只就地 patch `ExecStart` 行），所以这份手工改动能长期保留。
  - ⚠️ 仓库里的 `lolosia-backend-native.service` 模板**不含** `User=`：新机器首次部署后需要手工补，否则会重现上面的属主问题。
- 磁盘配置 `/srv/lolosia-backend/application.yaml` 与内嵌 `application.json` 相互独立，改配置不用重编二进制。

## 5. 旁路服务

- **Samba**：`/etc/samba/smb.conf` 里的 `include = /etc/samba/conf.d/*.conf` 必须放在 `[global]` 段**末尾**（紧邻 Share Definitions 之前）。
  放在段开头，include 展开的 `[hdd1]` 之后的所有全局参数都会被解析进服务段，日志狂刷 `Global parameter ... found in service section!`；改完用 `testparm -s` 复核（应只回 `Loaded services file OK.`）。
- 共享 `[hdd1]` → `/mnt/hdd1`：`read only = no`、`valid users = lolosia`、`write list = lolosia`、`create mask = 0664` / `directory mask = 0775`。

## 6. 网络与吞吐实测（2026-10）

| 路径 | 实测 |
| --- | --- |
| 板内 loopback（本服务） | 118 MB/s |
| HDD 顺序写（`dd`） | 352 MB/s |
| PC 有线 → 板子 WebDAV / HTTP | 75–114 MB/s |
| PC 有线 → 板子 SMB | 17.2 MB/s（SMB 在这块板子上的天花板） |
| 经 Wi-Fi → 板子 | 3.2 MB/s |

- 板子无线模组是 **Broadcom BCM43456**（驱动 `bcmsdh_sdmmc`），**2.4GHz 单频**（`WIFI-PROPERTIES.5GHZ: no`）：
  实测协商速率 **58.5 Mb/s**、信号 -53 dBm、链路质量 5/5，但实际吞吐只有 ~3 MB/s。
  → 大文件传输**不要走 Wi-Fi**，走有线（`eth0`）。
- 板子两块网卡：`eth0`（接光猫）+ `wlan0`（接路由器 Wi-Fi）；经路由器 NAT 进来的 SMB 客户端，在板子上显示为**路由器 WAN 侧的地址**。
- 域名**只有 AAAA 记录、没有 A 记录**时，不支持纯 IPv6 的客户端（部分手机 WebDAV App）会连不上 —— 局域网内改用板子 IP 直连即可绕开。
- SMB 写入慢先看**客户端那一侧**：板子侧 4 核基本空闲、`smbd` 只占单核约 1/4、磁盘写入几乎为零（数据在 page cache），这些都不是瓶颈。

## 7. 仓库 · 远端 · 分支布局（2026-10-09 迁至 Gitee）

> 上游 [development-notes.md](./development-notes.md) §7「工程形态」记的是**旧机器**的路径与仓库名（`E:\Dev\…`、`ktor-template`），
> 本机实际布局以本节为准。

### 7.1 远端

| remote | 地址 | 说明 |
| --- | --- | --- |
| `origin` | `https://gitee.com/lolosia/lolosia-project.git` | **主力远端**（2026-10-09 由 GitHub 迁来） |
| `github` | `https://github.com/lolosiax/lolosia-backend.git` | 旧远端，仅存档 |

迁移动作：原 `origin` 改名 `github` → `git remote add origin <gitee>` → 各分支上游改指 `origin`。原 `ktor-template` / `template` 两个 remote 已删除。

### 7.2 分支

| 分支 | 提交 | 形态 | 上游 | 说明 |
| --- | --- | --- | --- | --- |
| `native` | `cf1df5e` | **Kotlin/Native（KMP）** | `origin/native` | **当前主线、最新版**；`doc/` 五个笔记都在这条线上 |
| `master` | `b579347` | Spring Boot（WebFlux） | `origin/master` | Spring 时代的存档 |
| `spring` | `b579347` | 同上 | 无 | 与 `master` 同提交的本地指针 |
| `dev/avif` | `3124713` | Spring Boot | `origin/dev/avif` | 仅多一个「资源图片 avif 支持」提交 |
| `dev/ktor-jvm` | `a3eade3` | **JVM + Ktor（已废弃）** | `origin/dev/ktor-jvm` | 见下 |

- ⚠️ **`dev/ktor-jvm` 已废弃**：它由 `master` 合入 `ktor-template` 得到，是 JVM 版 Ktor 实现；同期 `native` 已用 Kotlin/Native 重写且功能远超（WebDAV、在线文件管理器、RBAC、媒体索引…）。除翻历史外不要基于它开发。
  - 其历史里含 `ktor-template` 仓库的 4 个提交（`808466a` `fabb41a` `48d4509` `5b218f3`），推 Gitee 时被一并带入。
- **不入库的本机文件**（`.gitignore` 覆盖，含**明文凭据**，别往里塞）：`application.yaml`（数据库 / 邮件口令）、`platforms.json`（服务器口令）、`.idea/`、`work/`。

### 7.3 本机路径

| 内容 | 路径 |
| --- | --- |
| 后端仓库 | `F:\Develop\Kotlin\lolosia-backend` |
| 前端仓库 | `F:\Develop\Node\lolosia-web` |
| Trae 工作区 | `F:\Develop\Trae\lolosia-home`（`backend/`、`web/` 均为 junction 指向上表两处） |

### 7.4 版本控制踩坑

| 症状 | 根因 / 处理 |
| --- | --- |
| 工作区里**一批未提交改动凭空消失**、`git status` 变干净 | 执行过 `git checkout [--no-overlay] <ref> -- .`：它用 `<ref>` 整棵树**覆盖索引与工作区**，`--no-overlay` 还会**删掉 `<ref>` 里没有的文件**。2026-10-09 实测：`git checkout --no-overlay origin/native -- .` 相对 HEAD 造成 248 增 / 13 改 / 201 删，把 `build.gradle.kts` 等处的未提交改动覆盖掉了。**探查某个 ref 的内容，只用 `git status` / `git diff <ref>` / `git show <ref>:<path>` / `git ls-tree <ref>`，不要用 checkout。** |
| 想从 git 里捞回被覆盖的内容 | `git fsck --lost-found` 只捞得到**曾暂存过**的 blob（已进对象库）；**未暂存（worktree-only）的修改 git 不留痕**，只能靠 IDE 本地历史（IntelliJ：右键文件 → Local History → Show History；VS Code：Timeline）。本次实测捞出的 97 个 dangling blob 全是 `com.zxce.igame` / `top.lolosia` 老包名时期的历史对象，与丢失内容无关。 |
| 分支改名后仍指向旧远端 | 上游跟着旧 remote 走（`native` 由 `master` 改名而来，上游一度仍是 `github/master`）→ 改名后立刻 `git branch --set-upstream-to=origin/<分支>`，或 `git config --unset branch.<名>.remote`。 |

## 8. 数据库迁移备注（2026-10-09，搬到 TSP-1M）

把 Spring 版数据从**远程那台雨云库**（主机 / 端口 / 口令见本地 `platforms.json`，按 [AGENTS.md](../AGENTS.md) §9 不入库）搬到开发板 `TSP-1M`（MariaDB 10.6.23 / aarch64 / 无 Docker）：

- 目标库 `lolosia-backend`；账号 `lolosia@localhost` 与 `lolosia@%`，按需求给 **SUPER + 该库 ALL**（不建子账号）。
- 导入命令（管道直连，不落中间文件）：

  ```bash
  mysqldump -h <源库主机> -P <端口> -u lolosia -p'…' --routines --triggers --add-drop-table lolosia-backend \
    | sed 's/utf8mb4_uca1400_ai_ci/utf8mb4_unicode_ci/g' \
    | mysql -u lolosia -p'…' lolosia-backend
  ```

- ⚠️ **collation 坑**：源库（MariaDB 11.x）默认 `utf8mb4_uca1400_ai_ci`，目标 10.6 **不认**（`ERROR 1273 Unknown collation`）→ 必须用上面的 `sed` 换成 `utf8mb4_unicode_ci`。不换会导到一半报错中断。
- 结果：8 张表 —— `sys_user` 4 行、`sys_role` 5 行、`sys_user_roles` 3 行、`sys_sessions` 33 行，`sys_cache` / `oss_user_file` 为 0 行，另含 `sys_logs` 与视图 `view_user_role`。
