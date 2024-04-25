package top.lolosia.web.util.kotlin

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.suspendCoroutine

class PromiseContinuation<T>(override val context: CoroutineContext = Dispatchers.Default) : Continuation<T> {
    private val listener = mutableListOf<Continuation<T>>()
    private var result: Result<T>? = null
    var isResumed = false
        private set

    override fun resumeWith(result: Result<T>) {
        if (isResumed) throw IllegalStateException("PromiseContinuation.resumeWith is called twice")
        synchronized(listener) {
            this.isResumed = true
            this.result = result
            listener.forEach {
                it.resumeWith(result)
            }
            listener.clear()
        }
    }

    suspend fun await(): T {
        return suspendCoroutine {
            synchronized(listener) {
                if (isResumed) {
                    it.resumeWith(result!!)
                } else {
                    listener += it
                }
            }
        }
    }
}