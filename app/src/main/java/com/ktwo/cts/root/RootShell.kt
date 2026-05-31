package com.ktwo.cts.root

import com.topjohnwu.superuser.Shell

object RootShell {
    fun isRooted(): Boolean = try { Shell.getShell().isRoot } catch (_: Exception) { false }

    /** 执行一行 root 命令,返回 stdout */
    fun exec(cmd: String): String =
        Shell.cmd(cmd).exec().out.joinToString("\n")

    fun execSilent(cmd: String): Boolean =
        Shell.cmd(cmd).exec().isSuccess

    /**
     * 触发 /data/powercfg.sh <mode>(Scene 接管协议,模块的 init_vtools.sh 生成)
     * 失败则 fallback 直接调 switch.sh
     */
    fun applyMode(mode: String): Boolean {
        if (mode.isBlank()) return false
        // 优先 /data/powercfg.sh,这是 Scene 接管协议入口
        val r1 = Shell.cmd("[ -s /data/powercfg.sh ] && sh /data/powercfg.sh $mode").exec()
        if (r1.isSuccess) return true
        // fallback: switch.sh 直接调
        val r2 = Shell.cmd(
            "for d in /data/adb/modules/CoreTurboScheduler /data/adb/ksu/modules/CoreTurboScheduler; do" +
            "  [ -s \"\$d/switch.sh\" ] && sh \"\$d/switch.sh\" $mode && exit 0;" +
            " done; exit 1"
        ).exec()
        return r2.isSuccess
    }

    /** 读取当前模式状态文件 */
    fun currentMode(): String = try {
        Shell.cmd("cat /data/coreturbo_powermode 2>/dev/null").exec()
            .out.firstOrNull()?.trim().orEmpty()
    } catch (_: Exception) { "" }

    /** 模块是否已安装 */
    fun moduleInstalled(): Boolean =
        Shell.cmd(
            "[ -d /data/adb/modules/CoreTurboScheduler ] || [ -d /data/adb/ksu/modules/CoreTurboScheduler ]"
        ).exec().isSuccess

    // ============ perapp_powermode.txt 读写(app = txt 的 GUI 编辑器) ============
    // 格式: 每行 "包名 模式"; 以 # 开头为注释; 以 * 开头为全局默认行(* balance)。
    private const val PERAPP = "/sdcard/Android/CTS/perapp_powermode.txt"

    /** CTS 数据/日志目录 */
    const val CTS_DIR = "/sdcard/Android/CTS"

    /** 列出 CTS 目录下的日志文件(扩展名 log 或 txt),按修改时间倒序(最新在前)。 */
    fun listLogFiles(): List<String> {
        val cmd = "ls -1t " + CTS_DIR + "/*.log " + CTS_DIR + "/*.txt 2>/dev/null"
        return Shell.cmd(cmd).exec().out
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 读取某个日志文件的末尾若干行(默认 500),避免大文件卡 UI。
     * 返回从旧到新的行列表。
     */
    fun readLogTail(path: String, lines: Int = 500): List<String> =
        Shell.cmd("tail -n $lines '$path' 2>/dev/null").exec().out

    /** 清空(截断)某个日志文件 */
    fun clearLog(path: String): Boolean =
        Shell.cmd(": > '$path'").exec().isSuccess

    /** 读取全部画像条目 pkg -> mode(不含注释/默认行) */
    fun readPerApp(): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val raw = Shell.cmd("cat $PERAPP 2>/dev/null").exec().out
        for (line in raw) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("*")) continue
            val sp = t.split(Regex("\\s+"))
            if (sp.size >= 2) map[sp[0]] = sp[1]
        }
        return map
    }

    /** 读取全局默认模式(* 行),无则 balance */
    fun readGlobalMode(): String {
        val raw = Shell.cmd("grep '^\\*' $PERAPP 2>/dev/null | head -1").exec().out
        val line = raw.firstOrNull()?.trim().orEmpty()
        val sp = line.split(Regex("\\s+"))
        return if (sp.size >= 2) sp[1] else "balance"
    }

    /**
     * 写回整个画像表 + 全局默认。用 base64 落地避免特殊字符破坏 shell。
     */
    fun writePerApp(profiles: Map<String, String>, globalMode: String): Boolean {
        val sb = StringBuilder()
        sb.append("# 应用画像: 包名 模式 (由 OnePlus V Pro App 管理)\n")
        sb.append("# 模式: powersave/balance/performance/fast/game_heavy/game_lite/video/social/reading/music\n")
        sb.append("* ").append(globalMode).append("\n\n")
        for ((pkg, mode) in profiles) {
            if (pkg.isBlank() || mode.isBlank() || mode == "follow") continue
            sb.append(pkg).append(" ").append(mode).append("\n")
        }
        val b64 = android.util.Base64.encodeToString(
            sb.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return Shell.cmd(
            "mkdir -p /sdcard/Android/CTS; echo '$b64' | base64 -d > $PERAPP"
        ).exec().isSuccess
    }

    /** 设置单个 App 画像(follow=移除) */
    fun setPerApp(pkg: String, mode: String, globalMode: String): Boolean {
        val cur = readPerApp()
        if (mode == "follow") cur.remove(pkg) else cur[pkg] = mode
        return writePerApp(cur, globalMode)
    }
}
