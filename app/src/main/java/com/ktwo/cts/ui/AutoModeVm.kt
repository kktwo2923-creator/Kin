package com.ktwo.cts.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ktwo.cts.data.AutoModeCfg
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智能档(自动模式)设置 ViewModel。
 *  - editing 是内存编辑副本,改旋钮只动它;点"保存"才读-改-写回 config.json(触发 daemon inotify 热重载)。
 *  - 总开关(smartEnabled)即时切换全局模式 auto<->balance,并把当前设置一并落盘。
 *  - 与 CpuControlVm 一致:写盘后备份到私有目录,供 BootReceiver 开机恢复。
 */
class AutoModeVm(app: Application) : AndroidViewModel(app) {

    private val _cfg = MutableStateFlow(AutoModeCfg.EMPTY)

    private val _editing = MutableStateFlow(AutoModeCfg.EMPTY)
    val editing: StateFlow<AutoModeCfg> = _editing.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 全局模式当前是否为 auto(智能档已生效)。 */
    private val _smartEnabled = MutableStateFlow(false)
    val smartEnabled: StateFlow<Boolean> = _smartEnabled.asStateFlow()

    private val _saveResult = MutableStateFlow<Boolean?>(null)
    val saveResult: StateFlow<Boolean?> = _saveResult.asStateFlow()

    /** editing 与已保存的 cfg 是否有差异(供"保存"按钮高亮/启用)。 */
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val raw = withContext(Dispatchers.IO) { com.ktwo.cts.data.KinSettings.merged() }
                val parsed = AutoModeCfg.parse(raw)
                _cfg.value = parsed
                _editing.value = parsed
                _dirty.value = false
                // 以持久化全局默认判断, 不读 /data/coreturbo_powermode: 那是"当前生效档", auto 下会被
                // FSM 解析出的基础档(balance 等)改写, 据此判会让总开关在开启后又被误显示为"关"(抖动)。
                _smartEnabled.value = withContext(Dispatchers.IO) { RootShell.isSmartOn() }
            } catch (_: Throwable) {
                _cfg.value = AutoModeCfg.EMPTY
                _editing.value = AutoModeCfg.EMPTY
            } finally {
                _loading.value = false
            }
        }
    }

    /** 修改某个旋钮(仅改内存副本,标记 dirty)。 */
    fun update(f: (AutoModeCfg) -> AutoModeCfg) {
        val next = f(_editing.value)
        _editing.value = next
        _dirty.value = next != _cfg.value
    }

    /** 把当前编辑保存进覆盖层(settings.json),保留其它字段;成功后 reload。返回结果经 saveResult 发布。 */
    fun save() {
        viewModelScope.launch {
            val ok = runCatching {
                // 智能调度设置属覆盖层 → 读改写整体在 updateOverlay 锁内完成, config.json 不动。
                val editing = _editing.value
                val written = com.ktwo.cts.data.KinSettings.updateOverlay(getApplication()) { raw ->
                    editing.mergeInto(raw)
                }
                if (written) reload()
                written
            }.getOrDefault(false)
            _saveResult.value = ok
        }
    }

    /**
     * 总开关:开 = 全局切到智能档(auto);关 = 切回均衡(balance)。
     * 开启时先把当前设置落盘(确保 AutoMode 块存在且 enable=true),再切模式。
     */
    fun setSmartEnabled(on: Boolean) {
        viewModelScope.launch {
            val ok = runCatching {
                if (on) {
                    // 落盘当前设置(强制 enable=true),保证 auto 真正接管。写覆盖层 settings.json。
                    val editing = _editing.value.copy(enable = true)
                    com.ktwo.cts.data.KinSettings.updateOverlay(getApplication()) { raw ->
                        editing.mergeInto(raw)
                    }
                }
                val target = if (on) "auto" else "balance"
                withContext(Dispatchers.IO) {
                    RootShell.applyMode(target)        // 即时切换(写 mode.txt)
                    RootShell.setGlobalMode(target)    // 持久化全局默认,跨重启保持
                }
                // 持久化给底栏用: tab 集只信这个值, 不再热路径读 root shell(避免抖动弹页)。
                Prefs.setSmartEnabled(getApplication<Application>(), on)
                // 记录首次开启时间(智能策略问卷「运行满一天再提示」判据), 只记第一次。
                if (on && Prefs.smartEnabledSince(getApplication()) == 0L) {
                    Prefs.setSmartEnabledSince(getApplication(), System.currentTimeMillis())
                }
                true
            }.getOrDefault(false)
            _smartEnabled.value = if (ok) on else _smartEnabled.value
            if (ok) reload()
            _saveResult.value = ok
        }
    }

    fun clearSaveResult() { _saveResult.value = null }
}
