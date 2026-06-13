package com.ktwo.cts.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.ktwo.cts.root.RootShell

/**
 * 无障碍服务:监听窗口焦点变化(TYPE_WINDOW_STATE_CHANGED),把前台包名推给 daemon
 * (写 /sdcard/Android/CTS/topapp.txt,daemon inotify 即时消费)。
 *
 * 定位是 daemon dumpsys 轮询的"加速器":启用后 daemon 直接用推送值(零 dumpsys popen、零延迟);
 * 未启用/被杀时推送变陈旧,daemon 自动回退自身 dumpsys 检测,不影响"不开 App 也能用"。
 * 过滤(黑名单/小窗/游戏保活)全部交给 daemon,这里只忠实上报焦点包名 + 心跳保活新鲜度。
 * 参考 Scene(helloklf/vtools)AppSwitchHandler 的事件来源。
 */
class AppSwitchService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var lastPkg: String = ""

    // 心跳:前台长时间不变时无障碍不再回调,定时重推让 daemon 端推送值保持新鲜(否则 daemon 回退 dumpsys)
    private val heartbeat = object : Runnable {
        override fun run() {
            if (lastPkg.isNotEmpty()) RootShell.pushTopApp(lastPkg)
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 启用后立刻推一次当前焦点窗口包名, 不必等下一次切换事件
        runCatching {
            rootInActiveWindow?.packageName?.toString()?.let { pkg ->
                if (pkg.contains('.')) { lastPkg = pkg; foreground = pkg; RootShell.pushTopApp(pkg) }
            }
        }
        main.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == lastPkg) return
        if (!pkg.contains('.')) return   // 滤掉非包名(部分系统浮层的 className)
        lastPkg = pkg
        foreground = pkg
        RootShell.pushTopApp(pkg)
    }

    // 服务被系统解绑时允许重新绑定(无障碍服务异常后自动恢复)
    override fun onUnbind(intent: android.content.Intent?): Boolean = true

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(heartbeat)
    }

    companion object {
        private const val HEARTBEAT_MS = 10_000L

        /** 最近一次焦点包名(无障碍在进程内即时可得, 供通知服务零 root 读取); 服务未启用时为空。 */
        @Volatile var foreground: String = ""
            private set

        /** 当前无障碍服务是否已被用户启用。 */
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val full = ComponentName(ctx, AppSwitchService::class.java).flattenToString()
            val short = ComponentName(ctx, AppSwitchService::class.java).flattenToShortString()
            return flat.split(':').any { it.equals(full, true) || it.equals(short, true) }
        }
    }
}
