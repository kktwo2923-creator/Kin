// 线程规则守护(AppOpt 式执行层): 前台应用命中已配置规则时, 每 3 秒把其线程按
// 「线程名通配 → 核区间」taskset 绑核(周期重申, 覆盖新出生线程/系统改回)。
// 规则来自 ThreadAllocRuleStore; 全部规则被删空时自动退出。需要 Root, 无 root 空转不打扰。
package com.ktwo.cts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ktwo.cts.R
import com.ktwo.cts.data.TaRule
import com.ktwo.cts.data.ThreadAllocRuleStore
import com.ktwo.cts.data.ThreadAllocRules
import com.ktwo.cts.data.ThreadAllocator
import com.ktwo.cts.data.fpsrecord.ThreadSampler
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class ThreadRuleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var rules: Map<String, List<TaRule>> = emptyMap()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotif())
        // 订阅规则表: 变化即生效; 清空则自动退出
        scope.launch {
            ThreadAllocRuleStore.flow(this@ThreadRuleService).collect { m ->
                rules = m
                if (m.isEmpty()) stopSelf()
            }
        }
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ── 主循环: 3s 一拍(AppOpt/守护同款节奏), 前台命中规则才动手 ──
    private suspend fun loop() {
        var fgDetector: ForegroundDetector? = null
        var tick = 0L
        while (scope.isActive) {
            runCatching {
                var fg = AppSwitchService.foreground
                if ((fg.isEmpty() || fg == packageName) && tick % 3 == 0L) {
                    val d = fgDetector ?: ForegroundDetector(this).also { fgDetector = it }
                    fg = runCatching { d.currentForegroundPackage() }.getOrNull().orEmpty()
                }
                if (fg.isNotEmpty() && fg != packageName) applyFor(fg)
            }
            tick++
            delay(3000)
        }
    }

    /**
     * 对前台应用应用规则:
     *  - 规则键既可是包名(命中主进程与全部子进程)也可是完整进程名(pkg:sub);
     *  - 每条线程按序取第一条命中的通配规则; 线程名规则都未命中时落到包默认(thread="")。
     *  - 所有 taskset 合并成一次 root 调用, 控制开销。
     */
    private fun applyFor(fgPkg: String) {
        if (rules.isEmpty() || !RootShell.isRooted()) return
        val rs = rules[fgPkg] ?: rules[fgPkg.substringBefore(':')] ?: return
        if (rs.isEmpty()) return
        val pid = runCatching { ThreadSampler.resolvePid(fgPkg) }.getOrDefault(-1)
        if (pid <= 0) return

        val taskDir = File("/proc/$pid/task")
        val tids = taskDir.list()?.mapNotNull { it.toIntOrNull() } ?: return
        val threadRules = rs.filter { it.thread.isNotEmpty() }
        val defaultRule = rs.firstOrNull { it.thread.isEmpty() }

        val sb = StringBuilder()
        var n = 0
        for (tid in tids) {
            val comm = runCatching { File("/proc/$pid/task/$tid/comm").readText().trim() }
                .getOrDefault("")
            if (comm.isEmpty()) continue
            val rule = threadRules.firstOrNull { ThreadAllocRules.globMatch(it.thread, comm) }
                ?: defaultRule ?: continue
            val cpus = ThreadAllocator.parseCpuList(rule.cpus)
            if (cpus.isEmpty()) continue
            var mask = 0
            cpus.forEach { c -> if (c in 0..30) mask = mask or (1 shl c) }
            if (mask == 0) continue
            sb.append("taskset -p ").append(Integer.toHexString(mask)).append(' ')
                .append(tid).append(" >/dev/null 2>&1\n")
            n++
        }
        if (n > 0) runCatching { RootShell.execSilent(sb.toString()) }
    }

    private fun buildNotif(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr?.getNotificationChannel(CH_ID) == null) {
            mgr?.createNotificationChannel(
                NotificationChannel(CH_ID, "线程规则守护", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("线程规则守护运行中")
            .setContentText("前台应用命中规则时自动绑核")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CH_ID = "cts_thread_rules"
        private const val NOTIF_ID = 0x6CB2

        fun start(ctx: Context) {
            runCatching {
                ctx.startForegroundService(Intent(ctx, ThreadRuleService::class.java))
            }
        }
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ThreadRuleService::class.java)) }
        }
    }
}
