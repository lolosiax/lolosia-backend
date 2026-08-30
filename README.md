# ktor-template

洛洛希雅（Lolosia）的 Ktor 后端项目模板：一个开箱即用的 **Kotlin + Ktor 3 服务端脚手架**，从同源 Spring 项目移植而来，保留了原有的业务能力与架构习惯（会话、SSE 事件总线、响应信封、鉴权、OSS 文件服务），并重构为 Ktor 原生的注解式控制器 + 编译期代码生成。

- 技术栈：**Kotlin 21 + Ktor 3.4.2（Netty）+ Ebean ORM + Koin DI**
- 路由：**ktorfitx 注解式控制器**（`@Controller`/`@GET`/`@POST`…），KSP 编译期生成路由注册代码
- 部署：`bootJar` 产出**单个可执行 jar**，vite 前端产物可直接并入，适合开发板/单机部署

## ✨ 特性

- **注解式控制器**：`@Controller` + `@GET`/`@POST` + `@Path`/`@Query`/`@Body`/`@Attribute`，无需手写路由
- **洋葱模型中间件**：流量统计 → 请求上下文 → SSE → 鉴权 → 响应信封，注册顺序即执行顺序
- **响应信封中间件**：自动把所有 `/api/` 的 JSON 响应包成 `{code, msg, data}`（等价 Spring 的 `ReturnWebFilter`），新增接口无需手动包信封
- **SSE 事件总线**：`x-upgrade` 握手换 ID → `eventSourceId` 建流，带 5 秒心跳保活，`EventBus` 支持任意事件推送
- **会话系统**：SessionManager + Ebean 持久化，`AuthInterceptor` 白名单鉴权
- **OSS 文件服务**：流式 multipart 上传（避开 Ktor 3.4 的 FileItem body 回收坑）、MD5 去重、下载/预览带 304 协商缓存、头像上传
- **手动路由注解**：`@Route` 标在任意 object 的函数上，编译期生成 `Routing.() -> Unit` 注册函数，把文件下载/上传等流式端点从集中路由文件拆回各 Controller
- **DI 自动注册**：类上标 `@Service`/`@Component` 即被扫描生成 Koin 注册，无需手动逐个声明
- **vite 前端打包**：`viteJar` 任务把前端构建产物并入 jar 的 `static/home/`，SPA history 路由 fallback 到 index.html
- **AI 集成**：Koog（JetBrains）预配置 OpenAI / Anthropic / Ollama / DeepSeek 等 LLM
- **单文件部署**：`bootJar` 嵌套 jar 方案，避免 flat fat jar 的 `module-info`/`META-INF/versions` 冲突

## 🧱 技术栈

| 类别 | 选型 |
|------|------|
| 语言 / 运行时 | Kotlin 21 / JDK 21 |
| Web 框架 | Ktor 3.4.2（Netty） |
| 序列化 | kotlinx.serialization（ContentNegotiation），Jackson 备用 |
| 路由 | ktorfitx（KSP 编译期生成） |
| ORM | Ebean 19.4.0（内置 `:orm` querybean 模块） |
| 数据库 | MariaDB（MySQL 兼容） |
| DI | Koin（Ktor 插件 + 自动注册生成） |
| 鉴权/加密 | BCrypt（spring-security-crypto）+ Hutool 图形验证码 |
| 图片 | WebP 转换（磁盘缓存，`/res/?webp=true`） |
| AI | Koog（OpenAI / Anthropic / Ollama / Google / OpenRouter / DeepSeek） |

## 📁 项目结构

```
├── buildSrc/                  # 自定义 Gradle 插件 moe.lolosia
│   └── src/main/kotlin/moe/lolosia/gradle/
│       ├── task/              # viteJar / generateDiModule / generateRouteModule
│       │                       # generateConstants / gitArchive / deploy / docker
│       └── extension/         # lolosia { ... } 配置扩展
├── orm/                       # Ebean querybean 替代实现（typequery 支持）
├── sql/                       # 建表脚本（sys_user / sys_role / oss_user_file ...）
└── src/main/
    ├── kotlin/moe/lolosia/web/
    │   ├── KtorApplication.kt # 入口
    │   ├── annotation/        # @Service / @Component / @Route / @JwtIgnore
    │   ├── config/web/        # 装配：Application/Http/Security/Serialization/Routing...
    │   ├── controller/        # 业务控制器（ktorfitx 注解式）
    │   ├── interceptor/       # 洋葱中间件：Traffic/Ctx/Sse/Auth/Envelope
    │   ├── manager/           # SessionManager / CacheManager / EventSourceManager
    │   ├── service/           # 业务服务（Login / User / Role / Oss / SSE）
    │   ├── model/             # Ebean 实体
    │   └── util/              # bundle / ebean / event / sse / session / property
    └── resources/
        ├── application.yaml   # 端口 / 数据源配置
        └── logback.xml
```

## 🚀 快速开始

### 环境要求

- JDK 21
- MariaDB（或 MySQL）
- （可选）Node.js —— 仅当需要构建 vite 前端并入 jar

### 1. 建库并导入表

```sql
CREATE DATABASE ktor-template DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

导入 `sql/` 目录下的建表脚本：

```bash
mysql -u root -p ktor-template < sql/sys_user.sql
mysql -u root -p ktor-template < sql/sys_role.sql
mysql -u root -p ktor-template < sql/sys_sessions.sql
# ... 其余脚本同理
```

### 2. 配置数据源

编辑 `src/main/resources/application.yaml`：

```yaml
datasource:
  db:
    username: "root"
    password: "123456"
    driver: "org.mariadb.jdbc.Driver"
    url: 'jdbc:mariadb://localhost:3306/ktor-template?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8'
```

### 3. 启动

```bash
./gradlew run
```

启动后服务监听 `http://localhost:7129`。

> 注意：`application.yaml` 中显式设置了 `ktor.development: false`。Ktor 热重载会用 parent-last 类加载器重新加载应用类，与 Ebean 按类身份缓存实体冲突（同一实体双类加载器 → `ClassCastException`），故默认关闭 dev 模式。

## 🛠 构建与部署

| 任务 | 说明 |
|------|------|
| `./gradlew run` | 开发模式启动 |
| `./gradlew build` | 构建 |
| `./gradlew test` | 运行测试 |
| `./gradlew bootJar` | 产出单文件可执行 jar（`build/libs/*-fat.jar`） |
| `./gradlew viteJar` | 构建 vite 前端并把产物并入 jar（`static/home/`） |
| `./gradlew generateDiModule` | 重新扫描 `@Service`/`@Component` 生成 DI 注册 |
| `./gradlew generateRouteModule` | 重新扫描 `@Route` 生成手动路由注册 |
| `./gradlew generateConstants` | 从 `config/` 配置类生成常量 |
| `./gradlew gitArchive` / `deploy` / `docker*` | Git 归档 / 部署 / Docker 相关（按需配置） |

单文件部署：

```bash
./gradlew bootJar
java -jar build/libs/ktor-template-1.0.0-SNAPSHOT-fat.jar
```

前端产物集成（可选）：在根目录 `platforms.json` 中配置 `webPath` 指向 vite 前端工程目录，`viteJar` 任务会自动构建并并入。

## 📡 API 规范

- **统一响应信封**：所有 `/api/` 接口返回 `{code, msg, data}`；`code=200` 为成功，业务错误由 `ErrorResponseException` 抛出并由 StatusPages 统一转 `{code, msg}`。
- **认证**：登录返回 token，请求头 `Authorization: Bearer <token>` 走 `AuthInterceptor` 鉴权（`@JwtIgnore` / 白名单路径免认证）。
- **SSE**：
  1. `POST /api/sse`（`x-upgrade: event-source`）→ 返回会话 ID；
  2. `GET /api/sse?eventSourceId=<id>` → 建立 event-stream 连接（5 秒心跳保活）；
  3. `POST /api/sse/registry` → 登录后将连接绑定到用户，业务经 `EventBus` 推送事件。

## 🤖 AI 集成

[KoogConfig.kt](src/main/kotlin/moe/lolosia/web/config/web/KoogConfig.kt) 预置了多个 LLM 提供方，按需填入密钥并取消注释：

```kotlin
install(Koog) {
    llm {
        openAI(apiKey = "your-openai-api-key")
        ollama { baseUrl = "http://localhost:11434" }
        deepSeek(apiKey = "your-deepseek-api-key")
    }
}
```

## 📜 License

[MIT](LICENSE) © 洛洛希雅Lolosia
