package top.lolosia.web.util.llm

import top.lolosia.web.util.event.EventHandle
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.DefaultChatClient.DefaultChatClientRequestSpec
import org.springframework.ai.tool.ToolCallback
import java.util.function.Consumer

class ToolCallbackInspector {

    companion object {
        @JvmStatic
        fun inspect(spec: ChatClient.ChatClientRequestSpec, block: Consumer<Spec>) {
            spec.inspect { block.accept(this) }
        }
    }

    private val entryHandle = EventHandle<MergedToolCallback.EntryEvent>()
    private val exitHandle = EventHandle<MergedToolCallback.ExitEvent>()
    val spec = Spec()

    fun onEntry(event: MergedToolCallback.EntryEvent) {
        entryHandle.publish(event)
    }

    fun onExit(event: MergedToolCallback.ExitEvent) {
        exitHandle.publish(event)
    }

    inner class Spec {
        fun onEntry(block: Consumer<MergedToolCallback.EntryEvent>) {
            entryHandle += {
                block.accept(it)
            }
        }

        fun onExit(block: Consumer<MergedToolCallback.ExitEvent>) {
            exitHandle += {
                block.accept(it)
            }
        }
    }
}

/**
 * 将所有 ToolCallback 转换为 MergedToolCallback，并可以添加函数调用监听
 */
fun ChatClient.ChatClientRequestSpec.inspect(
    block: ToolCallbackInspector.Spec.() -> Unit = {}
): ChatClient.ChatClientRequestSpec {
    this as DefaultChatClientRequestSpec
    val inspector = ToolCallbackInspector()
    val mapped = this.toolCallbacks.map {
        val callback = it as? MergedToolCallback ?: MergedToolCallback(it as ToolCallback)

        callback.entryEventHandle += inspector::onEntry
        callback.exitEventHandle += inspector::onExit
        callback
    }
    this.toolCallbacks.clear()
    this.toolCallbacks += mapped
    block(inspector.spec)
    return this
}