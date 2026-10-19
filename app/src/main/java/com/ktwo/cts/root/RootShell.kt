package com.ktwo.cts.root

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.topjohnwu.superuser.Shell

object RootShell {
    fun isRooted(): Boolean = try { Shell.getShell().isRoot } catch (_: Exception) { false }

    /**
     * 重新请求 ROOT: libsu 会缓存被拒绝/超时的非 root 会话, 直接再调 isRooted() 只会命中缓存
     * 永远弹不出授权窗。先关掉缓存的非 root 会话再探测, su 弹窗才能再次出现。
     */
    fun retryRoot(): Boolean {
        runCatching { Shell.getCachedShell()?.takeIf { it.isAlive && !it.isRoot }?.close() }
        return isRooted()
    }

    // Shell.cmd().exec() 在 root 会话死亡时抛 NoShellException;统一用这三个原语兜底,对外永不抛出。
    private fun sh(cmd: String): Shell.Result? =
        runCatching { Shell.cmd(cmd).exec() }.getOrNull()

    private fun shOut(cmd: String): List<String> = sh(cmd)?.out ?: emptyList()

    private fun shOk(cmd: String): Boolean = sh(cmd)?.isSuccess == true

    /** 执行一行 root 命令,返回 stdout(失败/无 shell 返回空串, 不抛出) */
    fun exec(cmd: String): String = shOut(cmd).joinToString("\n")

    // toybox timeout 是否可用: 一次性探测并缓存。个别精简 ROM 无 timeout 时若强行包裹,
    // 命令会报 "timeout: not found" 返回空 → fps/帧时间恒 0(功能回归)。故缺失时退回不包裹。
    @Volatile private var timeoutAvail: Boolean? = null
    private fun hasTimeout(): Boolean {
        timeoutAvail?.let { return it }
        val ok = runCatching { shOut("command -v timeout 2>/dev/null").any { it.isNotBlank() } }
            .getOrDefault(false)
        timeoutAvail = ok
        return ok
    }

    /**
     * 带超时的 root 执行: 用 toybox `timeout -s KILL` 包住可能挂起/耗时过长的命令。
     * libsu 全局共享【单个】su 会话, 命令串行执行 —— 一旦某条命令卡死(如高负载/开着模糊合成时
     * `dumpsys SurfaceFlinger` 会阻塞), 后面所有 root 调用都排在它后面一起卡死: 悬浮窗停更、
     * 点"记录"没反应, 整个 App 像冻住。给这些命令加超时, 卡死时自杀释放会话, 绝不拖垮其它功能。
     * -s KILL: dumpsys 忽略 SIGTERM, 必须 SIGKILL 才能真正杀掉。
     * cmd 内不要再带外层管道(超时只作用于紧跟的那个进程; 复合脚本请自行 `sh -c '...'` 包裹)。
     * 无 timeout 的 ROM: 自动退回不包裹执行(等同接入前, 无回归)。
     */
    fun execTimed(sec: Int, cmd: String): String =
        if (hasTimeout()) exec("timeout -s KILL $sec $cmd") else exec(cmd)

    /** 带超时的静默执行(返回是否成功)。与 execTimed 同款, 供需 Boolean 结果的调用方用。 */
    fun shOkTimed(sec: Int, cmd: String): Boolean =
        if (hasTimeout()) shOk("timeout -s KILL $sec $cmd") else shOk(cmd)

    fun execSilent(cmd: String): Boolean = shOk(cmd)

    /** 优先走 /data/powercfg.sh(Scene 接管协议入口),失败则 fallback 到 switch.sh。 */
    fun applyMode(mode: String): Boolean {
        if (mode.isBlank()) return false
        // ⚠️ 安全(P0-1): mode 来源是 /sdcard 上的 perapp_powermode.txt(用户/其它应用可写),
        // 直接拼 `sh /data/powercfg.sh $mode` 会 shell 注入(如 "* balance; reboot")。
        // 强制白名单: 只接受 Mode 枚举定义的 key, 杜绝任意命令执行。
        val allowed = com.ktwo.cts.data.Mode.entries.any { it.key == mode }
        if (!allowed) return false
        if (shOk("[ -s /data/powercfg.sh ] && sh /data/powercfg.sh $mode")) return true
        // libsu 持久 shell 里 exit 会终止会话,用变量记结果代替 exit
        return shOk(
            "ok=1; for d in /data/adb/modules/Kin /data/adb/ksu/modules/Kin; do" +
            "  [ -s \"\$d/switch.sh\" ] && sh \"\$d/switch.sh\" $mode && ok=0 && break;" +
            " done; [ \"\$ok\" = 0 ]"
        )
    }

    fun currentMode(): String =
        shOut("cat /data/coreturbo_powermode 2>/dev/null").firstOrNull()?.trim().orEmpty()

    /**
     * 智能档(自动模式)是否开启。【以持久化的全局默认(perapp 的 * 行)为准】——
     * 它只在开关智能调度时写入, 稳定不抖。不优先读 /data/coreturbo_powermode:
     * 那是"当前生效档", auto 下会被解析出的基础档/外部电源 App/用户脚本改写,
     * 据此判断会让 smartOn 反复抖动, 进而误触发底栏 tab 切换与「记录/应用」页跳转。
     */
    fun isSmartOn(): Boolean = readGlobalMode() == "auto"

    fun moduleInstalled(): Boolean =
        shOk("[ -d /data/adb/modules/Kin ] || [ -d /data/adb/ksu/modules/Kin ]")

    // perapp_powermode.txt 格式: 每行 "包名 模式"; # 开头注释; * 开头全局默认行。
    // 运行数据文件统一收到 AI/ 子目录(见 AI_DIR),使根目录整洁。
    private const val PERAPP = "/sdcard/Android/Kin/AI/perapp_powermode.txt"
    // pkg → App 显示名 映射, 供守护日志把包名翻成 App 名字(MainActivity 打开时刷新)。
    private const val PKG_LABEL = "/sdcard/Android/Kin/AI/pkg_label.txt"

    /** CTS 数据/日志目录 */
    const val CTS_DIR = "/sdcard/Android/Kin"

    /** 运行数据子目录:auto_status / power_log / power_sessions / perapp_powermode 均收于此。 */
    const val AI_DIR = "/sdcard/Android/Kin/AI"

    /**
     * 一次性迁移: 把散落在 CTS_DIR 根目录的运行数据文件搬进 AI_DIR。
     * 幂等可重入: 新路径已存在则跳过, 仅当目标缺失且源文件非空时才 cp, 不删旧文件(防回滚丢数据)。
     * 覆盖: perapp_powermode.txt / pkg_label.txt / thermal_headroom.txt / auto_status/ / power_log/ / power_sessions/。
     * 应在首次获取 root 后尽早调用(早于任何读 perapp/pkg_label 的逻辑)。
     */
    fun migrateAiDirIfNeeded() {
        if (!isRooted()) return
        val files = listOf("perapp_powermode.txt", "pkg_label.txt", "thermal_headroom.txt")
        val dirs = listOf("auto_status", "power_log", "power_sessions")
        val sb = StringBuilder("mkdir -p '$AI_DIR'; ")
        for (f in files) {
            val old = "$CTS_DIR/$f"
            val new = "$AI_DIR/$f"
            sb.append("if [ ! -s '$new' ] && [ -s '$old' ]; then cp -f '$old' '$new' && chmod 666 '$new'; fi; ")
        }
        for (d in dirs) {
            val old = "$CTS_DIR/$d"
            val new = "$AI_DIR/$d"
            sb.append("if [ ! -d '$new' ] && [ -d '$old' ]; then cp -rf '$old/' '$new/' 2>/dev/null; fi; ")
        }
        sh(sb.toString())
    }

    /** 受保护文件(禁止清空):perapp_powermode.txt */
    private val PROTECTED_FILES = setOf(PERAPP.substringAfterLast('/'))

    /** 列出日志文件(*.log 与 *.txt),按修改时间倒序,排除受保护数据文件。 */
    fun listLogFiles(): List<String> =
        shOut("ls -1t " + CTS_DIR + "/*.log " + CTS_DIR + "/*.txt 2>/dev/null")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.substringAfterLast('/') !in PROTECTED_FILES }

    /**
     * 读取日志末尾若干行(默认 2000,避免大文件卡 UI)。
     * path 经 [shellQuote] 转义, 防异常文件名(含单引号)破坏 shell(原 P1-9)。
     */
    fun readLogTail(path: String, lines: Int = 2000): List<String> =
        shOut("tail -n $lines ${shellQuote(path)} 2>/dev/null")

    /** 截断日志文件;受保护文件拒绝操作。path 经 [shellQuote] 转义(原 P1-9)。 */
    fun clearLog(path: String): Boolean {
        if (path.substringAfterLast('/') in PROTECTED_FILES) return false
        return shOk(": > ${shellQuote(path)}")
    }

    /** 单引号转义: 把 path 里的 ' 替换为 '"'"', 安全地包进单引号喂给 shell。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

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

    /**
     * 探测「智能档是否开启」, 区分"读到了"与"没读到":
     *   true  = 全局默认是 auto;  false = 读到了其它有效模式;  null = 读不到(root 未就绪/文件不可读)。
     * 供底栏冷启动判定: null 时调用方应忽略本次, 而不是误判成"关"。
     * 优先直读文件(部分设备 App 有存储读权限), 失败再走 root；都拿不到 * 行即返回 null。
     */
    fun probeSmartOn(): Boolean? {
        val raw = runCatching {
            val f = java.io.File(PERAPP)
            if (f.canRead()) f.readText() else null
        }.getOrNull()
            ?: shOut("cat $PERAPP 2>/dev/null").joinToString("\n").ifBlank { null }
            ?: return null
        val star = raw.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("*") }
            ?: return null
        val mode = star.split(Regex("\\s+")).getOrNull(1)?.trim()?.ifBlank { null }
            ?: return null
        return mode == "auto"
    }

    /**
     * 写 ADPF 热余量(getThermalHeadroom 归一化 0.0-1.0, -1=无 ADPF/NaN)供守护融合进热管理。
     * 纯数字, 无需 base64; 守护按 mtime 判新鲜(>30s 陈旧作废)。低频调用(~15s)。
     */
    fun writeThermalHeadroom(v: Float): Boolean {
        val s = String.format(java.util.Locale.US, "%.3f", v)
        return shOk("mkdir -p $AI_DIR; echo '$s' > $AI_DIR/thermal_headroom.txt")
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
        return shOk("mkdir -p $AI_DIR; echo '$b64' | base64 -d > $PERAPP")
    }

    fun setPerApp(pkg: String, mode: String, globalMode: String): Boolean {
        val cur = readPerApp()
        if (mode == "follow") cur.remove(pkg) else cur[pkg] = mode
        return writePerApp(cur, globalMode)
    }

    /**
     * 写 pkg→App名字 映射(供守护日志显示 App 名字, 而非包名)。base64 落地避免中文/空格破坏 shell;
     * 每行 "包名<TAB>名字"(名字里的换行/制表折成空格)。空表或无 root 静默失败, 返回是否写成功。
     */
    fun writeAppLabels(labels: Map<String, String>): Boolean {
        if (labels.isEmpty()) return false
        val sb = StringBuilder()
        sb.append("# 包名 → App 名字 (供 Kin 守护日志显示; 由 App 生成, 勿手改)\n")
        for ((pkg, label) in labels) {
            if (pkg.isBlank() || label.isBlank()) continue
            val clean = label.replace(Regex("[\\r\\n\\t]"), " ").trim()
            if (clean.isEmpty()) continue
            sb.append(pkg).append('\t').append(clean).append('\n')
        }
        val b64 = android.util.Base64.encodeToString(
            sb.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return shOk("mkdir -p $AI_DIR; echo '$b64' | base64 -d > $PKG_LABEL")
    }

    /**
     * 只改全局默认模式(perapp 的 * 行),保留所有已配置的应用画像。
     * 用于"智能档"总开关: 切到 auto / 切回 balance 时持久化全局默认,跨重启保持。
     */
    fun setGlobalMode(globalMode: String): Boolean = writePerApp(readPerApp(), globalMode)

    private const val MODULE_CONFIG_DIR = "/data/adb/modules/Kin/config"

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
                append("mkdir -p '$AI_DIR'; chattr -i '$PERAPP' 2>/dev/null; ")
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

    // ── App 开关覆盖层 settings.json: 出厂值在 config.json, App 的所有开关/智能设置存这里,
    //    守护先读 config 再用本文件覆盖同名键 —— 模块升级/恢复出厂只动 config, 用户设置不丢。 ──
    private const val SETTINGS = "$CTS_DIR/settings.json"

    /** 读取 settings.json(免 root 优先, 失败再 root 读); 不存在返回空串。 */
    fun readSettingsRaw(): String {
        runCatching {
            val f = java.io.File(SETTINGS)
            if (f.canRead()) {
                val s = f.readText()
                if (s.isNotBlank()) return s
            }
        }
        return shOut("cat $SETTINGS 2>/dev/null").joinToString("\n")
    }

    /** 写回 settings.json(base64 落地 + touch 目录补 inotify, 同 writeConfigRaw)。 */
    fun writeSettingsRaw(json: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return shOk("mkdir -p $CTS_DIR; echo '$b64' | base64 -d > $SETTINGS; touch $CTS_DIR")
    }

    /**
     * 智能调度命令通道: 把一条命令行写入 auto_control.txt, daemon 约每 5s 轮询该文件
     * (无 inotify, 不需要 touch)。支持命令:
     *  night_seed=<sleep_min>,<wake_min>  纠正夜间学习(分钟制, 0..1439)
     *  night_reset                        清空夜间学习
     * 免 root 直写优先(daemon 运行时父目录必已存在), 失败再走 root shell base64 落地。
     */
    // ── 问卷回答落盘副本(存 /sdcard/Android/Kin/AI, 不在 App 私有目录):
    //    清除 App 数据/缓存不会丢, 重装/清数据后自动恢复。 ──
    private const val SURVEY_STORE = "$AI_DIR/survey.json"

    fun readSurveyStore(): String {
        runCatching {
            val f = java.io.File(SURVEY_STORE)
            if (f.canRead()) return f.readText()
        }
        return shOut("cat $SURVEY_STORE 2>/dev/null").joinToString("\n")
    }

    fun writeSurveyStore(json: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return shOk("mkdir -p $AI_DIR; echo '$b64' | base64 -d > $SURVEY_STORE")
    }

    fun sendAutoControl(line: String): Boolean {
        val path = "$CTS_DIR/auto_control.txt"
        val text = line + "\n"
        // 追加而非覆盖: daemon 按行解析全部命令, 追加不会冲掉恰好还没被消费的其它命令,
        // 也缩小与 daemon"读完即删"之间的竞态窗口。
        runCatching {
            java.io.File(path).appendText(text)
            return true
        }
        val b64 = android.util.Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return shOk("echo '$b64' | base64 -d >> $path")
    }

    /** 线程分配规则文件(AppOpt 兼容, 沿用 AppOpt 文件名 applist.prop); 守护读它做 pkg{线程}=核 绑核。 */
    private const val THREAD_RULES = "$CTS_DIR/applist.prop"
    /** 旧文件名(迁移用): 曾用 thread_alloc_rules.conf, 首次写新文件时顺带清理。 */
    private const val THREAD_RULES_OLD = "$CTS_DIR/thread_alloc_rules.conf"

    /**
     * 把线程分配规则文本推给守护(落到 $THREAD_RULES);守护按 mtime 变化热重载。
     * base64 落地避免规则里的花括号、星号、方括号等特殊字符破坏 shell。无 root/写失败静默返回 false。
     */
    fun writeThreadRules(text: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return shOk("mkdir -p $CTS_DIR; echo '$b64' | base64 -d > $THREAD_RULES; chmod 0644 $THREAD_RULES; rm -f $THREAD_RULES_OLD; touch $CTS_DIR")
    }

    /** 读回守护侧规则文件(免 root 优先, 失败再 root 读); 不存在返回空串。 */
    fun readThreadRules(): String {
        runCatching {
            val f = java.io.File(THREAD_RULES)
            if (f.canRead()) {
                val s = f.readText()
                if (s.isNotBlank()) return s
            }
        }
        return shOut("cat $THREAD_RULES 2>/dev/null").joinToString("\n")
    }

    /** 实时优先级规则文件(AppOpt priority.prop 兼容); 守护读它设 SCHED_FIFO/RR 调度类。 */
    private const val THREAD_PRIO_RULES = "$CTS_DIR/thread_priority_rules.conf"

    /** 把实时优先级规则文本推给守护(落到 $THREAD_PRIO_RULES); 守护按 mtime/size 变化热重载。 */
    fun writeThreadPriorityRules(text: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return shOk("mkdir -p $CTS_DIR; echo '$b64' | base64 -d > $THREAD_PRIO_RULES; chmod 0644 $THREAD_PRIO_RULES; touch $CTS_DIR")
    }

    /** 读回守护侧实时优先级规则文件; 不存在返回空串。 */
    fun readThreadPriorityRules(): String {
        runCatching {
            val f = java.io.File(THREAD_PRIO_RULES)
            if (f.canRead()) {
                val s = f.readText()
                if (s.isNotBlank()) return s
            }
        }
        return shOut("cat $THREAD_PRIO_RULES 2>/dev/null").joinToString("\n")
    }

    /** App→守护 授权信号文件。App 是付费权威, 守护读它知道是否已付费(不再自行验签 license.json)。 */
    private const val DAEMON_AUTH = "$AI_DIR/auth.json"

    /**
     * 把当前付费状态下发给守护(写 auth.json)。App 依据自身 Pro/卡密状态调用。
     * 守护【只信任该信号且无条件运行】—— 未付费也不会把用户锁死。
     * base64 落地避免特殊字符破坏 shell; 无 root/写失败静默返回 false。
     */
    fun writeDaemonAuth(paid: Boolean, plan: String = "", expiresAt: String = ""): Boolean {
        val json = org.json.JSONObject()
            .put("paid", paid)
            .put("plan", plan)
            .put("expires_at", expiresAt)
            .toString()
        val b64 = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return shOk("mkdir -p $AI_DIR; echo '$b64' | base64 -d > $DAEMON_AUTH; chmod 0600 $DAEMON_AUTH; touch $CTS_DIR")
    }

    /** 守护是否在运行：直接查进程(daemon 已不再写 daemon.alive 心跳)。 */
    fun daemonAlive(): Boolean =
        shOut("pidof Kinetic 2>/dev/null").firstOrNull()?.isNotBlank() == true

    /**
     * 取【当前分辨率】下刷新率≈hz 的模式物理分辨率(w,h)。锁刷新率时必须保持现分辨率 ——
     * 本机有 1272x2772 与 1080x2354 两套, 且开了自动分辨率; 用错分辨率 cmd display 会失败。
     * 当前分辨率没有该刷新率时, 回退到任意分辨率下面积最大的那个。
     */
    private fun displayModeRes(ctx: Context, hz: Int): Pair<Int, Int>? = runCatching {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val disp = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val cur = disp.mode
        val cw = cur.physicalWidth; val ch = cur.physicalHeight
        fun near(m: android.view.Display.Mode) = kotlin.math.abs(m.refreshRate - hz) < 1.5f
        val m = disp.supportedModes.firstOrNull { it.physicalWidth == cw && it.physicalHeight == ch && near(it) }
            ?: disp.supportedModes.filter { near(it) }.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
        m?.let { it.physicalWidth to it.physicalHeight }
    }.getOrNull()

    /** 面板最高刷新率(整数 Hz), 读不到回退 165。 */
    private fun panelMaxHz(ctx: Context): Int = runCatching {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.getDisplay(Display.DEFAULT_DISPLAY).supportedModes.maxOf { it.refreshRate }
            .toInt().coerceIn(60, 240)
    }.getOrDefault(165)

    /**
     * 刷新率手动档(root)。hz<=0=自动(面板 60..max 浮动); 否则锁定到 hz。
     * 经设备实测(OnePlus V Pro / OxygenOS)确定的可靠做法:
     *  ① cmd display set-user-preferred-display-mode 宽 高 hz —— 主力机制, 本机生效;
     *     分辨率取【当前】分辨率(本机 1272x2772 / 1080x2354 两套, 不能写错)。
     *  ② app_extreme_high_refresh_switch=1(global) —— 解锁 144/165, 否则 OEM「高」封顶 120。
     *  ③ OEM 档位键(secure: coloros/oneplus/oplus(_customize)_screen_refresh_rate)+
     *     AOSP min/peak/user(浮点)兜底锁定。
     */
    fun setRefreshRate(ctx: Context, hz: Int): Boolean {
        val res = if (hz > 0) displayModeRes(ctx, hz) else null
        return setRefreshRate(hz, res?.first ?: 0, res?.second ?: 0, panelMaxHz(ctx))
    }

    fun setRefreshRate(hz: Int, modeW: Int = 0, modeH: Int = 0, panelMax: Int = 165): Boolean {
        val cmds = ArrayList<String>()
        val oemKeys = listOf(
            "oplus_customize_screen_refresh_rate", "coloros_screen_refresh_rate",
            "oplus_screen_refresh_rate", "oneplus_screen_refresh_rate",
        )
        if (hz <= 0) {
            for (k in oemKeys) cmds += "settings put secure $k 0"
            cmds += "settings put secure oplus_customize_min_refresh_rate 0"
            cmds += "settings put secure oplus_customize_peak_refresh_rate 0"
            cmds += "settings put global app_extreme_high_refresh_switch 1"  // 允许自适应也能上 144/165
            cmds += "settings put system min_refresh_rate 60.0"
            cmds += "settings put system peak_refresh_rate $panelMax.0"
            cmds += "settings delete system user_refresh_rate"
            cmds += "cmd display clear-user-preferred-display-mode"
        } else {
            val f = "$hz.0"
            val oem = if (hz <= 60) 2 else 3       // 标准 / 高
            val mp  = if (hz <= 60) 2 else 1
            // 解锁超高刷(144/165): OnePlus 把 >120 锁在这个开关后面
            cmds += "settings put global app_extreme_high_refresh_switch ${if (hz > 120) 1 else 0}"
            for (k in oemKeys) cmds += "settings put secure $k $oem"
            cmds += "settings put secure oplus_customize_min_refresh_rate $mp"
            cmds += "settings put secure oplus_customize_peak_refresh_rate $mp"
            cmds += "settings put system min_refresh_rate $f"
            cmds += "settings put system peak_refresh_rate $f"
            cmds += "settings put system user_refresh_rate $f"
            // 主力: 精确模式锁(本机实测 user_preferred 被采纳)。分辨率用当前分辨率。
            if (modeW > 0 && modeH > 0)
                cmds += "cmd display set-user-preferred-display-mode $modeW $modeH $hz"
        }
        // P1-l: setRefreshRate 含 settings/cmd display 类命令, 高负载时可能卡死 su 会话; 加 8s 超时保护
        return shOkTimed(8, cmds.joinToString("; "))
    }

    /** 读功耗记录(daemon 每 30s 追加的 power_log.txt: ts,level,curMa,voltMv,tempDc,chg)。 */
    fun readPowerLog(): String {
        runCatching {
            val f = java.io.File("$AI_DIR/power_log.txt")
            if (f.canRead()) { val s = f.readText(); if (s.isNotBlank()) return s }
        }
        return shOut("cat $AI_DIR/power_log.txt 2>/dev/null").joinToString("\n")
    }

    /** 读充放电会话汇总(daemon power_sessions.txt: type,startTs,endTs,startLv,endLv,avgMw,avgVolt)。 */
    fun readPowerSessions(): String {
        runCatching {
            val f = java.io.File("$AI_DIR/power_sessions.txt")
            if (f.canRead()) { val s = f.readText(); if (s.isNotBlank()) return s }
        }
        return shOut("cat $AI_DIR/power_sessions.txt 2>/dev/null").joinToString("\n")
    }

    /** 清空充放电会话汇总(用户在记录页手动清空)。daemon 后续新会话会重新追加, 不影响记录功能。 */
    fun clearPowerSessions(): Boolean {
        runCatching {
            val f = java.io.File("$AI_DIR/power_sessions.txt")
            if (f.exists() && f.canWrite()) { f.writeText(""); return true }
        }
        return shOk(": > $AI_DIR/power_sessions.txt")
    }

    /** 智能调度学习数据文件(daemon 持久化的自学习状态)非空 = 已积累学习数据(老用户)。 */
    fun hasLearningData(): Boolean {
        val path = "$CTS_DIR/.kin/auto_state.txt"
        val text = runCatching {
            val f = java.io.File(path)
            if (f.canRead()) f.readText() else ""
        }.getOrDefault("").ifBlank { shOut("cat $path 2>/dev/null").joinToString("\n") }
        // 行格式 b<i>=alpha,obs,last_seen,...(daemon saveState, obs 为 ×100 定点数: 100≈1 个完整会话)。
        // daemon 一跑起来文件就存在, 光看存在性会把新用户误判成老用户; 这里要求任一桶
        // 累计约 8 个完整会话(800)才算"有数据"。
        return text.lineSequence().any { line ->
            val eq = line.indexOf('=')
            if (eq <= 0 || !line.startsWith("b")) return@any false
            val obs = line.substring(eq + 1).split(',').getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            obs >= 800
        }
    }

    /** 读智能调度实时状态文件(daemon 写的 auto_status.txt,key=value 行)。供监控面板用。 */
    fun readAutoStatus(): String {
        runCatching {
            val f = java.io.File("$AI_DIR/auto_status.txt")
            if (f.canRead()) return f.readText()
        }
        // 带超时: 存储 FUSE 卡住时自杀释放共享 su 会话, 不让调用方(启动检测/策略页)一起卡死。
        return execTimed(3, "cat $AI_DIR/auto_status.txt 2>/dev/null")
    }

    /**
     * 监控面板专用: 一次 root 往返同时取「状态文件内容 + 守护是否存活」。
     * 面板 1.5s 轮询, 原先每轮发 2 条 root 命令(readAutoStatus + daemonAlive)。libsu 全局只有
     * 一个 su 会话且命令串行, 与 FPS 录制(dumpsys SurfaceFlinger, 单条可占 3~4s)、写入类命令
     * 共用时会排队 → 面板偶发读空、且两条命令可能一条成功一条失败, 出现「有数据却显示守护未运行」。
     * 合并成 1 条命令 + toybox timeout 兜底(卡死时自杀释放会话, 下一轮即可恢复)。
     * @return first=状态文件内容(空串=没读到), second=守护是否在跑; null 表示整条 shell 失败, 存活未知。
     */
    fun readAutoStatusSnapshot(): Pair<String, Boolean?> {
        val sep = "@@KIN_ALIVE@@"
        val out = execTimed(
            3,
            "sh -c 'cat $AI_DIR/auto_status.txt 2>/dev/null; echo $sep; pidof Kinetic 2>/dev/null'"
        )
        val i = out.indexOf(sep)
        if (i >= 0) {
            val raw = out.substring(0, i).trim()
            val pid = out.substring(i + sep.length).trim()
            if (raw.isNotEmpty()) return raw to pid.isNotEmpty()
            // 文件还没生成(守护刚启动/智能调度刚开): 存活判定仍有效, 数据交由调用方保留旧值。
            return "" to pid.isNotEmpty()
        }
        // 整条 shell 失败(无 root / 会话死): 退回直读, 有存储权限的机型仍能显示数据。
        val direct = runCatching {
            val f = java.io.File("$AI_DIR/auto_status.txt")
            if (f.canRead()) f.readText().trim() else ""
        }.getOrDefault("")
        return direct to null
    }

    // 组件名 = 实际安装包名(applicationId)/ 服务类全名。包名随版本可变(如 com.ktwo.kin),
    // 故从 BuildConfig.APPLICATION_ID 动态拼,避免写死旧包名导致开关无效(写错组件→服务永远起不来)。
    private val A11Y_COMPONENT =
        "${com.ktwo.cts.BuildConfig.APPLICATION_ID}/com.ktwo.cts.service.AppSwitchService"

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
        // auto 模式「智能/FSM 独占」: 不套 App 画像/perapp,一律显示智能档(与 daemon 行为一致)。
        if (global == "auto") return ActiveMode(false, "auto")
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
