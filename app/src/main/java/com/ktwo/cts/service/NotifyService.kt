package com.ktwo.cts.service

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ktwo.cts.R
import com.ktwo.cts.data.Mode
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.QuickSwitchActivity
import kotlinx.coroutines.*

/**
 * 状态栏常驻通知服务:
 *  - 标题显示当前模式,正文显示电量
 *  - 点击通知 -> 在任意 App 之上弹出悬浮窗(WindowManager 叠加层)快切基础模式
 *    (无悬浮窗权限时回退到对话框 Activity)
 *  注: 不做任何自动调度(那是 CTS 模块的事),仅展示 + 手动入口。
 */
class NotifyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var battery = -1
    private var modeKey = ""
    private var overlay: View? = null

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_SWITCHER) showSwitcher()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(batteryRx) }
        removeOverlay()
        scope.cancel()
    }

    // ============ 悬浮窗 ============

    private fun showSwitcher() {
        // 无悬浮窗权限 -> 回退到对话框 Activity
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(this, QuickSwitchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        if (overlay != null) { removeOverlay(); return }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        // 全屏透明层(不变暗背景),点击空白关闭
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusableInTouchMode = true
            setOnClickListener { removeOverlay() }
            setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                    removeOverlay(); true
                } else false
            }
        }

        // 卡片
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(18), px(20), px(20))
            background = GradientDrawable().apply {
                cornerRadius = px(26).toFloat()
                setColor(Color.parseColor("#FFF7FAFF"))
            }
            isClickable = true   // 拦截点击,避免穿透关闭
        }
        card.addView(TextView(this).apply {
            text = "快速切换模式"
            setTextColor(Color.parseColor("#0A1626"))
            textSize = 16f
            setPadding(0, 0, 0, px(14))
        })
        val rowLp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cur = modeKey
        Mode.DOWNSTREAM.forEach { m ->
            val selected = m.key == cur
            val btn = TextView(this).apply {
                text = m.label
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#0A1626"))
                setPadding(0, px(18), 0, px(18))
                background = GradientDrawable().apply {
                    cornerRadius = px(16).toFloat()
                    setColor(Color.parseColor(if (selected) "#FF007AFF" else "#FFE8EFFA"))
                }
                setOnClickListener {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            RootShell.writePerApp(RootShell.readPerApp(), m.key)
                            RootShell.applyMode(m.key)
                        }
                        modeKey = m.key
                        updateNotification()
                        withContext(Dispatchers.Main) { removeOverlay() }
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (m != Mode.DOWNSTREAM.first()) marginStart = px(10)
            }
            rowLp.addView(btn, lp)
        }
        card.addView(rowLp)

        root.addView(card, FrameLayout.LayoutParams(
            px(330), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching {
            wm.addView(root, lp)
            overlay = root
            root.requestFocus()
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    // ============ 通知 ============

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val mode = Mode.fromKey(modeKey)
        // 点击 -> 给本服务发"显示悬浮窗"命令(不启动 Activity,不抢前台)
        val pi = PendingIntent.getService(
            this, 1,
            Intent(this, NotifyService::class.java).setAction(ACTION_SHOW_SWITCHER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val batteryText = if (battery in 0..100) "电量 $battery%" else "电量 --"
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("当前模式: ${mode.label}")
            .setContentText("$batteryText · 点击悬浮切换模式")
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
        private const val ACTION_SHOW_SWITCHER = "com.ktwo.cts.SHOW_SWITCHER"
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, NotifyService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NotifyService::class.java))
        }
    }
}
