package com.ktwo.cts.license

import android.content.Context
import com.ktwo.cts.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 付费解锁(Pro / 智能版)统一入口。
 *
 * 门控范围:智能模式总开关 + 智能设置里的「系统优化(WiFi/网络/内存/开机精简/后台回收)」。
 * 与 App 整体是否需要卡密(BuildConfig.NO_LICENSE)无关 —— 这是【功能级】付费点:
 * App 本体照常打开即用, 只有上述高级功能需要解锁。
 *
 * 真值来源 = License.state()==VALID(有有效令牌)。为让 UI 秒读、不卡 root/网络,
 * 解锁态缓存进 Prefs(proUnlocked): App 启动时 sync 一次, 激活成功时立即置位。
 */
object Pro {

    /** 是否已解锁(读本机授权令牌)。IO 线程调用。 */
    fun isUnlockedNow(): Boolean =
        runCatching { License.state() == License.State.VALID }.getOrDefault(false)

    /** 从 License 同步解锁态到 Prefs(App 启动 / 联网复验后调用)。 */
    suspend fun sync(context: Context) {
        val unlocked = withContext(Dispatchers.IO) { isUnlockedNow() }
        Prefs.setProUnlocked(context, unlocked)
    }

    /** 激活成功后置位缓存(避免等待下次 sync)。 */
    suspend fun markUnlocked(context: Context) {
        Prefs.setProUnlocked(context, true)
    }
}
