package moe.lolosia.web.util.ktor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import moe.lolosia.web.service.artifact.ArtifactStore
import moe.lolosia.web.service.dav.httpDate
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.fs.WorkPaths
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.log.Logger
import okio.ByteString.Companion.decodeBase64

private const val BUF_SIZE = 64 * 1024

private const val CHECKSUM_SUFFIX = ".sha256"

/** 仓库固定的一级目录；新增一级目录必须先改这里与目录规范。 */
private val ROOTS = setOf("maven", "raw", "sources")

/**
 * 把自建工件仓库挂在配置的 [AppSettings.ArtifactConfig.path] 下。
 *
 * 挂载点不走 `/api`，因此不会被会话鉴权拦截、响应也不会被 JSON 信封包装；
 * 鉴权用 HTTP Basic（Gradle 的 `credentials { }` 原生支持），读取可用
 * [AppSettings.ArtifactConfig.publicRead] 放开。
 *
 * 支持的方法：
 * - `GET` / `HEAD`：下载文件；目录返回 JSON 列表；
 * - `PUT`：流式上传，默认版本不可变（`?overwrite=true` 才允许覆盖）；
 * - `OPTIONS`：返回允许的方法。
 *
 * @author 洛洛希雅Lolosia
 */
fun Route.artifactRepository(config: AppSettings.ArtifactConfig, workPaths: WorkPaths) {
    val base = "/" + config.path.trim('/')
    val log = Log.of("ArtifactRepo")
    if (base.startsWith("/api")) {
        log.error { "artifact.path 不能以 /api 开头（会进入会话鉴权与信封），本次不挂载：$base" }
        return
    }

    val store = ArtifactStore(config.root.ifBlank { workPaths.artifactRoot.toString() })
    log.info { "工件仓库已挂载：$base -> ${store.rootPath}" }
    if (config.password.isEmpty()) {
        log.warn { "artifact.password 为空：所有请求都会因鉴权失败被拒，请在配置里设置密码" }
    }
    if (config.publicRead) {
        log.warn { "artifact.publicRead=true：读取接口允许匿名访问，请确认网络边界" }
    }

    suspend fun dispatch(call: ApplicationCall) {
        try {
            val method = call.request.httpMethod.value.uppercase()
            val needsAuth = method == "PUT" || !config.publicRead
            if (needsAuth && !call.artifactAuthorized(config)) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"${config.realm}\", charset=\"UTF-8\"")
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

            val segments = call.artifactSegments(base)
            if (segments == null) {
                call.respond(HttpStatusCode.BadRequest)
                return
            }
            if (!shapeAllowed(segments, write = method == "PUT")) {
                call.respond(HttpStatusCode.BadRequest)
                return
            }

            when (method) {
                "OPTIONS" -> call.artifactOptions()
                "GET" -> call.artifactGet(store, segments, head = false)
                "HEAD" -> call.artifactGet(store, segments, head = true)
                "PUT" -> call.artifactPut(store, segments, config, log)
                else -> {
                    call.response.header(HttpHeaders.Allow, "OPTIONS, GET, HEAD, PUT")
                    call.respond(HttpStatusCode.MethodNotAllowed)
                }
            }
        } catch (e: Throwable) {
            log.error(e) { "工件仓库 ${call.request.httpMethod.value} ${call.request.path()} 处理失败" }
            if (!call.response.isCommitted) call.respond(HttpStatusCode.InternalServerError)
        }
    }

    // 同时接受 /repo、/repo/ 与 /repo/<path>，客户端带不带尾斜杠都能挂上
    route(base) { handle { dispatch(call) } }
    route("$base/") { handle { dispatch(call) } }
    route("$base/{path...}") { handle { dispatch(call) } }
}

// ===== 鉴权与路径 =====

/** HTTP Basic 校验；密码为空时一律拒绝。 */
private fun ApplicationCall.artifactAuthorized(config: AppSettings.ArtifactConfig): Boolean {
    if (config.password.isEmpty()) return false
    val header = request.headers[HttpHeaders.Authorization] ?: return false
    if (!header.startsWith("basic ", ignoreCase = true)) return false
    val decoded = header.substring(6).trim().decodeBase64()?.utf8() ?: return false
    val separator = decoded.indexOf(':')
    if (separator < 0) return false
    return decoded.substring(0, separator) == config.username &&
        decoded.substring(separator + 1) == config.password
}

/** 取挂载点之后的相对路径分段；请求不在 [base] 之下时返回 `null`。 */
private fun ApplicationCall.artifactSegments(base: String): List<String>? {
    val raw = request.path()
    if (raw != base && !raw.startsWith("$base/")) return null
    val relative = raw.removePrefix(base).trim('/')
    if (relative.isEmpty()) return emptyList()
    return relative.split('/').map { it.decodeURLPart() }
}

/**
 * 约束各区域的路径形状：
 * - `maven/<group…>/<artifact>/<version>/<file>`，至少 5 段；
 * - `sources/<库名>/<version>/<file>`，至少 4 段；
 * - `raw/<namespace>/<业务>/<…>`，至少 3 段；
 * - 根路径只允许读取（返回布局说明）。
 *
 * 读取时允许停在任意中间目录，便于浏览；写入必须给到文件名。
 */
private fun shapeAllowed(segments: List<String>, write: Boolean): Boolean {
    if (segments.isEmpty()) return !write
    val root = segments.first()
    if (root !in ROOTS) return false
    return when (root) {
        "maven" -> if (write) segments.size >= 5 else true
        "sources" -> if (write) segments.size >= 4 else true
        "raw" -> if (write) segments.size >= 3 else true
        else -> false
    }
}

// ===== 各方法实现 =====

private suspend fun ApplicationCall.artifactOptions() {
    response.header(HttpHeaders.Allow, "OPTIONS, GET, HEAD, PUT")
    response.header(HttpHeaders.AcceptRanges, "bytes")
    respondBytes(ByteArray(0), status = HttpStatusCode.OK)
}

private suspend fun ApplicationCall.artifactGet(store: ArtifactStore, segments: List<String>, head: Boolean) {
    if (segments.isEmpty()) {
        respondJson(
            buildJsonObject {
                put("layout", 1)
                put("roots", buildJsonObject {
                    put("maven", "<group 路径>/<artifact>/<version>/<文件>")
                    put("sources", "<库名>/<version>/<源码包>")
                    put("raw", "<namespace>/<业务 slug>/<...>")
                })
                put("rules", buildJsonArray {
                    add("段名仅限 ASCII slug（字母数字开头，可含 . _ + -）")
                    add("根下不允许新增一级目录；业务归类用 Maven 坐标或 namespace/slug")
                    add("版本目录不可变；覆盖需显式 overwrite=true")
                })
            }
        )
        return
    }

    val path = store.resolve(segments)
    if (path == null) {
        respond(HttpStatusCode.Forbidden)
        return
    }
    val meta = store.metadata(path)
    if (meta == null) {
        respond(HttpStatusCode.NotFound)
        return
    }

    if (meta.isDirectory) {
        val entries = JsonArray(
            store.children(path).mapNotNull { child ->
                val childMeta = store.metadata(child) ?: return@mapNotNull null
                buildJsonObject {
                    put("name", child.name)
                    put("directory", childMeta.isDirectory)
                    if (!childMeta.isDirectory) put("size", childMeta.size ?: 0L)
                }
            }
        )
        respondJson(
            buildJsonObject {
                put("path", segments.joinToString("/"))
                put("entries", entries)
            }
        )
        return
    }

    val size = meta.size ?: 0L
    response.header(HttpHeaders.AcceptRanges, "bytes")
    response.header(HttpHeaders.ETag, "\"$size-${meta.lastModifiedAtMillis ?: 0L}\"")
    meta.lastModifiedAtMillis?.let { response.header(HttpHeaders.LastModified, httpDate(it)) }

    val contentType = ContentType.defaultForFilePath(path.name)
    respondBytesWriter(contentType, HttpStatusCode.OK, contentLength = size) {
        if (head) return@respondBytesWriter
        val handle = store.openReadOnly(path)
        try {
            writeRange(handle, 0, size, this)
        } finally {
            handle.close()
        }
    }
}

private suspend fun ApplicationCall.artifactPut(
    store: ArtifactStore,
    segments: List<String>,
    config: AppSettings.ArtifactConfig,
    log: Logger,
) {
    val target = store.resolve(segments)
    if (target == null) {
        respond(HttpStatusCode.Forbidden)
        return
    }
    val existing = store.metadata(target)
    if (existing?.isDirectory == true) {
        respond(HttpStatusCode.Conflict)
        return
    }
    val overwrite = request.queryParameters["overwrite"].toBoolean()
    if (existing != null && !overwrite) {
        respondJson(
            buildJsonObject {
                put("error", "版本已存在；如需覆盖请显式加 overwrite=true")
                put("path", segments.joinToString("/"))
            },
            HttpStatusCode.Conflict,
        )
        return
    }

    val temp = store.newTempFile()
    var size = 0L
    var tooLarge = false
    try {
        val channel = receiveChannel()
        val sink = store.sink(temp)
        try {
            val buffer = ByteArray(BUF_SIZE)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read <= 0) continue
                size += read
                if (size > config.maxUploadBytes) {
                    tooLarge = true
                    break
                }
                sink.write(buffer, 0, read)
            }
            sink.flush()
        } finally {
            sink.close()
        }

        if (tooLarge) {
            respondJson(
                buildJsonObject {
                    put("error", "文件超过上限 ${config.maxUploadBytes} 字节")
                },
                HttpStatusCode.PayloadTooLarge,
            )
            return
        }

        val sha256 = store.sha256(temp)
        if (sha256 == null) {
            respondJson(buildJsonObject { put("error", "无法计算 SHA-256") }, HttpStatusCode.InternalServerError)
            return
        }
        val declared = request.headers["X-Checksum-Sha256"]?.trim()?.lowercase()
        if (!declared.isNullOrEmpty() && declared != sha256) {
            respondJson(
                buildJsonObject {
                    put("error", "SHA-256 不匹配")
                    put("expected", declared)
                    put("actual", sha256)
                },
                HttpStatusCode.UnprocessableEntity,
            )
            return
        }

        if (!store.commit(temp, target, overwrite = overwrite)) {
            respondJson(buildJsonObject { put("error", "目标已存在") }, HttpStatusCode.Conflict)
            return
        }
        runCatching {
            val sidecar = (target.parent ?: store.rootPath).resolve(target.name + CHECKSUM_SUFFIX)
            store.writeText(sidecar, sha256)
        }.onFailure { log.warn(it) { "写入校验和旁车失败：$target" } }

        respondJson(
            buildJsonObject {
                put("path", segments.joinToString("/"))
                put("size", size)
                put("sha256", sha256)
            },
            HttpStatusCode.Created,
        )
    } finally {
        store.delete(temp)
    }
}

// ===== 工具 =====

private suspend fun ApplicationCall.respondJson(element: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(Json.encodeToString(JsonElement.serializer(), element), ContentType.Application.Json, status)
}
