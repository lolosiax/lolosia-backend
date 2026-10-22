package moe.lolosia.web.ui.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import moe.lolosia.web.ui.event.EventBusException
import moe.lolosia.web.ui.event.EventStreamTransport
import moe.lolosia.web.ui.event.HttpResult

/**
 * 事件总线用的 HTTP 传输。
 *
 * 用 Ktor Curl（libcurl）而不是 WinHTTP：后者会把响应体整体读完才返回，
 * SSE 这种永不结束的响应读不到任何事件；libcurl 的写回调是增量的。
 */
internal class CurlEventStreamTransport : EventStreamTransport {

    private val client = HttpClient(Curl)

    override suspend fun postJson(
        baseUrl: String,
        path: String,
        token: String?,
        body: String,
        headers: Map<String, String>,
    ): HttpResult {
        val response = client.post(baseUrl + path) {
            contentType(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            setBody(body)
        }
        return HttpResult(response.status.value, response.bodyAsText())
    }

    override suspend fun openEventStream(
        baseUrl: String,
        path: String,
        headers: Map<String, String>,
        onLine: (String) -> Unit,
    ) {
        client.prepareGet(baseUrl + path) {
            headers.forEach { (name, value) -> header(name, value) }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw EventBusException("SSE 建流失败：HTTP ${response.status.value}")
            }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var pending = ""
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                pending += buffer.decodeToString(0, read)
                while (true) {
                    val end = pending.indexOf('\n')
                    if (end < 0) break
                    val line = pending.substring(0, end).trimEnd('\r')
                    pending = pending.substring(end + 1)
                    onLine(line)
                }
            }
        }
    }

    override fun close() {
        client.close()
    }

    private companion object {
        const val STREAM_BUFFER_SIZE = 8192
    }
}
