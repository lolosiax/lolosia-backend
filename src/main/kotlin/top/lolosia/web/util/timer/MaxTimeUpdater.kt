package top.lolosia.web.util.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * 最大限时延迟执行工具。
 *
 * 当在指定时间内连续执行操作时，除最后一次操作外皆被取消。
 * 当最后一次操作的指定时间段内没有新的操作，则执行这一次操作。
 * 当指定操作是这一时间段内的最后一次操作时，该操作不会被取消。
 */
class MaxTimeUpdater(
    private val delay: Duration,
    context: CoroutineContext = Dispatchers.Default
) : CoroutineScope {
    override val coroutineContext = context

    private val lastAccess = AtomicInteger(0)
    private var lastAccessTime: Long = System.currentTimeMillis()
    operator fun invoke(block: suspend () -> Unit) {
        val acc = lastAccess.incrementAndGet()
        launch {
            delay(delay)
            val now = System.currentTimeMillis()
            if (now - lastAccessTime > delay.inWholeMilliseconds) {
                lastAccessTime = now
                block()
                return@launch
            }
            if (acc == lastAccess.get()) {
                block()
                return@launch
            }
        }
    }

    fun now(block: () -> Unit) {
        lastAccess.incrementAndGet()
        lastAccessTime = System.currentTimeMillis()
        block()
    }
}