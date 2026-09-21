package moe.lolosia.web.util.event

/**
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */

/** 事件处理器接口 */
interface IEventHandle<E : IEvent> {
    fun addListener(runnable: IEventRunnable<E>)

    val eventListeners: Iterable<IEventRunnable<E>>

    fun removeListener(runnable: IEventRunnable<E>)

    operator fun minusAssign(runnable: IEventRunnable<E>) {
        removeListener(runnable)
    }

    operator fun plusAssign(runnable: IEventRunnable<E>) {
        addListener(runnable)
    }

    operator fun plusAssign(runnable: (e: E) -> Unit) {
        addListener(runnable)
    }

    fun addListener(runnable: (e: E) -> Unit) {
        addListener(KtLambdaEventRunnable<E>().apply {
            this.runnable = runnable
        })
    }

    fun removeListener(runnable: (e: E) -> Unit) {
        for (lis in this.eventListeners) {
            if (lis is KtLambdaEventRunnable) {
                if (lis == runnable) removeListener(lis as IEventRunnable)
                break
            }
        }
    }

    operator fun minusAssign(runnable: (e: E) -> Unit) {
        removeListener(runnable)
    }
}
