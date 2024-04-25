package top.lolosia.web.util.spring

import java.io.Closeable

interface EventSource : Closeable {
    val isClosed: Boolean
    fun send(event: String = "message", data: Any? = null)
}