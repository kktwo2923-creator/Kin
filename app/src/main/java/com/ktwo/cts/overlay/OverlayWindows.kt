package com.ktwo.cts.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.ProcSampler

/** 一帧喂给所有悬浮窗的数据。fg* 为前台应用信息(线程窗表头用), 取不到给默认值。 */
data class TickData(
    val snap: MetricReader.Snapshot?,
    val battTempC: Double,
    val procs: List<ProcSampler.Proc>?,
    val threads: List<ProcSampler.Thr>?,
    val fgPkg: String = "",
    val fgLabel: String = "",
    val fgPid: Int = -1,
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

    /** 关闭请求回调: 由服务注入(点击窗内 ✕ 时调用, 停掉该类型窗并持久化关闭)。 */
    var onCloseRequest: (() -> Unit)? = null

    /** 整窗单击(服务在拖动阈值内的 ACTION_UP 回调)。默认不响应, 子类按需覆写。 */
    open fun onTap() {}

    // Metric 风格灰玻璃胶囊: 中性浅灰半透明底 + 大圆角(~20dp), 描边仅留极弱的深色细线。
    protected fun cardBg(cornerDp: Int = 20) = GradientDrawable().apply {
        cornerRadius = px(cornerDp).toFloat()
        setColor(Color.argb(alpha, 226, 229, 234))
        setStroke(1, Color.argb(24, 20, 30, 45))
    }

    protected fun verticalCard(cornerDp: Int = 20): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(11), px(8), px(11), px(8))
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

    /** 右上角关闭按钮(✕): 自带点击, 触发 onCloseRequest。子 View 消费点击, 不会与整窗拖动冲突。 */
    protected fun closeButton(): TextView = TextView(ctx).apply {
        text = "✕"
        setTextColor(GREY)
        textSize = textSp
        typeface = Typeface.MONOSPACE
        includeFontPadding = false
        setPadding(px(10), px(2), px(2), px(4))
        setOnClickListener { runCatching { onCloseRequest?.invoke() } }
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
 * 进度变化带补间动画(仿 Metric/Compose 的 animateFloatAsState 观感), 不是瞬跳。
 */
class RingView(ctx: Context, strokePx: Float) : View(ctx) {
    private var shownPct = 0f      // 当前绘制值(动画中间态)
    private var targetPct = 0f
    private var anim: ValueAnimator? = null
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

    /** 更新进度与弧色: 从当前显示值平滑补间到新值(约 450ms 减速曲线)。 */
    fun set(pct: Int, arcColor: Int) {
        arcPaint.color = arcColor
        val to = pct.coerceIn(0, 100).toFloat()
        if (to == targetPct) return
        targetPct = to
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shownPct, to).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener { shownPct = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel(); anim = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        if (shownPct > 0f) canvas.drawArc(bounds, -90f, shownPct / 100f * 360f, false, arcPaint)
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

    // 展开态右侧等宽文本面板(RAM/DDR/CPU温/每簇每核/FPS/PWR), 默认收起(GONE)。
    private var expanded = false
    private var lastTick: TickData? = null
    private val detail = row((textSp - 2f).coerceAtLeast(8f)).apply {
        visibility = View.GONE
    }

    private val ringsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(cpuCol.col)
        addView(gpuCol.col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = px(12); marginEnd = px(12)
        })
        addView(battCol.col)
    }

    private val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14), px(9), px(14), px(9))
        background = cardBg(20)
        addView(ringsRow)
        addView(detail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(14) })
    }
    override val root: View = pill

    // 展开/收起补间: 逐帧改 detail 宽度+透明度+左边距, 每帧 requestLayout 使
    // WRAP_CONTENT 的悬浮窗跟着平滑伸缩(Compose AnimatedVisibility 的观感)。
    private var detailAnim: ValueAnimator? = null

    init {
        // 窗被 removeView 时兜底停掉进行中的展开/收起动画
        pill.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { detailAnim?.cancel(); detailAnim = null }
        })
    }

    /** 整窗单击: 收起/展开切换(拖动由服务的移动阈值区分, 不冲突)。带宽度+淡入淡出过渡。 */
    override fun onTap() {
        expanded = !expanded
        if (expanded) {
            runCatching { renderDetail(lastTick) }
            animateDetail(show = true)
        } else {
            animateDetail(show = false)
        }
    }

    private fun animateDetail(show: Boolean) {
        val lpD = detail.layoutParams as LinearLayout.LayoutParams
        detailAnim?.cancel()
        if (show) {
            detail.visibility = View.VISIBLE
            detail.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val target = detail.measuredWidth
            lpD.width = 0; lpD.marginStart = 0
            detail.alpha = 0f
            detail.layoutParams = lpD
            detailAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedFraction
                    lpD.width = (target * f).toInt()
                    lpD.marginStart = (px(14) * f).toInt()
                    detail.alpha = f
                    detail.layoutParams = lpD
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        // 恢复 WRAP_CONTENT, 让展开态的文本刷新能自适应宽度
                        lpD.width = LinearLayout.LayoutParams.WRAP_CONTENT
                        lpD.marginStart = px(14)
                        detail.alpha = 1f
                        detail.layoutParams = lpD
                    }
                })
                start()
            }
        } else {
            val from = if (detail.width > 0) detail.width else detail.measuredWidth
            val fromMargin = lpD.marginStart
            detailAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedFraction
                    lpD.width = (from * (1f - f)).toInt()
                    lpD.marginStart = (fromMargin * (1f - f)).toInt()
                    detail.alpha = 1f - f
                    detail.layoutParams = lpD
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        detail.visibility = View.GONE
                        lpD.width = LinearLayout.LayoutParams.WRAP_CONTENT
                        lpD.marginStart = px(14)
                        detail.alpha = 1f
                        detail.layoutParams = lpD
                    }
                })
                start()
            }
        }
    }

    // 三环弧色对齐 Metric: 按指标固定色(CPU 恒绿 / GPU 恒橙 / 电量恒绿), 不随负载值变色。
    override fun onTick(t: TickData) {
        lastTick = t
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

        if (expanded) runCatching { renderDetail(t) }
    }

    /** 展开面板文本(仿 Metric): 全部字段取不到显示 --, 不抛异常。 */
    private fun renderDetail(t: TickData?) {
        val s = t?.snap
        val sb = StringBuilder()
        // #RAM 物理% 合计%(物理+swap)
        val ram = s?.ramUsedPct ?: -1
        val comb = s?.ramCombinedPct ?: -1
        sb.append("#RAM ").append(if (ram >= 0) "$ram%" else "--")
        if (comb >= 0) sb.append(" $comb%")
        sb.append('\n')
        // DDR 频率(MHz, 裸数字对齐截图)
        val ddr = s?.ddrMhz ?: -1
        sb.append("DDR ").append(if (ddr > 0) "$ddr" else "--").append('\n')
        // #CPU 最高温
        val tc = s?.tempC ?: -999
        sb.append("#CPU ").append(if (tc > -100) "${tc}℃" else "--").append('\n')
        // 每簇 + 每核(簇行: #核范围 簇频率; 核行: 负载% + 该核频率)
        val loads = s?.coreLoadPct.orEmpty()
        val freqs = s?.coreMhz.orEmpty()
        val clusters = s?.clusters.orEmpty().filter { it.cpus.isNotEmpty() }
        if (clusters.isNotEmpty()) {
            for (c in clusters) {
                val cpus = c.cpus.sorted()
                val range = if (cpus.size == 1) "${cpus.first()}"
                    else "${cpus.first()}-${cpus.last()}"
                sb.append('#').append(range).append(' ')
                    .append(if (c.mhz > 0) "${c.mhz}MHz" else "--").append('\n')
                for (cpu in cpus) appendCoreLine(sb, loads.getOrNull(cpu), freqs.getOrNull(cpu))
            }
        } else if (loads.isNotEmpty() || freqs.isNotEmpty()) {
            // 拿不到簇→核映射时平铺所有核
            val n = maxOf(loads.size, freqs.size)
            for (cpu in 0 until n) appendCoreLine(sb, loads.getOrNull(cpu), freqs.getOrNull(cpu))
        }
        // #FPS / #PWR
        val fps = s?.fps ?: -1
        sb.append("#FPS ").append(if (fps >= 0) String.format("%.1f", fps.toFloat()) else "--").append('\n')
        val w = s?.watt ?: 0.0
        sb.append("#PWR ").append(if (w > 0.0) String.format("%.2fW", w) else "--")
        detail.text = sb.toString()
    }

    private fun appendCoreLine(sb: StringBuilder, load: Int?, mhz: Int?) {
        val l = if (load != null && load >= 0) "$load%" else "--"
        val f = if (mhz != null && mhz > 0) "${mhz}MHz" else "--"
        sb.append(String.format(" %-4s %s", l, f)).append('\n')
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

/** 温度监视器: BAT / CPU / GPU / DDR 四行(仿 Metric), 取不到显示 --。 */
class TempOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = verticalCard()
    private val tHead = title("温度监视器")
    private val tBat = row()
    private val tCpu = row()
    private val tGpu = row()
    private val tDdr = row()
    override val root: View = card
    init {
        card.addView(tHead)
        card.addView(tBat); card.addView(tCpu); card.addView(tGpu); card.addView(tDdr)
    }

    private fun line(tv: TextView, label: String, c: Double) {
        tv.text = if (c > -100) String.format("%s %.1f℃", label, c) else "$label --"
        tv.setTextColor(when {
            c <= -100 -> WHITE
            c < 40 -> WHITE
            c <= 45 -> ORANGE
            else -> RED
        })
    }

    override fun onTick(t: TickData) {
        line(tBat, "BAT", t.battTempC)
        val soc = t.snap?.tempC ?: -999
        line(tCpu, "CPU", if (soc > -100) soc.toDouble() else -999.0)
        line(tGpu, "GPU", t.snap?.gpuTempC ?: -999.0)
        line(tDdr, "DDR", t.snap?.ddrTempC ?: -999.0)
    }
}

/**
 * 进程监视器(仿 Metric): 标题「进程」+ 右侧「全部/应用」标签(可点切换) + 关闭✕;
 * 列表每行 = 应用图标 + 名称 + CPU%。「应用」只留能解析到已安装非系统应用的行(只过滤不重采)。
 */
class ProcessOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {

    private class Line(val box: LinearLayout, val icon: ImageView, val name: TextView, val pct: TextView)

    private val card = verticalCard()
    private val tHead = title("进程")
    private var appsOnly = false
    private var lastProcs: List<ProcSampler.Proc>? = null

    private val tabAll = row(textSp - 2f, accent).apply {
        text = "全部"
        setPadding(px(8), px(2), px(4), px(4))
        setOnClickListener { setMode(false) }
    }
    private val tabApp = row(textSp - 2f, GREY).apply {
        text = "应用"
        setPadding(px(4), px(2), px(2), px(4))
        setOnClickListener { setMode(true) }
    }

    private val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(tHead)
        addView(tabAll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10) })
        addView(tabApp)
        addView(closeButton())
    }

    private val lines = ArrayList<Line>()
    override val root: View = card

    init {
        card.addView(header)
        repeat(8) {
            val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val name = row(textSp - 1f)
            val pct = row(textSp - 1f)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams(px(14), px(14)))
                addView(name, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
                addView(pct, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
            }
            lines.add(Line(box, icon, name, pct))
            card.addView(box)
        }
    }

    private fun setMode(only: Boolean) {
        if (appsOnly == only) return
        appsOnly = only
        tabAll.setTextColor(if (only) GREY else accent)
        tabApp.setTextColor(if (only) accent else GREY)
        runCatching { render() }
    }

    // ── 图标/应用判定缓存(进程名 → Drawable / 是否用户应用)──
    private val iconCache = HashMap<String, Drawable?>()
    private val userAppCache = HashMap<String, Boolean>()
    private val defaultIcon: Drawable? by lazy {
        runCatching { ctx.getDrawable(android.R.drawable.sym_def_app_icon) }.getOrNull()
    }

    // 进程名可能是 "com.foo.bar:push" 形式, 取 ':' 前作为包名候选
    private fun pkgOf(name: String) = name.substringBefore(':').trim()

    // 注意不用 getOrPut: 它不缓存 null(未命中), 会导致系统进程每拍重查 PackageManager
    private fun iconFor(name: String): Drawable? {
        if (!iconCache.containsKey(name)) {
            if (iconCache.size > 256) iconCache.clear()   // 防长期运行无限增长
            iconCache[name] = runCatching {
                ctx.packageManager.getApplicationIcon(pkgOf(name))
            }.getOrNull()
        }
        return iconCache[name] ?: defaultIcon
    }

    private fun isUserApp(name: String): Boolean = userAppCache.getOrPut(name) {
        runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkgOf(name), 0)
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }.getOrDefault(false)
    }

    override fun onTick(t: TickData) {
        val ps = t.procs ?: return   // null=本拍未采, 保持上次
        lastProcs = ps
        runCatching { render() }
    }

    private fun render() {
        val all = lastProcs
        if (all == null || all.isEmpty()) {
            lines.forEachIndexed { i, l ->
                l.box.visibility = if (i == 0) View.VISIBLE else View.GONE
                if (i == 0) {
                    l.icon.setImageDrawable(null)
                    l.name.text = "需要 Root / 采样中…"
                    l.pct.text = ""
                }
            }
            return
        }
        val ps = if (appsOnly) all.filter { isUserApp(it.name) } else all
        lines.forEachIndexed { i, l ->
            val p = ps.getOrNull(i)
            if (p == null) {
                l.box.visibility = View.GONE
            } else {
                l.box.visibility = View.VISIBLE
                l.icon.setImageDrawable(runCatching { iconFor(p.name) }.getOrNull())
                l.name.text = String.format("%-14.14s", p.name)
                l.pct.text = String.format("%5.1f%%", p.cpuPct)
                val c = if (p.cpuPct > 50f) ORANGE else WHITE
                l.name.setTextColor(c); l.pct.setTextColor(c)
            }
        }
        if (appsOnly && ps.isEmpty()) {
            lines[0].box.visibility = View.VISIBLE
            lines[0].icon.setImageDrawable(null)
            lines[0].name.text = "无应用进程"
            lines[0].pct.text = ""
        }
    }
}

/**
 * 线程监视器(仿 Metric): 标题=前台应用名(取不到显示「线程」)+ 副行「包名 PID xxxx」+ 关闭✕;
 * 表格四列 CPU% / CPUS(亲和[当前核], 如 0-7[4]) / TID / COMM, 等宽对齐。
 */
class ThreadOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = verticalCard()
    private val tHead = title("线程")
    private val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(tHead)
        addView(closeButton(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10) })
    }
    private val tSub = row(textSp - 3f, GREY).apply { visibility = View.GONE }
    private val tCols = row(textSp - 2f, GREY).apply {
        text = String.format("%5s %-9s %6s %s", "CPU%", "CPUS", "TID", "COMM")
    }
    private val lines = ArrayList<TextView>()
    override val root: View = card
    init {
        card.addView(header)
        card.addView(tSub)
        card.addView(tCols)
        repeat(8) { val r = row(textSp - 1f); lines.add(r); card.addView(r) }
    }

    override fun onTick(t: TickData) {
        // 表头(前台应用)每拍都可刷新, 与线程表是否采到无关
        runCatching {
            tHead.text = t.fgLabel.ifEmpty { t.fgPkg.ifEmpty { "线程" } }
            if (t.fgPkg.isNotEmpty()) {
                tSub.text = t.fgPkg + (if (t.fgPid > 0) "  PID ${t.fgPid}" else "")
                tSub.visibility = View.VISIBLE
            } else {
                tSub.visibility = View.GONE
            }
        }
        val ts = t.threads ?: return   // null=本拍未采, 保持上次
        if (ts.isEmpty()) {
            lines.forEachIndexed { i, tv -> tv.text = if (i == 0) "需要 Root / 采样中…" else "" }
            return
        }
        lines.forEachIndexed { i, tv ->
            val th = ts.getOrNull(i)
            if (th == null) { tv.text = "" } else {
                val cpus = if (th.affinity.isEmpty()) "--"
                    else th.affinity + (if (th.core >= 0) "[${th.core}]" else "")
                tv.text = String.format("%5.1f %-9.9s %6d %-14.14s", th.cpuPct, cpus, th.tid, th.name)
                tv.setTextColor(if (th.state == 'R') GREEN else WHITE)
            }
        }
    }
}
