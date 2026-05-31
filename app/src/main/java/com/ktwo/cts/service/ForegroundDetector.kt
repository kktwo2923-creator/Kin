package com.ktwo.cts.service

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.ktwo.cts.root.RootShell

/**
 * 前台 App 探测,双轨:
 * - root + dumpsys window (优先,最快最准)
 * - UsageStatsManager (兜底,要"使用情况"权限)
 */
class ForegroundDetector(private val ctx: Context) {

    private val hasRoot by lazy { RootShell.isRooted() }

    fun currentForegroundPackage(): String? {
        if (hasRoot) {
            val r = tryDumpsys()
            if (!r.isNullOrEmpty()) return r
        }
        return tryUsageStats()
    }

    /** 优先用 dumpsys window — 最快最准 */
    private fun tryDumpsys(): String? = try {
        val out = RootShell.exec(
            "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -1"
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
