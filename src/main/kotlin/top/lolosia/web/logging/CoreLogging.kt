package top.lolosia.web.logging

import java.io.OutputStream
import java.util.*

/**
 * 日志输出流
 *
 * @author 洛洛希雅Lolosia
 * @since 2023-12-25 11:42
 */
object CoreLogging {
    private val mStream = CorePrintStream()
    val listener get() = mStream.listener
    val outputStream: OutputStream get() = mStream
}

private class CorePrintStream : OutputStream() {

    val listener: MutableList<(String) -> Unit> = Collections.synchronizedList(mutableListOf<(String) -> Unit>())
    private val regex = "\u001B[^m]+m".toRegex()

    override fun write(b: ByteArray) {
        try {
            var str = String(b)
            str = str.replace(regex, "")
            listener.forEach {
                try {
                    it(str)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        write(b.sliceArray(off until (off + len)))
    }
}