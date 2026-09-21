package moe.lolosia.web.util.event

/**
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */

import moe.lolosia.web.util.packageLogger

/** 事件控制器 */
open class EventHandle<E : IEvent> : PublisableEventHandle<E> {

    companion object {
        val logger = packageLogger<EventHandle<*>>()
    }

    private var callbacks = mutableSetOf<IEventRunnable<E>>()
    private var queue = ArrayDeque<() -> Unit>()
    private var running = false
    override fun addListener(runnable: IEventRunnable<E>) {
        if (running) queue.add { callbacks.add(runnable) }
        else callbacks.add(runnable)
    }

    override fun removeListener(runnable: IEventRunnable<E>) {
        if (running) queue.add { callbacks.remove(runnable) }
        else callbacks.remove(runnable)
    }

    override fun publish(event: E) {
        running = true
        try {
            for (runnable in callbacks) {
                try {
                    runnable.onEvent(event)
                } catch (e: Throwable) {
                    logger.warn("EventHandle执行中发生异常", e)
                }
            }
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        } catch (e: Throwable) {
            throw e
        } finally {
            running = false
        }
    }

    override val eventListeners: Iterable<IEventRunnable<E>> get() = callbacks.toList()

    fun clear() {
        callbacks.clear()
    }
}
