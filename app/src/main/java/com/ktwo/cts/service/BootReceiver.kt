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
                // daemon 存活时不启动 App 端兜底服务(避免多余的"启动中"通知)
                if (Prefs.enabled(ctx) && !RootShell.daemonAlive()) ScheduleService.start(ctx)
                // 若开启了状态栏通知,开机拉起
                if (Prefs.notifyEnabled(ctx)) NotifyService.start(ctx)
            } finally { pending.finish() }
        }
    }
}
