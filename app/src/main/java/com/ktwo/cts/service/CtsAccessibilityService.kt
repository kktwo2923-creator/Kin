package com.ktwo.cts.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.ktwo.cts.data.Prefs
import kotlinx.coroutines.runBlocking

/**
 * 无障碍常驻服务。
 * 仅作为"保活锚点": 被启用后系统会让本进程常驻、被杀后自动重启,
 * 并在开机时由系统自动拉起。不读取/不处理任何屏幕内容。
 */
class CtsAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 常驻时若用户开了状态栏通知,确保它在运行(也覆盖开机自启场景)
        val svc = this
        runCatching {
            if (runBlocking { Prefs.notifyEnabled(svc) }) NotifyService.start(svc)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    companion object {
        /** 当前无障碍服务是否已启用 */
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val cn = ComponentName(ctx, CtsAccessibilityService::class.java).flattenToString()
            return flat.split(':').any { it.equals(cn, ignoreCase = true) }
        }
    }
}
