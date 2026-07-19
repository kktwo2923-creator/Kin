package com.ktwo.cts.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.ktwo.cts.root.RootShell

/**
 * 无障碍服务:监听窗口焦点变化(TYPE_WINDOW_STATE_CHANGED),把前台包名推给 daemon
 * (写 /sdcard/Android/Kin/topapp.txt,daemon inotify 即时消费)。
 *
 * 定位是 daemon dumpsys 轮询的"加速器":启用后 daemon 直接用推送值(零 dumpsys popen、零延迟);
 * 未启用/被杀时推送变陈旧,daemon 自动回退自身 dumpsys 检测,不影响"不开 App 也能用"。
 * 系统UI/输入法等非应用窗口会频繁触发本事件, 必须忽略, 否则 topapp.txt 会被 systemui 等刷屏覆盖。
 * 其余过滤(黑名单/小窗/游戏保活)交给 daemon。参考 Scene(helloklf/vtools)AppSwitchHandler 的事件来源。
 */
class AppSwitchService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var lastPkg: String = ""

    // 息屏判定: 息屏时前台不会变, 且 daemon 息屏本就不读前台 —— 此时重推 topapp.txt 只会每 10s 白白
    // 唤醒 daemon(用户反馈"息屏还一直检测前台")。故息屏不写, 亮屏后心跳/事件自然恢复推送。
    private val power by lazy { getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }
    private fun isInteractive(): Boolean = runCatching { power.isInteractive }.getOrDefault(true)

    // 非应用窗口忽略集: 这些包频繁触发窗口事件却不是"前台应用", 推了会盖掉真实前台
    private val ignore = mutableSetOf(
        "com.android.systemui", "com.oplus.systemui", "com.coloros.systemui",
        "android", "com.android.intentresolver",
    )

    private fun loadImePackages() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.inputMethodList.forEach { ignore.add(it.packageName) }
        }
    }

    private fun shouldIgnore(pkg: String): Boolean =
        pkg.isEmpty() || !pkg.contains('.') || pkg in ignore

    // 心跳:前台长时间不变时无障碍不再回调,定时重推让 daemon 端推送值保持新鲜(否则 daemon 回退 dumpsys)。
    // 重推前【重新枚举窗口】而不是盲推 lastPkg: 若切换瞬间的事件被漏掉(节流/系统丢事件),
    // lastPkg 是旧应用, 盲推会让 daemon 永远以为"推送新鲜且没换应用" —— 心跳自检后最多 10s 自愈。
    private val heartbeat = object : Runnable {
        override fun run() {
            // 息屏跳过重推(只保留轻量 Handler 计时, 不做 root 写 / 不唤醒 daemon); 亮屏后下一拍自然恢复。
            if (isInteractive()) {
                val cur = mainAppFromWindows()
                if (cur != null && cur != lastPkg) push(cur)               // 漏检自愈: 真实前台已换
                else if (lastPkg.isNotEmpty()) RootShell.pushTopApp(lastPkg)  // 未换: 仅刷新鲜度
            }
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        loadImePackages()
        push(mainAppFromWindows())   // 启用后立刻推一次, 不必等下次切换
        // 服务可能被系统解绑后重连: 先摘旧心跳链/挂起的尾沿补评再挂新的, 保证任意时刻只有一条
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(trailingEval)
        trailingQueued = false
        main.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private var lastEvalMs = 0L
    private var trailingQueued = false

    // 节流的尾沿补评: 切换应用时事件在几百毫秒内连发, 首个事件评估时切换动画未完成、
    // 窗口列表里面积最大的还是【旧应用】; 若把窗口期内后续事件直接丢弃, "反映新前台的最后一发"
    // 就没了 → topapp.txt 卡在旧应用(心跳又不断重推旧值保新鲜, daemon 永远不回退 dumpsys)。
    // 故节流期内只标记, 安静后统一补评一次, 保证最终状态一定被推送。
    private val trailingEval = Runnable {
        trailingQueued = false
        lastEvalMs = android.os.SystemClock.uptimeMillis()
        mainAppFromWindows()?.let { push(it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // typeWindowsChanged 触发频繁: 节流避免主线程频繁枚举窗口造成卡顿; 被节流的事件不丢, 尾沿补评
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastEvalMs < 300) {
            if (!trailingQueued) {
                trailingQueued = true
                main.postDelayed(trailingEval, 300)   // 尾沿补评: 450→300ms, 切换更跟手
            }
            return
        }
        lastEvalMs = now
        // 多窗口/小窗: 取面积最大的应用窗口(全屏主应用), 而非被触摸/聚焦的小窗;
        // 拿不到窗口列表时退回事件自身包名
        push(mainAppFromWindows() ?: e.packageName?.toString())
    }

    private fun push(pkg: String?) {
        val p = pkg.orEmpty()
        if (p == lastPkg || shouldIgnore(p)) return
        lastPkg = p
        foreground = p   // 进程内前台缓存照常更新(供 NotifyService 零 root 读)
        // 仅亮屏时写 topapp.txt: 息屏 daemon 不读前台, 写了只会无谓唤醒 daemon。
        if (isInteractive()) RootShell.pushTopApp(p)
    }

    /** 枚举所有窗口, 取面积最大的应用窗口的包名 = 全屏主应用(小窗面积小, 不会胜出)。 */
    private fun mainAppFromWindows(): String? {
        val wins = runCatching { windows }.getOrNull() ?: return null
        val r = android.graphics.Rect()
        var best: String? = null
        var bestArea = 0L
        for (w in wins) {
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            w.getBoundsInScreen(r)
            val area = r.width().toLong() * r.height().toLong()
            if (area <= bestArea) continue
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
            if (pkg.isEmpty() || shouldIgnore(pkg)) continue
            bestArea = area
            best = pkg
        }
        return best
    }

    // 服务被系统解绑时允许重新绑定(无障碍服务异常后自动恢复)
    override fun onUnbind(intent: android.content.Intent?): Boolean = true

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(trailingEval)
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
