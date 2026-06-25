package com.ktwo.cts.service

import android.app.*
import android.content.*
import android.content.res.Configuration
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
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
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
 * 状态栏常驻通知服务:显示当前模式+电量;点击弹 WindowManager 悬浮窗快切(无悬浮窗权限则回退到 Activity)。
 */
class NotifyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var battery = -1
    private var modeKey = ""
    private var isProfile = false   // 当前前台是否被应用画像接管(true=只读, false=可调基础模式)
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
                val active = withContext(Dispatchers.IO) {
                    // 优先用无障碍服务进程内的前台(零 root); 未启用则 root 兜底探测
                    val fg = AppSwitchService.foreground.ifEmpty {
                        ForegroundDetector(this@NotifyService).currentForegroundPackage().orEmpty()
                    }
                    RootShell.resolveActiveMode(fg)
                }
                if (active.key != modeKey || active.isProfile != isProfile) {
                    modeKey = active.key; isProfile = active.isProfile; updateNotification()
                }
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

    private fun showSwitcher() {
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

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val accent = Color.parseColor("#FF007AFF")
        val cardBg = if (night) Color.parseColor("#F21D2026") else Color.parseColor("#F2FFFFFF")
        val strokeC = if (night) Color.parseColor("#33FFFFFF") else Color.parseColor("#66FFFFFF")
        val titleC = if (night) Color.parseColor("#FFEDEDED") else Color.parseColor("#FF0A1626")
        val unselBg = if (night) Color.parseColor("#FF2C2F37") else Color.parseColor("#FFEAF1FB")
        val unselTx = if (night) Color.parseColor("#FFDDE3EC") else Color.parseColor("#FF24303F")

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

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(18), px(20), px(20))
            background = GradientDrawable().apply {
                cornerRadius = px(28).toFloat()
                setColor(cardBg)
                setStroke(px(1), strokeC)
            }
            elevation = px(16).toFloat()
            isClickable = true
        }
        card.addView(TextView(this).apply {
            text = if (isProfile) "应用画像接管中" else "快速切换模式"
            setTextColor(titleC)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(px(4), 0, 0, px(14))
        })

        if (isProfile) {
            // 画像由 daemon 按当前应用自动调度,不允许手动切基础模式;只读展示画像名
            card.addView(TextView(this).apply {
                text = Mode.fromKey(modeKey).label
                gravity = Gravity.CENTER
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, px(20), 0, px(20))
                background = GradientDrawable().apply {
                    cornerRadius = px(18).toFloat(); setColor(accent)
                }
            })
            card.addView(TextView(this).apply {
                text = "当前应用由画像自动调度,基础模式快切已禁用"
                setTextColor(unselTx)
                textSize = 12f
                setPadding(px(4), px(12), px(4), 0)
            })
            root.addView(card, FrameLayout.LayoutParams(
                px(340), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ).apply { marginStart = px(16); marginEnd = px(16) })
            addOverlayAnimated(wm, root, card, px(40))
            return
        }

        val rowLp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cur = modeKey
        // 智能档开启(全局为 auto)时锁手动切档:按钮置灰,点按只提示不切换。
        val smartOn = cur == "auto"
        Mode.DOWNSTREAM.forEach { m ->
            val selected = m.key == cur
            val btn = TextView(this).apply {
                text = m.label
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (selected) Color.WHITE else unselTx)
                setPadding(0, px(20), 0, px(20))
                if (smartOn) alpha = 0.5f
                background = GradientDrawable().apply {
                    cornerRadius = px(18).toFloat()
                    setColor(if (selected) accent else unselBg)
                }
                setOnClickListener {
                    if (smartOn) {
                        android.widget.Toast.makeText(
                            this@NotifyService,
                            "智能调度开启中,请先在 App 设置 → 智能调度 关闭智能模式",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        removeOverlay()
                        return@setOnClickListener
                    }
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
            px(340), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ).apply { marginStart = px(16); marginEnd = px(16) })
        addOverlayAnimated(wm, root, card, px(40))
    }

    /** 把悬浮根视图加到 WindowManager 并播放卡片入场动画(两种卡片共用)。 */
    private fun addOverlayAnimated(wm: WindowManager, root: View, card: View, dy: Int) {
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
            card.alpha = 0f
            card.scaleX = 0.85f
            card.scaleY = 0.85f
            card.translationY = dy.toFloat()
            card.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(1.6f))
                .start()
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val card = (v as? FrameLayout)?.getChildAt(0)
        if (card != null) {
            card.animate()
                .alpha(0f).scaleX(0.9f).scaleY(0.9f).translationY(20f)
                .setDuration(180)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { runCatching { wm.removeView(v) } }
                .start()
        } else {
            runCatching { wm.removeView(v) }
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val mode = Mode.fromKey(modeKey)
        val pi = PendingIntent.getService(
            this, 1,
            Intent(this, NotifyService::class.java).setAction(ACTION_SHOW_SWITCHER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val batteryText = if (battery in 0..100) "电量 $battery%" else "电量 --"
        val title = if (isProfile) "应用画像: ${mode.label}" else "当前模式: ${mode.label}"
        val hint = if (isProfile) "$batteryText · 画像自动接管" else "$batteryText · 点击悬浮切换模式"
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(hint)
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
