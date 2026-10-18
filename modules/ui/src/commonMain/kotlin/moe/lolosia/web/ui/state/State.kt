package moe.lolosia.web.ui.state

/**
 * 可观察状态：赋值即通知订阅者，值相同不触发。
 *
 * UI 循环是单线程的；跨线程写入由宿主的任务队列转回 UI 线程后再赋值。
 *
 * @param initial 初始值。
 * @author 洛洛希雅Lolosia
 */
class State<T>(initial: T) {

    private val observers = mutableListOf<(T) -> Unit>()

    var value: T = initial
        set(next) {
            if (field == next) return
            field = next
            observers.toList().forEach { it(next) }
        }

    /** 订阅变更；返回的句柄用于取消订阅。不立即回调当前值。 */
    fun observe(handler: (T) -> Unit): Subscription {
        observers += handler
        return Subscription { observers.remove(handler) }
    }

    /** 取消订阅的句柄；重复取消无副作用。 */
    class Subscription internal constructor(private val onCancel: () -> Unit) {
        private var active = true

        fun cancel() {
            if (!active) return
            active = false
            onCancel()
        }
    }
}
