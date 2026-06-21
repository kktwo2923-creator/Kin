package com.ktwo.cts.license

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
 * 联网再校验任务。验证节奏:
 *   - App 启动时跑一次(见 schedule)。
 *   - 每 12h 周期跑一次(WorkManager,联网才有意义)。
 *   - 联网恢复时立刻跑一次(网络回调,见 registerNetworkRevalidation)。
 * 离线时不做任何处理、也绝不锁用户;一旦能连上服务器即刻校验。
 * 服务器明确吊销(禁用/封禁/过期/换机)→ App 作废本机令牌并红屏锁定,守护随之停机。
 */
class LicenseRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            // 未激活或离线 → 直接成功(不打扰、不锁);OK/REVOKED 都已在 revalidate 内处理
            License.revalidate()
            Result.success()
        }.getOrElse { Result.success() }
    }

    companion object {
        private const val UNIQUE = "cts_license_refresh"
        @Volatile private var netCbRegistered = false

        /** App 启动时登记:12h 周期 + 立即校验一次。 */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP, // 已存在则不重排,避免重置周期
                PeriodicWorkRequestBuilder<LicenseRefreshWorker>(12, TimeUnit.HOURS).build()
            )
            enqueueOnce(context)
        }

        /** 立即校验一次(App 打开 / 联网恢复时调用)。 */
        fun enqueueOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${UNIQUE}_once",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LicenseRefreshWorker>().build()
            )
        }

        /** 注册网络回调:每当联网恢复,立刻再校验一次。整进程登记一次即可。 */
        fun registerNetworkRevalidation(context: Context) {
            if (netCbRegistered) return
            runCatching {
                val app = context.applicationContext
                val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { enqueueOnce(app) }
                })
                netCbRegistered = true
            }
        }
    }
}
