package com.ktwo.cts.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "cts_prefs")

// 调度切换完全由 CTS 模块负责(读 perapp_powermode.txt),App 不再持有
// 服务开关/黑名单/STRICT 等运行时状态。DataStore 仅保留外观(主题、壁纸)。
object Prefs {
    private val THEME_KEY     = stringPreferencesKey("theme_mode")     // system/light/dark/mono
    private val WALLPAPER_KEY = stringPreferencesKey("wallpaper_uri")  // 文件路径/URI;空=默认渐变

    /** 主题模式(system/light/dark/mono),默认跟随系统 */
    fun themeModeFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[THEME_KEY] ?: "system" }
    suspend fun setThemeMode(ctx: Context, mode: String) {
        ctx.dataStore.edit { it[THEME_KEY] = mode }
    }

    /** 自定义壁纸(空 = 用默认渐变) */
    fun wallpaperUriFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[WALLPAPER_KEY] ?: "" }
    suspend fun setWallpaperUri(ctx: Context, uri: String) {
        ctx.dataStore.edit { it[WALLPAPER_KEY] = uri }
    }
}
