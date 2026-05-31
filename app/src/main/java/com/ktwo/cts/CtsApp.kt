package com.ktwo.cts

import android.app.Application
import com.topjohnwu.superuser.Shell

class CtsApp : Application() {
    companion object {
        init {
            // 全局 Shell 配置:libsu
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
        }
    }
    override fun onCreate() {
        super.onCreate()
        // 预热 shell(异步,避免首次切换卡顿)
        Shell.getShell { /* warmed */ }
    }
}
