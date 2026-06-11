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
        installCrashLogger()
        // 预热 shell(异步,避免首次切换卡顿)
        Shell.getShell {
            // 进 App 先识别 SoC(结果缓存进程内, CPU 面板/恢复默认直接用)
            Thread { runCatching { com.ktwo.cts.root.RootShell.detectSoc() } }.start()
        }
    }

    /**
     * 全局崩溃捕获: 把堆栈写到 /sdcard/Android/CTS/app_crash.log(经 root, base64 传输避免转义),
     * 这样崩溃后能在 App「日志」页直接看到栈, 便于定位。写完仍交还系统默认处理器(正常崩溃退出)。
     */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                val text = "==== APP CRASH $ts thread=${t.name} ====\n$sw\n"
                // [Fix] 只复用已存活的 shell;崩溃线程上同步新建 su 会话可能卡授权窗/超时
                //   数秒甚至死锁,系统会把崩溃升级成 ANR。没有现成 shell 就退化写私有目录。
                val shell = Shell.getCachedShell()
                if (shell != null && shell.isAlive) {
                    val b64 = android.util.Base64.encodeToString(
                        text.toByteArray(), android.util.Base64.NO_WRAP,
                    )
                    Shell.cmd(
                        "mkdir -p /sdcard/Android/CTS; " +
                            "echo '$b64' | base64 -d >> /sdcard/Android/CTS/app_crash.log",
                    ).exec()
                } else {
                    java.io.File(filesDir, "app_crash.log").appendText(text)
                }
            }
            prev?.uncaughtException(t, e)
        }
    }
}
