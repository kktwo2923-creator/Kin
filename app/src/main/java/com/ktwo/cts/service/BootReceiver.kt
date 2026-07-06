package com.ktwo.cts.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                restoreUserConfig(ctx)   // 数据恢复与自启开关无关, 始终执行
                // 重新断言无障碍授权: 部分 ROM 重启会掉 sideload 应用的无障碍, root 写回 secure settings
                if (Prefs.a11yWanted(ctx) && RootShell.isRooted()) RootShell.setAccessibility(true)
                // 刷新率手动锁定重应用(ColorOS 重启会复位刷新率档位)。OEM 显示服务在 BOOT_COMPLETED
                // 后才起, 一次写常被它覆盖 → 立即写一次, 晚 9s 再写一次。
                val rr = Prefs.refreshRate(ctx)
                if (rr > 0 && RootShell.isRooted()) {
                    RootShell.setRefreshRate(ctx, rr)
                    delay(9000)
                    RootShell.setRefreshRate(ctx, rr)
                }
                if (Prefs.autostart(ctx)) {
                    if (Prefs.enabled(ctx) && !RootShell.daemonAlive()) ScheduleService.start(ctx)
                    if (Prefs.notifyEnabled(ctx)) NotifyService.start(ctx)
                }
                // 悬浮监视器: 开机若有已启用的窗且有悬浮窗权限, 拉起服务恢复
                if (Prefs.enabledOverlayTypes(ctx).isNotEmpty() &&
                    android.provider.Settings.canDrawOverlays(ctx)) {
                    com.ktwo.cts.overlay.FloatWindowService.sync(ctx)
                }
            } finally { pending.finish() }
        }
    }

    /**
     * 模块开机脚本会用默认 config.json 覆盖 sdcard 上的用户配置。
     * BOOT_COMPLETED 晚于模块拷贝,用 App 私有备份写回,触发 daemon inotify 热重载。
     */
    private suspend fun restoreUserConfig(ctx: Context) {
        runCatching {
            val backup = java.io.File(ctx.filesDir, "config_backup.json")
            if (!backup.exists()) return
            val txt = backup.readText()
            if (txt.isBlank() || !RootShell.isRooted()) return
            // 延迟 5s 再写一次,覆盖"模块拷贝略晚于 BOOT_COMPLETED"的竞态
            RootShell.writeConfigRaw(txt)
            delay(5000)
            RootShell.writeConfigRaw(txt)
        }
    }
}
