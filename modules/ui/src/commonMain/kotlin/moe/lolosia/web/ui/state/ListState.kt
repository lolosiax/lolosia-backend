package moe.lolosia.web.ui.state

/**
 * 可观察列表：整体替换时通知订阅者。
 *
 * 本期列表规模很小（番组表一页），不做增量 diff；整表替换足够，
 * 后续接分页时再按需补 append / remove。
 *
 * @author 洛洛希雅Lolosia
 */
class ListState<T> {

    private val items = mutableListOf<T>()
    private val observers = mutableListOf<(List<T>) -> Unit>()

    val size: Int get() = items.size

    operator fun get(index: Int): T = items[index]

    /** 当前快照；调用方只读。 */
    fun snapshot(): List<T> = items.toList()

    /** 整体替换并通知。 */
    fun replaceAll(next: List<T>) {
        items.clear()
        items.addAll(next)
        val view = items.toList()
        observers.toList().forEach { it(view) }
    }

    /** 清空并通知。 */
    fun clear() {
        if (items.isEmpty()) return
        replaceAll(emptyList())
    }

    /** 订阅变更；返回的句柄用于取消订阅。 */
    fun observe(handler: (List<T>) -> Unit): State.Subscription {
        observers += handler
        return State.Subscription { observers.remove(handler) }
    }
}
