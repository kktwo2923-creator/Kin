package com.ktwo.cts.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "cts_prefs")

// 注: App 画像与全局模式已迁移到 perapp_powermode.txt(见 RootShell),
// 故 DataStore 仅保留服务开关 / 灭屏模式 / 轮询间隔等本地设置。
object Prefs {
    private val SCREEN_OFF_KEY   = stringPreferencesKey("screen_off_mode")
    private val ENABLED_KEY      = booleanPreferencesKey("service_enabled")
    private val POLL_MS_KEY      = intPreferencesKey("poll_ms")

    /** 灭屏模式(默认 powersave) */
    fun screenOffModeFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[SCREEN_OFF_KEY] ?: "powersave" }
    suspend fun screenOffMode(ctx: Context): String =
        ctx.dataStore.data.first()[SCREEN_OFF_KEY] ?: "powersave"
    suspend fun setScreenOffMode(ctx: Context, key: String) {
        ctx.dataStore.edit { it[SCREEN_OFF_KEY] = key }
    }

    /** 服务总开关 */
    fun enabledFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[ENABLED_KEY] ?: true }
    suspend fun enabled(ctx: Context): Boolean =
        ctx.dataStore.data.first()[ENABLED_KEY] ?: true
    suspend fun setEnabled(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[ENABLED_KEY] = b }
    }

    /** 轮询间隔毫秒 (默认 2000) */
    suspend fun pollMs(ctx: Context): Int =
        ctx.dataStore.data.first()[POLL_MS_KEY] ?: 2000
    suspend fun setPollMs(ctx: Context, ms: Int) {
        ctx.dataStore.edit { it[POLL_MS_KEY] = ms }
    }
}

// ============ Scene 风格新增配置 ============
private val STRICT_KEY      = booleanPreferencesKey("strict_mode")
private val BLACKLIST_KEY   = stringPreferencesKey("blacklist_json")  // 不接管包名列表

object PrefsExt {
    /** STRICT: 同模式也强制重发(对应 Scene 的 GLOBAL_SPF_DYNAMIC_CONTROL_STRICT) */
    fun strictFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[STRICT_KEY] ?: false }
    suspend fun strict(ctx: Context): Boolean =
        ctx.dataStore.data.first()[STRICT_KEY] ?: false
    suspend fun setStrict(ctx: Context, b: Boolean) {
        ctx.dataStore.edit { it[STRICT_KEY] = b }
    }

    /** 黑名单: 不接管的包(对应 Scene 的 sceneBlackList) */
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
