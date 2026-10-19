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
import com.ktwo.cts.root.RootShell
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App / Kin(Kinetic 二进制) / 模块(Magisk zip) 三通道版本检查 + 下载 + 安装。
 *
 * 服务端 /api/version 返回结构:
 * ```
 * { code, success, data: {
 *     app:    {version_code, version_name, changelog, download_url, size, force_update},  // APK
 *     daemon: {...同结构...},   // Kinetic 二进制, 热更新免重启
 *     module: {...同结构...}    // Magisk/KernelSU 模块 zip, 需刷入并重启
 * } }
 * ```
 * - App 更新:  下载 APK 到外部下载目录,通过 FileProvider 调系统安装器安装
 * - Kin 更新:  下载 Kinetic 到 cache, root cp 覆盖到模块目录, 杀旧守护让 keeper 拉起新版(免重启)
 * - 模块 更新: 下载模块 zip 到 /sdcard/Android/Kin/,提示用户在 Magisk/KernelSU 里刷入并重启
 *
 * 版本比较: 服务器 version_code > 本地 version_code 即有更新。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private val mainHandler = Handler(Looper.getMainLooper())

    // APP 更新服务器(独立于守护授权服务器 License.BASE_URL), 由构建参数 ctsUpdateBase 注入。
    // /api/version 返回 app/daemon/module 三通道版本信息,
    // 下载地址(download_url)为绝对路径时, fullUrl() 会原样透传。
    private val UPDATE_BASE_URL: String = com.ktwo.cts.BuildConfig.UPDATE_BASE_URL

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val changelog: String,
        val downloadUrl: String,
        val size: Long,
        val forceUpdate: Boolean,
    )

    data class CheckResult(
        val appUpdate: VersionInfo?,            // data.app    → APK 安装
        val kinUpdate: VersionInfo? = null,     // data.daemon → Kinetic 二进制热更新(cp 覆盖 + 重启守护)
        val moduleUpdate: VersionInfo? = null,  // data.module → Magisk/KernelSU 模块 zip(用户刷入)
    ) {
        val hasUpdate: Boolean
            get() = appUpdate != null || kinUpdate != null || moduleUpdate != null
    }

    /** 取完整下载 URL(相对路径拼 UPDATE_BASE_URL;绝对路径原样返回)。 */
    private fun fullUrl(u: String): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("/") -> UPDATE_BASE_URL + u
        else -> UPDATE_BASE_URL + "/" + u
    }

    /** 查询服务器版本信息,返回有更新的组件(没有更新的返回 null)。 */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(fullUrl("/api/version")).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return@runCatching CheckResult(null, null, null) }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val j = JSONObject(body)
            if (!j.optBoolean("success", false)) return@runCatching CheckResult(null, null, null)
            val data = j.optJSONObject("data") ?: return@runCatching CheckResult(null, null, null)

            // 三通道对应(服务端已如此配置):
            //   App    → data.app    → APK 安装
            //   Kin    → data.daemon → Kinetic 二进制热更新(本地版本读 binary.version)
            //   模块   → data.module → Magisk/KernelSU 模块 zip(本地版本读 module.prop)
            val appVer    = parsePart(data.optJSONObject("app"),    BuildConfig.VERSION_CODE)
            val kinVer    = parsePart(data.optJSONObject("daemon"), localBinaryVersion())
            val moduleVer = parsePart(data.optJSONObject("module"), localDaemonVersion())
            CheckResult(appVer, kinVer, moduleVer)
        }.getOrElse {
            Log.w(TAG, "check failed", it)
            CheckResult(null, null, null)
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

    /**
     * 本地 Kinetic 二进制版本: 从模块目录读 binary.version(由 customize.sh 写入,
     * 内容即 module.prop 的 versionCode)。读不到 → 退回 module.prop 的 versionCode;
     * 再读不到 → 0(未装/未知)。
     *
     * binary.version 与 module.prop 解耦: 模块刷入后二进制还能被 App 热更新多次,
     * 此时 module.prop 不变, 但 binary.version 会随每次热更新写入新值 ——
     * App 据此判断是否需要再次推送二进制。
     */
    private fun localBinaryVersion(): Int {
        val raw = RootShell.exec(
            "cat /data/adb/modules/Kin/binary.version 2>/dev/null || " +
            "cat /data/adb/ksu/modules/Kin/binary.version 2>/dev/null"
        ).trim()
        raw.toIntOrNull()?.let { if (it > 0) return it }
        // 兜底: 旧模块还没写 binary.version, 退回 module.prop 的 versionCode
        return localDaemonVersion()
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
                        val uriResult = runCatching {
                            dm.getUriForDownloadedFile(id)
                                ?: localUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                                ?: error("Downloaded APK URI is unavailable")
                        }
                        mainHandler.post {
                            val result = uriResult.mapCatching { installApkFromUri(appCtx, it) }
                            result.fold(
                                onSuccess = { onDone(true, "") },
                                onFailure = {
                                    Log.w(TAG, "APK installer launch failed", it)
                                    onDone(false, "安装器无法打开，请从下载目录手动安装 APK")
                                },
                            )
                        }
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
        val installUri = when (apkUri.scheme) {
            "content" -> apkUri
            "file" -> FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(requireNotNull(apkUri.path))
            )
            else -> error("Unsupported APK URI scheme")
        }
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
            setDataAndType(installUri, "application/vnd.android.package-archive")
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

    // ── Binary(Kinetic) 热更新: 下载二进制 → root cp 覆盖模块目录 → 杀旧守护让 keeper 拉起新版 ──

    /**
     * 下载 Kinetic 二进制并热更新到运行中的模块目录,无需重启手机。
     *
     * 流程:
     *  1. 下载到 app cache(永远可写),进度回调在主线程;
     *  2. 用 root 覆盖到 /data/adb/modules/Kin/Kinetic 与 /data/adb/ksu/modules/Kin/Kinetic(都尝试);
     *  3. 同步写入 binary.version 到上述目录(便于下次再判断是否需要更新);
     *  4. 杀旧 pidof Kinetic, 模块自带的 keeper(service.sh) 会在数秒内拉起新版二进制。
     *
     * @return Pair(是否成功, 错误信息/提示)。成功时第二项为简要状态文案。
     */
    suspend fun downloadAndInstallBinary(
        context: Context,
        info: VersionInfo,
        onProgress: (Int) -> Unit = {},
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val url = fullUrl(info.downloadUrl)
        val tmpFile = File(context.cacheDir, "Kinetic.new")
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

            if (!tmpFile.exists() || tmpFile.length() == 0L) {
                return@runCatching Pair(false, "下载失败: 文件为空")
            }

            // root 部署: 覆盖 Kinetic + 写 binary.version + 杀旧守护。
            // 与 customize.sh 热更新同款逻辑: 先 chattr -i 解锁, cp 覆盖, chmod 0755,
            // 再 echo 写版本号, 最后 kill 旧 pid, keeper 下一拍拉起新版。
            val src = tmpFile.absolutePath
            val newVer = info.versionCode.toString()
            val sb = StringBuilder()
            sb.append("SRC=").append(shellQuote(src)).append("; ")
            sb.append("VER=").append(newVer).append("; ")
            sb.append("for D in /data/adb/modules/Kin /data/adb/ksu/modules/Kin; do ")
            sb.append("  [ -d \"\$D\" ] || continue; ")
            sb.append("  command -v chattr >/dev/null 2>&1 && chattr -i \"\$D/Kinetic\" 2>/dev/null; ")
            sb.append("  cp -f \"\$SRC\" \"\$D/Kinetic\" && chmod 0755 \"\$D/Kinetic\"; ")
            sb.append("  echo \"\$VER\" > \"\$D/binary.version\" && chmod 0644 \"\$D/binary.version\"; ")
            sb.append("done; ")
            // 杀旧守护(避免 ETXTBSY; keeper 会在数秒内拉起新版)。stop oiface 也先恢复一下,
            // 防 oiface 也被一起 kill 后起不来(参考风驰保活策略)。
            sb.append("OLD=\$(pidof Kinetic 2>/dev/null); ")
            sb.append("[ -n \"\$OLD\" ] && kill \$OLD 2>/dev/null; ")
            sb.append("start oiface >/dev/null 2>&1; ")
            sb.append("echo done")
            val out = RootShell.exec(sb.toString())
            tmpFile.delete()

            if (!out.contains("done")) {
                Pair(false, "root 部署失败(root 未授权或模块目录不可写)")
            } else {
                Pair(true, "已热更新到模块目录, 守护将在数秒内自动重启")
            }
        }.getOrElse {
            Log.w(TAG, "binary download failed", it)
            tmpFile.delete()
            Pair(false, "下载失败: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    /** 三通道本地版本号(供 UI 在卡片上显示"当前版本")。 */
    data class LocalVersions(
        val appCode: Int,
        val binaryCode: Int,
        val moduleCode: Int,
    )

    /** 读取三通道本地版本号(APP 用 BuildConfig, 二进制/模块走 root 读模块目录)。 */
    suspend fun readLocalVersions(): LocalVersions = withContext(Dispatchers.IO) {
        LocalVersions(
            appCode = BuildConfig.VERSION_CODE,
            binaryCode = localBinaryVersion(),
            moduleCode = localDaemonVersion(),
        )
    }

    /**
     * 打开设备上的 Root 管理器, 供用户手动刷入模块 zip。
     *
     * root 生态已不止 Magisk: KernelSU / KSU-Next / SukiSU-Ultra / APatch 都是常见方案,
     * 只穷举 Magisk 系包名会在这些机型上直接落空。查找顺序:
     *   ① 已知包名穷举(同步, 命中即走)
     *   ② root 下按关键字扫已装包名兜底(异步, 覆盖列表外的新管理器)
     *   ③ 都找不到则打开模块 zip 所在目录, 让用户自己拖进管理器
     */
    fun openModuleManager(context: Context) {
        // ① 主流管理器已知包名(官方在前, 变体在后)
        val known = listOf(
            "com.topjohnwu.magisk",      // Magisk 官方
            "io.github.huskydg.magisk",  // Magisk Alpha
            "io.github.vvb2060.magisk",  // Magisk Delta
            "me.weishu.kernelsu",        // KernelSU(含 MKSU 等同包名分支)
            "com.rifsxd.ksunext",        // KernelSU Next
            "io.github.a13e300.ksu",     // KernelSU 变体
            "com.sukisu.ultra",          // SukiSU-Ultra
            "me.bmax.apatch",            // APatch
            "me.bmax.apatch.debug",      // APatch 调试版
        )
        for (pkg in known) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startRootManager(context, intent)
                return
            }
        }
        // ② 列表外的管理器: root 下扫包名, 取第一个能启动的。
        //    走异步 submit, 避免在 UI 线程上等 root shell。
        val scanCmd =
            "pm list packages -e | cut -d: -f2 | grep -Ei 'magisk|kernelsu|ksunext|sukisu|apatch|ksu'"
        runCatching {
            Shell.cmd(scanCmd).submit { r ->
                val hit = r.out.asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != context.packageName }
                    .mapNotNull { pkg -> context.packageManager.getLaunchIntentForPackage(pkg) }
                    .firstOrNull()
                Handler(Looper.getMainLooper()).post {
                    if (hit != null) startRootManager(context, hit) else openDownloadsDir(context)
                }
            }
        }.onFailure { openDownloadsDir(context) }
    }

    private fun startRootManager(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { openDownloadsDir(context) }
    }

    /** 兜底: 打开模块 zip 所在目录(没有任何管理器可拉起时)。 */
    private fun openDownloadsDir(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.fromFile(File(RootShell.CTS_DIR)), "resource/folder")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
