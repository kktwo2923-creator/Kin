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

    /** 受保护、严禁删除/清空的数据文件(画像表) */
    private val PROTECTED_FILES = setOf(PERAPP.substringAfterLast('/'))  // perapp_powermode.txt

    /**
     * 列出 CTS 目录下的日志文件(扩展名 log 或 txt),按修改时间倒序(最新在前)。
     * 排除受保护的数据文件(如 perapp_powermode.txt),防止误清空画像。
     */
    fun listLogFiles(): List<String> {
        val cmd = "ls -1t " + CTS_DIR + "/*.log " + CTS_DIR + "/*.txt 2>/dev/null"
        return Shell.cmd(cmd).exec().out
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.substringAfterLast('/') !in PROTECTED_FILES }
    }

    /**
     * 读取某个日志文件的末尾若干行(默认 2000, 配合 daemon 512KB 滚动日志),避免大文件卡 UI。
     * 返回从旧到新的行列表。
     */
    fun readLogTail(path: String, lines: Int = 2000): List<String> =
        Shell.cmd("tail -n $lines '$path' 2>/dev/null").exec().out

    /** 清空(截断)某个日志文件。受保护文件(perapp_powermode.txt)拒绝操作。 */
    fun clearLog(path: String): Boolean {
        if (path.substringAfterLast('/') in PROTECTED_FILES) return false
        return Shell.cmd(": > '$path'").exec().isSuccess
    }

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

    // ============ SoC 识别 + 恢复默认配置 ============
    // 默认配置目录(Magisk 模块内置, 装机时由 customize.sh 按 SoC 部署)
    private const val MODULE_CONFIG_DIR = "/data/adb/modules/CoreTurboScheduler/config"

    /** SoC 识别结果: socModel=ro.soc.model, platform=ro.board.platform, chip=映射档(空=未识别), configName=对应默认配置文件名 */
    data class SocInfo(
        val socModel: String,
        val platform: String,
        val chip: String,
        val configName: String,
    )

    @Volatile private var cachedSoc: SocInfo? = null

    /**
     * 识别 SoC(与模块 customize.sh 的 identify_chip 同一套映射)。
     * 结果进程内缓存; App 启动时预热(CtsApp), 进面板直接可用。
     */
    fun detectSoc(): SocInfo {
        cachedSoc?.let { return it }
        fun prop(k: String) =
            Shell.cmd("getprop $k").exec().out.firstOrNull()?.trim()?.lowercase().orEmpty()
        val model = prop("ro.soc.model")
        val plat = prop("ro.board.platform")
        val chip = when {
            model.startsWith("sm8750") -> "8E"
            model.startsWith("sm8850") -> "8E5"
            model.startsWith("sm8650") -> "8G3"
            model.startsWith("sm8550") -> "8G2"
            model.startsWith("sm8450") -> "8G1"
            model.startsWith("sm8845") -> "8G5"
            model == "mt6989" || plat == "mt6989" -> "D9300"
            model.startsWith("mt6991") || plat == "mt6991" -> "D9400"
            else -> ""
        }
        val cfg = if (chip.isEmpty()) "config.json" else "config_$chip.json"
        return SocInfo(model, plat, chip, cfg).also { cachedSoc = it }
    }

    /**
     * 恢复默认配置: 按识别到的 SoC 从模块目录拷贝对应 config_XXX.json 覆盖
     * /sdcard/Android/CTS/config.json(daemon inotify 热重载)。
     * 源文件缺失时兜底用模块 config.json。alsoPerapp=true 时一并恢复 perapp_powermode.txt。
     * 返回恢复后 config.json 是否非空。
     */
    fun restoreDefaultConfig(alsoPerapp: Boolean = false): Boolean {
        val soc = detectSoc()
        // 不用 exit(libsu 持久 shell 里 exit 会终止会话); 用 if 包裹
        val script = buildString {
            append("D='$MODULE_CONFIG_DIR'; SRC=\"\$D/${soc.configName}\"; ")
            append("[ -s \"\$SRC\" ] || SRC=\"\$D/config.json\"; ")
            append("if [ -s \"\$SRC\" ]; then ")
            append("command -v chattr >/dev/null 2>&1 && chattr -i '$CONFIG' 2>/dev/null; ")
            append("cp -f \"\$SRC\" '$CONFIG' && chmod 666 '$CONFIG'; ")
            if (alsoPerapp) {
                append("if [ -s \"\$D/perapp_powermode.txt\" ]; then ")
                append("chattr -i '$PERAPP' 2>/dev/null; ")
                append("cp -f \"\$D/perapp_powermode.txt\" '$PERAPP' && chmod 666 '$PERAPP'; fi; ")
            }
            append("fi")
        }
        runCatching { Shell.cmd(script).exec() }
        return runCatching {
            Shell.cmd("[ -s '$CONFIG' ] && echo ok").exec().out.firstOrNull() == "ok"
        }.getOrDefault(false)
    }

    // ============ config.json 读写(CPU 控制面板;daemon 靠 inotify 热重载) ============
    private const val CONFIG = "$CTS_DIR/config.json"

    /** 状态文件:daemon 周期写出的实时功耗/频率(App 只读) */
    private const val STATUS = "$CTS_DIR/status.json"

    /** 读取 config.json 原始文本(免 root 优先,失败再 root) */
    fun readConfigRaw(): String {
        runCatching {
            val f = java.io.File(CONFIG)
            if (f.canRead()) {
                val s = f.readText()
                if (s.isNotBlank()) return s
            }
        }
        return Shell.cmd("cat $CONFIG 2>/dev/null").exec().out.joinToString("\n")
    }

    /**
     * 写回 config.json。base64 落地,避免 JSON 里的引号/换行/特殊字符破坏 shell。
     * 与 perapp 同款范式。写成功后 daemon 的 configTriggerTask(inotify)会热重载。
     * 额外 touch 目录:/sdcard 是 FUSE,inotify 偶尔漏文件事件,touch 目录补触发。
     */
    fun writeConfigRaw(json: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return Shell.cmd(
            "mkdir -p $CTS_DIR; echo '$b64' | base64 -d > $CONFIG; touch $CTS_DIR"
        ).exec().isSuccess
    }

    /** 读取 daemon 写出的 status.json 原始文本(免 root 优先) */
    fun readStatusRaw(): String {
        runCatching {
            val f = java.io.File(STATUS)
            if (f.canRead()) {
                val s = f.readText()
                if (s.isNotBlank()) return s
            }
        }
        return Shell.cmd("cat $STATUS 2>/dev/null").exec().out.joinToString("\n")
    }

    /** daemon 是否存活:读 daemon.alive(秒级时间戳),15s 内更新视为存活 */
    fun daemonAlive(): Boolean {
        val raw = runCatching {
            val f = java.io.File("$CTS_DIR/daemon.alive")
            if (f.canRead()) f.readText().trim()
            else Shell.cmd("cat $CTS_DIR/daemon.alive 2>/dev/null").exec().out.firstOrNull()?.trim()
        }.getOrNull().orEmpty()
        val ts = raw.toLongOrNull() ?: return false
        val nowSec = System.currentTimeMillis() / 1000
        return (nowSec - ts) in 0..15
    }
}
