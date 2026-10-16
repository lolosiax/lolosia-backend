package moe.lolosia.web.ui.experiment

import moe.lolosia.web.ui.mvvm.Command
import moe.lolosia.web.ui.mvvm.Property

/**
 * 「RmlUi + flexbox + 数据绑定」实验用的 ViewModel。
 *
 * MVVM 的中间层：文档（View）只认 [fields] 里的变量名与 [dispatch] 收到的动作名，
 * 状态与逻辑全在下面这些 [Property] / [Command] 上；文档里没有任何业务判断。
 * 界面改动的链路是「按钮 → data-event-click → C++ 回调 → [dispatch] → 改 Property
 * → 平台侧推回数据模型 → RmlUi 重绘」，全程不需要手写坐标或手工刷新。
 *
 * @author 洛洛希雅Lolosia
 */
class UiProbeViewModel {

    val title = Property("洛洛希雅 · RmlUi 布局实验")

    val subtitle = Property("结构写在 RML、样式写在 RCSS（flexbox 自动排版）；数值全部来自 Kotlin 的 ViewModel")

    val count = Property(0)

    val highlighted = Property(false)

    /** 最近一次收到的动作名，直接显示在界面上，方便确认回调链路是通的。 */
    val lastAction = Property("（还没有交互）")

    val increment: Command = Command { count.value += 1 }

    val incrementTen: Command = Command { count.value += 10 }

    val reset: Command = Command { count.value = 0 }

    val toggleHighlight: Command = Command { highlighted.value = !highlighted.value }

    /** 对文档公开的字段表；平台侧据此建立与视图层数据模型的绑定。 */
    val fields: List<ModelField> = listOf(
        ModelField.Text("title", title),
        ModelField.Text("subtitle", subtitle),
        ModelField.Integer("count", count),
        ModelField.Flag("highlighted", highlighted),
        ModelField.Text("lastAction", lastAction),
    )

    /** 文档里 `data-event-click="名字"` 统一从这里进来；不认识的名字也记下来，便于发现拼写错误。 */
    fun dispatch(action: String) {
        val command = when (action) {
            "increment" -> increment
            "increment-ten" -> incrementTen
            "reset" -> reset
            "toggle-highlight" -> toggleHighlight
            else -> null
        }
        if (command == null) {
            lastAction.value = "未处理的动作：$action"
            return
        }
        lastAction.value = action
        command.execute()
    }
}
