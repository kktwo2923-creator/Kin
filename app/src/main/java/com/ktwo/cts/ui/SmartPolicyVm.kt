// 智能策略页 ViewModel: fsm_policy(六状态频率上限覆盖)读-改-写 + 问卷应用 + auto_status 轮询。
package com.ktwo.cts.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ktwo.cts.data.AutoModeCfg
import com.ktwo.cts.data.FsmStateOverride
import com.ktwo.cts.data.PolicyMap
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.SmartPolicyCfg
import com.ktwo.cts.data.SmartSurvey
import com.ktwo.cts.data.SurveyAnswers
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * auto_status.txt 的轻量视图(只取本页需要的字段)。
 * 解析约定与监控页(AutoMonitorScreen 的私有 AutoStatus)一致, 但独立实现, 不动监控页。
 */
data class SmartLiveStatus(
    val fsm: String = "",          // 当前 FSM 状态名(daemon 写大写, 比较时忽略大小写)
    val alpha: Int = -1,           // 省电倾向 0..1000(智能曲线参考用)
    val conf: Int = -1,
    val nightSamples: Int = 0,
    val cpuMaxLittle: Int = 0,     // 当前生效频率上限(kHz)
    val cpuMaxBig: Int = 0,
    val valid: Boolean = false,
) {
    companion object {
        fun parse(raw: String): SmartLiveStatus {
            if (raw.isBlank()) return SmartLiveStatus()
            val m = HashMap<String, String>()
            raw.lineSequence().forEach {
                val i = it.indexOf('=')
                if (i > 0) m[it.substring(0, i).trim()] = it.substring(i + 1).trim()
            }
            fun gi(k: String, d: Int) = m[k]?.toIntOrNull() ?: d
            return SmartLiveStatus(
                fsm = m["fsm"] ?: "",
                alpha = gi("alpha", -1),
                conf = gi("conf", -1),
                nightSamples = gi("night_samples", 0),
                cpuMaxLittle = gi("cpu_max_little", 0),
                cpuMaxBig = gi("cpu_max_big", 0),
                valid = true,
            )
        }
    }
}

/**
 * 内置 FSM 调频曲线(镜像 daemon AutoFsm.hpp:204,211-214 的常量), 只用于向用户展示
 * "未覆盖时智能曲线大概把上限压到多少"; 真正的调频始终由 daemon 计算。
 */
object FsmCurve {
    // 六状态负载级别(State 枚举顺序 0..5)
    private val LEVEL = floatArrayOf(0.0f, 0.15f, 0.45f, 0.72f, 0.62f, 1.0f)
    // battery/balanced 两端的上限区间(参考机型 kHz), alpha 在两端之间插值。
    private const val BAT_F_MAXL = 921_600f
    private const val BAT_C_MAXL = 1_670_400f
    private const val BAT_F_MAXB = 921_600f
    private const val BAT_C_MAXB = 1_632_000f
    private const val BAL_F_MAXL = 1_190_400f
    private const val BAL_C_MAXL = 2_611_200f
    private const val BAL_F_MAXB = 1_881_600f
    private const val BAL_C_MAXB = 3_974_400f
    // 参考机型硬件最高频(kHz), 换算到本机时按 hwmax/REF 缩放。
    private const val REF_HWMAX_LITTLE = 2_035_200f
    private const val REF_HWMAX_BIG = 3_302_400f

    /** 内置 GPU 上限百分比(镜像 AutoFsm::gpuPctForState)。 */
    val GPU_BUILTIN = intArrayOf(40, 45, 60, 85, 80, 100)

    private fun blend(bal: Float, bat: Float, alpha: Int): Float =
        bal + (bat - bal) * alpha.coerceIn(0, 1000) / 1000f

    /**
     * 某状态的内置 CPU 频率上限(kHz)。
     * hwMaxKhz<=0(读不到本机 cpuinfo_max_freq)时返回参考机型原始值, 由调用方标注"参考"。
     */
    fun maxCapKhz(stateIdx: Int, alpha: Int, little: Boolean, hwMaxKhz: Long): Long {
        val t = LEVEL.getOrElse(stateIdx) { 1f }
        val a = alpha.coerceAtLeast(0)   // 未知 alpha 按 0(均衡端)展示
        val floor = if (little) blend(BAL_F_MAXL, BAT_F_MAXL, a) else blend(BAL_F_MAXB, BAT_F_MAXB, a)
        val ceil = if (little) blend(BAL_C_MAXL, BAT_C_MAXL, a) else blend(BAL_C_MAXB, BAT_C_MAXB, a)
        val k = floor + (ceil - floor) * t
        val ref = if (little) REF_HWMAX_LITTLE else REF_HWMAX_BIG
        return if (hwMaxKhz > 0) (k * hwMaxKhz / ref).toLong().coerceAtMost(hwMaxKhz)
        else k.toLong()
    }
}

/**
 * 与 AutoModeVm 同一套结构: editing 是内存编辑副本, 点「保存」才读-改-写回 config.json;
 * 写成功后备份到私有目录(config_backup.json)供开机恢复。另外轮询 auto_status 做实时高亮。
 */
class SmartPolicyVm(app: Application) : AndroidViewModel(app) {

    private val _cfg = MutableStateFlow(SmartPolicyCfg())

    /** 编辑副本: 六个状态都有 key(未覆盖的为默认 FsmStateOverride)。 */
    private val _editing = MutableStateFlow(normalize(emptyMap()))
    val editing: StateFlow<Map<String, FsmStateOverride>> = _editing.asStateFlow()

    /** 当前 AutoMode 配置(夜间/温控等), 问卷结果展示参考。 */
    private val _autoCfg = MutableStateFlow(AutoModeCfg.EMPTY)
    val autoCfg: StateFlow<AutoModeCfg> = _autoCfg.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _saveResult = MutableStateFlow<Boolean?>(null)
    val saveResult: StateFlow<Boolean?> = _saveResult.asStateFlow()

    /** 问卷「完成并应用」的结果(与手动保存分开, 提示语不同)。 */
    private val _surveyResult = MutableStateFlow<Boolean?>(null)
    val surveyResult: StateFlow<Boolean?> = _surveyResult.asStateFlow()

    // 1.5s 轮询 auto_status(当前状态高亮 + alpha); WhileSubscribed: 页面不在前台收集时
    // 自动停止, 不让 root cat 在后台一直空转。
    val live: StateFlow<SmartLiveStatus> = flow {
        while (true) {
            emit(SmartLiveStatus.parse(runCatching { RootShell.readAutoStatus() }.getOrDefault("")))
            delay(1500)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SmartLiveStatus())

    /** 小/大簇硬件最高频(kHz); 0 = 未知(智能曲线只能展示参考机型数值)。 */
    private val _hwMaxL = MutableStateFlow(0L)
    val hwMaxL: StateFlow<Long> = _hwMaxL.asStateFlow()
    private val _hwMaxB = MutableStateFlow(0L)
    val hwMaxB: StateFlow<Long> = _hwMaxB.asStateFlow()

    init {
        reload()
    }

    private fun normalize(m: Map<String, FsmStateOverride>): Map<String, FsmStateOverride> =
        SmartPolicyCfg.STATE_ORDER.associateWith { m[it] ?: FsmStateOverride() }

    fun reload() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val raw = withContext(Dispatchers.IO) { RootShell.readConfigRaw() }
                val parsed = SmartPolicyCfg.parse(raw)
                _cfg.value = parsed
                _editing.value = normalize(parsed.states)
                _dirty.value = false
                _autoCfg.value = AutoModeCfg.parse(raw)
                // Policy 映射(c0=小核簇, c1=大核簇)-> 本机 cpuinfo_max_freq。
                withContext(Dispatchers.IO) {
                    val pm = runCatching { PolicyMap.parse(JSONObject(raw)) }.getOrDefault(PolicyMap())
                    _hwMaxL.value = readHwMaxKhz(pm.policyOf(0))
                    _hwMaxB.value = readHwMaxKhz(pm.policyOf(1))
                }
            } catch (_: Throwable) {
                _cfg.value = SmartPolicyCfg()
                _editing.value = normalize(emptyMap())
            } finally {
                _loading.value = false
            }
        }
    }

    /** 读某 policy 的 cpuinfo_max_freq(kHz); 免 root 直读优先, 失败再 root cat。 */
    private fun readHwMaxKhz(policy: Int): Long {
        if (policy < 0) return 0L
        val path = "/sys/devices/system/cpu/cpufreq/policy$policy/cpuinfo_max_freq"
        runCatching {
            val f = java.io.File(path)
            if (f.canRead()) f.readText().trim().toLongOrNull()?.let { if (it > 0) return it }
        }
        return runCatching { RootShell.exec("cat '$path' 2>/dev/null").trim().toLongOrNull() ?: 0L }
            .getOrDefault(0L)
    }

    /** 修改某个状态的覆盖(仅改内存副本, 标记 dirty)。 */
    fun update(state: String, ov: FsmStateOverride) {
        val next = _editing.value.toMutableMap().also { it[state] = ov }
        _editing.value = next
        _dirty.value = next != normalize(_cfg.value.states)
    }

    /** 保存 fsm_policy 覆盖(读-改-写, 其余节点保留); 结果经 saveResult 发布。 */
    fun save() {
        viewModelScope.launch {
            val ok = runCatching {
                val merged = withContext(Dispatchers.IO) {
                    val raw = RootShell.readConfigRaw().ifBlank { _cfg.value.rawJson }
                    SmartPolicyCfg(states = _editing.value).mergeInto(raw)
                }
                val written = withContext(Dispatchers.IO) { RootShell.writeConfigRaw(merged) }
                if (written) {
                    backup(merged)
                    reload()
                }
                written
            }.getOrDefault(false)
            _saveResult.value = ok
        }
    }

    /**
     * 完成问卷: 按回答改 AutoMode 参数 + 整体重写 fsm_policy, 落盘;
     * 成功后记录最新回答 + 追加历史 + 清除「稍后再说」。结果经 surveyResult 发布。
     */
    fun applySurvey(answers: SurveyAnswers) {
        viewModelScope.launch {
            val ok = runCatching {
                val merged = withContext(Dispatchers.IO) {
                    val raw = RootShell.readConfigRaw().ifBlank { _cfg.value.rawJson }
                    SmartSurvey.apply(answers, raw)
                }
                val written = withContext(Dispatchers.IO) { RootShell.writeConfigRaw(merged) }
                if (written) {
                    backup(merged)
                    val ctx = getApplication<Application>()
                    Prefs.setSmartSurvey(ctx, answers.toJson())
                    Prefs.appendSmartSurveyHistory(ctx, answers.toJson())
                    Prefs.setSmartSurveySnooze(ctx, 0L)
                    reload()
                }
                written
            }.getOrDefault(false)
            _surveyResult.value = ok
        }
    }

    private suspend fun backup(merged: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                java.io.File(getApplication<Application>().filesDir, "config_backup.json")
                    .writeText(merged)
            }
        }
    }

    fun clearSaveResult() { _saveResult.value = null }
    fun clearSurveyResult() { _surveyResult.value = null }
}
