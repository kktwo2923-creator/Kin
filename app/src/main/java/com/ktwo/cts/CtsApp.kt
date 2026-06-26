package com.ktwo.cts

import android.app.Application
import com.topjohnwu.superuser.Shell

class CtsApp : Application() {
    companion object {
        init {
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
        installCrashLogger()
        // 异步预热 shell,避免首次切换卡顿;同时识别 SoC 并缓存进程内供 CPU 面板直接用
        Shell.getShell {
            Thread {
                runCatching { com.ktwo.cts.root.RootShell.detectSoc() }
                // 自动 root 授权无障碍前台推送: 未启用则用 root 写 secure settings 开启, 省去手动去系统设置。
                runCatching {
                    if (com.ktwo.cts.root.RootShell.isRooted()
                        && !com.ktwo.cts.service.AppSwitchService.isEnabled(this@CtsApp)
                    ) {
                        com.ktwo.cts.root.RootShell.setAccessibility(true)
                        kotlinx.coroutines.runBlocking {
                            com.ktwo.cts.data.Prefs.setA11yWanted(this@CtsApp, true)
                        }
                    }
                }
            }.start()
        }
    }

    /** 崩溃堆栈经 root+base64 写到 app_crash.log,可在日志页查看;写完交还系统默认处理器。 */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                val text = "==== APP CRASH $ts thread=${t.name} ====\n$sw\n"
                // 崩溃线程同步新建 su 会话会卡授权窗/超时乃至 ANR;只复用已存活的 shell。
                val shell = Shell.getCachedShell()
                if (shell != null && shell.isAlive) {
                    val b64 = android.util.Base64.encodeToString(
                        text.toByteArray(), android.util.Base64.NO_WRAP,
                    )
                    Shell.cmd(
                        "mkdir -p /sdcard/Android/Oxygen; " +
                            "echo '$b64' | base64 -d >> /sdcard/Android/Oxygen/app_crash.log",
                    ).exec()
                } else {
                    java.io.File(filesDir, "app_crash.log").appendText(text)
                }
            }
            prev?.uncaughtException(t, e)
        }
    }
}
