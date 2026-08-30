package moe.lolosia.web.util.event

/**
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */

/** IEventHandle 扩展：lambda 事件回调 */
class KtLambdaEventRunnable<E : IEvent> : IEventRunnable<E> {
    lateinit var runnable: (e: E) -> Unit
    override fun onEvent(event: E) {
        runnable(event)
    }
}
