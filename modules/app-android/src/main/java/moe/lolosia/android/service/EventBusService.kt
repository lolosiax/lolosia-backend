package moe.lolosia.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.lolosia.android.MainActivity
import moe.lolosia.android.ui.AndroidSettingsStore
import moe.lolosia.web.ui.event.EventBusSubscription
import moe.lolosia.web.ui.event.ServerPushMessage
import java.util.concurrent.atomic.AtomicInteger

/**
 * 服务端 EventBus 的 Android 前台宿主。
 *
 * 订阅逻辑（握手、SSE 解析、退避重连、事件文案）都在公共层
 * `moe.lolosia.web.ui.event`，本类只做 Android 生命周期与通知栏：
 * - 前台通知展示连接状态；
 * - 每条推送用独立通知展示；
 * - 通知上的「停止」会结束服务。
 *
 * 登录态由公共层每次重连时从 [AndroidSettingsStore] 重新读取：未登录时安静等待，
 * Activity 登录/登出后不需要额外通知本服务。
 *
 * @author 洛洛希雅Lolosia
 */
class EventBusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventId = AtomicInteger(EVENT_ID_BASE)

    @Volatile
    private var lastStatus: String = ""

    private lateinit var subscription: EventBusSubscription

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForegroundCompat(buildOngoing("正在连接消息总线…"))

        subscription = EventBusSubscription(
            settings = AndroidSettingsStore(this),
            transportFactory = { AndroidEventStreamTransport() },
            onStatus = ::updateOngoing,
            onMessage = ::postEvent,
            onLog = { Log.i(TAG, it) },
        )
        scope.launch { subscription.run() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        subscription.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun postEvent(message: ServerPushMessage) {
        getSystemService(NotificationManager::class.java)
            .notify(eventId.incrementAndGet(), buildEvent(message))
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "消息推送连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持与服务器事件总线的连接"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "服务器消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "番组更新等服务端推送"
            },
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun updateOngoing(status: String) {
        if (status == lastStatus) return
        lastStatus = status
        getSystemService(NotificationManager::class.java).notify(FOREGROUND_ID, buildOngoing(status))
    }

    private fun buildOngoing(status: String): Notification {
        val builder = newBuilder(CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("洛洛希雅 · 消息推送")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "停止", stopIntent())
        return builder.build()
    }

    private fun buildEvent(message: ServerPushMessage): Notification =
        newBuilder(CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(Notification.BigTextStyle().bigText(message.text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun newBuilder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, 0, intent, pendingFlags())
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, EventBusService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(this, 1, intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        return PendingIntent.FLAG_UPDATE_CURRENT or immutable
    }

    companion object {
        private const val TAG = "EventBusService"
        private const val CHANNEL_SERVICE = "event-bus"
        private const val CHANNEL_EVENTS = "event-bus-events"
        private const val FOREGROUND_ID = 1001
        private const val EVENT_ID_BASE = 2000
        private const val ACTION_STOP = "moe.lolosia.android.action.STOP_EVENT_BUS"
    }
}
