// 线程分配(M5)的本地配置。所有键默认关闭/保守值;真正写系统的动作在 ThreadAllocator。
// 注意: Prefs.kt 里的 cts_prefs DataStore 委托是文件私有的, 这里单独开一个存储文件,
// 避免对同一文件重复实例化 DataStore 导致运行时崩溃。
package com.ktwo.cts.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.taDataStore by preferencesDataStore(name = "thread_alloc_cfg")

object ThreadAllocCfg {

    /** 绑核模式: 线程被固定在指定核心上运行。 */
    const val MODE_AFFINITY = "affinity"
    /** 迁移模式: 允许调度器在核心间迁移(首版仅存偏好, 不写调度节点)。 */
    const val MODE_MIGRATION = "migration"

    private val ENABLED       = booleanPreferencesKey("ta_enabled")        // 总开关, 默认关
    private val MODE          = stringPreferencesKey("ta_mode")            // affinity / migration
    private val APP_ENABLED   = booleanPreferencesKey("ta_app_enabled")    // 整理前台应用线程
    private val GAME_ENABLED  = booleanPreferencesKey("ta_game_enabled")   // 游戏线程分配
    private val GAME_ADAPTIVE = booleanPreferencesKey("ta_game_adaptive")  // 自适应分配
    private val GAME_RT       = booleanPreferencesKey("ta_game_rt")        // 实时调度(SCHED_FIFO/RR)
    private val GAME_WEIGHT   = booleanPreferencesKey("ta_game_weight")    // 权重调整(即将支持)
    private val GAME_PKGS     = stringPreferencesKey("ta_game_pkgs")       // 逗号分隔的游戏包名
    private val EBPF_ENABLED  = booleanPreferencesKey("ta_ebpf_enabled")   // 事件驱动(eBPF/proc connector)

    fun enabledFlow(ctx: Context): Flow<Boolean> =
        ctx.taDataStore.data.map { it[ENABLED] ?: false }
    suspend fun enabled(ctx: Context): Boolean =
        ctx.taDataStore.data.first()[ENABLED] ?: false
    suspend fun setEnabled(ctx: Context, b: Boolean) {
        ctx.taDataStore.edit { it[ENABLED] = b }
    }

    fun modeFlow(ctx: Context): Flow<String> =
        ctx.taDataStore.data.map { it[MODE] ?: MODE_AFFINITY }
    suspend fun setMode(ctx: Context, mode: String) {
        ctx.taDataStore.edit { it[MODE] = mode }
    }

    fun appEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.taDataStore.data.map { it[APP_ENABLED] ?: false }
    suspend fun setAppEnabled(ctx: Context, b: Boolean) {
        ctx.taDataStore.edit { it[APP_ENABLED] = b }
    }

    fun gameEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.taDataStore.data.map { it[GAME_ENABLED] ?: false }
    suspend fun setGameEnabled(ctx: Context, b: Boolean) {
        ctx.taDataStore.edit { it[GAME_ENABLED] = b }
    }

    fun gameAdaptiveFlow(ctx: Context): Flow<Boolean> =
        ctx.taDataStore.data.map { it[GAME_ADAPTIVE] ?: false }
    suspend fun setGameAdaptive(ctx: Context, b: Boolean) {
        ctx.taDataStore.edit { it[GAME_ADAPTIVE] = b }
    }

    fun gameRtFlow(ctx: Context): Flow<Boolean> =
        ctx.taDataStore.data.map { it[GAME_RT] ?: false }
    suspend fun setGameRt(ctx: Context, b: Boolean) {
        ctx.taDataStore.edit { it[GAME_RT] = b }
    }

    fun ebpfEnabledFlow(ctx: Context): Flow<Boolean> =
        ctx.taDataStore.data.map { it[EBPF_ENABLED] ?: false }
    suspend fun ebpfEnabled(ctx: Context): Boolean =
        ctx.taDataStore.data.first()[EBPF_ENABLED] ?: false
    suspend fun setEbpfEnabled(ctx: Context, b: Boolean) {
        ctx.taDataStore.edit { it[EBPF_ENABLED] = b }
    }

    fun gameWeightFlow(ctx: Context): Flow<Boolean> =
        ctx.taDataStore.data.map { it[GAME_WEIGHT] ?: false }
    suspend fun setGameWeight(ctx: Context, b: Boolean) {
        ctx.taDataStore.edit { it[GAME_WEIGHT] = b }
    }

    /** 已选游戏包名集合(逗号分隔存储, 读出去重去空)。 */
    fun gamePkgsFlow(ctx: Context): Flow<Set<String>> =
        ctx.taDataStore.data.map { p ->
            (p[GAME_PKGS] ?: "")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    suspend fun setGamePkgs(ctx: Context, pkgs: Set<String>) {
        ctx.taDataStore.edit { p ->
            p[GAME_PKGS] = pkgs.filter { it.isNotBlank() }.sorted().joinToString(",")
        }
    }
    suspend fun toggleGamePkg(ctx: Context, pkg: String) {
        ctx.taDataStore.edit { p ->
            val cur = (p[GAME_PKGS] ?: "")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            if (!cur.add(pkg)) cur.remove(pkg)
            p[GAME_PKGS] = cur.sorted().joinToString(",")
        }
    }
}
