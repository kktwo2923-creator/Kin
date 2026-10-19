package com.ktwo.cts.updater

import android.util.Log
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BetaReporter {
    private const val TAG = "BetaReporter"
    data class Result(val ok: Boolean, val message: String)

    suspend fun uploadValidationReport(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val report = RootShell.runDeviceValidation()
            if (report.isBlank() || report.startsWith("validation=unavailable"))
                return@withContext Result(false, "尚未生成真机验证报告，请确认守护已运行")
            val conn = (URL("${com.ktwo.cts.license.License.BASE_URL}/api/beta/report")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 10000; requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Kin-Report-Version", "1")
            }
            val body = JSONObject().put("report", report).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            (if (code in 200..299) Result(true, "验证报告已上传")
             else Result(false, "服务器返回 HTTP $code")).also { conn.disconnect() }
        }.getOrElse {
            Log.w(TAG, "validation report upload failed", it)
            Result(false, "上传失败，请稍后重试")
        }
    }
}
