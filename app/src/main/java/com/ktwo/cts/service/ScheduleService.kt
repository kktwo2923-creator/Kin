package com.ktwo.cts.service

import android.app.*
import android.content.*
import android.os.*
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import com.ktwo.cts.R
import com.ktwo.cts.data.Mode
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.PrefsExt
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 前台服务:替代 Scene 接管。daemon 存活时立即自停;daemon 不在时作为兜底运行。
 * 参考 Scene AppSwitchHandler:输入法/系统UI忽略,用户黑名单,同模式去重,灭屏切省电。
 */
class ScheduleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var pm: PowerManager
    private var lastPkg: String = ""
    private var lastDispatched: String = ""
    private var screenOn: Boolean = true

    private val detector by lazy { ForegroundDetector(this) }

    private val builtinIgnored = setOf(
        "com.android.systemui",
        "com.android.launcher", "com.android.launcher3",
        "com.oplus.launcher", "com.oneplus.launcher",
        "com.miui.home", "com.huawei.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "android",
        "com.system.ui",
    )

    private var imePackages: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (RootShell.daemonAlive()) {
            startForegroundCompat()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        pm = getSystemService(POWER_SERVICE) as PowerManager
        screenOn = pm.isInteractive
        loadImePackages()
        startForegroundCompat()
        registerReceiver(screenRx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        startLoop()
    }

    /** Android 14+ startForeground 必须带 foregroundServiceType,否则抛异常。 */
    private fun startForegroundCompat() {
        val notif = buildNotification("启动中…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenRx) }
        scope.cancel()
    }

    private fun loadImePackages() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imePackages = imm.inputMethodList.map { it.packageName }.toSet()
        }
    }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    scope.launch { dispatchScreenOff() }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    // 清空 lastPkg 强制重发:否则解锁后前台仍同一 App,主循环不重新下发,
                    // 设备会卡在灭屏的省电模式。
                    lastPkg = ""
                    lastDispatched = ""
                }
            }
        }
    }

    private fun startLoop() = scope.launch {
        while (isActive) {
            try {
                if (!Prefs.enabled(this@ScheduleService)) { delay(3000); continue }
                // daemon 存活时退为纯 GUI,避免与 daemon 同时写 sysfs 打架
                if (RootShell.daemonAlive()) { delay(5000); continue }
                if (!screenOn) { delay(5000); continue }
                val pkg = detector.currentForegroundPackage().orEmpty()
                if (pkg.isNotEmpty() && pkg != lastPkg) {
                    lastPkg = pkg
                    dispatchForPackage(pkg)
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { /* ignore */ }
            delay(Prefs.pollMs(this@ScheduleService).toLong())
        }
    }

    private suspend fun dispatchForPackage(pkg: String) {
        val ctx = this@ScheduleService
        if (pkg in builtinIgnored || pkg in imePackages) return
        val bl = PrefsExt.blacklist(ctx)
        if (pkg in bl) {
            // 黑名单=不接管:回到全局模式,而不是停留在上一个(常为灭屏/前一应用的省电)模式
            val g = RootShell.readGlobalMode()
            if (g.isNotBlank() && g != lastDispatched) applyAndUpdate(g, "$pkg(黑名单·跟随全局)")
            return
        }

        val profiles = RootShell.readPerApp()
        val perApp = profiles[pkg]
        val target = if (perApp.isNullOrEmpty() || perApp == "follow")
            RootShell.readGlobalMode() else perApp
        if (target.isBlank()) return

        if (target == lastDispatched) return

        applyAndUpdate(target, pkg)
    }

    private suspend fun dispatchScreenOff() {
        applyAndUpdate("powersave", "(灭屏)")
    }

    private suspend fun applyAndUpdate(mode: String, reason: String) {
        val ok = RootShell.applyMode(mode)
        if (ok) lastDispatched = mode
        val label = Mode.fromKey(mode).label
        updateNotification("${if (ok) "✓" else "✗"} $label · $reason")
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("OnePlus V Pro 调度")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CH_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CH_ID, "调度服务", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    companion object {
        private const val CH_ID = "cts_schedule"
        private const val NOTIF_ID = 0x6BAD
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ScheduleService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScheduleService::class.java))
        }
    }
}
