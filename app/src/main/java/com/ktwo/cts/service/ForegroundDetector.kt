package com.ktwo.cts.service

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.ktwo.cts.root.RootShell

/**
 * 前台 App 探测优先级:cpuset 直读(最省电) -> dumpsys window(约 100~300ms,兜底) ->
 * UsageStatsManager(无 root 时)。
 */
class ForegroundDetector(private val ctx: Context) {

    private val hasRoot by lazy { RootShell.isRooted() }

    fun currentForegroundPackage(): String? {
        if (hasRoot) {
            tryCpuset()?.let { return it }
            tryDumpsys()?.let { return it }
        }
        return tryUsageStats()
    }

    /** top-app cgroup pid -> /proc/pid/cmdline 取包名;与 CTS-Daemon getTopAppFast 同思路。 */
    private fun tryCpuset(): String? = try {
        val out = RootShell.exec(
            "for p in \$(cat /dev/cpuset/top-app/tasks 2>/dev/null); do " +
            "  c=\$(tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null | awk '{print \$1}'); " +
            "  case \"\$c\" in *.*) echo \"\${c%%:*}\";; esac; " +
            "done | tail -1"
        ).trim()
        out.ifEmpty { null }
    } catch (_: Exception) { null }

    private fun tryDumpsys(): String? = try {
        // 超时包裹 dumpsys(慢命令): 卡死时自杀, 不拖垮共享 su 会话(否则前台探测一挂, 录制/悬浮窗全卡)
        val out = RootShell.execTimed(
            2, "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -1"
        )
        // 形如: mCurrentFocus=Window{... u0 com.tencent.tmgp.sgame/com.xxx.Activity}
        Regex("[a-zA-Z][a-zA-Z0-9._]+/")
            .find(out)?.value?.trimEnd('/')
    } catch (_: Exception) { null }

    private fun tryUsageStats(): String? {
        if (!hasUsageStatsPermission()) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000, now)
        var topPkg: String? = null
        val e = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                topPkg = e.packageName
            }
        }
        return topPkg
    }

    fun hasUsageStatsPermission(): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), ctx.packageName)
        else
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
