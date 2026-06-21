package com.ktwo.cts.license

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 后台周期续签:令牌是短有效期的(服务器侧默认 7 天),守护到期即停机。
 * 本任务定期(约每 12h)用已存卡密回服务器换新令牌,刷新 token_not_after。
 * - 卡密正常 → 续到新签,守护持续运行。
 * - 卡密被后台禁用/到期 → 续签失败 → 令牌在窗口内过期 → 守护自动停机(远程吊销)。
 * 不依赖用户主动打开 App;只要 App 仍安装且未被强制停止即生效。
 */
class LicenseRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            // 未激活则跳过;临近到期才真正联网,平时不打扰
            if (License.currentLicenseKey().isBlank()) return@withContext Result.success()
            if (!License.needsRefresh()) return@withContext Result.success()
            val r = License.refresh()
            // 续签失败(网络/被禁用)→ 让 WorkManager 稍后重试;到期前多试几次
            if (r.ok) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val UNIQUE = "cts_license_refresh"

        /** 在 App 启动时登记一次;WorkManager 持久化,重启后仍按周期运行。同时立即续签一次。 */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP, // 已存在则不重排,避免重置周期
                PeriodicWorkRequestBuilder<LicenseRefreshWorker>(12, TimeUnit.HOURS).build()
            )
            // App 每次打开顺手续签一次(临近到期才真正联网,见 needsRefresh)
            wm.enqueueUniqueWork(
                "${UNIQUE}_once",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LicenseRefreshWorker>().build()
            )
        }
    }
}
