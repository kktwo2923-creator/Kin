package com.ktwo.cts.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.ProcSampler

/** 一帧喂给所有悬浮窗的数据。 */
data class TickData(
    val snap: MetricReader.Snapshot?,
    val battTempC: Double,
    val procs: List<ProcSampler.Proc>?,
    val threads: List<ProcSampler.Thr>?,
)

// 悬浮窗改为液态玻璃(浅色磨砂 + 窗口背景模糊)后, 文本用深色墨。
// 命名沿用 WHITE 以缩小改动面 —— 它现在代表"浅玻璃底上的主文本色(深墨)"。
private val WHITE = Color.parseColor("#141A24")
private val GREY = Color.parseColor("#5F6B7A")
private val ORANGE = Color.parseColor("#E8760F")
private val RED = Color.parseColor("#E5484D")
private val GREEN = Color.parseColor("#2FA65A")

/** 悬浮窗抽象基类: 提供近黑玻璃卡背景、monospace 文本行等公共构件。 */
abstract class OverlayWindow(
    protected val ctx: Context,
    protected val accent: Int,
    protected val alpha: Int,       // 0..255
    protected val textSp: Float,
) {
    protected val dp = ctx.resources.displayMetrics.density
    protected fun px(v: Int) = (v * dp).toInt()

    abstract val root: View
    abstract fun onTick(t: TickData)

    // 液态玻璃卡: 磨砂浅玻璃底 + 细描边(配合窗口 blurBehind 呈现真实玻璃质感)。
    protected fun cardBg(cornerDp: Int = 14) = GradientDrawable().apply {
        cornerRadius = px(cornerDp).toFloat()
        setColor(Color.argb(alpha, 246, 248, 251))
        setStroke(px(1), Color.argb(110, 255, 255, 255))
    }

    protected fun verticalCard(cornerDp: Int = 12): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(10), px(7), px(10), px(7))
        background = cardBg(cornerDp)
    }

    protected fun row(sizeSp: Float = textSp, color: Int = WHITE): TextView = TextView(ctx).apply {
        setTextColor(color)
        textSize = sizeSp
        typeface = Typeface.MONOSPACE
        includeFontPadding = false
        setLineSpacing(px(2).toFloat(), 1f)
    }

    protected fun title(text: String): TextView = row(textSp, accent).apply {
        this.text = text
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    protected fun pctColor(p: Int): Int = when {
        p < 0 -> WHITE
        p < 60 -> WHITE
        p < 85 -> ORANGE
        else -> RED
    }
}

/** 负载监视器: CPU 总占用 + 每簇频率 + GPU。 */
class LoadOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = verticalCard()
    private val tHead = title("负载监视器")
    private val tCpu = row()
    private val tGpu = row()
    private val clusterRows = ArrayList<TextView>()
    override val root: View = card
    init {
        card.addView(tHead); card.addView(tCpu)
        repeat(4) { val r = row(); r.visibility = View.GONE; clusterRows.add(r); card.addView(r) }
        card.addView(tGpu)
    }
    override fun onTick(t: TickData) {
        val s = t.snap
        val cpu = s?.cpuLoadPct ?: -1
        tCpu.text = "CPU " + (if (cpu >= 0) "$cpu%" else "--")
        tCpu.setTextColor(pctColor(cpu))
        val cs = s?.clusters ?: emptyList()
        clusterRows.forEachIndexed { i, tv ->
            val c = cs.getOrNull(i)
            if (c == null) { tv.visibility = View.GONE } else {
                tv.visibility = View.VISIBLE
                tv.text = String.format("%-4s %5dMHz", c.name, c.mhz.coerceAtLeast(0))
            }
        }
        val g = s?.gpuLoadPct ?: -1
        val gm = s?.gpuMhz ?: -1
        tGpu.text = "GPU " + (if (g >= 0) "$g% " else "-- ") + (if (gm > 0) "${gm}MHz" else "")
        tGpu.setTextColor(pctColor(g))
    }
}

/** 迷你监视器: 单行药丸 fps · CPU% · 温度。 */
class MiniOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(12), px(6), px(12), px(6))
        background = cardBg(18)
    }
    private val tv = row()
    override val root: View = pill
    init {
        pill.addView(tv)
        pill.post { (pill.background as? GradientDrawable)?.cornerRadius = pill.height / 2f }
    }
    override fun onTick(t: TickData) {
        val s = t.snap
        val fps = s?.fps ?: -1
        val cpu = s?.cpuLoadPct ?: -1
        val temp = s?.tempC ?: -999
        tv.text = (if (fps >= 0) "${fps}fps" else "--fps") + " · " +
            (if (cpu >= 0) "$cpu%" else "--") + " · " +
            (if (temp > -100) "$temp℃" else "--")
    }
}

/** 帧率记录器: 当前 FPS + min/avg/max + 折线。 */
class FpsOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = verticalCard()
    private val tHead = title("帧率记录器")
    private val tCur = row(textSp + 6f, accent).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val tStat = row(textSp - 2f, GREY)
    private val graph = FpsGraphView(ctx, accent).apply {
        layoutParams = LinearLayout.LayoutParams(px(120), px(36))
    }
    private var mn = Int.MAX_VALUE; private var mx = 0; private var sum = 0L; private var cnt = 0L
    override val root: View = card
    init { card.addView(tHead); card.addView(tCur); card.addView(tStat); card.addView(graph) }
    override fun onTick(t: TickData) {
        val fps = t.snap?.fps ?: -1
        tCur.text = if (fps >= 0) "$fps FPS" else "-- FPS"
        tCur.setTextColor(if (fps in 0..44) RED else accent)
        if (fps >= 0) {
            mn = minOf(mn, fps); mx = maxOf(mx, fps); sum += fps; cnt++
            val avg = if (cnt > 0) (sum / cnt).toInt() else 0
            tStat.text = "min ${if (mn == Int.MAX_VALUE) "--" else mn} · avg $avg · max $mx"
            graph.push(fps)
        }
    }
}

/** FPS 折线自绘 View。 */
class FpsGraphView(ctx: Context, lineColor: Int) : View(ctx) {
    private val data = ArrayDeque<Int>()
    private val cap = 60
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor; style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    fun push(v: Int) { if (data.size >= cap) data.removeFirst(); data.addLast(v); invalidate() }
    override fun onDraw(canvas: Canvas) {
        if (data.size < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        val maxV = maxOf(120, data.maxOrNull() ?: 120).toFloat()
        val stepX = w / (cap - 1)
        path.reset()
        var started = false
        val start = cap - data.size
        data.forEachIndexed { i, v ->
            val x = (start + i) * stepX
            val y = h - (v.coerceAtLeast(0) / maxV) * h
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}

/** 温度监视器: SoC 最高温 + 电池温。 */
class TempOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = verticalCard()
    private val tHead = title("温度监视器")
    private val tBig = row(textSp + 8f).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val tBatt = row(textSp - 2f, GREY)
    override val root: View = card
    init { card.addView(tHead); card.addView(tBig); card.addView(tBatt) }
    override fun onTick(t: TickData) {
        val soc = t.snap?.tempC ?: -999
        tBig.text = if (soc > -100) "$soc ℃" else "-- ℃"
        tBig.setTextColor(when { soc <= -100 -> WHITE; soc < 40 -> GREEN; soc <= 45 -> ORANGE; else -> RED })
        tBatt.text = if (t.battTempC > -100) String.format("电池 %.1f℃", t.battTempC) else "电池 --"
    }
}

/** 进程监视器: TOP 进程列表。 */
class ProcessOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = verticalCard()
    private val tHead = title("进程 TOP8")
    private val lines = ArrayList<TextView>()
    override val root: View = card
    init {
        card.addView(tHead)
        repeat(8) { val r = row(textSp - 1f); lines.add(r); card.addView(r) }
    }
    override fun onTick(t: TickData) {
        val ps = t.procs ?: return   // null=本拍未采, 保持上次
        if (ps.isEmpty()) {
            lines.forEachIndexed { i, tv -> tv.text = if (i == 0) "需要 Root / 采样中…" else "" }
            return
        }
        lines.forEachIndexed { i, tv ->
            val p = ps.getOrNull(i)
            if (p == null) { tv.text = "" } else {
                tv.text = String.format("%-14.14s %5.1f%%", p.name, p.cpuPct)
                tv.setTextColor(if (p.cpuPct > 50f) ORANGE else WHITE)
            }
        }
    }
}

/** 线程监视器: TOP 线程列表。 */
class ThreadOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = verticalCard()
    private val tHead = title("线程 TOP8")
    private val lines = ArrayList<TextView>()
    override val root: View = card
    init {
        card.addView(tHead)
        repeat(8) { val r = row(textSp - 1f); lines.add(r); card.addView(r) }
    }
    override fun onTick(t: TickData) {
        val ts = t.threads ?: return
        if (ts.isEmpty()) {
            lines.forEachIndexed { i, tv -> tv.text = if (i == 0) "需要 Root / 采样中…" else "" }
            return
        }
        lines.forEachIndexed { i, tv ->
            val th = ts.getOrNull(i)
            if (th == null) { tv.text = "" } else {
                tv.text = String.format("%-12.12s %5.1f%% %s", th.name, th.cpuPct, th.state)
                tv.setTextColor(if (th.state == 'R') GREEN else WHITE)
            }
        }
    }
}
