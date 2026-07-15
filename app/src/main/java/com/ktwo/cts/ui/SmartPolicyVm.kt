// 智能策略页 ViewModel: fsm_policy(六状态频率上限覆盖)读-改-写 + 问卷应用 + auto_status 轮询。
package com.ktwo.cts.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ktwo.cts.data.AppCatPolicyCfg
import com.ktwo.cts.data.AutoModeCfg
import com.ktwo.cts.data.CatOverride
import com.ktwo.cts.data.FsmStateOverride
import com.ktwo.cts.data.PolicyMap
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.SmartPolicyCfg
import com.ktwo.cts.data.SmartSurvey
import com.ktwo.cts.data.SmartSurveyStore
import com.ktwo.cts.data.SurveyAnswers
import com.ktwo.cts.data.SurveyContext
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
    val nightSleep: Int = -1,      // 学到的就寝时刻(当日分钟数 0..1439), -1 = 未学到
    val nightWake: Int = -1,       // 学到的起床时刻(当日分钟数), -1 = 未学到
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
                nightSleep = gi("night_sleep", -1),
                nightWake = gi("night_wake", -1),
                cpuMaxLittle = gi("cpu_max_little", 0),
                cpuMaxBig = gi("cpu_max_big", 0),
                valid = true,
            )
        }
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

    /** 应用分类封顶策略(app_policy): 落盘副本与编辑副本(rawJson 置空便于相等比较判 dirty)。 */
    private val _catCfg = MutableStateFlow(AppCatPolicyCfg())
    private val _catEditing = MutableStateFlow(AppCatPolicyCfg())
    val catEditing: StateFlow<AppCatPolicyCfg> = _catEditing.asStateFlow()

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
                _autoCfg.value = AutoModeCfg.parse(raw)
                // 分类策略: rawJson 置空存两份, 编辑副本与落盘副本可直接相等比较。
                val cat = AppCatPolicyCfg.parse(raw).copy(rawJson = "")
                _catCfg.value = cat
                _catEditing.value = cat
                _dirty.value = false
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

    private fun recomputeDirty() {
        _dirty.value = _editing.value != normalize(_cfg.value.states) ||
            _catEditing.value != _catCfg.value
    }

    /** 修改某个状态的覆盖(仅改内存副本, 标记 dirty)。 */
    fun update(state: String, ov: FsmStateOverride) {
        _editing.value = _editing.value.toMutableMap().also { it[state] = ov }
        recomputeDirty()
    }

    /** 修改某个应用分类的封顶(仅改内存副本)。 */
    fun updateCat(cat: String, ov: CatOverride) {
        _catEditing.value = _catEditing.value.let {
            it.copy(cats = it.cats.toMutableMap().also { m -> m[cat] = ov })
        }
        recomputeDirty()
    }

    fun setCatEnable(on: Boolean) {
        _catEditing.value = _catEditing.value.copy(enable = on)
        recomputeDirty()
    }

    fun setCatCoreEnable(on: Boolean) {
        _catEditing.value = _catEditing.value.copy(coreEnable = on)
        recomputeDirty()
    }

    /** 保存 fsm_policy + app_policy(读-改-写, 其余节点保留); 结果经 saveResult 发布。 */
    fun save() {
        viewModelScope.launch {
            val ok = runCatching {
                val merged = withContext(Dispatchers.IO) {
                    val raw = RootShell.readConfigRaw().ifBlank { _cfg.value.rawJson }
                    _catEditing.value.mergeInto(
                        SmartPolicyCfg(states = _editing.value).mergeInto(raw)
                    )
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
     * 完成问卷(确认-或-纠正): 在当前生效值基础上按回答做相对调整, 落盘;
     * 纠正了睡眠时间时再经 auto_control.txt 下发 night_seed 纠正 daemon 的夜间学习
     * (学习样本 >=3 后会覆盖配置里的 night_start/end, 只改配置不够)。
     * 成功后记录最新回答 + 追加历史 + 清除「稍后再说」。结果经 surveyResult 发布。
     */
    fun applySurvey(answers: SurveyAnswers) {
        viewModelScope.launch {
            val ok = runCatching {
                // 顺序: 先纠正学习数据(幂等, 重试无副作用), 再写配置。反过来的话
                // 配置写成功而 night_seed 失败, 用户重试会在已抬高的基线上再叠一层相对调整。
                // 学到窗口(样本>=3)后学习值覆盖配置, night_seed 是唯一有效纠正通道 ——
                // 它失败必须整体算失败, 让用户看到"写入失败"并可重试。
                val needSeed = !answers.nightOk && answers.nightTouched
                val seeded = if (needSeed) withContext(Dispatchers.IO) {
                    RootShell.sendAutoControl(
                        "night_seed=" + (answers.sleepStart * 60) + "," + (answers.sleepEnd * 60)
                    )
                } else true
                if (!seeded) return@runCatching false

                val surveyCtx = SurveyContext(
                    alpha = live.value.alpha.takeIf { it >= 0 } ?: 500,
                    hwMaxL = hwMaxL.value,
                    hwMaxB = hwMaxB.value,
                )
                val merged = withContext(Dispatchers.IO) {
                    val raw = RootShell.readConfigRaw().ifBlank { _cfg.value.rawJson }
                    // 以界面所见为基线: 未保存的手动编辑(状态覆盖+分类策略)先并入, 相对加减
                    // 与问卷里展示的"当前上限"一致(顺带把脏编辑一起落盘, 不丢用户改动)。
                    val base = _catEditing.value.mergeInto(
                        SmartPolicyCfg(states = _editing.value).mergeInto(raw)
                    )
                    SmartSurvey.apply(answers, base, surveyCtx)
                }
                val written = withContext(Dispatchers.IO) { RootShell.writeConfigRaw(merged) }
                if (written) {
                    backup(merged)
                    val ctx = getApplication<Application>()
                    Prefs.setSmartSurvey(ctx, answers.toJson())
                    Prefs.appendSmartSurveyHistory(ctx, answers.toJson())
                    Prefs.setSmartSurveySnooze(ctx, 0L)
                    // 落盘副本(存 /sdcard/Android/Kin/AI): 清 App 数据也不丢, 失败静默。
                    SmartSurveyStore.backup(ctx)
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
