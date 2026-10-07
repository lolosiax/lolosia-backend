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
- 存储层 `service/dav/DavStore.kt` 把 URL 分段逐段校验（拒 `.` / `..` / 空段 / 含分隔符与 NUL 的段）后落盘，因此请求必然落在 `root` 内。
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

## 3. 部署要点（systemd）

- 服务**以 `lolosia` 用户运行**：远端 `/srv/lolosia-backend/lolosia-backend-native.service` 的 `[Service]` 段手工加了 `User=lolosia` / `Group=lolosia`。
  - 原因：WebDAV 若以 root 运行，它新建的目录会属 `root:root 755`，SMB 侧（`lolosia`）写不进去 —— 表现为「局域网里往共享复制文件失败」。
  - 部署插件**不覆盖远端已存在的 unit**（只就地 patch `ExecStart` 行），所以这份手工改动能长期保留。
  - ⚠️ 仓库里的 `lolosia-backend-native.service` 模板**不含** `User=`：新机器首次部署后需要手工补，否则会重现上面的属主问题。
- 磁盘配置 `/srv/lolosia-backend/application.yaml` 与内嵌 `application.json` 相互独立，改配置不用重编二进制。

## 4. 旁路服务

- **Samba**：`/etc/samba/smb.conf` 里的 `include = /etc/samba/conf.d/*.conf` 必须放在 `[global]` 段**末尾**（紧邻 Share Definitions 之前）。
  放在段开头，include 展开的 `[hdd1]` 之后的所有全局参数都会被解析进服务段，日志狂刷 `Global parameter ... found in service section!`；改完用 `testparm -s` 复核（应只回 `Loaded services file OK.`）。
- 共享 `[hdd1]` → `/mnt/hdd1`：`read only = no`、`valid users = lolosia`、`write list = lolosia`、`create mask = 0664` / `directory mask = 0775`。

## 5. 网络与吞吐实测（2026-10）

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
