// 调速器参数(M6): 枚举每个 cpufreq policy 当前 governor 的可调参数(tunable)并读写。需 Root。
package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell

/**
 * governor tunable 读写器。
 * 目录约定(功能事实):
 *  - 多数内核: /sys/devices/system/cpu/cpufreq/policy 目录下再套一层以 governor 命名的子目录,
 *    如 policy0/schedutil 里放 rate_limit_us 等文件;
 *  - 部分老内核把 tunable 放在全局 /sys/devices/system/cpu/cpufreq/<governor> 目录。
 * 两处都查, 目录不存在就跳过。枚举与读取统一走 root shell(部分节点 0600 只有 root 可读)。
 */
object GovernorParams {

    private const val CPUFREQ = "/sys/devices/system/cpu/cpufreq"

    /** 一个可调参数: 文件名 + 当前值(读不到为空串)。 */
    data class Tunable(val name: String, val value: String)

    /** 一个 policy 的 governor 参数集; dir 是 tunable 所在目录(policy 内或全局), 无则空串。 */
    data class PolicyParams(
        val policy: Int,
        val governor: String,
        val dir: String,
        val tunables: List<Tunable>,
    )

    // root ls -1p 枚举目录: 结尾带斜杠的是子目录, 过滤掉只留普通文件。
    private fun listFiles(dir: String): List<String> = runCatching {
        RootShell.exec("ls -1p '$dir' 2>/dev/null")
            .lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith("/") }
    }.getOrDefault(emptyList())

    private fun readValue(dir: String, file: String): String = runCatching {
        RootShell.exec("cat '$dir/$file' 2>/dev/null").trim()
    }.getOrDefault("")

    /** 读出所有 policy 的 governor 参数。找不到 tunable 目录的 policy 也保留(tunables 为空)。 */
    fun readAll(): List<PolicyParams> = runCatching {
        SysfsReader.discoverPolicies().map { p ->
            val gov = runCatching { SysfsReader.governor(p) }.getOrDefault("")
            if (gov.isEmpty()) return@map PolicyParams(p, "", "", emptyList())
            // 先 policy 内目录, 再全局目录; 都没有则该 governor 无可调参数(如 performance)
            var useDir = ""
            var files = emptyList<String>()
            for (d in listOf("$CPUFREQ/policy$p/$gov", "$CPUFREQ/$gov")) {
                val fs = listFiles(d)
                if (fs.isNotEmpty()) {
                    useDir = d; files = fs; break
                }
            }
            PolicyParams(p, gov, useDir, files.map { Tunable(it, readValue(useDir, it)) })
        }
    }.getOrDefault(emptyList())

    /**
     * 写一个 tunable, 返回写入后读回的新值(供 UI 校验); 失败返回 null。
     * 值允许数字/字母/空格/冒号/点/横线/下划线(兼容 target_loads 那类 "80 1500000:90" 格式)。
     */
    fun write(dir: String, file: String, value: String): String? {
        val v = value.trim()
        if (v.isEmpty() || v.length > 256) return null
        if (!v.all { it.isLetterOrDigit() || it == ' ' || it == ':' || it == '.' || it == '-' || it == '_' }) return null
        // 文件名/目录来自枚举结果, 再兜底校验一次防路径穿越
        if (file.isEmpty() || file.contains('/') || file.contains('\'') || dir.contains('\'')) return null
        if (!dir.startsWith(CPUFREQ)) return null
        if (!runCatching { RootShell.isRooted() }.getOrDefault(false)) return null
        val ok = runCatching { RootShell.execSilent("echo '$v' > '$dir/$file'") }.getOrDefault(false)
        if (!ok) return null
        return readValue(dir, file)
    }
}
