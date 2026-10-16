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

    /**
     * 表单控件的绑定目标。
     *
     * 这几个值的写入者是**视图**（用户在输入框里敲、拖滑杆、勾复选框），
     * 与上面那些由命令驱动的字段方向相反；改完由平台侧读回，见 [viewSyncPending]。
     */
    val nickname = Property("洛洛希雅")

    val agree = Property(false)

    val theme = Property("dark")

    val opacity = Property(0.6f)

    /**
     * 视图侧刚改过数据，等平台侧在帧末把值读回。
     *
     * 为什么不在 change 回调里当场读：同一次 change 事件里，视图层写回数据模型的监听器
     * 与我们的 action 监听器谁先被调用没有保证，当场读可能读到写之前的旧值。
     * 等这一帧的事件处理全部跑完再读，就与监听器顺序无关了。
     */
    var viewSyncPending: Boolean = false

    /** 表单值读回 ViewModel 后的快照，显示在界面上 —— 用来确认这条反向数据流真的通了。 */
    val formEcho = Property("（还没改过表单控件）")

    /** 平台侧把表单控件的值读回 [Property] 之后调用；把结果记成一行文字供界面显示。 */
    fun onFormSynced() {
        formEcho.value =
            "已读回 ViewModel：昵称=${nickname.value}｜主题=${theme.value}｜不透明度=${opacity.value}｜同意=${agree.value}"
    }

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
        ModelField.Text("nickname", nickname),
        ModelField.Flag("agree", agree),
        ModelField.Text("theme", theme),
        ModelField.Decimal("opacity", opacity),
        ModelField.Text("formEcho", formEcho),
    )

    /** 文档里 `data-event-click="名字"` 统一从这里进来；不认识的名字也记下来，便于发现拼写错误。 */
    fun dispatch(action: String) {
        // 表单控件改值只是通知「值变了」，不是用户动作：只置标记，由平台侧在帧末读回
        if (action == SYNC_FORM) {
            viewSyncPending = true
            return
        }
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

    companion object {
        /** 表单控件在 `data-event-change` 里报上来的动作名，见 [viewSyncPending]。 */
        const val SYNC_FORM = "sync-form"
    }
}
