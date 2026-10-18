package moe.lolosia.web.ui.mvvm

/**
 * 可观察属性：MVVM 里 ViewModel 暴露给 View 的最小单元。
 *
 * 赋值即通知订阅者，View 收到通知后标记脏区、按需重跑布局与重绘；值相同时不触发通知，
 * 避免无意义的整帧重绘。Kotlin/Native 的 UI 循环是单线程的，这里不做同步。
 *
 * @param initial 初始值。
 * @author 洛洛希雅Lolosia
 */
class Property<T>(initial: T) {

    private val observers = mutableListOf<(T) -> Unit>()

    var value: T = initial
        set(next) {
            if (field == next) return
            field = next
            // 复制一份再遍历：回调里取消订阅会改动 observers
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
