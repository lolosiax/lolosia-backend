package moe.lolosia.android.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.lolosia.web.ui.api.UnavailableLocalServiceApi
import moe.lolosia.web.ui.app.AppStage
import moe.lolosia.web.ui.app.ClientApp
import moe.lolosia.web.ui.app.ClientHost
import moe.lolosia.web.ui.app.MainTab

/**
 * Android 侧的应用入口：在 Activity 主线程构造共享的 [ClientApp] / [ClientHost]。
 *
 * native 帧循环单独跑在 SDL 线程；这里用 100ms 的 tick 代替桌面版的「每帧结束」回调，
 * 做表单回读与文档切换 —— 登录框输入这类双向绑定不需要逐帧精度。
 *
 * @author 洛洛希雅Lolosia
 */
object AndroidUiHost {

    private const val TAG = "LolosiaUi"
    private const val TICK_MS = 100L

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var backend: AndroidUiBackend
    private lateinit var app: ClientApp
    private lateinit var host: ClientHost
    private var started = false

    /** 由 MainActivity 在 `super.onCreate` 之后调用（此时 native 库已加载）。 */
    @JvmStatic
    fun start(context: Context) {
        if (started) return
        started = true

        NativeUi.nativeInit()

        backend = AndroidUiBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        app = ClientApp(
            settingsStore = AndroidSettingsStore(context.applicationContext),
            remoteApiFactory = { AndroidRemoteApi(it) },
            localServiceApi = UnavailableLocalServiceApi(),
            imageLoader = AndroidImageLoader(),
            scope = scope,
            postToUi = { it() },
        )
        host = ClientHost(app, backend) { message -> Log.e(TAG, message) }
        host.start()
        mainHandler.postDelayed(tick, TICK_MS)
    }

    /** native 文档动作（SDL 线程）→ 主线程。 */
    @JvmStatic
    fun onAction(name: String) {
        if (!started) return
        mainHandler.post { app.onAction(name) }
    }

    /** 返回键：控件库先回「我的」，其余情况退出 Activity。 */
    @JvmStatic
    fun onBack(activity: Activity) {
        mainHandler.post {
            if (started && app.stage.value == AppStage.Main && app.tab.value == MainTab.Components) {
                app.onAction("back")
            } else {
                activity.finish()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { host.onFrameEnd() }
                .onFailure { Log.e(TAG, "帧末同步失败", it) }
            mainHandler.postDelayed(this, TICK_MS)
        }
    }
}
