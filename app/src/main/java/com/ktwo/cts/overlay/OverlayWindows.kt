package com.ktwo.cts.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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

// 悬浮窗容器为 Metric 风格灰玻璃(中性浅灰半透明), 文本用深色墨。
// 命名沿用 WHITE 以缩小改动面 —— 它现在代表"浅灰玻璃底上的主文本色(深墨)"。
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

    // Metric 风格灰玻璃胶囊: 中性浅灰半透明底 + 大圆角, 描边仅留极弱的深色细线。
    protected fun cardBg(cornerDp: Int = 18) = GradientDrawable().apply {
        cornerRadius = px(cornerDp).toFloat()
        setColor(Color.argb(alpha, 228, 231, 236))
        setStroke(1, Color.argb(24, 20, 30, 45))
    }

    protected fun verticalCard(cornerDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
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

/**
 * 环形进度表(纯自绘): 浅色整圈轨道 + 彩色进度弧。
 * 环心/环下文字不在这里画, 由外层 FrameLayout 叠 TextView 实现。
 */
class RingView(ctx: Context, strokePx: Float) : View(ctx) {
    private var pct = 0
    private val inset = strokePx / 2f + 1f
    private val bounds = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = Color.argb(60, 20, 30, 45)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = GREEN
    }

    /** 更新进度与弧色并重绘。pct 会被夹到 0..100。 */
    fun set(pct: Int, arcColor: Int) {
        this.pct = pct.coerceIn(0, 100)
        arcPaint.color = arcColor
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        if (pct > 0) canvas.drawArc(bounds, -90f, pct / 100f * 360f, false, arcPaint)
    }
}

/**
 * 负载监视器: 横向灰玻璃胶囊, 内含三个环形表(CPU / GPU / 电量)。
 * 环 = 进度弧, 环心 = 主标签, 环下 = 频率/温度小字 —— 对齐 Metric 观感。
 */
class LoadOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {

    /** 一列环形表的视图引用: 竖直列容器 + 环 + 环心字 + 环下字。 */
    private class RingColumn(
        val col: LinearLayout,
        val ring: RingView,
        val center: TextView,
        val below: TextView,
    )

    private val ringSize = px(46)

    private fun ringColumn(centerText: String, belowText: String): RingColumn {
        val ring = RingView(ctx, px(4).toFloat())
        val center = row((textSp - 3f).coerceAtLeast(8f)).apply {
            text = centerText
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val frame = FrameLayout(ctx).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize))
            addView(center, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
        }
        val below = row((textSp - 4f).coerceAtLeast(8f), GREY).apply {
            text = belowText
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(frame, LinearLayout.LayoutParams(ringSize, ringSize))
            addView(below, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(3) })
        }
        return RingColumn(col, ring, center, below)
    }

    private val cpuCol = ringColumn("CPU", "--")
    private val gpuCol = ringColumn("GPU", "--")
    private val battCol = ringColumn("--", "--")

    private val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14), px(9), px(14), px(9))
        background = cardBg(18)
        addView(cpuCol.col)
        addView(gpuCol.col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = px(12); marginEnd = px(12)
        })
        addView(battCol.col)
    }
    override val root: View = pill

    // 三环弧色对齐 Metric: 按指标固定色(CPU 恒绿 / GPU 恒橙 / 电量恒绿), 不随负载值变色。
    override fun onTick(t: TickData) {
        val s = t.snap

        val cpu = s?.cpuLoadPct ?: -1
        cpuCol.ring.set(cpu.coerceAtLeast(0), GREEN)
        val topMhz = s?.clusters?.maxOfOrNull { it.mhz } ?: -1
        cpuCol.below.text = if (topMhz > 0) "${topMhz}MHz" else "--"

        val gpu = s?.gpuLoadPct ?: -1
        gpuCol.ring.set(gpu.coerceAtLeast(0), ORANGE)
        val gpuMhz = s?.gpuMhz ?: -1
        gpuCol.below.text = if (gpuMhz > 0) "${gpuMhz}MHz" else "--"

        val batt = s?.battPct ?: -1
        battCol.ring.set(batt.coerceAtLeast(0), GREEN)
        battCol.center.text = if (batt >= 0) "$batt%" else "--"
        val temp = s?.tempC ?: -999
        battCol.below.text = if (temp > -100) "$temp℃" else "--"
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
