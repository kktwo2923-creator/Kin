package com.ktwo.cts.updater

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.ktwo.cts.BuildConfig
import com.ktwo.cts.license.License
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App & daemon 双组件版本检查 + 下载 + 安装。
 *
 * 服务端 version.json 结构:
 * ```
 * { app: {version_code, version_name, changelog, download_url, size, force_update},
 *   daemon: {...同结构...} }
 * ```
 * - App 更新: 下载 APK 到外部下载目录,通过 FileProvider 调系统安装器安装
 * - Daemon 更新: 下载模块 zip 到 /sdcard/Android/Kin/,提示用户在 Magisk/KernelSU 里刷入并重启
 *
 * 版本比较: 服务器 version_code > 本地 version_code 即有更新。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private val mainHandler = Handler(Looper.getMainLooper())

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val changelog: String,
        val downloadUrl: String,
        val size: Long,
        val forceUpdate: Boolean,
    )

    data class CheckResult(
        val appUpdate: VersionInfo?,
        val daemonUpdate: VersionInfo?,
    ) {
        val hasUpdate: Boolean get() = appUpdate != null || daemonUpdate != null
    }

    /** 取完整下载 URL(相对路径拼 BASE_URL)。 */
    private fun fullUrl(u: String): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("/") -> License.BASE_URL + u
        else -> License.BASE_URL + "/" + u
    }

    /** 查询服务器版本信息,返回有更新的组件(没有更新的返回 null)。 */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(fullUrl("/api/version")).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return@runCatching CheckResult(null, null) }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val j = JSONObject(body)
            if (!j.optBoolean("success", false)) return@runCatching CheckResult(null, null)
            val data = j.optJSONObject("data") ?: return@runCatching CheckResult(null, null)

            val appVer = parsePart(data.optJSONObject("app"), BuildConfig.VERSION_CODE)
            val daemonVer = parsePart(data.optJSONObject("daemon"), localDaemonVersion())
            CheckResult(appVer, daemonVer)
        }.getOrElse {
            Log.w(TAG, "check failed", it)
            CheckResult(null, null)
        }
    }

    private fun parsePart(part: JSONObject?, localCode: Int): VersionInfo? {
        if (part == null) return null
        val vc = part.optInt("version_code", 0)
        if (vc <= localCode) return null
        return VersionInfo(
            versionCode = vc,
            versionName = part.optString("version_name", ""),
            changelog = part.optString("changelog", ""),
            downloadUrl = part.optString("download_url", ""),
            size = part.optLong("size", 0),
            forceUpdate = part.optBoolean("force_update", false),
        )
    }

    /** 本地 daemon 版本:从模块目录读 module.prop 的 versionCode;读不到返回 0(视为未装/未知)。 */
    private fun localDaemonVersion(): Int {
        val prop = RootShell.exec(
            "cat /data/adb/modules/Kin/module.prop 2>/dev/null || " +
            "cat /data/adb/ksu/modules/Kin/module.prop 2>/dev/null"
        )
        val m = Regex("""versionCode=(\d+)""").find(prop)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** 当前 daemon 是否安装(供 UI 显示状态)。 */
    fun daemonInstalled(): Boolean = RootShell.moduleInstalled()

    /** 人类可读的大小。 */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }

    // ── App 更新:下载 APK 并调安装器 ──

    /**
     * 用系统 DownloadManager 下载 APK,下载完成后自动弹出安装。
     * 返回 downloadId(可用于取消/查询进度)。progress(0-100) 周期性回调(主线程)。
     * 需 REQUEST_INSTALL_PACKAGES 权限(Android 8+):安装前会引导用户开启"允许安装未知来源"。
     */
    fun downloadAndInstallApk(
        context: Context,
        info: VersionInfo,
        onProgress: (Int) -> Unit = {},
        onDone: (Boolean, String) -> Unit = { _, _ -> },
    ): Long {
        val url = fullUrl(info.downloadUrl)
        val apkFileName = "Kin_v${info.versionName}.apk"

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Kin v${info.versionName} 更新")
            setDescription("正在下载 App 更新包…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, apkFileName)
            } else {
                @Suppress("DEPRECATION")
                setDestinationUri(Uri.fromFile(File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    apkFileName
                )))
            }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
        }
        val id = dm.enqueue(req)

        val appCtx = context.applicationContext
        val t = Thread({
            var lastProgress = -1
            val query = DownloadManager.Query().setFilterById(id)
            while (!Thread.interrupted()) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                val c: Cursor = dm.query(query) ?: break
                if (!c.moveToFirst()) { c.close(); break }
                val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val bytesIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                if (statusIdx < 0) { c.close(); break }
                val status = c.getInt(statusIdx)
                val dled = if (bytesIdx >= 0) c.getLong(bytesIdx) else 0L
                val total = if (totalIdx >= 0) c.getLong(totalIdx) else 0L
                val localUri = if (uriIdx >= 0) c.getString(uriIdx) else null
                c.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        mainHandler.post { onProgress(100) }
                        // 优先用 DownloadManager 提供的 content:// URI(兼容分区存储),
                        // 否则用 file:// 路径的 FileProvider
                        val apkUri = when {
                            !localUri.isNullOrBlank() -> Uri.parse(localUri)
                            else -> {
                                val f = File(
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                    apkFileName
                                )
                                FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
                            }
                        }
                        mainHandler.post { installApkFromUri(appCtx, apkUri) }
                        mainHandler.post { onDone(true, "") }
                        return@Thread
                    }
                    DownloadManager.STATUS_FAILED -> {
                        mainHandler.post { onDone(false, "下载失败,请检查网络后重试") }
                        return@Thread
                    }
                    DownloadManager.STATUS_RUNNING -> {
                        if (total > 0) {
                            val p = ((dled * 100) / total).toInt().coerceIn(0, 100)
                            if (p != lastProgress) {
                                lastProgress = p
                                mainHandler.post { onProgress(p) }
                            }
                        }
                    }
                    DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_PENDING -> { /* 继续轮询 */ }
                }
            }
        }, "apk-download-poll")
        t.isDaemon = true
        t.start()
        return id
    }

    /** 用给定 URI(可为 content:// 或 file://)调起 APK 安装器(必须主线程)。 */
    private fun installApkFromUri(context: Context, apkUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = context.packageManager.canRequestPackageInstalls()
            if (!canInstall) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    // ── Daemon 更新:下载模块 zip 到 /sdcard/Android/Kin/,提示用户刷入 ──

    /**
     * 下载 daemon 模块 zip 到 CTS_DIR/kin-module-v{version}.zip。
     * 进度回调在主线程;返回文件路径(成功)或 null(失败)。
     * 模块刷入需用户在 Magisk/KernelSU 里手动选择 zip(自动刷模块风险高,不做)。
     */
    suspend fun downloadDaemonZip(
        context: Context,
        info: VersionInfo,
        onProgress: (Int) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val url = fullUrl(info.downloadUrl)
        val fileName = "kin-module-v${info.versionName}.zip"
        // 先下载到 app cache(永远可写),下载完成后用 root 搬到 CTS_DIR(兼容分区存储)
        val tmpFile = File(context.cacheDir, fileName)
        if (tmpFile.exists()) tmpFile.delete()
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 0
            conn.connect()
            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { ins ->
                tmpFile.outputStream().use { os ->
                    val buf = ByteArray(8192)
                    var lastP = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val p = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (p != lastP) {
                                lastP = p
                                mainHandler.post { onProgress(p) }
                            }
                        }
                    }
                    os.flush()
                }
            }
            conn.disconnect()
            mainHandler.post { onProgress(100) }
            // 用 root 搬到 CTS_DIR(确保 daemon/Magisk 可访问)
            val ctsDir = RootShell.CTS_DIR
            val dstPath = "$ctsDir/$fileName"
            RootShell.execSilent("mkdir -p $ctsDir")
            RootShell.execSilent("cp ${shellQuote(tmpFile.absolutePath)} ${shellQuote(dstPath)}")
            RootShell.execSilent("chmod 0644 ${shellQuote(dstPath)}")
            tmpFile.delete()
            dstPath
        }.getOrElse {
            Log.w(TAG, "daemon download failed", it)
            tmpFile.delete()
            null
        }
    }

    /** 打开 Magisk/KernelSU 管理器(若能找到包名),否则打开下载目录。 */
    fun openModuleManager(context: Context) {
        val managers = listOf(
            "io.github.huskydg.magisk",
            "me.weishu.kernelsu",
            "io.github.a13e300.ksu",
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
        )
        for (pkg in managers) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(File(RootShell.CTS_DIR)), "resource/folder")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
