package com.ktwo.cts.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "cts_prefs")

// App 画像与全局模式已迁移到 perapp_powermode.txt;DataStore 只存本地设置。
object Prefs {
    private val ENABLED_KEY      = booleanPreferencesKey("service_enabled")
    private val POLL_MS_KEY      = intPreferencesKey("poll_ms")
    private val THEME_KEY        = stringPreferencesKey("theme_mode")
    private val WALLPAPER_KEY    = stringPreferencesKey("wallpaper_uri")
    private val NOTIFY_KEY       = booleanPreferencesKey("notify_enabled")

    // 智能调度(auto)是否开启。【底栏 tab 集的唯一可信来源】——
    // 由 App 开关时写入 + 启动时从 root 探测一次同步。不在 UI 热路径里反复读 root shell:
    // shell 偶发失败会让判定在 true/false 间抖动, 导致 tab 集乱切、页面被弹回。
    // 返回 null = 尚未写入(启动探测前), 此时调用方不应据此跳转。
    private val SMART_KEY = booleanPreferencesKey("smart_enabled")
    fun smartEnabledFlow(ctx: Context): Flow<Boolean?> =
        ctx.dataStore.data.map { it[SMART_KEY] }
    suspend fun setSmartEnabled(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[SMART_KEY] = b }
    }

    // 付费解锁(Pro/智能版)是否已解锁。缓存本地供 UI 秒读, 真正的来源是 License.state()==VALID:
    // App 启动 / 激活成功时由 Pro.sync() 从 License 同步写入。智能模式与「系统优化」相关功能受它门控。
    private val PRO_KEY = booleanPreferencesKey("pro_unlocked")
    fun proUnlockedFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[PRO_KEY] ?: false }
    suspend fun proUnlocked(ctx: Context): Boolean =
        ctx.dataStore.data.first()[PRO_KEY] ?: false
    suspend fun setProUnlocked(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[PRO_KEY] = b }
    }

    fun notifyEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[NOTIFY_KEY] ?: false }
    suspend fun notifyEnabled(ctx: Context): Boolean =
        ctx.dataStore.data.first()[NOTIFY_KEY] ?: false
    suspend fun setNotifyEnabled(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[NOTIFY_KEY] = b }
    }

    // 日志页默认只显示 log.txt / mode.txt / topapp.txt;开启「更多日志」才显示其余文件。
    private val MORE_LOGS_KEY = booleanPreferencesKey("more_logs")
    fun moreLogsFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[MORE_LOGS_KEY] ?: false }
    suspend fun setMoreLogs(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[MORE_LOGS_KEY] = b }
    }

    // SukiSU-Ultra 同款外观开关: 悬浮底栏 / 液态玻璃(悬浮底栏的玻璃效果)
    private val ENABLE_FLOATING_BAR_KEY      = booleanPreferencesKey("enable_floating_bottom_bar")
    private val ENABLE_FLOATING_BAR_BLUR_KEY = booleanPreferencesKey("enable_floating_bottom_bar_blur")

    fun enableFloatingBottomBarFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[ENABLE_FLOATING_BAR_KEY] ?: true }
    suspend fun setEnableFloatingBottomBar(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[ENABLE_FLOATING_BAR_KEY] = b }
    }

    fun enableFloatingBottomBarBlurFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[ENABLE_FLOATING_BAR_BLUR_KEY] ?: true }
    suspend fun setEnableFloatingBottomBarBlur(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[ENABLE_FLOATING_BAR_BLUR_KEY] = b }
    }

    // 界面风格: glass(液态玻璃, 默认现状) / metric(Metric 浅色仪表盘)
    private val UI_STYLE_KEY = stringPreferencesKey("ui_style")
    fun uiStyleFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[UI_STYLE_KEY] ?: "glass" }
    suspend fun setUiStyle(ctx: Context, k: String) {
        ctx.dataStore.edit { it[UI_STYLE_KEY] = k }
    }

    fun themeModeFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[THEME_KEY] ?: "system" }
    suspend fun setThemeMode(ctx: Context, mode: String) {
        ctx.dataStore.edit { it[THEME_KEY] = mode }
    }

    fun wallpaperUriFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[WALLPAPER_KEY] ?: "" }
    suspend fun setWallpaperUri(ctx: Context, uri: String) {
        ctx.dataStore.edit { it[WALLPAPER_KEY] = uri }
    }

    fun enabledFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[ENABLED_KEY] ?: true }
    suspend fun enabled(ctx: Context): Boolean =
        ctx.dataStore.data.first()[ENABLED_KEY] ?: true
    suspend fun setEnabled(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[ENABLED_KEY] = b }
    }

    suspend fun pollMs(ctx: Context): Int =
        ctx.dataStore.data.first()[POLL_MS_KEY] ?: 2000
    suspend fun setPollMs(ctx: Context, ms: Int) {
        ctx.dataStore.edit { it[POLL_MS_KEY] = ms }
    }

    // 开机自启动:默认开。控制 BootReceiver 是否在开机后拉起常驻服务。
    private val AUTOSTART_KEY = booleanPreferencesKey("autostart")
    fun autostartFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[AUTOSTART_KEY] ?: true }
    suspend fun autostart(ctx: Context): Boolean =
        ctx.dataStore.data.first()[AUTOSTART_KEY] ?: true
    suspend fun setAutostart(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[AUTOSTART_KEY] = b }
    }

    // ── 悬浮性能监控窗(仿 Metric)──
    // 开关 / 显示项(csv, 见 MonitorItems) / 透明度(0-100) / 字号(sp) / 落点(px)
    private val MON_ENABLED_KEY  = booleanPreferencesKey("monitor_overlay_enabled")
    private val MON_ITEMS_KEY    = stringPreferencesKey("monitor_items")
    private val MON_OPACITY_KEY  = intPreferencesKey("monitor_opacity")
    private val MON_TEXTSIZE_KEY = intPreferencesKey("monitor_text_size")
    private val MON_POSX_KEY     = intPreferencesKey("monitor_pos_x")
    private val MON_POSY_KEY     = intPreferencesKey("monitor_pos_y")
    // 默认显示: 帧率 / CPU频率 / GPU频率 / 温度 / 电量
    const val MON_ITEMS_DEFAULT = "fps,cpu,gpu,temp,batt"

    fun monEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[MON_ENABLED_KEY] ?: false }
    suspend fun monEnabled(ctx: Context): Boolean =
        ctx.dataStore.data.first()[MON_ENABLED_KEY] ?: false
    suspend fun setMonEnabled(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[MON_ENABLED_KEY] = b }
    }
    fun monItemsFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[MON_ITEMS_KEY] ?: MON_ITEMS_DEFAULT }
    suspend fun monItems(ctx: Context): String =
        ctx.dataStore.data.first()[MON_ITEMS_KEY] ?: MON_ITEMS_DEFAULT
    suspend fun setMonItems(ctx: Context, csv: String) {
        ctx.dataStore.edit { it[MON_ITEMS_KEY] = csv }
    }
    fun monOpacityFlow(ctx: Context): Flow<Int> =
        ctx.dataStore.data.map { it[MON_OPACITY_KEY] ?: 85 }
    suspend fun monOpacity(ctx: Context): Int =
        ctx.dataStore.data.first()[MON_OPACITY_KEY] ?: 85
    suspend fun setMonOpacity(ctx: Context, v: Int) {
        ctx.dataStore.edit { it[MON_OPACITY_KEY] = v }
    }
    fun monTextSizeFlow(ctx: Context): Flow<Int> =
        ctx.dataStore.data.map { it[MON_TEXTSIZE_KEY] ?: 12 }
    suspend fun monTextSize(ctx: Context): Int =
        ctx.dataStore.data.first()[MON_TEXTSIZE_KEY] ?: 12
    suspend fun setMonTextSize(ctx: Context, v: Int) {
        ctx.dataStore.edit { it[MON_TEXTSIZE_KEY] = v }
    }
    suspend fun monPos(ctx: Context): Pair<Int, Int> {
        val p = ctx.dataStore.data.first()
        return (p[MON_POSX_KEY] ?: Int.MIN_VALUE) to (p[MON_POSY_KEY] ?: Int.MIN_VALUE)
    }
    suspend fun setMonPos(ctx: Context, x: Int, y: Int) {
        ctx.dataStore.edit { it[MON_POSX_KEY] = x; it[MON_POSY_KEY] = y }
    }

    // ── 悬浮窗管理器: 六个独立监视器各自开关 + 各自落点(仿 Metric)──
    // key 动态生成 ovl_{id}_enabled / ovl_{id}_x / ovl_{id}_y。默认关、落点 MIN_VALUE(用错开默认)。
    private fun ovlEnabledKey(t: OverlayType) = booleanPreferencesKey("ovl_${t.id}_enabled")
    private fun ovlXKey(t: OverlayType) = intPreferencesKey("ovl_${t.id}_x")
    private fun ovlYKey(t: OverlayType) = intPreferencesKey("ovl_${t.id}_y")

    fun ovlEnabledFlow(ctx: Context, t: OverlayType): Flow<Boolean> =
        ctx.dataStore.data.map { it[ovlEnabledKey(t)] ?: false }
    suspend fun setOvlEnabled(ctx: Context, t: OverlayType, b: Boolean) {
        ctx.dataStore.edit { it[ovlEnabledKey(t)] = b }
    }
    /** 一次读出所有已开启的监视器(服务恢复 / 开机同步用)。 */
    suspend fun enabledOverlayTypes(ctx: Context): Set<OverlayType> {
        val p = ctx.dataStore.data.first()
        return OverlayType.entries.filter { p[ovlEnabledKey(it)] == true }.toSet()
    }
    suspend fun ovlPos(ctx: Context, t: OverlayType): Pair<Int, Int> {
        val p = ctx.dataStore.data.first()
        return (p[ovlXKey(t)] ?: Int.MIN_VALUE) to (p[ovlYKey(t)] ?: Int.MIN_VALUE)
    }
    suspend fun setOvlPos(ctx: Context, t: OverlayType, x: Int, y: Int) {
        ctx.dataStore.edit { it[ovlXKey(t)] = x; it[ovlYKey(t)] = y }
    }

    // 用户是否要求开启无障碍前台推送(root 授权): 开机由 BootReceiver 重新断言, 防 ROM 重启掉权限
    private val A11Y_WANTED_KEY = booleanPreferencesKey("a11y_wanted")
    suspend fun a11yWanted(ctx: Context): Boolean =
        ctx.dataStore.data.first()[A11Y_WANTED_KEY] ?: false
    suspend fun setA11yWanted(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[A11Y_WANTED_KEY] = b }
    }

    // 刷新率手动档:0=自动(系统决定), 60/90/120=锁定该刷新率。开机由 BootReceiver 重应用。
    private val REFRESH_RATE_KEY = intPreferencesKey("refresh_rate_hz")
    fun refreshRateFlow(ctx: Context): Flow<Int> =
        ctx.dataStore.data.map { it[REFRESH_RATE_KEY] ?: 0 }
    suspend fun refreshRate(ctx: Context): Int =
        ctx.dataStore.data.first()[REFRESH_RATE_KEY] ?: 0
    suspend fun setRefreshRate(ctx: Context, hz: Int) {
        ctx.dataStore.edit { it[REFRESH_RATE_KEY] = hz }
    }

    // 电芯数:默认 1(单芯, voltage_now 即整包电压, 功率不翻倍)。双芯串联机型在「记录」首开矫正
    // 或「设置 → 重新矫正电池」里改为 2,届时 computeWatt 按整包电压判定是否 ×2。
    private val CELL_COUNT_KEY = intPreferencesKey("cell_count")
    fun cellCountFlow(ctx: Context): Flow<Int> =
        ctx.dataStore.data.map { it[CELL_COUNT_KEY] ?: 1 }
    suspend fun cellCount(ctx: Context): Int =
        ctx.dataStore.data.first()[CELL_COUNT_KEY] ?: 1
    suspend fun setCellCount(ctx: Context, n: Int) {
        ctx.dataStore.edit { it[CELL_COUNT_KEY] = n }
    }

    // 电芯矫正标记:「记录」页首开时若为 false 则弹一次矫正对话框(确认单/双电芯)。选后置 true 不再打扰。
    private val CELL_CALIBRATED_KEY = booleanPreferencesKey("cell_calibrated")
    fun cellCalibratedFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[CELL_CALIBRATED_KEY] ?: false }
    suspend fun setCellCalibrated(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[CELL_CALIBRATED_KEY] = b }
    }
    // 一次性矫正提交:同时写电芯数 + 置矫正完成(避免两次 edit)。
    suspend fun commitCellCalibration(ctx: Context, cells: Int) {
        ctx.dataStore.edit {
            it[CELL_COUNT_KEY] = cells
            it[CELL_CALIBRATED_KEY] = true
        }
    }

    // 使用文档已读标记:首次启动若为 false, AppRoot 弹一次「先看文档」提示; 无论选什么都置 true 不再打扰。
    private val DOCS_SEEN_KEY = booleanPreferencesKey("docs_seen")
    fun docsSeenFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[DOCS_SEEN_KEY] ?: false }
    suspend fun setDocsSeen(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[DOCS_SEEN_KEY] = b }
    }
}

private val BLACKLIST_KEY   = stringPreferencesKey("blacklist_json")

object PrefsExt {
    fun blacklistFlow(ctx: Context): Flow<Set<String>> =
        ctx.dataStore.data.map { p ->
            val raw = p[BLACKLIST_KEY] ?: return@map emptySet()
            runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }.getOrDefault(emptySet())
        }
    suspend fun blacklist(ctx: Context): Set<String> =
        blacklistFlow(ctx).first()
    suspend fun toggleBlacklist(ctx: Context, pkg: String) {
        ctx.dataStore.edit { p ->
            val cur = (p[BLACKLIST_KEY]?.let {
                runCatching { org.json.JSONArray(it) }.getOrNull()
            }) ?: org.json.JSONArray()
            val list = (0 until cur.length()).map { cur.getString(it) }.toMutableSet()
            if (!list.add(pkg)) list.remove(pkg)
            val out = org.json.JSONArray()
            list.sorted().forEach { out.put(it) }
            p[BLACKLIST_KEY] = out.toString()
        }
    }
}
