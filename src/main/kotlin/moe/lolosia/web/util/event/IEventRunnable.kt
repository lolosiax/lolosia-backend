package moe.lolosia.web.util.event

/**
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */

/** 事件执行Runnable */
fun interface IEventRunnable<T : IEvent> {
    fun onEvent(event: T)
}
