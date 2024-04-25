package top.lolosia.web.util.kotlin

import kotlinx.coroutines.Dispatchers
import org.apache.commons.collections4.BidiMap
import org.apache.commons.collections4.bidimap.DualLinkedHashBidiMap
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

private val logger by lazy {
    LoggerFactory.getLogger("top.lolosia.web.util.kotlin.PackageKt")
}

val pass = Unit

fun Instant.toDate(): Date {
    return Date(toEpochMilli())
}

/**
 * 保留n位小数
 * @param fractionDigits n
 */
fun Double.fixed(fractionDigits: Int): String {
    return String.format("%.${fractionDigits}f", this)
}

/**
 * 保留n位小数
 * @param fractionDigits n
 */
fun Float.fixed(fractionDigits: Int): String {
    return String.format("%.${fractionDigits}f", this)
}


fun <T> createContinuation(): Continuation<T> = createContinuation { _, e ->
    if (e != null) {
        logger.error("An exception occurs in Continuation", e)
    }
}

fun <T> createContinuation(block: (result: T?, error: Throwable?) -> Unit?): Continuation<T> {
    return object : Continuation<T> {
        override val context: CoroutineContext
            get() = Dispatchers.Default

        override fun resumeWith(result: Result<T>) {
            result.fold({
                block(it, null)
            }) {
                block(null, it)
            }
        }
    }
}

fun <K, V> Map<K, V>.toBidiMap(): BidiMap<K, V> {
    return DualLinkedHashBidiMap(this)
}