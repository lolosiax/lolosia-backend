package moe.lolosia.android.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.lolosia.web.ui.event.EventStreamTransport
import moe.lolosia.web.ui.event.HttpResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 的 EventBus 传输：`HttpURLConnection`。
 *
 * SSE 协议解析、握手顺序都在公共层（`:modules:ui` 的 `EventBusClient`），
 * 这里只负责发请求、读行与断开；换成 Ktor 或 OkHttp 不影响上层。
 *
 * @author 洛洛希雅Lolosia
 */
internal class AndroidEventStreamTransport : EventStreamTransport {

    @Volatile
    private var streamConnection: HttpURLConnection? = null

    private val closed = AtomicBoolean(false)

    override suspend fun postJson(
        baseUrl: String,
        path: String,
        token: String?,
        body: String,
        headers: Map<String, String>,
    ): HttpResult = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(body.toByteArray()) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes() }?.decodeToString().orEmpty()
            HttpResult(status, text)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun openEventStream(
        baseUrl: String,
        path: String,
        headers: Map<String, String>,
        onLine: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        streamConnection = connection
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("SSE 建流失败：HTTP $status")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!closed.get()) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
        } finally {
            streamConnection = null
            connection.disconnect()
        }
    }

    override fun close() {
        closed.set(true)
        streamConnection?.disconnect()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        /** 服务端每 5 秒心跳；35 秒读不到任何字节视为死连接。 */
        const val READ_TIMEOUT_MS = 35_000
    }
}
