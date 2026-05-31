package com.ktwo.cts.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ktwo.cts.data.Prefs
import kotlinx.coroutines.runBlocking

/** 开机自启: 若用户开启了状态栏通知,开机后拉起通知服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            runCatching {
                if (runBlocking { Prefs.notifyEnabled(ctx) }) NotifyService.start(ctx)
            }
        }
    }
}
