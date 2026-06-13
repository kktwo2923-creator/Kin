package com.ktwo.cts.root

import com.topjohnwu.superuser.Shell

object RootShell {
    fun isRooted(): Boolean = try { Shell.getShell().isRoot } catch (_: Exception) { false }

    // Shell.cmd().exec() 在 root 会话死亡时抛 NoShellException;统一用这三个原语兜底,对外永不抛出。
    private fun sh(cmd: String): Shell.Result? =
        runCatching { Shell.cmd(cmd).exec() }.getOrNull()

    private fun shOut(cmd: String): List<String> = sh(cmd)?.out ?: emptyList()

    private fun shOk(cmd: String): Boolean = sh(cmd)?.isSuccess == true

    /** 执行一行 root 命令,返回 stdout(失败/无 shell 返回空串, 不抛出) */
    fun exec(cmd: String): String = shOut(cmd).joinToString("\n")

    fun execSilent(cmd: String): Boolean = shOk(cmd)

    /** 优先走 /data/powercfg.sh(Scene 接管协议入口),失败则 fallback 到 switch.sh。 */
    fun applyMode(mode: String): Boolean {
        if (mode.isBlank()) return false
        if (shOk("[ -s /data/powercfg.sh ] && sh /data/powercfg.sh $mode")) return true
        // libsu 持久 shell 里 exit 会终止会话,用变量记结果代替 exit
        return shOk(
            "ok=1; for d in /data/adb/modules/CoreTurboScheduler /data/adb/ksu/modules/CoreTurboScheduler; do" +
            "  [ -s \"\$d/switch.sh\" ] && sh \"\$d/switch.sh\" $mode && ok=0 && break;" +
            " done; [ \"\$ok\" = 0 ]"
        )
    }

    fun currentMode(): String =
        shOut("cat /data/coreturbo_powermode 2>/dev/null").firstOrNull()?.trim().orEmpty()

    fun moduleInstalled(): Boolean =
        shOk("[ -d /data/adb/modules/CoreTurboScheduler ] || [ -d /data/adb/ksu/modules/CoreTurboScheduler ]")

    // perapp_powermode.txt 格式: 每行 "包名 模式"; # 开头注释; * 开头全局默认行。
    private const val PERAPP = "/sdcard/Android/CTS/perapp_powermode.txt"

    /** CTS 数据/日志目录 */
    const val CTS_DIR = "/sdcard/Android/CTS"

    /** 受保护文件(禁止清空):perapp_powermode.txt */
    private val PROTECTED_FILES = setOf(PERAPP.substringAfterLast('/'))

    /** 列出日志文件(*.log 与 *.txt),按修改时间倒序,排除受保护数据文件。 */
    fun listLogFiles(): List<String> =
        shOut("ls -1t " + CTS_DIR + "/*.log " + CTS_DIR + "/*.txt 2>/dev/null")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.substringAfterLast('/') !in PROTECTED_FILES }

    /** 读取日志末尾若干行(默认 2000,避免大文件卡 UI)。 */
    fun readLogTail(path: String, lines: Int = 2000): List<String> =
        shOut("tail -n $lines '$path' 2>/dev/null")

    /** 截断日志文件;受保护文件拒绝操作。 */
    fun clearLog(path: String): Boolean {
        if (path.substringAfterLast('/') in PROTECTED_FILES) return false
        return shOk(": > '$path'")
    }

    fun readPerApp(): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val raw = shOut("cat $PERAPP 2>/dev/null")
        for (line in raw) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("*")) continue
            val sp = t.split(Regex("\\s+"))
            if (sp.size >= 2) map[sp[0]] = sp[1]
        }
        return map
    }

    /** 读取全局默认模式(* 行),无则返回 "balance"。 */
    fun readGlobalMode(): String {
        val line = shOut("grep '^\\*' $PERAPP 2>/dev/null | head -1").firstOrNull()?.trim().orEmpty()
        val sp = line.split(Regex("\\s+"))
        return if (sp.size >= 2) sp[1] else "balance"
    }

    /** 写回整个画像表 + 全局默认;base64 落地避免特殊字符破坏 shell。 */
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
        return shOk("mkdir -p /sdcard/Android/CTS; echo '$b64' | base64 -d > $PERAPP")
    }

    fun setPerApp(pkg: String, mode: String, globalMode: String): Boolean {
        val cur = readPerApp()
        if (mode == "follow") cur.remove(pkg) else cur[pkg] = mode
        return writePerApp(cur, globalMode)
    }

    private const val MODULE_CONFIG_DIR = "/data/adb/modules/CoreTurboScheduler/config"

    /** SoC 识别结果;chip 为空表示未识别,configName 是对应的默认配置文件名。 */
    data class SocInfo(
        val socModel: String,
        val platform: String,
        val chip: String,
        val configName: String,
    )

    @Volatile private var cachedSoc: SocInfo? = null

    /** 识别 SoC,与模块 customize.sh 同一套映射;结果进程内缓存。 */
    fun detectSoc(): SocInfo {
        cachedSoc?.let { return it }
        fun prop(k: String) = shOut("getprop $k").firstOrNull()?.trim()?.lowercase().orEmpty()
        val model = prop("ro.soc.model")
        val plat = prop("ro.board.platform")
        val chip = when {
            model.startsWith("sm8750") -> "8E"
            model.startsWith("sm8850") -> "8E5"
            model.startsWith("sm8650") -> "8G3"
            model.startsWith("sm8550") -> "8G2"
            model.startsWith("sm8450") -> "8G1"
            model.startsWith("sm8845") -> "8G5"
            model.startsWith("sm7675") -> "7P3"
            model == "mt6989" || plat == "mt6989" -> "D9300"
            model.startsWith("mt6991") || plat == "mt6991" -> "D9400"
            else -> ""
        }
        val cfg = if (chip.isEmpty()) "config.json" else "config_$chip.json"
        return SocInfo(model, plat, chip, cfg).also { cachedSoc = it }
    }

    /**
     * 按 SoC 从模块目录拷贝 config_XXX.json 覆盖运行配置(daemon inotify 热重载)。
     * alsoPerapp=true 时一并恢复 perapp_powermode.txt。
     */
    fun restoreDefaultConfig(alsoPerapp: Boolean = false): Boolean {
        val soc = detectSoc()
        // libsu 持久 shell 里 exit 会终止会话,用 if 包裹代替 exit
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
        sh(script)
        return shOut("[ -s '$CONFIG' ] && echo ok").firstOrNull() == "ok"
    }

    private const val CONFIG = "$CTS_DIR/config.json"

    /** 读取 config.json(免 root 优先,失败再 root 读)。 */
    fun readConfigRaw(): String {
        runCatching {
            val f = java.io.File(CONFIG)
            if (f.canRead()) {
                val s = f.readText()
                if (s.isNotBlank()) return s
            }
        }
        return shOut("cat $CONFIG 2>/dev/null").joinToString("\n")
    }

    /**
     * 写回 config.json;base64 落地避免特殊字符破坏 shell。
     * touch 目录是因为 /sdcard FUSE 偶尔漏 inotify 文件事件,touch 父目录补触发。
     */
    fun writeConfigRaw(json: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return shOk("mkdir -p $CTS_DIR; echo '$b64' | base64 -d > $CONFIG; touch $CTS_DIR")
    }

    /** 守护是否在运行：直接查进程(daemon 已不再写 daemon.alive 心跳)。 */
    fun daemonAlive(): Boolean =
        shOut("pidof CoreTurboScheduler 2>/dev/null").firstOrNull()?.isNotBlank() == true

    private const val A11Y_COMPONENT = "com.ktwo.cts/com.ktwo.cts.service.AppSwitchService"

    /**
     * 用 root 写 secure settings 开/关本应用无障碍服务(持久, 跨重启不掉权限)。
     * 追加而非覆盖现有已启用服务; 关闭时仅移除自己。开机由 BootReceiver 重新断言。
     */
    fun setAccessibility(enable: Boolean): Boolean {
        val cur = shOut("settings get secure enabled_accessibility_services")
            .firstOrNull()?.trim().orEmpty()
        val items = cur.split(':')
            .filter { it.isNotEmpty() && it != "null" && it != A11Y_COMPONENT }
            .toMutableList()
        if (enable) items.add(A11Y_COMPONENT)
        val joined = items.joinToString(":")
        val cmd = buildString {
            if (joined.isEmpty()) append("settings delete secure enabled_accessibility_services; ")
            else append("settings put secure enabled_accessibility_services '$joined'; ")
            append("settings put secure accessibility_enabled ${if (items.isEmpty()) 0 else 1}")
        }
        return shOk(cmd)
    }

    /** 当前生效模式:isProfile=true 表示当前前台被应用画像接管(只读),false 为可调的基础模式。 */
    data class ActiveMode(val isProfile: Boolean, val key: String)

    private fun matchPkg(pattern: String, pkg: String): Boolean = when {
        pattern.isEmpty() -> false
        pattern.endsWith("*") -> pkg.startsWith(pattern.dropLast(1))
        else -> pattern == pkg
    }

    /**
     * 判定前台包名当前生效的模式,镜像 daemon 优先级:黑名单→跟随全局;perapp 覆盖优先于
     * config 画像;都没有则基础(全局)模式。仅用于通知/悬浮窗显示,近似即可。
     */
    fun resolveActiveMode(fg: String): ActiveMode {
        val global = readGlobalMode()
        if (fg.isBlank()) return ActiveMode(false, global)
        val raw = readConfigRaw()
        if (raw.isNotBlank()) runCatching {
            val o = org.json.JSONObject(raw)
            o.optJSONArray("package_blacklist")?.let { bl ->
                for (i in 0 until bl.length())
                    if (matchPkg(bl.optString(i), fg)) return ActiveMode(false, global)
            }
            readPerApp()[fg]?.let { if (it.isNotBlank() && it != "follow") return ActiveMode(true, it) }
            o.optJSONArray("models")?.let { models ->
                for (i in 0 until models.length()) {
                    val m = models.optJSONObject(i) ?: continue
                    val pkgs = m.optJSONArray("packages") ?: continue
                    for (j in 0 until pkgs.length())
                        if (matchPkg(pkgs.optString(j), fg))
                            return ActiveMode(true, m.optString("model_name"))
                }
            }
        }
        // config 读不到时仍尊重 perapp 覆盖
        readPerApp()[fg]?.let { if (it.isNotBlank() && it != "follow") return ActiveMode(true, it) }
        return ActiveMode(false, global)
    }

    /**
     * 无障碍服务推送当前前台包名给 daemon(写 topapp.txt,daemon inotify 即时消费)。
     * 异步提交不阻塞调用线程;持久 su 会话开销极小。包名仅含 [A-Za-z0-9._:],单引号安全。
     */
    fun pushTopApp(pkg: String) {
        if (pkg.isBlank() ||
            !pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == ':' }) return
        runCatching {
            Shell.cmd("mkdir -p $CTS_DIR; printf '%s' '$pkg' > $CTS_DIR/topapp.txt").submit()
        }
    }
}
