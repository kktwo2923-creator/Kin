package com.ktwo.cts.service

import android.app.*
import android.content.*
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ktwo.cts.R
import com.ktwo.cts.data.Mode
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.QuickSwitchActivity
import kotlinx.coroutines.*

/**
 * 状态栏常驻通知服务:
 *  - 标题显示当前模式,正文显示电量
 *  - 点击通知 -> QuickSwitchActivity(悬浮对话框)快切基础模式
 *  注: 不做任何自动调度(那是 CTS 模块的事),仅展示 + 手动入口。
 */
class NotifyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var battery = -1
    private var modeKey = ""

    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) {
                val pct = level * 100 / scale
                if (pct != battery) { battery = pct; updateNotification() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // 轮询当前模式(读不到取全局),变化才刷新
        scope.launch {
            while (isActive) {
                val m = withContext(Dispatchers.IO) {
                    RootShell.currentMode().ifEmpty { RootShell.readGlobalMode() }
                }
                if (m != modeKey) { modeKey = m; updateNotification() }
                delay(4000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(batteryRx) }
        scope.cancel()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val mode = Mode.fromKey(modeKey)
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, QuickSwitchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val batteryText = if (battery in 0..100) "电量 $battery%" else "电量 --"
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("当前模式: ${mode.label}")
            .setContentText("$batteryText · 点击切换调度模式")
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
                NotificationChannel(CH_ID, "调度状态", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    companion object {
        private const val CH_ID = "cts_status"
        private const val NOTIF_ID = 0x6CA7
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, NotifyService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NotifyService::class.java))
        }
    }
}
