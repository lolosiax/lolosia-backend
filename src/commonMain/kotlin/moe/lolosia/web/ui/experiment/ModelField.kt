package moe.lolosia.web.ui.experiment

import moe.lolosia.web.ui.mvvm.Property

/**
 * ViewModel 里对文档可见的一个数据模型字段。
 *
 * 它是 [Property] 与视图层数据模型之间的桥：平台侧只要遍历 ViewModel 的字段表，
 * 就能一次做完「声明变量 → 订阅变更 → 把新值推回视图」，
 * 因此新增一个可绑定字段只需要在字段表里加一行，平台代码不用动。
 *
 * @author 洛洛希雅Lolosia
 */
sealed interface ModelField {

    /** RML 里 `{{ 名字 }}` / `data-if` / `data-class-*` 引用的变量名。 */
    val name: String

    /** 订阅本字段的值变更；返回的句柄用于取消订阅。 */
    fun observe(handler: () -> Unit): Property.Subscription

    /** 文本字段。 */
    class Text(override val name: String, val source: Property<String>) : ModelField {
        override fun observe(handler: () -> Unit): Property.Subscription = source.observe { handler() }
    }

    /** 整数字段。 */
    class Integer(override val name: String, val source: Property<Int>) : ModelField {
        override fun observe(handler: () -> Unit): Property.Subscription = source.observe { handler() }
    }

    /** 布尔字段，通常配 `data-if` / `data-class-*` 用。 */
    class Flag(override val name: String, val source: Property<Boolean>) : ModelField {
        override fun observe(handler: () -> Unit): Property.Subscription = source.observe { handler() }
    }
}
