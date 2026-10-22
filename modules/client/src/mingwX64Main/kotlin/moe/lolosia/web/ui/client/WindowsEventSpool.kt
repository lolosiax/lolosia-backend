package moe.lolosia.web.ui.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.web.ui.event.ServerPushMessage
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import platform.posix.time

/** 服务与 UI 之间的单向队列：服务追加，UI 进程按行读取并弹出通知。 */
internal object WindowsEventSpool {

    private val json = Json { encodeDefaults = true }

    val path: String get() = "${programDataDir()}\\Lolosia\\event-bus-events.jsonl"

    fun append(message: ServerPushMessage) {
        runCatching {
            val file = path.toPath()
            file.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            val record = EventSpoolRecord(time(null) * 1000L, message.title, message.text)
            val sink = FileSystem.SYSTEM.appendingSink(file).buffer()
            try {
                sink.writeUtf8(json.encodeToString(record))
                sink.writeUtf8("\n")
            } finally {
                sink.close()
            }
        }
    }
}
