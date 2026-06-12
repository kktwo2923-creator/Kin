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
                restoreUserConfig(ctx)
                if (Prefs.enabled(ctx) && !RootShell.daemonAlive()) ScheduleService.start(ctx)
                if (Prefs.notifyEnabled(ctx)) NotifyService.start(ctx)
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
