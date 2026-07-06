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

// 悬浮窗配色对齐 Metric 原版: 深色玻璃底(#1B1D22 半透明, 支持时叠背景模糊) + 白字分层。
// Metric 文字层级: 主文 white、次级 white@0.74、弱化 white@0.62(见反编译 ph2/qb2/pc1)。
private val WHITE = Color.WHITE
private val GREY = Color.argb(158, 255, 255, 255)   // white @0.62
private val ORANGE = Color.parseColor("#FFB300")     // Metric 琥珀(r30.e)
private val RED = Color.parseColor("#EF5350")        // Metric 红(r30.f)
private val GREEN = Color.parseColor("#00E676")      // Metric 绿(r30.d)

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

    /** 整窗长按(服务 500ms 未动回调)。默认不响应; 负载窗按 Metric 语义用它关闭。 */
    open fun onLongPress() {}

    // Metric 深色玻璃卡: #1B1D22 半透明(有背景模糊时服务会调低 alpha 更透) + 18dp 圆角,
    // 描边仅留极弱的白色细线勾边。
    protected fun cardBg(cornerDp: Int = 18) = GradientDrawable().apply {
        cornerRadius = px(cornerDp).toFloat()
        setColor(Color.argb(alpha, 27, 29, 34))
        setStroke(1, Color.argb(18, 255, 255, 255))
    }

    protected fun verticalCard(cornerDp: Int = 18): LinearLayout = LinearLayout(ctx).apply {
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
 * 限高滚动容器(仿 Compose heightIn+LazyColumn): 内容少于 max 时包内容, 超过则固定 max 高并滚动。
 * 悬浮窗列表用(Metric: 进程窗 165dp 定高、线程窗 96~126dp)。滚动手势由本容器消费,
 * 整窗拖动仍可通过标题/空白区进行, 与 Metric 的"拖标题移动"一致。
 */
class MaxHeightScrollView(ctx: Context, private val maxHPx: Int) : android.widget.ScrollView(ctx) {
    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(maxHPx, View.MeasureSpec.AT_MOST),
        )
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
        color = Color.argb(51, 255, 255, 255)   // Metric 轨道: white @0.2
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = GREEN
    }

    /** 更新进度与弧色: 从当前显示值平滑补间到新值(Metric loadIndicatorPercent: 420ms 强调缓出)。 */
    fun set(pct: Int, arcColor: Int) {
        arcPaint.color = arcColor
        val to = pct.coerceIn(0, 100).toFloat()
        if (to == targetPct) { invalidate(); return }
        targetPct = to
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shownPct, to).apply {
            duration = 420
            interpolator = android.view.animation.PathInterpolator(0.15f, 1f, 0.3f, 1f)
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

    // Metric 原版: 环 32dp/描边 4dp、环心 7sp、环下 6sp —— 取 34dp 环 + 稍大字保持可读
    private val ringSize = px(34)

    private fun ringColumn(centerText: String, belowText: String): RingColumn {
        val ring = RingView(ctx, px(4).toFloat())
        val center = row((textSp - 4f).coerceAtLeast(7f)).apply {
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
        val below = row((textSp - 5f).coerceAtLeast(6f), GREY).apply {
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
            marginStart = px(8); marginEnd = px(8)   // Metric: 环间距 8dp
        })
        addView(battCol.col)
    }

    private val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(10), px(6), px(10), px(6))   // Metric: 外边距 6dp 档
        background = cardBg(16)                     // Metric 负载窗圆角 16dp
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

    /** 长按关闭(Metric 负载窗语义: 单击展开/收起, 长按移除)。 */
    override fun onLongPress() {
        onCloseRequest?.invoke()
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
                duration = 420   // Metric loadOverlayWidth/Height: 展开 420ms
                interpolator = android.view.animation.PathInterpolator(0.15f, 1f, 0.3f, 1f)
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
                duration = 360   // Metric: 收起 360ms
                interpolator = android.view.animation.PathInterpolator(0.15f, 1f, 0.3f, 1f)
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

    // 弧色对齐 Metric 原版阈值(qb2.j): <70% 绿 / <90% 琥珀 / ≥90% 红。
    private fun loadColor(pct: Int): Int = when {
        pct < 70 -> Color.parseColor("#00E676")
        pct < 90 -> Color.parseColor("#FFB300")
        else -> Color.parseColor("#EF5350")
    }

    override fun onTick(t: TickData) {
        lastTick = t
        val s = t.snap

        val cpu = s?.cpuLoadPct ?: -1
        cpuCol.ring.set(cpu.coerceAtLeast(0), loadColor(cpu.coerceAtLeast(0)))
        val topMhz = s?.clusters?.maxOfOrNull { it.mhz } ?: -1
        cpuCol.below.text = if (topMhz > 0) "${topMhz}MHz" else "--"

        val gpu = s?.gpuLoadPct ?: -1
        gpuCol.ring.set(gpu.coerceAtLeast(0), loadColor(gpu.coerceAtLeast(0)))
        val gpuMhz = s?.gpuMhz ?: -1
        gpuCol.below.text = if (gpuMhz > 0) "${gpuMhz}MHz" else "--"

        // 电量环画"已消耗"占比(Metric: 1-电量), 电量越低弧越满、越红
        val batt = s?.battPct ?: -1
        val drained = if (batt >= 0) 100 - batt else 0
        battCol.ring.set(drained, loadColor(drained))
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

/**
 * 迷你监视器(对齐 Metric 原版): 顶部居中贴状态栏的超细 HUD 横条, 点击穿透(窗口层加 NOT_TOUCHABLE)。
 * 深色半透明底 #D91B1D22 + 8dp 圆角, 四段: CPU(绿, 带微型负载折线) / GPU(蓝) / FPS(金) / 功耗温度轮换(粉)。
 * 第四段按 Metric 的 mini-overlay-power-swap 节奏: 9s 一循环, 前 6s 显功耗后 3s 显温度。
 * 紧凑字号固定(Metric 原版 7~8sp), 不随用户悬浮窗字号设置走。
 */
class MiniOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {

    private val segGreen = Color.parseColor("#8DEB9A")
    private val segBlue = Color.parseColor("#8FB9FF")
    private val segGold = Color.parseColor("#FFD166")
    private val segPink = Color.parseColor("#FF8EA1")
    private val ink = Color.parseColor("#F2F5F9")
    private val fs = 8f

    private fun cell(color: Int, minWidthDp: Int = 0) = TextView(ctx).apply {
        setTextColor(color)
        textSize = fs
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        if (minWidthDp > 0) minimumWidth = px(minWidthDp)
    }

    private val spark = MiniSparkView(ctx, segGreen)
    private val vCpu = cell(ink, 22)
    private val vGpu = cell(ink, 22)
    private val vFps = cell(ink, 26)
    private val lPwr = cell(segPink)
    private val vPwr = cell(ink, 36)

    private val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(8), px(3), px(8), px(3))
        background = GradientDrawable().apply {
            cornerRadius = px(8).toFloat()
            setColor(Color.argb(217, 27, 29, 34))   // Metric #D91B1D22
        }
    }
    override val root: View = bar

    private fun addSeg(label: TextView, vararg views: View) {
        bar.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = if (bar.childCount == 0) 0 else px(7)
        })
        views.forEach { v ->
            bar.addView(v, LinearLayout.LayoutParams(
                if (v is MiniSparkView) px(20) else LinearLayout.LayoutParams.WRAP_CONTENT,
                if (v is MiniSparkView) px(8) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = px(2) })
        }
    }

    init {
        addSeg(cell(segGreen).apply { text = "CPU" }, spark, vCpu)
        addSeg(cell(segBlue).apply { text = "GPU" }, vGpu)
        addSeg(cell(segGold).apply { text = "FPS" }, vFps)
        lPwr.text = "PWR"
        addSeg(lPwr, vPwr)
    }

    private var tick = 0L

    override fun onTick(t: TickData) {
        tick++
        val s = t.snap
        val cpu = s?.cpuLoadPct ?: -1
        vCpu.text = if (cpu >= 0) "$cpu%" else "--"
        if (cpu >= 0) spark.push(cpu)
        val gpu = s?.gpuLoadPct ?: -1
        vGpu.text = if (gpu >= 0) "$gpu%" else "--"
        val fps = s?.fps ?: -1
        vFps.text = if (fps >= 0) String.format(java.util.Locale.US, "%.1f", fps.toFloat()) else "--"
        // 功耗/温度轮换(Metric: phase<2/3 显功耗, 其余显温度)
        if (tick % 9 < 6) {
            lPwr.text = "PWR"
            val w = s?.watt ?: 0.0
            vPwr.text = if (w > 0.0) String.format(java.util.Locale.US, "%.2fW", w) else "--W"
        } else {
            lPwr.text = "TEMP"
            val c = s?.tempC ?: -999
            vPwr.text = if (c > -100) "$c℃" else "--℃"
        }
    }
}

/** 迷你条上的微型负载折线(0..100 固定量程, 保留最近 30 个点)。 */
class MiniSparkView(ctx: Context, lineColor: Int) : View(ctx) {
    private val data = ArrayDeque<Int>()
    private val cap = 30
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor; style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    fun push(v: Int) {
        if (data.size >= cap) data.removeFirst()
        data.addLast(v.coerceIn(0, 100))
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        if (data.size < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        val stepX = w / (cap - 1)
        val start = cap - data.size
        path.reset()
        var started = false
        data.forEachIndexed { i, v ->
            val x = (start + i) * stepX
            val y = h - v / 100f * (h - 2f) - 1f
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}

/**
 * 帧率记录器(对齐 Metric 原版): 双态悬浮窗。
 *  - 收起 = 小胶囊只显当前帧率(空闲琥珀色 / 录制中红色, 同 Metric 0xB7D29A04 / 0xB7D32F2F);
 *  - 单击胶囊以 Metric 同款 420ms CubicBezier(0.15,1,0.3,1) 强调缓出展开为完整面板:
 *    收起/关闭按钮、录制状态丸(呼吸点+时长)、录制/停止按钮(绿/红)、时长限制片(不限/5/10/15/30m)、
 *    大号当前帧率、温度(琥珀)/平均/最低/最高四格统计、74dp 高发光平滑曲线图; 收起 360ms。
 * 录制走已有 FpsRecordService(状态 StateFlow 每拍读取, 时长限制到点自动停)。
 */
class FpsOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {

    private val idleTint = Color.argb(0xB7, 0xD2, 0x9A, 0x04)   // Metric 胶囊空闲色
    private val recTint = Color.argb(0xB7, 0xD3, 0x2F, 0x2F)    // Metric 胶囊录制色
    private val panelTint = Color.argb(alpha, 27, 29, 34)        // 展开面板 = 家族深色玻璃
    private val amber = Color.parseColor("#FFA726")              // Metric 温度标签琥珀
    private val recGreen = Color.argb(0x80, 0x4C, 0xAF, 0x50)    // 录制按钮绿(@~0.5)
    private val recRed = Color.argb(0x94, 0xD3, 0x2F, 0x2F)      // 停止按钮红(@~0.58)
    private val morphEase = android.view.animation.PathInterpolator(0.15f, 1f, 0.3f, 1f)

    // ── 收起态胶囊 ──
    private val capsule = TextView(ctx).apply {
        text = "--"
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(px(10), px(5), px(10), px(5))
        minimumWidth = px(40)
    }

    // ── 展开态面板 ──
    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph
        setTextColor(WHITE)
        textSize = 13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(26, 255, 255, 255))   // Metric: 白 10% 圆形底
        }
        layoutParams = LinearLayout.LayoutParams(px(30), px(30))
        setOnClickListener { runCatching { onClick() } }
    }

    private fun pillText(sizeSp: Float, color: Int = Color.WHITE) = TextView(ctx).apply {
        setTextColor(color)
        textSize = sizeSp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private val btnCollapse = iconBtn("⌄") { setExpanded(false) }
    private val btnClose = iconBtn("✕") { onCloseRequest?.invoke() }

    // 录制状态丸: 6dp 呼吸圆点 + "录制中/等待中 时长"
    private val statusDot = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
    }
    private val statusText = pillText(10f, WHITE)
    private val statusPill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumWidth = px(50)
        setPadding(px(7), px(3), px(7), px(3))
        background = GradientDrawable().apply {
            cornerRadius = px(11).toFloat()
            setColor(Color.argb(30, 255, 255, 255))
        }
        addView(statusDot, LinearLayout.LayoutParams(px(6), px(6)))
        addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(5) })
        visibility = View.GONE
    }
    private var dotAnim: ValueAnimator? = null

    private val recBtn = pillText(10f).apply {
        text = "开始录制"
        setPadding(px(9), px(4), px(9), px(4))
        background = GradientDrawable().apply {
            cornerRadius = px(11).toFloat()
            setColor(recGreen)
        }
        setOnClickListener { runCatching { toggleRecord() } }
    }

    // 时长限制片: 0=不限
    private val limits = intArrayOf(0, 5, 10, 15, 30)
    private var limitMin = 0
    private val chipViews = ArrayList<TextView>()
    private val chipsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        limits.forEach { m ->
            val chip = pillText(9f, WHITE).apply {
                text = if (m == 0) "不限" else "${m}m"
                setPadding(px(8), px(3), px(8), px(3))
                background = GradientDrawable().apply { cornerRadius = px(11).toFloat() }
                setOnClickListener { limitMin = m; refreshChips() }
            }
            chipViews.add(chip)
            addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = if (childCount == 0) 0 else px(4)
            })
        }
    }

    private val tCurLabel = pillText(9f, GREY).apply { text = "当前帧率" }
    private val tBig = pillText(22f, accent)
    private fun statCell(label: String, labelColor: Int): Pair<LinearLayout, TextView> {
        val l = pillText(9f, labelColor).apply { text = label }
        val v = pillText(13f, WHITE)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(l)
            addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(1) })
        }
        return col to v
    }
    private val cTemp = statCell("温度", amber)
    private val cAvg = statCell("平均", GREY)
    private val cMin = statCell("最低", GREY)
    private val cMax = statCell("最高", GREY)

    private val chart = FpsChartView(ctx, accent)

    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(9), px(9), px(9), px(9))
        // 顶排: 收起 + 关闭(间距 4dp, Metric 顶排双圆钮)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnCollapse)
            addView(btnClose, LinearLayout.LayoutParams(px(30), px(30)).apply { marginStart = px(4) })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(statusPill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, px(22)))
        })
        addView(recBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, px(22)).apply { topMargin = px(7) })
        addView(chipsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, px(22)).apply { topMargin = px(5) })
        addView(tCurLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(7) })
        addView(tBig)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cTemp.first)
            listOf(cAvg, cMin, cMax).forEach {
                addView(it.first, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(14) })
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        addView(chart, LinearLayout.LayoutParams(px(250), px(74)).apply { topMargin = px(7) })
        visibility = View.GONE
        this.alpha = 0f   // 明确指 View.alpha(外层类有同名 Int 属性 alpha)
    }

    // shell 承载玻璃底, 尺寸/圆角/底色随展开态补间; 外层 root 透明仅作窗根
    private val shellBg = GradientDrawable().apply {
        setColor(idleTint)
        setStroke(1, Color.argb(18, 255, 255, 255))
    }
    private val shell = FrameLayout(ctx).apply {
        background = shellBg
        addView(capsule, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT))
        clipToOutline = false
    }
    override val root: View = FrameLayout(ctx).apply {
        addView(shell, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private var expandedState = false
    private var morphAnim: ValueAnimator? = null

    init {
        shell.post { shellBg.cornerRadius = shell.height / 2f }   // 初始胶囊 = 全圆角
        refreshChips()
        (root as FrameLayout).addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                morphAnim?.cancel(); morphAnim = null
                dotAnim?.cancel(); dotAnim = null
            }
        })
    }

    /** 单击整窗: 收起态展开(展开态内部按钮各管各的, 单击不响应)。 */
    override fun onTap() {
        if (!expandedState) setExpanded(true)
    }

    private fun setExpanded(want: Boolean) {
        if (expandedState == want) return
        expandedState = want
        morphAnim?.cancel()

        val show: View = if (want) panel else capsule
        val hide: View = if (want) capsule else panel
        show.visibility = View.VISIBLE

        // 量出目标尺寸(以 shell 现尺寸为起点)
        val ws = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        show.measure(ws, ws)
        val fromW = shell.width; val fromH = shell.height
        val toW = show.measuredWidth; val toH = show.measuredHeight
        val fromR = shellBg.cornerRadius
        val toR = if (want) px(18).toFloat() else toH / 2f
        val fromC = currentTint()
        val toC = if (want) panelTint else recordingTint()
        val lpS = shell.layoutParams as FrameLayout.LayoutParams
        val ev = android.animation.ArgbEvaluator()

        morphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (want) 420 else 360   // Metric: 展开 420ms / 收起 360ms
            interpolator = morphEase
            addUpdateListener {
                val f = it.animatedFraction
                lpS.width = (fromW + (toW - fromW) * f).toInt()
                lpS.height = (fromH + (toH - fromH) * f).toInt()
                shell.layoutParams = lpS
                shellBg.cornerRadius = fromR + (toR - fromR) * f
                shellBg.setColor(ev.evaluate(f, fromC, toC) as Int)
                show.alpha = f
                hide.alpha = 1f - f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    hide.visibility = View.GONE
                    hide.alpha = 1f
                    lpS.width = FrameLayout.LayoutParams.WRAP_CONTENT
                    lpS.height = FrameLayout.LayoutParams.WRAP_CONTENT
                    shell.layoutParams = lpS
                    if (!want) shell.post { shellBg.cornerRadius = shell.height / 2f }
                }
            })
            start()
        }
    }

    // ── 录制联动(读 FpsRecordService.state, 每拍一次) ──
    private fun recState() = com.ktwo.cts.service.FpsRecordService.state.value

    private fun currentTint(): Int =
        if (expandedState) panelTint else recordingTint()

    private fun recordingTint(): Int = if (recState().recording) recTint else idleTint

    private fun toggleRecord() {
        val st = recState()
        if (st.active) com.ktwo.cts.service.FpsRecordService.stop(ctx)
        else com.ktwo.cts.service.FpsRecordService.start(ctx)
    }

    private fun refreshChips() {
        chipViews.forEachIndexed { i, chip ->
            val sel = limits[i] == limitMin
            (chip.background as GradientDrawable).setColor(
                if (sel) (accent and 0x00FFFFFF) or (0xBD shl 24)   // 选中: accent @0.74
                else Color.argb(0x1F, 255, 255, 255))   // 未选中: 白 12%
            chip.setTextColor(if (sel) Color.WHITE else GREY)
        }
    }

    private fun fmtElapsed(sec: Int): String {
        val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
            else String.format("%02d:%02d", m, s)
    }

    private fun setDotBreathing(on: Boolean) {
        if (on && dotAnim == null) {
            dotAnim = ValueAnimator.ofFloat(1f, 0.25f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { statusDot.alpha = it.animatedValue as Float }
                start()
            }
        } else if (!on) {
            dotAnim?.cancel(); dotAnim = null
            statusDot.alpha = 1f
        }
    }

    override fun onTick(t: TickData) {
        val fps = t.snap?.fps ?: -1
        val fpsStr = if (fps >= 0) String.format(java.util.Locale.US, "%.1f", fps.toFloat()) else "--"
        capsule.text = fpsStr
        if (fps >= 0) chart.push(fps)

        val st = recState()
        // 胶囊底色跟随录制态(未展开时)
        if (!expandedState && morphAnim?.isRunning != true) shellBg.setColor(recordingTint())

        if (!expandedState) return   // 面板收起时不必刷新面板内文本

        tBig.text = fpsStr
        val temp = t.snap?.tempC ?: -999
        cTemp.second.text = if (temp > -100) String.format(java.util.Locale.US, "%.1f℃", temp.toFloat()) else "--℃"
        val pts = chart.values()
        cAvg.second.text = if (pts.isEmpty()) "--" else String.format(java.util.Locale.US, "%.1f", pts.average())
        cMin.second.text = if (pts.isEmpty()) "--" else "${pts.min()}"
        cMax.second.text = if (pts.isEmpty()) "--" else "${pts.max()}"

        // 录制状态丸 + 按钮
        if (st.active) {
            statusPill.visibility = View.VISIBLE
            if (st.recording) {
                (statusDot.background as GradientDrawable).setColor(RED)
                statusText.text = "录制中 " + fmtElapsed(st.elapsedSec) +
                    (if (st.appName.isNotEmpty()) " · ${st.appName}" else "")
            } else {
                (statusDot.background as GradientDrawable).setColor(ORANGE)
                statusText.text = "等待进入目标应用…"
            }
            setDotBreathing(true)
            recBtn.text = "停止录制"
            (recBtn.background as GradientDrawable).setColor(recRed)
        } else {
            statusPill.visibility = View.GONE
            setDotBreathing(false)
            recBtn.text = "开始录制"
            (recBtn.background as GradientDrawable).setColor(recGreen)
        }
        // 时长限制到点自动停
        if (st.recording && limitMin > 0 && st.elapsedSec >= limitMin * 60) {
            runCatching { com.ktwo.cts.service.FpsRecordService.stop(ctx) }
        }
    }
}

/**
 * 帧率曲线图(对齐 Metric 观感): 10dp 圆角深色底 + 自动量程 Y 轴刻度 +
 * 中点二次贝塞尔平滑折线 + 主色渐变填充 + 多层描边霓虹辉光 + 端点圆点。
 */
class FpsChartView(ctx: Context, private val accent: Int) : View(ctx) {
    private val data = ArrayDeque<Int>()
    private val cap = 120
    private val dp = ctx.resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 10, 12, 16) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        textSize = 8 * dp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fillPath = Path()
    private val bgRect = RectF()

    // Metric eb1 的辉光层(取 3 层, 控制过度绘制): (宽度倍率, 透明度)
    private val glow = arrayOf(3.2f to 0.08f, 2.1f to 0.145f, 1.35f to 0.22f)

    fun push(v: Int) {
        if (data.size >= cap) data.removeFirst()
        data.addLast(v.coerceAtLeast(0))
        invalidate()
    }

    fun values(): List<Int> = data.toList()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, 10 * dp, 10 * dp, bgPaint)
        if (data.size < 2) return

        // 自动量程(Metric 支持到 185Hz 的阶梯)
        val maxV = (data.maxOrNull() ?: 60)
        val top = when {
            maxV <= 60 -> 60; maxV <= 90 -> 90; maxV <= 120 -> 120
            maxV <= 144 -> 144; maxV <= 165 -> 165; else -> 185
        }.toFloat()

        // Y 轴刻度(0 与 top 之间四档)
        val leftPad = 18 * dp; val vPad = 6 * dp
        val chartH = h - vPad * 2
        for (i in 0..3) {
            val v = top / 3f * i
            val y = h - vPad - v / top * chartH
            canvas.drawText("${v.toInt()}", leftPad - 4 * dp, y + 3 * dp, labelPaint)
        }

        // 数据点 → 坐标(右端对齐最新点)
        val stepX = (w - leftPad - 6 * dp) / (cap - 1)
        val start = cap - data.size
        val xs = FloatArray(data.size); val ys = FloatArray(data.size)
        data.forEachIndexed { i, v ->
            xs[i] = leftPad + (start + i) * stepX
            ys[i] = h - vPad - (v.coerceAtMost(top.toInt()) / top) * chartH
        }

        // 中点二次贝塞尔平滑(Metric qb1.e 同法)
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until data.size) {
            val mx = (xs[i - 1] + xs[i]) / 2f
            val my = (ys[i - 1] + ys[i]) / 2f
            path.quadTo(xs[i - 1], ys[i - 1], mx, my)
        }
        path.lineTo(xs.last(), ys.last())

        // 渐变填充(accent 25% → 透明)
        fillPath.set(path)
        fillPath.lineTo(xs.last(), h - vPad)
        fillPath.lineTo(xs[0], h - vPad)
        fillPath.close()
        fillPaint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h,
            (accent and 0x00FFFFFF) or (0x40 shl 24), Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)

        // 霓虹辉光(粗→细多层低透明描边) + 主线
        val base = 2f * dp
        glow.forEach { (mul, a) ->
            linePaint.strokeWidth = base * mul
            linePaint.color = (accent and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            canvas.drawPath(path, linePaint)
        }
        linePaint.strokeWidth = base
        linePaint.color = accent
        canvas.drawPath(path, linePaint)

        // 端点圆点(外圈 60% + 内圈 90%)
        dotPaint.color = (accent and 0x00FFFFFF) or (0x99 shl 24)
        canvas.drawCircle(xs.last(), ys.last(), 4.5f * dp, dotPaint)
        dotPaint.color = (accent and 0x00FFFFFF) or (0xE6 shl 24)
        canvas.drawCircle(xs.last(), ys.last(), 2.7f * dp, dotPaint)
    }
}

/**
 * 温度监视器(对齐 Metric 原版): 无标题紧凑竖排卡(Metric 83×54dp / 18dp 圆角),
 * BAT/CPU/GPU/DDR 每行 "%s %.1f℃"(Locale.US), 取不到的行直接隐藏(Metric: temp≤0 → null)。
 * 全部取不到时显示一行占位, 避免空窗无法辨认。保留 CTS 的温度阈值着色(<40 墨 / ≤45 橙 / >45 红)。
 */
class TempOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = verticalCard(18).apply {
        setPadding(px(12), px(7), px(12), px(7))   // Metric: 12dp 横 / 7dp 纵
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val tBat = row()
    private val tCpu = row()
    private val tGpu = row()
    private val tDdr = row()
    private val tNone = row(textSp - 1f, GREY).apply { text = "温度不可用"; visibility = View.GONE }
    override val root: View = card
    init {
        card.addView(tBat); card.addView(tCpu); card.addView(tGpu); card.addView(tDdr)
        card.addView(tNone)
    }

    /** 有值填行并显示, 无值整行隐藏; 返回该行是否可见。 */
    private fun line(tv: TextView, label: String, c: Double): Boolean {
        if (c <= -100) { tv.visibility = View.GONE; return false }
        tv.visibility = View.VISIBLE
        tv.text = String.format(java.util.Locale.US, "%s %.1f℃", label, c)
        tv.setTextColor(when {
            c < 40 -> WHITE
            c <= 45 -> ORANGE
            else -> RED
        })
        return true
    }

    override fun onTick(t: TickData) {
        var any = false
        any = line(tBat, "BAT", t.battTempC) or any
        val soc = t.snap?.tempC ?: -999
        any = line(tCpu, "CPU", if (soc > -100) soc.toDouble() else -999.0) or any
        any = line(tGpu, "GPU", t.snap?.gpuTempC ?: -999.0) or any
        any = line(tDdr, "DDR", t.snap?.ddrTempC ?: -999.0) or any
        tNone.visibility = if (any) View.GONE else View.VISIBLE
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

    // 筛选片(Metric ProcessFilterTransition): 胶囊底 + 180ms 选中过渡
    private fun chipBgColor(sel: Boolean) =
        if (sel) Color.argb(46, 255, 255, 255) else Color.TRANSPARENT

    private fun tab(label: String, sel: Boolean, onClick: () -> Unit) = row(textSp - 2f).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(px(7), px(2), px(7), px(2))
        setTextColor(if (sel) WHITE else GREY)
        background = GradientDrawable().apply {
            cornerRadius = px(10).toFloat()
            setColor(chipBgColor(sel))
        }
        setOnClickListener { onClick() }
    }

    private val tabAll = tab("全部", true) { setMode(false) }
    private val tabApp = tab("应用", false) { setMode(true) }
    private var chipAnim: ValueAnimator? = null

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
    private val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val root: View = card

    init {
        card.addView(header)
        // Metric: 进程窗列表定高 165dp 可滚动, 最多 30 行(行高 21dp/间距 3dp 的紧凑观感)
        repeat(30) {
            val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val name = row(textSp - 1f)
            val pct = row(textSp - 1f)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(1), 0, px(1))
                addView(icon, LinearLayout.LayoutParams(px(14), px(14)))
                addView(name, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
                addView(pct, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
            }
            lines.add(Line(box, icon, name, pct))
            listBox.addView(box)
        }
        card.addView(MaxHeightScrollView(ctx, px(165)).apply { addView(listBox) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
    }

    private fun setMode(only: Boolean) {
        if (appsOnly == only) return
        appsOnly = only
        // 180ms 选中过渡(Metric ProcessFilterTransition tween(180))
        chipAnim?.cancel()
        val ev = android.animation.ArgbEvaluator()
        val fromSel = if (only) tabAll else tabApp   // 原选中 → 退场
        val toSel = if (only) tabApp else tabAll     // 新选中 → 进场
        chipAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                val f = it.animatedFraction
                (toSel.background as GradientDrawable)
                    .setColor(ev.evaluate(f, Color.TRANSPARENT, chipBgColor(true)) as Int)
                (fromSel.background as GradientDrawable)
                    .setColor(ev.evaluate(f, chipBgColor(true), Color.TRANSPARENT) as Int)
                toSel.setTextColor(ev.evaluate(f, GREY, WHITE) as Int)
                fromSel.setTextColor(ev.evaluate(f, WHITE, GREY) as Int)
            }
            start()
        }
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
    private val listBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = px(96)   // Metric: 线程表 heightIn 96~126dp
    }
    override val root: View = card
    init {
        card.addView(header)
        card.addView(tSub)
        card.addView(tCols)
        repeat(24) { val r = row(textSp - 1f); lines.add(r); listBox.addView(r) }
        card.addView(MaxHeightScrollView(ctx, px(126)).apply { addView(listBox) })
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
