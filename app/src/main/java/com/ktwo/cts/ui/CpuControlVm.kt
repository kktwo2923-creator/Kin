package com.ktwo.cts.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ktwo.cts.data.CpuControlCfg
import com.ktwo.cts.data.LiveStatus
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.ProfileModel
import com.ktwo.cts.data.SysfsReader
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

/**
 * CPU 控制面板 ViewModel(方案丙:画像编辑器)。
 *
 *  - cfg:整个 config.json 解析出的所有画像(models)。
 *  - selectedProfile:当前正在编辑的画像名(默认选 balance,没有就第一个)。
 *  - editing:当前画像的可编辑副本;改动只在内存,save() 时读-改-写回 config.json。
 *  - live:实时功耗/频率,优先读 daemon status.json,daemon 不在则 App 自读 sysfs。
 *    用 stateIn + WhileSubscribed 保证无 UI 订阅时停止轮询(省电)。
 */
class CpuControlVm(app: Application) : AndroidViewModel(app) {

    private val _cfg = MutableStateFlow(CpuControlCfg.EMPTY)
    val cfg: StateFlow<CpuControlCfg> = _cfg.asStateFlow()

    private val _selected = MutableStateFlow("")
    val selected: StateFlow<String> = _selected.asStateFlow()

    /** 当前编辑中的画像副本(随 selected / 用户编辑变化) */
    private val _editing = MutableStateFlow<ProfileModel?>(null)
    val editing: StateFlow<ProfileModel?> = _editing.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _saveResult = MutableStateFlow<Boolean?>(null)
    val saveResult: StateFlow<Boolean?> = _saveResult.asStateFlow()

    // 可用频率档 / 调速器,按物理 policy 缓存(只读一次)
    private val freqCache = HashMap<Int, List<Long>>()
    private val govCache = HashMap<Int, List<String>>()

    /** 实时状态:1 秒刷新,无订阅自动停 */
    val live: StateFlow<LiveStatus> = flow {
        while (true) {
            emit(loadLive())
            delay(1000)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LiveStatus.EMPTY)

    init { reload() }

    // 设备实际 CPU policy(从 /sys 动态发现),用于让面板按真机簇展示/读频率
    private var devicePolicies: List<Int> = emptyList()

    fun reload() {
        viewModelScope.launch {
            _loading.value = true
            val parsed = withContext(Dispatchers.IO) {
                if (devicePolicies.isEmpty()) devicePolicies = SysfsReader.discoverPolicies()
                CpuControlCfg.parse(RootShell.readConfigRaw())
            }
            _cfg.value = parsed
            // 选择默认画像:优先 balance,否则第一个
            val pick = when {
                _selected.value.isNotEmpty() && parsed.profileNames.contains(_selected.value) -> _selected.value
                parsed.profileNames.contains("balance") -> "balance"
                else -> parsed.profileNames.firstOrNull() ?: ""
            }
            _selected.value = pick
            _editing.value = deviceAligned(parsed.profile(pick), parsed.policyMap)
            _loading.value = false
        }
    }

    fun selectProfile(name: String) {
        _selected.value = name
        // 切画像时丢弃未保存的编辑,载入该画像的最新副本(按真机簇对齐)
        _editing.value = deviceAligned(_cfg.value.profile(name), _cfg.value.policyMap)
    }

    /**
     * 让画像的簇列表对齐设备实际 policy(从 /sys 发现):
     *  - 每个真机 policy 出一张卡;cN 索引取 config Policy 映射里指向该 policy 的那个,
     *    没有则按顺序兜底(保证写回 freq_*_cN 时能落到 daemon 认的字段)。
     *  - 频率/调速器候选项在 UI 层用 availFreqs/availGovernors 直接读该 policy 的 sysfs。
     */
    private fun deviceAligned(p: ProfileModel?, pm: com.ktwo.cts.data.PolicyMap): ProfileModel? {
        p ?: return null
        if (devicePolicies.isEmpty()) return p
        val clusters = devicePolicies.mapIndexed { i, policy ->
            val cIndex = pm.cToPolicy.indexOf(policy).let { if (it >= 0) it else i }
            val existing = p.clusters.firstOrNull { it.cIndex == cIndex }
            com.ktwo.cts.data.ClusterModelCfg(
                cIndex = cIndex,
                policy = policy,
                minFreqKhz = existing?.minFreqKhz ?: "",
                maxFreqKhz = existing?.maxFreqKhz ?: "",
                governor = existing?.governor ?: "",
            )
        }
        return p.copy(clusters = clusters)
    }

    /** 簇显示名:用该 policy 的实际核数(related_cpus) */
    fun clusterName(policy: Int): String {
        val n = SysfsReader.relatedCpus(policy).size
        return if (n > 0) "$n 核簇" else "簇"
    }

    private suspend fun loadLive(): LiveStatus {
        return if (RootShell.daemonAlive()) {
            val s = LiveStatus.parse(RootShell.readStatusRaw())
            if (s.ts > 0) s else fallbackSnapshot()
        } else fallbackSnapshot()
    }

    private suspend fun fallbackSnapshot(): LiveStatus {
        val cells = Prefs.cellCount(getApplication())
        return SysfsReader.snapshot(cells)
    }

    fun availFreqsKhz(policy: Int): List<Long> =
        freqCache.getOrPut(policy) { SysfsReader.availableFreqsKhz(policy) }

    fun availGovernors(policy: Int): List<String> =
        govCache.getOrPut(policy) { SysfsReader.availableGovernors(policy) }

    // GPU 可用频率(MHz),只读一次
    private var gpuFreqCache: List<Int>? = null
    fun availGpuMhz(): List<Int> =
        gpuFreqCache ?: SysfsReader.availableGpuFreqsMhz().also { gpuFreqCache = it }

    // ---------- 编辑动作(只改内存中的 editing 副本) ----------

    fun setClusterMin(cIndex: Int, khz: Long) = updateCluster(cIndex) { it.copy(minFreqKhz = khz.toString()) }
    fun setClusterMax(cIndex: Int, khz: Long) = updateCluster(cIndex) { it.copy(maxFreqKhz = khz.toString()) }
    fun setClusterGov(cIndex: Int, gov: String) = updateCluster(cIndex) { it.copy(governor = gov) }

    private fun updateCluster(
        cIndex: Int,
        f: (com.ktwo.cts.data.ClusterModelCfg) -> com.ktwo.cts.data.ClusterModelCfg,
    ) {
        val cur = _editing.value ?: return
        _editing.value = cur.copy(
            clusters = cur.clusters.map { if (it.cIndex == cIndex) f(it) else it }
        )
    }

    fun setGpu(minMhz: Int, maxMhz: Int) {
        val cur = _editing.value ?: return
        _editing.value = cur.copy(gpuMinMhz = minMhz.toString(), gpuMaxMhz = maxMhz.toString())
    }

    fun toggleCore(index: Int) {
        val cur = _editing.value ?: return
        val list = cur.coreOnline.toMutableList()
        if (index in list.indices) {
            // 在 1(开) / 0(关)间切换;-1(不指定)视为开,首次点击切到关
            list[index] = if (list[index] == 0) 1 else 0
            _editing.value = cur.copy(coreOnline = list)
        }
    }

    /** 写回 config.json:把当前编辑的画像合并进原始 JSON(保留其它画像与未知字段) */
    fun save() {
        val p = _editing.value ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                // 重新读最新原始 JSON(防止其它进程/daemon 期间改过),再合并写回
                val raw = RootShell.readConfigRaw().ifBlank { _cfg.value.rawJson }
                val merged = _cfg.value.mergeProfileInto(raw, p)
                RootShell.writeConfigRaw(merged)
            }
            if (ok) reload()           // 写成功后重读,保证 UI 与文件一致
            _saveResult.value = ok
        }
    }

    fun clearSaveResult() { _saveResult.value = null }
}
