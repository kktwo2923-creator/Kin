// 帧率记录数据模型与存储: JSON 文件落盘(org.json, 不引 Room), 存到 filesDir/fps_records/。
package com.ktwo.cts.data.fpsrecord

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 每秒一个采样点。取不到的字段保持哨兵值(-1/-999/0.0), UI 层显示 "--", 不造假。 */
data class FpsPoint(
    val t: Long,           // 采样时刻(epoch ms)
    val fps: Int,          // 实测帧率(-1 未知)
    val cpuLoad: Int,      // CPU 占用 %(-1 未知)
    val gpuLoad: Int,      // GPU 占用 %(-1 未知)
    val gpuMhz: Int,       // GPU 频率 MHz(-1 未知)
    val tempC: Int,        // 最高温度 ℃(-999 未知)
    val battPct: Int,      // 电量 %(-1 未知)
    val watt: Double,      // 整机功耗 W(0 未知)
    val frameMaxMs: Int,   // 本秒最大单帧耗时 ms(0=不可用)
    val jank: Int,         // 本秒卡顿次数(帧时间不可用时恒 0)
    val bigJank: Int,      // 本秒严重卡顿次数
)

/**
 * 单条线程在整个录制会话中的负载汇总(M3 线程负载详情)。
 *  - loadPoints/corePoints: 录制期每 2s 一个的采样序列(落盘前限长, 最多 120 点, 两者等长);
 *  - dominantCore: corePoints 众数(该线程主要运行的核心), dominantCorePct 为众数占比 %;
 *  - migrationCount: corePoints 相邻两点核心不同的次数(核间迁移);
 *  - affinityChanges: Cpus_allowed_list 在录制期发生变化的次数。
 */
data class ThreadLoad(
    val tid: Int,
    val name: String,
    val avgLoad: Float,          // 平均负载 %(单核口径, 0..100)
    val maxLoad: Float,          // 峰值负载 %
    val dominantCore: Int,       // 主要运行核心(-1 未知)
    val dominantCorePct: Int,    // 主核占比 %(0..100)
    val migrationCount: Int,     // 核间迁移次数
    val affinityChanges: Int,    // 亲和(Cpus_allowed_list)变更次数
    val loadPoints: List<Float>, // 负载采样序列(限长)
    val corePoints: List<Int>,   // 核心采样序列(限长, 与 loadPoints 等长)
)

/** 一次完整录制会话(含全部采样点)。 */
data class FpsSession(
    val id: String,
    val pkg: String,
    val appName: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val avgFps: Double,
    val minFps: Int,
    val maxFps: Int,
    val low1Fps: Int,      // 1% Low: 最低 1% 秒级帧率的均值(剔除 fps<=0 的无效秒)
    val low5Fps: Int,      // 5% Low
    val avgWatt: Double,
    val jankTotal: Int,
    val bigJankTotal: Int,
    val stopReason: String,
    val points: List<FpsPoint>,
    // M3: 线程负载详情。尾部追加且带默认值 → 旧存档/旧调用零破坏(读不到即空)。
    val threads: List<ThreadLoad> = emptyList(),
)

/** 列表页用的会话摘要(不载入 points, 单独存 <id>.meta.json)。 */
data class FpsSessionMeta(
    val id: String,
    val pkg: String,
    val appName: String,
    val startMs: Long,
    val durationMs: Long,
    val avgFps: Double,
    val minFps: Int,
    val maxFps: Int,
    val low1Fps: Int,
    val low5Fps: Int,
    val avgWatt: Double,
    val jankTotal: Int,
    val bigJankTotal: Int,
    val stopReason: String,
)

/**
 * 会话文件存储:
 *  - <id>.json      全量(含 points, 点存为紧凑数组 [t,fps,cpu,gpu,gpuMhz,temp,batt,watt,frameMax,jank,bigJank])
 *  - <id>.meta.json 摘要(列表页只读它, 避免载入大文件)
 * 所有读写 runCatching, 失败返回空/false, 永不抛出。
 */
object FpsRecordStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "fps_records").apply { if (!exists()) mkdirs() }

    private fun putMetaFields(o: JSONObject, s: FpsSession) {
        o.put("id", s.id)
        o.put("pkg", s.pkg)
        o.put("appName", s.appName)
        o.put("startMs", s.startMs)
        o.put("endMs", s.endMs)
        o.put("durationMs", s.durationMs)
        o.put("avgFps", if (s.avgFps.isFinite()) s.avgFps else 0.0)
        o.put("minFps", s.minFps)
        o.put("maxFps", s.maxFps)
        o.put("low1Fps", s.low1Fps)
        o.put("low5Fps", s.low5Fps)
        o.put("avgWatt", if (s.avgWatt.isFinite()) s.avgWatt else 0.0)
        o.put("jankTotal", s.jankTotal)
        o.put("bigJankTotal", s.bigJankTotal)
        o.put("stopReason", s.stopReason)
    }

    private fun metaFromJson(o: JSONObject, fallbackId: String): FpsSessionMeta = FpsSessionMeta(
        id = o.optString("id", fallbackId),
        pkg = o.optString("pkg", ""),
        appName = o.optString("appName", ""),
        startMs = o.optLong("startMs", 0L),
        durationMs = o.optLong("durationMs", 0L),
        avgFps = o.optDouble("avgFps", 0.0),
        minFps = o.optInt("minFps", 0),
        maxFps = o.optInt("maxFps", 0),
        low1Fps = o.optInt("low1Fps", 0),
        low5Fps = o.optInt("low5Fps", 0),
        avgWatt = o.optDouble("avgWatt", 0.0),
        jankTotal = o.optInt("jankTotal", 0),
        bigJankTotal = o.optInt("bigJankTotal", 0),
        stopReason = o.optString("stopReason", ""),
    )

    /** 保存会话(全量 + 摘要两个文件)。失败返回 false。 */
    fun save(ctx: Context, s: FpsSession): Boolean = runCatching {
        val d = dir(ctx)
        val full = JSONObject().also { putMetaFields(it, s) }
        val arr = JSONArray()
        for (p in s.points) {
            arr.put(JSONArray().apply {
                put(p.t); put(p.fps); put(p.cpuLoad); put(p.gpuLoad); put(p.gpuMhz)
                put(p.tempC); put(p.battPct)
                put(if (p.watt.isFinite()) p.watt else 0.0)
                put(p.frameMaxMs); put(p.jank); put(p.bigJank)
            })
        }
        full.put("points", arr)
        // M3: 线程负载(可选字段, 空则不写; 旧版本读到该字段会自动忽略)
        if (s.threads.isNotEmpty()) {
            val th = JSONArray()
            for (t in s.threads) {
                th.put(JSONObject().apply {
                    put("tid", t.tid)
                    put("name", t.name)
                    put("avgLoad", if (t.avgLoad.isFinite()) t.avgLoad.toDouble() else 0.0)
                    put("maxLoad", if (t.maxLoad.isFinite()) t.maxLoad.toDouble() else 0.0)
                    put("dominantCore", t.dominantCore)
                    put("dominantCorePct", t.dominantCorePct)
                    put("migrationCount", t.migrationCount)
                    put("affinityChanges", t.affinityChanges)
                    put("loadPoints", JSONArray().apply {
                        t.loadPoints.forEach { put(if (it.isFinite()) it.toDouble() else 0.0) }
                    })
                    put("corePoints", JSONArray().apply { t.corePoints.forEach { put(it) } })
                })
            }
            full.put("threads", th)
        }
        File(d, "${s.id}.json").writeText(full.toString())
        File(d, "${s.id}.meta.json").writeText(JSONObject().also { putMetaFields(it, s) }.toString())
        true
    }.getOrDefault(false)

    /** 列出所有会话摘要, 按开始时间倒序。优先读 meta 文件; 缺失时从全量文件取头部字段。 */
    fun list(ctx: Context): List<FpsSessionMeta> = runCatching {
        val d = dir(ctx)
        val files = d.listFiles().orEmpty()
        val ids = files
            .filter { it.name.endsWith(".json") && !it.name.endsWith(".meta.json") }
            .map { it.name.removeSuffix(".json") }
        ids.mapNotNull { id ->
            runCatching {
                val metaFile = File(d, "$id.meta.json")
                val text = if (metaFile.exists()) metaFile.readText()
                           else File(d, "$id.json").readText()
                metaFromJson(JSONObject(text), id)
            }.getOrNull()
        }.sortedByDescending { it.startMs }
    }.getOrDefault(emptyList())

    /** 载入完整会话(含 points); 不存在/损坏返回 null。 */
    fun load(ctx: Context, id: String): FpsSession? = runCatching {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return@runCatching null
        val o = JSONObject(f.readText())
        val meta = metaFromJson(o, id)
        val pts = ArrayList<FpsPoint>()
        val arr = o.optJSONArray("points") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            if (a.length() < 11) continue
            pts.add(
                FpsPoint(
                    t = a.optLong(0),
                    fps = a.optInt(1, -1),
                    cpuLoad = a.optInt(2, -1),
                    gpuLoad = a.optInt(3, -1),
                    gpuMhz = a.optInt(4, -1),
                    tempC = a.optInt(5, -999),
                    battPct = a.optInt(6, -1),
                    watt = a.optDouble(7, 0.0),
                    frameMaxMs = a.optInt(8, 0),
                    jank = a.optInt(9, 0),
                    bigJank = a.optInt(10, 0),
                )
            )
        }
        // M3: 线程负载(旧存档无此字段 → 空列表; 单条损坏跳过, 不影响整体)
        val threads = ArrayList<ThreadLoad>()
        val tArr = o.optJSONArray("threads") ?: JSONArray()
        for (i in 0 until tArr.length()) {
            val t = tArr.optJSONObject(i) ?: continue
            runCatching {
                val lp = ArrayList<Float>()
                t.optJSONArray("loadPoints")?.let { a ->
                    for (j in 0 until a.length()) lp.add(a.optDouble(j, 0.0).toFloat())
                }
                val cp = ArrayList<Int>()
                t.optJSONArray("corePoints")?.let { a ->
                    for (j in 0 until a.length()) cp.add(a.optInt(j, -1))
                }
                threads.add(
                    ThreadLoad(
                        tid = t.optInt("tid", -1),
                        name = t.optString("name", ""),
                        avgLoad = t.optDouble("avgLoad", 0.0).toFloat(),
                        maxLoad = t.optDouble("maxLoad", 0.0).toFloat(),
                        dominantCore = t.optInt("dominantCore", -1),
                        dominantCorePct = t.optInt("dominantCorePct", 0),
                        migrationCount = t.optInt("migrationCount", 0),
                        affinityChanges = t.optInt("affinityChanges", 0),
                        loadPoints = lp,
                        corePoints = cp,
                    )
                )
            }
        }
        FpsSession(
            id = meta.id, pkg = meta.pkg, appName = meta.appName,
            startMs = meta.startMs, endMs = o.optLong("endMs", 0L), durationMs = meta.durationMs,
            avgFps = meta.avgFps, minFps = meta.minFps, maxFps = meta.maxFps,
            low1Fps = meta.low1Fps, low5Fps = meta.low5Fps,
            avgWatt = meta.avgWatt, jankTotal = meta.jankTotal, bigJankTotal = meta.bigJankTotal,
            stopReason = meta.stopReason, points = pts, threads = threads,
        )
    }.getOrNull()

    /** 删除会话(全量 + 摘要)。 */
    fun delete(ctx: Context, id: String): Boolean = runCatching {
        val d = dir(ctx)
        val a = File(d, "$id.json").let { !it.exists() || it.delete() }
        val b = File(d, "$id.meta.json").let { !it.exists() || it.delete() }
        a && b
    }.getOrDefault(false)
}
