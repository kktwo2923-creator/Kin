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
                // 开机后恢复用户自定义配置(模块开机脚本会用默认 config.json 覆盖 sdcard)
                restoreUserConfig(ctx)
                // daemon 存活时不启动 App 端兜底服务(避免多余的"启动中"通知)
                if (Prefs.enabled(ctx) && !RootShell.daemonAlive()) ScheduleService.start(ctx)
                // 若开启了状态栏通知,开机拉起
                if (Prefs.notifyEnabled(ctx)) NotifyService.start(ctx)
            } finally { pending.finish() }
        }
    }

    /**
     * 模块开机脚本(service.sh / post-fs-data.sh)会把模块自带的默认 config.json
     * 拷到 /sdcard/Android/CTS/config.json,覆盖用户在 App 里改的配置,导致"重启恢复默认"。
     * BOOT_COMPLETED 晚于模块拷贝,这里用 App 私有目录里的备份把用户配置写回去,
     * 再触发 daemon 的 inotify 热重载。仅在用户改过配置(备份存在)时执行。
     */
    private suspend fun restoreUserConfig(ctx: Context) {
        runCatching {
            val backup = java.io.File(ctx.filesDir, "config_backup.json")
            if (!backup.exists()) return
            val txt = backup.readText()
            if (txt.isBlank() || !RootShell.isRooted()) return
            // 立即写一次;再延迟 5s 写一次,覆盖"模块拷贝略晚于 BOOT_COMPLETED"的竞态
            RootShell.writeConfigRaw(txt)
            delay(5000)
            RootShell.writeConfigRaw(txt)
        }
    }
}
