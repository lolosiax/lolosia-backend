package moe.lolosia.android;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;

import org.libsdl.app.SDLActivity;

import moe.lolosia.android.service.EventBusService;
import moe.lolosia.android.ui.AndroidUiHost;

/**
 * Android 入口：直接复用 SDL3 的 Activity / Surface 生命周期。
 *
 * SDLActivity 会加载 {@code getLibraries()} 里的共享库，再通过 dlsym 调用最后一个库里的
 * {@code SDL_main}（见 {@code org.libsdl.app.SDLActivity#getMainSharedObject}）。
 * liblolosia.so 是这个入口，窗口与帧循环在它的 SDL 线程里；Kotlin 的页面状态在 Activity
 * 主线程，通过 JNI 命令队列驱动同一套 RmlUi 数据模型。
 */
public class MainActivity extends SDLActivity {

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "lolosia" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // super.onCreate 里已经 loadLibraries()，此时 JNI 符号可用。
        AndroidUiHost.start(this);
        requestNotificationPermission();
        startEventBusService();
    }

    /** 前台订阅服务：未登录时自己等待，登录后自动连上事件总线。 */
    private void startEventBusService() {
        Intent intent = new Intent(this, EventBusService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /** Android 13+ 的通知权限；被拒绝也不影响连接，只是看不到推送。 */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1001);
        }
    }

    /**
     * SDLActivity 会把硬件返回键直接送进 SDL（不走 onBackPressed），所以这里先截下来：
     * 松开时交给 UI 决定是「控件库 → 我的」还是退出。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            AndroidUiHost.onBack(this);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // 手势返回 / 旧式返回：控件库先回「我的」，其余情况退出。
        AndroidUiHost.onBack(this);
    }
}
