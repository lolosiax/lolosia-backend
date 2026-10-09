package moe.lolosia.web.util.ktor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import moe.lolosia.web.service.dav.DAV_XML
import moe.lolosia.web.service.dav.DavException
import moe.lolosia.web.service.dav.DavLockManager
import moe.lolosia.web.service.dav.DavStore
import moe.lolosia.web.service.dav.XML_DECL
import moe.lolosia.web.service.dav.davHref
import moe.lolosia.web.service.dav.davResponseXml
import moe.lolosia.web.service.dav.escapeXml
import moe.lolosia.web.service.dav.etagOf
import moe.lolosia.web.service.dav.httpDate
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import okio.ByteString.Companion.decodeBase64
import okio.Path

private const val BUF_SIZE = 64 * 1024
private const val DEFAULT_LOCK_SECONDS = 3600L
private const val MAX_LOCK_SECONDS = 7 * 24 * 3600L

/** 允许的方法：OPTIONS 的 `Allow` 与 405 共用一份。 */
private const val ALLOW_METHODS =
    "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK"

/**
 * 把 [store]（一般是挂载在板子上的机械盘）以 WebDAV 协议挂在配置的 [AppSettings.WebdavConfig.path] 下。
 *
 * 挂载路径不走 `/api`，因此不会被会话鉴权拦截、响应也不会被 JSON 信封包装（WebDAV 要求
 * 原样的状态码与 207 多状态 XML）。鉴权用 HTTP Basic，账号取自配置。
 *
 * 覆盖 Windows 内置 WebDAV 客户端（WebClient）读写挂载所需的方法集：
 * `OPTIONS / PROPFIND / PROPPATCH / GET / HEAD / PUT / DELETE / MKCOL / COPY / MOVE / LOCK / UNLOCK`。
 *
 * @author 洛洛希雅Lolosia
 */
fun Route.webDav(config: AppSettings.WebdavConfig, store: DavStore, locks: DavLockManager) {
    val base = "/" + config.path.trim('/')
    val log = Log.of("WebDAV")
    log.info { "WebDAV 已挂载：$base -> ${store.root}（用户 ${config.username}）" }
    if (config.password.isEmpty()) {
        log.warn { "webdav.password 为空：所有请求都会因鉴权失败被拒，请在配置里设置密码" }
    }

    suspend fun dispatch(call: ApplicationCall) {
        try {
            if (!call.davAuthorized(config)) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"${config.realm}\", charset=\"UTF-8\"")
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

            val segments = call.davSegments(base)
            if (segments == null) {
                call.respond(HttpStatusCode.BadRequest)
                return
            }
            val path = store.resolve(segments)
            if (path == null) {
                call.respond(HttpStatusCode.Forbidden)
                return
            }

            when (call.request.httpMethod.value.uppercase()) {
                "OPTIONS" -> call.davOptions()
                "PROPFIND" -> call.davPropfind(segments, path, store, base)
                "PROPPATCH" -> call.davProppatch(segments, path, store, base)
                "GET" -> call.davGet(path, store, head = false)
                "HEAD" -> call.davGet(path, store, head = true)
                "PUT" -> call.davPut(path, store)
                "DELETE" -> call.davDelete(segments, path, store)
                "MKCOL" -> call.davMkcol(segments, path, store)
                "COPY" -> call.davCopyMove(segments, path, store, base, move = false)
                "MOVE" -> call.davCopyMove(segments, path, store, base, move = true)
                "LOCK" -> call.davLock(locks)
                "UNLOCK" -> call.davUnlock(locks)
                else -> {
                    call.response.header(HttpHeaders.Allow, ALLOW_METHODS)
                    call.respondBytes(ByteArray(0), status = HttpStatusCode.MethodNotAllowed)
                }
            }
        } catch (e: DavException) {
            call.respondBare(e.status)
        } catch (e: Throwable) {
            log.error(e) { "WebDAV ${call.request.httpMethod.value} ${call.request.path()} 处理失败" }
            call.respondBare(HttpStatusCode.InternalServerError)
        }
    }

    // 同时接受 /dav、/dav/ 与 /dav/<path>（客户端给的 URL 带不带尾斜杠都要能挂上）
    route(base) { handle { dispatch(call) } }
    route("$base/") { handle { dispatch(call) } }
    route("$base/{path...}") { handle { dispatch(call) } }
}

// ===== 鉴权与路径 =====

/** HTTP Basic 校验；密码为空时一律拒绝。 */
private fun ApplicationCall.davAuthorized(config: AppSettings.WebdavConfig): Boolean {
    if (config.password.isEmpty()) return false
    val header = request.headers[HttpHeaders.Authorization] ?: return false
    if (!header.startsWith("basic ", ignoreCase = true)) return false
    val decoded = header.substring(6).trim().decodeBase64()?.utf8() ?: return false
    val separator = decoded.indexOf(':')
    if (separator < 0) return false
    return decoded.substring(0, separator) == config.username &&
        decoded.substring(separator + 1) == config.password
}

/**
 * 取挂载点之后的相对路径分段；请求不在 [base] 之下时返回 `null`。
 *
 * 用原始 URI（未解码）逐段 `decodeURLPart`，避免文件名里的 `%2F`、空格等被提前处理掉。
 */
private fun ApplicationCall.davSegments(base: String): List<String>? {
    val raw = request.path()
    if (raw != base && !raw.startsWith("$base/")) return null
    val relative = raw.removePrefix(base).trim('/')
    if (relative.isEmpty()) return emptyList()
    return relative.split('/').map { it.decodeURLPart() }
}

/** COPY/MOVE 的 `Destination`（绝对 URL）→ 相对分段。 */
private fun davDestinationSegments(destination: String?, base: String): List<String>? {
    if (destination.isNullOrBlank()) return null
    val encodedPath = runCatching { Url(destination).encodedPath }.getOrNull() ?: return null
    if (encodedPath != base && !encodedPath.startsWith("$base/")) return null
    val relative = encodedPath.removePrefix(base).trim('/')
    if (relative.isEmpty()) return emptyList()
    return relative.split('/').map { it.decodeURLPart() }
}

// ===== 各方法实现 =====

private suspend fun ApplicationCall.davOptions() {
    response.header("DAV", "1, 2")
    response.header(HttpHeaders.Allow, ALLOW_METHODS)
    response.header(HttpHeaders.AcceptRanges, "bytes")
    respondBytes(ByteArray(0), status = HttpStatusCode.OK)
}

private suspend fun ApplicationCall.davPropfind(segments: List<String>, path: Path, store: DavStore, base: String) {
    val depth = request.headers[HttpHeaders.Depth]?.trim()?.lowercase() ?: "1"
    // Depth: infinity 在大目录上代价不可控（Windows 只发 0/1），直接拒绝
    if (depth == "infinity") {
        respondBare(HttpStatusCode.Forbidden)
        return
    }
    val self = store.metadata(path) ?: throw DavException.NotFound()

    val xml = buildString(1024) {
        append(XML_DECL)
        append("<D:multistatus xmlns:D=\"DAV:\">")
        append(davResponseXml(davHref(base, segments, self.isDirectory), self, self.isDirectory))
        if (depth != "0" && self.isDirectory) {
            for (child in store.children(path)) {
                val childMeta = store.metadata(child) ?: continue
                append(davResponseXml(davHref(base, segments + child.name, childMeta.isDirectory), childMeta, childMeta.isDirectory))
            }
        }
        append("</D:multistatus>")
    }
    respondBytes(xml.encodeToByteArray(), DAV_XML, HttpStatusCode.MultiStatus)
}

/** 属性集不做持久化：一律回 200，避免 Windows 因设置失败反复重试。 */
private suspend fun ApplicationCall.davProppatch(segments: List<String>, path: Path, store: DavStore, base: String) {
    val meta = store.metadata(path) ?: throw DavException.NotFound()
    val xml = XML_DECL +
        "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>" +
        escapeXml(davHref(base, segments, meta.isDirectory)) +
        "</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"
    respondBytes(xml.encodeToByteArray(), DAV_XML, HttpStatusCode.MultiStatus)
}

private suspend fun ApplicationCall.davGet(path: Path, store: DavStore, head: Boolean) {
    val meta = store.metadata(path) ?: throw DavException.NotFound()
    if (meta.isDirectory) {
        respondBare(HttpStatusCode.MethodNotAllowed)
        return
    }

    val size = meta.size ?: 0L
    response.header(HttpHeaders.AcceptRanges, "bytes")
    response.header(HttpHeaders.ETag, etagOf(meta))
    meta.lastModifiedAtMillis?.let { response.header(HttpHeaders.LastModified, httpDate(it)) }

    val offset: Long
    val length: Long
    val status: HttpStatusCode
    when (val range = parseRange(request.headers[HttpHeaders.Range], size)) {
        RangeParse.None -> {
            offset = 0
            length = size
            status = HttpStatusCode.OK
        }

        RangeParse.Unsatisfiable -> {
            response.header(HttpHeaders.ContentRange, "bytes */$size")
            respondBare(HttpStatusCode(416, "Range Not Satisfiable"))
            return
        }

        is RangeParse.Ok -> {
            offset = range.start
            length = range.endInclusive - range.start + 1
            status = HttpStatusCode.PartialContent
            response.header(HttpHeaders.ContentRange, "bytes ${range.start}-${range.endInclusive}/$size")
        }
    }

    val contentType = ContentType.defaultForFilePath(path.name)
    respondBytesWriter(contentType, status, contentLength = length) {
        if (head) return@respondBytesWriter
        val handle = store.openReadOnly(path)
        try {
            writeRange(handle, offset, length, this@respondBytesWriter)
        } finally {
            handle.close()
        }
    }
}

private suspend fun ApplicationCall.davPut(path: Path, store: DavStore) {
    val existing = store.metadata(path)
    if (existing?.isDirectory == true) {
        respondBare(HttpStatusCode.MethodNotAllowed)
        return
    }
    val channel = receiveChannel()
    val sink = store.sink(path)
    try {
        val buffer = ByteArray(BUF_SIZE)
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            if (read > 0) sink.write(buffer, 0, read)
        }
        sink.flush()
    } finally {
        sink.close()
    }
    respondBare(if (existing == null) HttpStatusCode.Created else HttpStatusCode.NoContent)
}

private suspend fun ApplicationCall.davDelete(segments: List<String>, path: Path, store: DavStore) {
    // 不允许把挂载根删掉
    if (segments.isEmpty()) {
        respondBare(HttpStatusCode.Forbidden)
        return
    }
    store.delete(path)
    respondBare(HttpStatusCode.NoContent)
}

private suspend fun ApplicationCall.davMkcol(segments: List<String>, path: Path, store: DavStore) {
    if (segments.isEmpty() || store.exists(path)) {
        respondBare(HttpStatusCode.MethodNotAllowed)
        return
    }
    store.createDirectory(path)
    respondBare(HttpStatusCode.Created)
}

private suspend fun ApplicationCall.davCopyMove(
    segments: List<String>,
    path: Path,
    store: DavStore,
    base: String,
    move: Boolean,
) {
    if (segments.isEmpty()) {
        respondBare(HttpStatusCode.Forbidden)
        return
    }
    val destinationSegments = davDestinationSegments(request.headers[HttpHeaders.Destination], base)
    if (destinationSegments == null) {
        respondBare(HttpStatusCode.BadRequest)
        return
    }
    if (destinationSegments.isEmpty()) {
        respondBare(HttpStatusCode.Forbidden)
        return
    }
    val target = store.resolve(destinationSegments) ?: run {
        respondBare(HttpStatusCode.Forbidden)
        return
    }
    if (target == path) {
        respondBare(HttpStatusCode.Forbidden)
        return
    }

    val overwrite = !request.headers[HttpHeaders.Overwrite].equals("F", ignoreCase = true)
    val existed = store.exists(target)
    if (move) store.move(path, target, overwrite) else store.copy(path, target, overwrite)
    respondBare(if (existed) HttpStatusCode.NoContent else HttpStatusCode.Created)
}

private suspend fun ApplicationCall.davLock(locks: DavLockManager) {
    val timeout = parseLockTimeout(request.headers["Timeout"])
    val token = locks.lock(timeout)
    response.header("Lock-Token", "<$token>")
    val xml = XML_DECL +
        "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
        "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>" +
        "<D:depth>0</D:depth><D:timeout>Second-$timeout</D:timeout>" +
        "<D:locktoken><D:href>$token</D:href></D:locktoken>" +
        "</D:activelock></D:lockdiscovery></D:prop>"
    respondBytes(xml.encodeToByteArray(), DAV_XML, HttpStatusCode.OK)
}

private suspend fun ApplicationCall.davUnlock(locks: DavLockManager) {
    request.headers["Lock-Token"]?.trim()?.removeSurrounding("<", ">")?.let(locks::unlock)
    respondBare(HttpStatusCode.NoContent)
}

// ===== 工具 =====

/** 状态码应答（无正文）；已提交过的响应不再重复应答。 */
private suspend fun ApplicationCall.respondBare(status: HttpStatusCode) {
    if (response.isCommitted) return
    respondBytes(ByteArray(0), status = status)
}

/** `Timeout: Second-3600, Infinite` → 秒数，缺省 1 小时、上限 7 天。 */
private fun parseLockTimeout(header: String?): Long {
    val seconds = header?.split(',')?.firstNotNullOfOrNull { part ->
        val token = part.trim()
        when {
            token.startsWith("Second-", ignoreCase = true) -> token.substring(7).toLongOrNull()
            token.equals("Infinite", ignoreCase = true) -> MAX_LOCK_SECONDS
            else -> null
        }
    }
    return (seconds ?: DEFAULT_LOCK_SECONDS).coerceIn(60L, MAX_LOCK_SECONDS)
}


