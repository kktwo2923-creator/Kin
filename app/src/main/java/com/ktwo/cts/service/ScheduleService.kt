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
 * 前台服务: 替代 Scene 接管
 * 思路参考 Scene (helloklf/vtools) 的 AppSwitchHandler:
 *   - 输入法/系统 UI 包强制忽略
 *   - 用户黑名单
 *   - dynamic 总开关 + STRICT 同模式强制重发
 *   - 灭屏切设定模式(默认 powersave)
 */
class ScheduleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var pm: PowerManager
    private var lastPkg: String = ""
    private var lastDispatched: String = ""
    private var screenOn: Boolean = true

    private val detector by lazy { ForegroundDetector(this) }

    /** Scene 风格: 永远忽略的前台 App(系统 UI / 启动器 / 锁屏 等) */
    private val builtinIgnored = setOf(
        "com.android.systemui",
        "com.android.launcher", "com.android.launcher3",
        "com.oplus.launcher", "com.oneplus.launcher",
        "com.miui.home", "com.huawei.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "android",
        "com.system.ui",
    )

    /** 输入法包(动态查询) */
    private var imePackages: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        pm = getSystemService(POWER_SERVICE) as PowerManager
        screenOn = pm.isInteractive
        loadImePackages()
        startForeground(NOTIF_ID, buildNotification("启动中…"))
        registerReceiver(screenRx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenRx) }
        scope.cancel()
    }

    /** 加载输入法列表(Scene 风格 — 输入法切到前台不动模式) */
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
                    // 亮屏强制重发当前画像: 同时清空 lastPkg,
                    // 否则解锁后前台仍是同一 App(pkg == lastPkg),主循环不会重新下发,
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

        // Scene 风格: 输入法 / 系统 UI / 启动器永远忽略
        if (pkg in builtinIgnored || pkg in imePackages) return

        // 用户黑名单忽略
        val bl = PrefsExt.blacklist(ctx)
        if (pkg in bl) return

        val profiles = RootShell.readPerApp()
        val perApp = profiles[pkg]
        val target = if (perApp.isNullOrEmpty() || perApp == "follow")
            RootShell.readGlobalMode() else perApp
        if (target.isBlank()) return

        // STRICT: 即使同模式也重发(默认关)
        val strict = PrefsExt.strict(ctx)
        if (!strict && target == lastDispatched) return

        applyAndUpdate(target, pkg)
    }

    private suspend fun dispatchScreenOff() {
        val key = Prefs.screenOffMode(this@ScheduleService)
        applyAndUpdate(key, "(灭屏)")
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
