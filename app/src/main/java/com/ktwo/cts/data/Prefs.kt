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

    // 电芯数:默认 2(一加大电池多为双芯串联)。若功耗偏高一倍,改 1(voltage_now 已是整包电压)。
    private val CELL_COUNT_KEY = intPreferencesKey("cell_count")
    fun cellCountFlow(ctx: Context): Flow<Int> =
        ctx.dataStore.data.map { it[CELL_COUNT_KEY] ?: 2 }
    suspend fun cellCount(ctx: Context): Int =
        ctx.dataStore.data.first()[CELL_COUNT_KEY] ?: 2
    suspend fun setCellCount(ctx: Context, n: Int) {
        ctx.dataStore.edit { it[CELL_COUNT_KEY] = n }
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
