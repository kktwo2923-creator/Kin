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
                // 开机把当前付费状态同步给守护(App 是付费权威, 守护据此知道是否已付费)
                if (RootShell.isRooted()) com.ktwo.cts.license.License.pushEntitlementToDaemon()
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
     * 模块开机/更新脚本可能用默认 config.json 覆盖 sdcard 上的用户配置。
     * BOOT_COMPLETED 晚于模块拷贝, 用 App 私有备份写回, 触发 daemon inotify 热重载。
     * 覆盖层架构: config_backup(频率表等) + settings_backup(所有 App 开关/智能设置)两份都恢复。
     * 走 KinSettings.restoreFromBackup: 与 App 写入器同一把锁, 不会把旧备份横插进正在进行的保存。
     */
    private suspend fun restoreUserConfig(ctx: Context) {
        if (!RootShell.isRooted()) return
        runCatching {
            // 延迟 5s 再写一次, 覆盖"模块拷贝略晚于 BOOT_COMPLETED"的竞态
            com.ktwo.cts.data.KinSettings.restoreFromBackup(ctx)
            delay(5000)
            com.ktwo.cts.data.KinSettings.restoreFromBackup(ctx)
        }
    }
}
