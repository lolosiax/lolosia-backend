package moe.lolosia.web.util.property

import kotlin.reflect.KProperty0

/**
 * 计算式可监听属性
 *
 * @param dependencies 计算属性所需依赖
 * @param block 用于计算结果的回调
 * @author 洛洛希雅Lolosia
 * @since 2024-08-28 17:27
 */
class ComputedProperty<T>(
    vararg dependencies: KProperty0<*>,
    val block: () -> T
) : AbstractTriggerProperty<T>() {

    override val rawValue: T
        get() = block()

    // 此字段用于保持侦听器不被 GC。
    @Suppress("unused")
    private val hooks = dependencies.map {
        it.asProperty().addListener(false) {
            trigger()
        }
    }

    private var updating = false

    override fun trigger() {
        if (updating) throw IllegalStateException("不允许循环更新！")
        synchronized(this) {
            try {
                updating = true
                super.trigger()
            } finally {
                updating = false
            }
        }
    }
}