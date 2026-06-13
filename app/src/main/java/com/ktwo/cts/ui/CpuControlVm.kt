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
 * CPU 面板 ViewModel。editing 是当前画像的内存副本,save() 时读-改-写回 config.json。
 * live 实时数据由 App 自读 sysfs(daemon 不再写 status.json);WhileSubscribed 无订阅时停轮询。
 */
class CpuControlVm(app: Application) : AndroidViewModel(app) {

    private val _cfg = MutableStateFlow(CpuControlCfg.EMPTY)
    val cfg: StateFlow<CpuControlCfg> = _cfg.asStateFlow()

    private val _selected = MutableStateFlow("")
    val selected: StateFlow<String> = _selected.asStateFlow()

    private val _editing = MutableStateFlow<ProfileModel?>(null)
    val editing: StateFlow<ProfileModel?> = _editing.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _saveResult = MutableStateFlow<Boolean?>(null)
    val saveResult: StateFlow<Boolean?> = _saveResult.asStateFlow()

    private val _soc = MutableStateFlow<RootShell.SocInfo?>(null)
    val soc: StateFlow<RootShell.SocInfo?> = _soc.asStateFlow()

    private val _restoreResult = MutableStateFlow<Boolean?>(null)
    val restoreResult: StateFlow<Boolean?> = _restoreResult.asStateFlow()

    private val freqCache = HashMap<Int, List<Long>>()
    private val govCache = HashMap<Int, List<String>>()

    // loadLive 的 Shell 兜底可能抛异常,stateIn 里未捕获会闪退;整体兜底退回 EMPTY 下一秒重试。
    val live: StateFlow<LiveStatus> = flow {
        while (true) {
            emit(runCatching { loadLive() }.getOrDefault(LiveStatus.EMPTY))
            delay(1000)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LiveStatus.EMPTY)

    // 必须声明在 init{} 之前:IO 线程可能在 init 里的 reload() 挂起前读到此字段,声明晚了会 NPE。
    private var devicePolicies: List<Int> = emptyList()

    init {
        reload()
        viewModelScope.launch(Dispatchers.IO) { _soc.value = runCatching { RootShell.detectSoc() }.getOrNull() }
    }

    fun reload() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    if (devicePolicies.isEmpty())
                        devicePolicies = runCatching { SysfsReader.discoverPolicies() }.getOrDefault(emptyList())
                    CpuControlCfg.parse(RootShell.readConfigRaw())
                }
                _cfg.value = parsed
                val pick = when {
                    _selected.value.isNotEmpty() && parsed.profileNames.contains(_selected.value) -> _selected.value
                    parsed.profileNames.contains("balance") -> "balance"
                    else -> parsed.profileNames.firstOrNull() ?: ""
                }
                _selected.value = pick
                _editing.value = runCatching { deviceAligned(parsed.profile(pick), parsed.policyMap) }.getOrNull()
            } catch (_: Throwable) {
                _editing.value = null
            } finally {
                _loading.value = false
            }
        }
    }

    fun selectProfile(name: String) {
        _selected.value = name
        _editing.value = deviceAligned(_cfg.value.profile(name), _cfg.value.policyMap)
    }

    /**
     * 画像簇列表 = config 拓扑定义的簇 ∪ 设备实际枚举到的 policy(并集, 按 policy 升序)。
     * 取并集而非只用设备枚举: 省电关大核时 /sys 里 policy6 目录消失, 仍要从 config 显示大核簇供编辑。
     * cN 索引优先从 config Policy 映射取, 无则按顺序兜底。
     */
    private fun deviceAligned(p: ProfileModel?, pm: com.ktwo.cts.data.PolicyMap): ProfileModel? {
        p ?: return null
        val configPolicies = pm.activeClusters.map { pm.policyOf(it) }
        val policies = (configPolicies + devicePolicies).filter { it >= 0 }.distinct().sorted()
        if (policies.isEmpty()) return p
        val clusters = policies.map { policy ->
            val cIndex = pm.cToPolicy.indexOf(policy).let { if (it >= 0) it else policies.indexOf(policy) }
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

    // clusterName 每次重组都会被调用,relatedCpus 是 root IO;缓存避免重组风暴打 root → ANR。
    private val nameCache = HashMap<Int, String>()

    fun clusterName(policy: Int): String = nameCache.getOrPut(policy) {
        val n = runCatching { SysfsReader.relatedCpus(policy).size }.getOrDefault(0)
        if (n > 0) "$n 核簇" else "簇"
    }

    // daemon 已不再写 status.json，实时数据全部由 App 自读 sysfs
    private suspend fun loadLive(): LiveStatus {
        val cells = Prefs.cellCount(getApplication())
        return SysfsReader.snapshot(cells)
    }

    // 频点/调速器表持久缓存到 App 私有目录: 省电关大核时 policyN 节点读不到,
    // 用上次在线缓存的表填充选择器, 使大核频率/调速器仍可编辑。空结果不进内存缓存以便核上线后重读。
    private val tablesFile by lazy { java.io.File(getApplication<Application>().filesDir, "sysfs_tables.json") }
    private val tables: org.json.JSONObject by lazy {
        runCatching { org.json.JSONObject(tablesFile.readText()) }.getOrDefault(org.json.JSONObject())
    }
    private fun saveTables() { runCatching { tablesFile.writeText(tables.toString()) } }

    fun availFreqsKhz(policy: Int): List<Long> {
        freqCache[policy]?.let { return it }
        val key = "freq_$policy"
        val live = runCatching { SysfsReader.availableFreqsKhz(policy) }.getOrDefault(emptyList())
        val result = if (live.isNotEmpty()) {
            tables.put(key, org.json.JSONArray(live)); saveTables(); live
        } else {
            tables.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
        }
        if (result.isNotEmpty()) freqCache[policy] = result
        return result
    }

    fun availGovernors(policy: Int): List<String> {
        govCache[policy]?.let { return it }
        val key = "gov_$policy"
        val live = runCatching { SysfsReader.availableGovernors(policy) }.getOrDefault(emptyList())
        val result = if (live.isNotEmpty()) {
            tables.put(key, org.json.JSONArray(live)); saveTables(); live
        } else {
            tables.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        }
        if (result.isNotEmpty()) govCache[policy] = result
        return result
    }

    private var gpuFreqCache: List<Int>? = null
    fun availGpuMhz(): List<Int> =
        gpuFreqCache ?: SysfsReader.availableGpuFreqsMhz().also { gpuFreqCache = it }

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
            // -1(不指定)视为开,首次点击切到关
            list[index] = if (list[index] == 0) 1 else 0
            _editing.value = cur.copy(coreOnline = list)
        }
    }

    /** 把当前编辑的画像合并写回 config.json,保留其它画像与未知字段。 */
    fun save() {
        val p = _editing.value ?: return
        viewModelScope.launch {
            // JSONObject 操作 / Shell 调用均可能抛;兜底避免 viewModelScope 里未捕获异常闪退。
            val ok = runCatching {
                val merged = withContext(Dispatchers.IO) {
                    // 重新读最新 JSON 防止 daemon 期间改过
                    val raw = RootShell.readConfigRaw().ifBlank { _cfg.value.rawJson }
                    _cfg.value.mergeProfileInto(raw, p)
                }
                val written = withContext(Dispatchers.IO) { RootShell.writeConfigRaw(merged) }
                if (written) {
                    // 备份到 App 私有目录;模块开机脚本会用默认 config 覆盖 sdcard,
                    // BootReceiver 开机后用此备份恢复用户配置。
                    withContext(Dispatchers.IO) {
                        runCatching {
                            java.io.File(getApplication<Application>().filesDir, "config_backup.json")
                                .writeText(merged)
                        }
                    }
                    reload()
                }
                written
            }.getOrDefault(false)
            _saveResult.value = ok
        }
    }

    fun clearSaveResult() { _saveResult.value = null }

    /**
     * 恢复默认配置后同步覆盖 App 私有备份;否则 BootReceiver 开机会把旧配置盖回来。
     */
    fun restoreDefaults(alsoPerapp: Boolean) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val r = runCatching { RootShell.restoreDefaultConfig(alsoPerapp) }.getOrDefault(false)
                if (r) {
                    runCatching {
                        val raw = RootShell.readConfigRaw()
                        val f = java.io.File(getApplication<Application>().filesDir, "config_backup.json")
                        if (raw.isNotBlank()) f.writeText(raw) else f.delete()
                    }
                }
                r
            }
            if (ok) reload()
            _restoreResult.value = ok
        }
    }

    fun clearRestoreResult() { _restoreResult.value = null }
}
