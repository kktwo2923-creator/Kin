// App 开关覆盖层(settings.json)的核心工具:
//   出厂值住 config.json(模块升级/恢复出厂会重写), App 的所有开关/智能设置住 settings.json,
//   守护先读 config 再用 settings 覆盖同名键。App 侧统一走本类:
//   - merged():         config ⊕ settings 的合并视图(只读展示用, 与守护实际生效值一致)
//   - updateOverlay():  写入器唯一出口 —— 整个"读合并→改→抽覆盖层→落盘"闭环持锁执行,
//                       杜绝跨写入方 read-modify-write 丢更新; settings 撕裂读(非空但解析失败)
//                       直接中止, 防止覆盖层被"config-only 视图"整体抹掉。
//   - restoreFromBackup(): BootReceiver 开机恢复(同一把锁, 不与写入器交叉)
//   - migrateIfNeeded(): 老版本升级迁移(config 里的 App 设置搬进 settings 并清理), 幂等可重入
package com.ktwo.cts.data

import android.content.Context
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

object KinSettings {

    // settings.json 收纳的 Function 子节点(整段归 App): 键在这些段里的都属覆盖层。
    private val OVERLAY_SECTIONS = listOf(
        "AutoMode", "ThreadAlloc", "SysTune", "BootTrim", "BgTrim", "TouchBoost", "AppProfile",
    )

    // 应用分类策略(app_policy 家族)是随机型出厂调校(如 8E5 的关核掩码), 默认不进覆盖层 ——
    // 否则任意一次无关保存都会把出厂值"冻结"进 settings, 模块升级换新调校后被旧值遮蔽。
    // 只有智能策略页(该家族的编辑器)保存时才带上(includeAppPolicy=true)。
    private val APP_POLICY_KEYS = setOf(
        "app_policy", "app_policy_enable", "app_policy_core_enable",
    )

    // config.json 出厂自带、迁移清理时必须保留在 config 的 AutoMode 键:
    // app_policy 家族 + SUSTAINED 温控阈值(均为随机型出厂调校, 作为覆盖层缺省时的兜底)。
    private val FACTORY_KEEP_AUTOMODE = APP_POLICY_KEYS +
        setOf("fsm_sustained_enter", "fsm_sustained_exit")

    // 迁移清理时从 config 整段移除的覆盖层段(AutoMode 按键清理; AppProfile 留 enable 出厂值)。
    private val REMOVE_SECTIONS = listOf("ThreadAlloc", "SysTune", "BootTrim", "BgTrim", "TouchBoost")

    // 所有写路径(各 VM 保存/接管开关/线程分配同步/开机恢复/迁移)共用一把锁,
    // 把"读→合并→改→写"整体串行化 —— 锁外读到的旧视图不会再被用作写基线。
    private val writeMutex = Mutex()

    /** config ⊕ settings 合并视图(settings 逐键深覆盖 Function 段)。任一文件坏了都尽力返回可用视图。只读展示用。 */
    fun merged(): String {
        val cfgRaw = runCatching { RootShell.readConfigRaw() }.getOrDefault("")
        val setRaw = runCatching { RootShell.readSettingsRaw() }.getOrDefault("")
        val cfg = runCatching { JSONObject(cfgRaw) }.getOrElse { JSONObject() }
        val set = runCatching { JSONObject(setRaw) }.getOrNull() ?: return cfg.toStableString()
        val sf = set.optJSONObject("Function") ?: return cfg.toStableString()
        val cf = cfg.optJSONObject("Function") ?: JSONObject().also { cfg.put("Function", it) }
        deepMerge(cf, sf)
        return cfg.toStableString()
    }

    /** 把 src 的键深合并进 dst(对象递归, 标量/数组直接覆盖)。 */
    private fun deepMerge(dst: JSONObject, src: JSONObject) {
        for (k in src.keys()) {
            val sv = src.opt(k)
            val dv = dst.opt(k)
            if (sv is JSONObject && dv is JSONObject) deepMerge(dv, sv)
            else dst.put(k, sv)
        }
    }

    /**
     * 从完整文档(合并视图被写入器修改后的结果)抽出覆盖层子树 → settings.json 文档。
     * dropAutoModeKeys: 从 AutoMode 段剔除的键(出厂调校不随普通保存进覆盖层)。
     */
    private fun extract(fullRaw: String, dropAutoModeKeys: Set<String>): String {
        val full = runCatching { JSONObject(fullRaw) }.getOrElse { JSONObject() }
        val f = full.optJSONObject("Function") ?: JSONObject()
        val out = JSONObject()
        val of = JSONObject()
        for (sec in OVERLAY_SECTIONS) {
            f.optJSONObject(sec)?.let { src ->
                val copy = JSONObject(src.toString())
                if (sec == "AutoMode") dropAutoModeKeys.forEach { copy.remove(it) }
                of.put(sec, copy)
            }
        }
        out.put("Function", of)
        return out.toStableString()
    }

    /**
     * 写入器唯一出口: 持锁执行"读 config+settings → 严格合并 → transform 改文档 →
     * 抽覆盖层写 settings.json → 刷私有备份 settings_backup.json"。
     * 严格读: config 缺失/解析失败, 或 settings 非空但解析失败(撕裂读) → 返回 false, 不落盘
     * (撕裂读若照 config-only 视图继续, 覆盖层会被整体抹掉)。settings 为空 = 尚无覆盖层, 合法。
     * app_policy 家族(出厂调校)的取舍:
     *  - includeAppPolicy=true(智能策略页 = 该家族编辑器): 编辑结果照常进覆盖层;
     *  - 默认 false: 只保留覆盖层里已有的键(用户以前编辑过的不丢), 不把 config 出厂值新"冻结"进来。
     * transform 在 IO 线程、锁内执行, 收到的 raw 保证非空可解析。
     */
    suspend fun updateOverlay(
        ctx: Context,
        includeAppPolicy: Boolean = false,
        transform: (String) -> String,
    ): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            runCatching {
                val cfgRaw = runCatching { RootShell.readConfigRaw() }.getOrDefault("")
                if (cfgRaw.isBlank()) return@runCatching false
                val cfg = runCatching { JSONObject(cfgRaw) }.getOrNull() ?: return@runCatching false
                val setRaw = runCatching { RootShell.readSettingsRaw() }.getOrDefault("")
                var curSet: JSONObject? = null
                if (setRaw.isNotBlank()) {
                    curSet = runCatching { JSONObject(setRaw) }.getOrNull() ?: return@runCatching false
                }
                curSet?.optJSONObject("Function")?.let { sf ->
                    val cf = cfg.optJSONObject("Function")
                        ?: JSONObject().also { cfg.put("Function", it) }
                    deepMerge(cf, sf)
                }
                val base = cfg.toStableString()
                // 覆盖层已有的 app_policy 键不在剔除之列: 合并视图里它们本就来自覆盖层,
                // 原样带回等于"保持不变", 只有 config 独有(纯出厂)的键才被挡在覆盖层外。
                val curAmKeys = curSet?.optJSONObject("Function")?.optJSONObject("AutoMode")
                    ?.keys()?.asSequence()?.toSet() ?: emptySet()
                val drop = if (includeAppPolicy) emptySet() else APP_POLICY_KEYS - curAmKeys
                val overlay = extract(transform(base), drop)
                val ok = RootShell.writeSettingsRaw(overlay)
                if (ok) {
                    runCatching {
                        java.io.File(ctx.filesDir, "settings_backup.json").writeText(overlay)
                    }
                }
                ok
            }.getOrDefault(false)
        }
    }

    /**
     * BootReceiver 开机恢复: 用 App 私有备份写回 config.json / settings.json。
     * 与写入器同锁, 避免"恢复旧备份"横插进正在进行的保存把新写盖掉。
     */
    suspend fun restoreFromBackup(ctx: Context) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            runCatching {
                java.io.File(ctx.filesDir, "config_backup.json").takeIf { it.exists() }
                    ?.readText()?.takeIf { it.isNotBlank() }
                    ?.let { RootShell.writeConfigRaw(it) }
            }
            runCatching {
                java.io.File(ctx.filesDir, "settings_backup.json").takeIf { it.exists() }
                    ?.readText()?.takeIf { it.isNotBlank() }
                    ?.let { RootShell.writeSettingsRaw(it) }
            }
        }
    }

    /**
     * 迁移(老版本 → 覆盖层架构), 幂等可重入:
     *  1) settings.json 还不存在时, 把 config 的覆盖层段抽出写成 settings.json
     *     (出厂调校键全部不带 —— 用户此前没有"编辑"过它们, 不该被迁移冻结);
     *  2) config.json 仍残留纯 App 键时清理掉(出厂键 FACTORY_KEEP 保留)。
     * 两步各自判断是否需要做: 上次哪步失败(如 su 抖动), 下次启动只补那一步。
     */
    suspend fun migrateIfNeeded(ctx: Context) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            runCatching {
                val cfgRaw = RootShell.readConfigRaw()
                if (cfgRaw.isBlank()) return@runCatching                    // 模块未刷, 无从迁移
                val cfg = runCatching { JSONObject(cfgRaw) }.getOrNull() ?: return@runCatching
                val f = cfg.optJSONObject("Function") ?: return@runCatching

                // ① 首次迁移: 抽覆盖层(剔除全部出厂调校键)写 settings.json
                if (RootShell.readSettingsRaw().isBlank()) {
                    val overlay = extract(cfgRaw, FACTORY_KEEP_AUTOMODE)
                    if (!RootShell.writeSettingsRaw(overlay)) return@runCatching
                    runCatching {
                        java.io.File(ctx.filesDir, "settings_backup.json").writeText(overlay)
                    }
                }

                // ② 清理 config: AutoMode 只留出厂键, 覆盖层其余段整段移除。
                //    上次写 config 失败时 settings 已存在但 config 仍脏 —— 据"是否残留"重入。
                val am = f.optJSONObject("AutoMode")
                val dirty = REMOVE_SECTIONS.any { f.has(it) } ||
                    am?.keys()?.asSequence()?.any { it !in FACTORY_KEEP_AUTOMODE } == true
                if (dirty) {
                    am?.let { a ->
                        a.keys().asSequence().toList()
                            .filter { it !in FACTORY_KEEP_AUTOMODE }
                            .forEach { a.remove(it) }
                    }
                    REMOVE_SECTIONS.forEach { f.remove(it) }
                    val cleaned = cfg.toStableString()
                    if (RootShell.writeConfigRaw(cleaned)) {
                        runCatching {
                            java.io.File(ctx.filesDir, "config_backup.json").writeText(cleaned)
                        }
                    }
                }
            }
        }
    }
}
