package com.ktwo.cts.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.ProcSampler
import java.util.Locale

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

// 悬浮窗配色 1:1 对齐 Metric: 深色玻璃 tint(#1B1D22, surface@0.55) + 白字分层。
// Metric 文字层级: 纯白 / 白92(选中) / 白90(参数标签) / 白86(状态丸) / 白80(表头/参数值) / 白74(数据行) / 白62(弱化)。
private val WHITE = Color.WHITE
private val WHITE92 = Color.argb(235, 255, 255, 255)
private val WHITE90 = Color.argb(230, 255, 255, 255)
private val WHITE86 = Color.argb(219, 255, 255, 255)
private val WHITE80 = Color.argb(204, 255, 255, 255)
private val WHITE74 = Color.argb(189, 255, 255, 255)
private val GREY = Color.argb(158, 255, 255, 255)   // white @0.62
private val ORANGE = Color.parseColor("#FFB300")     // Metric 琥珀(r30.e)
private val RED = Color.parseColor("#EF5350")        // Metric 红(r30.f)
private val GREEN = Color.parseColor("#00E676")      // Metric 绿(r30.d)

/** Metric 展开/收起等尺寸动画统一缓动: CubicBezier(0.15, 1, 0.3, 1)。 */
private fun metricEase() = android.view.animation.PathInterpolator(0.15f, 1f, 0.3f, 1f)

/** 悬浮窗抽象基类: 提供深色玻璃卡背景、monospace 文本行等公共构件。 */
abstract class OverlayWindow(
    protected val ctx: Context,
    protected val accent: Int,
    protected val alpha: Int,       // 玻璃 tint alpha(Metric surface@0.55 → 140)
    protected val textSp: Float,
) {
    protected val dp = ctx.resources.displayMetrics.density
    protected fun px(v: Int) = (v * dp).toInt()
    protected fun pxF(v: Float) = (v * dp).toInt()

    abstract val root: View
    abstract fun onTick(t: TickData)

    /** 关闭请求回调: 由服务注入(点击窗内 ✕ 时调用, 停掉该类型窗并持久化关闭)。 */
    var onCloseRequest: (() -> Unit)? = null

    /** 窗背景圆角(dp): 服务用它构造透明圆角窗背景(模糊/裁剪轮廓)。负载 16 / 迷你 8 / 其余 18。 */
    open val windowCornerDp: Int = 18

    /** 模糊半径驱动(服务注入, FPS 窗状态机用; 其他窗为 null)。 */
    var blurSetter: ((Int) -> Unit)? = null

    /** 整窗单击(服务在拖动阈值内的 ACTION_UP 回调)。默认不响应, 子类按需覆写。 */
    open fun onTap() {}

    /** 整窗长按(服务 500ms 未动回调)。默认不响应; 负载/温度窗按 Metric 语义用它关闭。 */
    open fun onLongPress() {}

    /** 拖动越过阈值时回调一次(FPS 收起态: 触碰唤醒玻璃)。 */
    open fun onDragged() {}

    /** FPS 窗展开态(服务钳制窗口位置时选 40×26 或 268×230)。其他窗恒 false。 */
    open val isExpandedWindow: Boolean get() = false

    // ── 液态玻璃底(Liquid Glass): 跟随系统的半透明中性表面 + 顶部高光渐变 + 亮边 ──
    // Met 悬浮窗玻璃 = 跨窗口背景模糊(窗层 setBackgroundBlurRadius) 叠半透明动态表面色;
    // 关键是底色要够透(让模糊透出来)且用中性灰而非近黑, 再加一圈亮边高光模拟玻璃棱。
    // 注: AGSL 折射玻璃只能采样 App 自身画面, 无法透视悬浮窗下方的别的应用, 故这里用模糊玻璃。
    protected fun glassBaseColor(): Int = runCatching {
        // Material You 动态中性表面(暗色档, 比写死近黑更接近 Met 的玻璃灰)
        if (android.os.Build.VERSION.SDK_INT >= 31)
            ctx.getColor(android.R.color.system_neutral1_800)
        else Color.rgb(0x26, 0x2A, 0x30)
    }.getOrDefault(Color.rgb(0x26, 0x2A, 0x30))

    private fun lighten(c: Int, f: Float): Int {
        val r = (Color.red(c) + (255 - Color.red(c)) * f).toInt().coerceIn(0, 255)
        val g = (Color.green(c) + (255 - Color.green(c)) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(c) + (255 - Color.blue(c)) * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
    protected fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /** 液态玻璃背景: 顶部略亮→底部本色的竖向渐变(玻璃厚度感) + 白色亮边(玻璃棱)。 */
    protected fun glassBg(cornerDp: Int = 18): GradientDrawable {
        val base = glassBaseColor()
        // alpha 由服务传入(Met surface@0.55≈140), 顶部高光比底部再透一点、更亮
        val top = withAlpha(lighten(base, 0.14f), (alpha * 0.92f).toInt().coerceIn(0, 255))
        val bottom = withAlpha(base, alpha)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
        ).apply {
            cornerRadius = px(cornerDp).toFloat()
            // 液态玻璃亮边: 1px 白@22%(顶亮的玻璃棱, Met 无描边但玻璃观感靠它兜住)
            setStroke(maxOf(1, pxF(0.8f)), Color.argb(56, 255, 255, 255))
        }
    }

    // 兼容旧调用名: 统一走液态玻璃底
    protected fun cardBg(cornerDp: Int = 18) = glassBg(cornerDp)

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

    /** Metric 固定字号等宽文本(1:1 还原用): DIP 单位, 不随系统/用户字号缩放,
     * 保证固定 dp 行高/单元格在大字体设置下也不裁字。 */
    protected fun mono(sizeSp: Float, color: Int = WHITE, bold: Boolean = true): TextView =
        TextView(ctx).apply {
            setTextColor(color)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizeSp)
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                else Typeface.MONOSPACE
            includeFontPadding = false
            maxLines = 1
        }

    /** Metric 圆形图标钮: 白 10% 圆底 + 白 74% 图标(线程窗 30dp / 进程窗 24dp)。 */
    protected fun circleBtn(glyph: String, sizeSp: Float = 12f, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            setTextColor(WHITE74)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizeSp)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(26, 255, 255, 255))
            }
            setOnClickListener { runCatching { onClick() } }
        }

    /** 频率格式(对照 Met 截图: 环下/簇头都是 "1900MHz" 裸 MHz, 不转 GHz)。 */
    protected fun fmtMhz(mhz: Int): String = if (mhz <= 0) "-- MHz" else "${mhz}MHz"
}

/**
 * 限高滚动容器(仿 Compose heightIn/LazyColumn):
 * exact=false → 内容少于 max 包内容(线程窗 96~126dp); exact=true → 恒为 max 定高(进程窗 165dp)。
 */
class MaxHeightScrollView(
    ctx: Context,
    private val maxHPx: Int,
    private val exact: Boolean = false,
) : android.widget.ScrollView(ctx) {
    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = if (exact) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHPx, mode))
    }
}

/**
 * 环形进度表(纯自绘): 白@0.2 整圈轨道 + 阈值色进度弧。
 * 进度变化以 Metric loadIndicatorPercent 同节奏补间(420ms, CubicBezier(0.15,1,0.3,1))。
 * onShown 在每帧动画回调当前显示值(电量环心数字随弧滚动用)。
 */
class RingView(ctx: Context, strokePx: Float) : View(ctx) {
    private var shownPct = 0f
    private var targetPct = 0f
    private var anim: ValueAnimator? = null
    private val inset = strokePx / 2f + 1f
    private val bounds = RectF()
    var onShown: ((Float) -> Unit)? = null
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

    fun set(pct: Int, arcColor: Int) {
        arcPaint.color = arcColor
        val to = pct.coerceIn(0, 100).toFloat()
        if (to == targetPct) { invalidate(); return }
        targetPct = to
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shownPct, to).apply {
            duration = 420
            interpolator = metricEase()
            addUpdateListener {
                shownPct = it.animatedValue as Float
                onShown?.invoke(shownPct)
                invalidate()
            }
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
 * 负载监视器(1:1 对齐 Metric qb2):
 *  - 收起 = 固定 124×54dp 横排三环胶囊(环 32dp/间距 8/padding 6), 16dp 圆角;
 *  - 展开 = 固定宽 165dp(有 IPC)/134dp(无 IPC), 高 = max(136, 8.5×行数+2)+12dp;
 *    左侧 44dp 竖排三环, 右侧参数网格(行高 8.5dp, 标签右对齐白90 + 值左对齐白80, 7sp);
 *  - 形变 420/360ms CubicBezier(0.15,1,0.3,1), 内容锁定尺寸只裁剪不重排;
 *  - 单击展开/收起, 长按关闭; 电量环画剩余电量、环心数字随弧动画滚动。
 */
class LoadOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {

    override val windowCornerDp = 16

    private class RingCol(
        val col: LinearLayout,
        val ring: RingView,
        val center: TextView,
        val below: TextView,
    )

    private val ringSize = px(32)   // Metric: 环 32dp / 单元 32×42dp

    private fun ringCol(centerText: String): RingCol {
        val ring = RingView(ctx, px(4).toFloat())
        val center = mono(7f).apply {   // Metric 环心 7sp
            text = centerText
            gravity = Gravity.CENTER
        }
        val frame = FrameLayout(ctx).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize))
            addView(center, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
        }
        // Metric 环下小字盒: 32×8dp 定尺寸, 6sp 纯白
        val below = mono(6f).apply {
            text = "-- MHz"
            gravity = Gravity.CENTER
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(frame, LinearLayout.LayoutParams(ringSize, ringSize))
            addView(below, LinearLayout.LayoutParams(px(32), px(8)).apply { topMargin = px(2) })
        }
        return RingCol(col, ring, center, below)
    }

    // 两套环: 收起横排 / 展开竖排, 数据每拍双写, 切换走交叉淡化
    private val hCpu = ringCol("CPU"); private val hGpu = ringCol("GPU"); private val hBat = ringCol("--")
    private val vCpu = ringCol("CPU"); private val vGpu = ringCol("GPU"); private val vBat = ringCol("--")

    private val collapsedRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(px(6), px(6), px(6), px(6))   // Metric: padding 6dp → 124×54
        addView(hCpu.col)
        addView(hGpu.col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = px(8); marginEnd = px(8)   // Metric: 环间距 8dp
        })
        addView(hBat.col)
    }

    // ── 展开态参数网格(qb2.b/c): 行高 8.5dp, 标签右对齐白90 + 值左对齐白80, 7sp ──
    private class GridRow(val box: LinearLayout, val label: TextView, val value: TextView)

    private val gridRows = ArrayList<GridRow>()
    private val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var gridHasIpc = false

    private fun makeGridRow(hasIpc: Boolean): GridRow {
        val labelW = if (hasIpc) 26 else 20   // Metric fb2: (26,90,4) / (20,46,10)
        val gapW = if (hasIpc) 4 else 10
        val valueW = if (hasIpc) 90 else 46
        val label = mono(7f, WHITE90).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val value = mono(7f, WHITE80).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LinearLayout.LayoutParams(px(labelW), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(value, LinearLayout.LayoutParams(px(valueW), LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { marginStart = px(gapW) })
        }
        return GridRow(box, label, value)
    }

    /** 按行数/模式重建网格行(结构变化时才重建, 文本每拍只改内容)。 */
    private fun ensureGridRows(n: Int, hasIpc: Boolean) {
        if (gridRows.size == n && gridHasIpc == hasIpc) return
        gridHasIpc = hasIpc
        grid.removeAllViews()
        gridRows.clear()
        repeat(n) {
            val r = makeGridRow(hasIpc)
            gridRows.add(r)
            grid.addView(r.box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, pxF(8.5f)))   // Metric 行高 8.5dp
        }
    }

    private val ringsColV = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(vCpu.col)
        addView(vGpu.col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(5) })
        addView(vBat.col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(5) })
    }

    private val expandedBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(6), px(6), px(6), px(6))   // Metric a:363/365: 6dp
        addView(ringsColV, LinearLayout.LayoutParams(
            px(44), LinearLayout.LayoutParams.WRAP_CONTENT))   // Metric: 环列限宽 44dp
        addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = pxF(1.5f) })
        visibility = View.GONE
        this.alpha = 0f
    }

    private val shellBg = cardBg(16)
    private val shell = FrameLayout(ctx).apply {
        clipChildren = true
        clipToPadding = true
        background = shellBg
        addView(collapsedRow, FrameLayout.LayoutParams(px(124), px(54)))
        addView(expandedBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT))
    }
    override val root: View = FrameLayout(ctx).apply {
        clipChildren = true
        addView(shell, FrameLayout.LayoutParams(px(124), px(54)))
    }

    private var expanded = false
    private var lastTick: TickData? = null
    private var morph: ValueAnimator? = null

    init {
        vBat.ring.onShown = { s -> vBat.center.text = "${s.toInt()}%" }
        hBat.ring.onShown = { s -> hBat.center.text = "${s.toInt()}%" }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { morph?.cancel(); morph = null }
        })
    }

    override fun onTap() = setExpanded(!expanded)

    override fun onLongPress() {
        onCloseRequest?.invoke()
    }

    private fun hasIpc(): Boolean {
        val loads = lastTick?.snap?.coreLoadPct.orEmpty()
        for (i in loads.indices) if (!com.ktwo.cts.data.PerfIpcSampler.ipcOf(i).isNaN()) return true
        return false
    }

    /** Metric 尺寸公式: 行数 = 5 + Σ(每簇核数+1); 高 = max(136, 8.5×行+2)+12; 宽 = 165/134。 */
    private fun expandedSize(): Pair<Int, Int> {
        val s = lastTick?.snap
        val clusters = s?.clusters.orEmpty().filter { it.cpus.isNotEmpty() }
        val coreRows = if (clusters.isNotEmpty()) clusters.sumOf { it.cpus.size + 1 }
            else maxOf(s?.coreLoadPct?.size ?: 0, s?.coreMhz?.size ?: 0)
        val rows = 5 + coreRows
        val w = pxF(if (hasIpc()) 165f else 134f)
        val h = pxF(maxOf(136f, 8.5f * rows + 2f) + 12f)
        return w to h
    }

    private fun setExpanded(want: Boolean) {
        if (expanded == want) return
        expanded = want
        morph?.cancel()

        if (want) runCatching { renderGrid(lastTick) }
        val show: View = if (want) expandedBox else collapsedRow
        val hide: View = if (want) collapsedRow else expandedBox
        show.visibility = View.VISIBLE

        val (toW, toH) = if (want) expandedSize() else (px(124) to px(54))
        if (want) {
            // 展开内容锁定为目标固定尺寸(Metric 固定值), 形变期间只裁剪不重排
            val lpE = expandedBox.layoutParams as FrameLayout.LayoutParams
            lpE.width = toW; lpE.height = toH
            expandedBox.layoutParams = lpE
        }
        val fromW = if (shell.width > 0) shell.width else px(124)
        val fromH = if (shell.height > 0) shell.height else px(54)
        val lpS = shell.layoutParams as FrameLayout.LayoutParams

        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (want) 420 else 360   // Metric loadOverlayWidth/Height 节奏
            interpolator = metricEase()
            addUpdateListener {
                val f = it.animatedFraction
                lpS.width = (fromW + (toW - fromW) * f).toInt()
                lpS.height = (fromH + (toH - fromH) * f).toInt()
                shell.layoutParams = lpS
                show.alpha = f
                hide.alpha = 1f - f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(a: android.animation.Animator) { canceled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (canceled) return
                    hide.visibility = View.GONE
                    hide.alpha = 1f
                    lpS.width = toW; lpS.height = toH
                    shell.layoutParams = lpS
                }
            })
            start()
        }
    }

    // 弧色对齐 Metric 阈值(qb2.j): <70% 绿 / <90% 琥珀 / ≥90% 红
    private fun loadColor(pct: Int): Int = when {
        pct < 70 -> GREEN
        pct < 90 -> ORANGE
        else -> RED
    }

    override fun onTick(t: TickData) {
        lastTick = t
        val s = t.snap

        val cpu = (s?.cpuLoadPct ?: -1).coerceAtLeast(0)
        val topMhz = s?.clusters?.maxOfOrNull { it.mhz } ?: -1
        val cpuBelow = fmtMhz(topMhz)
        val gpu = (s?.gpuLoadPct ?: -1).coerceAtLeast(0)
        val gpuBelow = fmtMhz(s?.gpuMhz ?: -1)
        // 电量环 = 剩余电量, 颜色按耗电程度(低电量红); 环心数字由 onShown 随弧滚动
        val batt = s?.battPct ?: -1
        val battArc = batt.coerceAtLeast(0)
        val battColor = loadColor(if (batt >= 0) 100 - batt else 0)
        // Metric rb2.f: 电量环下小字 = 电池温度 "%.0f℃"
        val bt = t.battTempC
        val battBelow = if (bt > 0) String.format(Locale.US, "%.0f℃", bt) else "--℃"

        listOf(hCpu to vCpu, hGpu to vGpu, hBat to vBat).forEachIndexed { i, (h, v) ->
            val (arc, color, below) = when (i) {
                0 -> Triple(cpu, loadColor(cpu), cpuBelow)
                1 -> Triple(gpu, loadColor(gpu), gpuBelow)
                else -> Triple(battArc, battColor, battBelow)
            }
            h.ring.set(arc, color); v.ring.set(arc, color)
            h.below.text = below; v.below.text = below
        }

        // 形变期间不动内容(锁定尺寸下改文本会重排), 结束后下一拍自然刷新
        if (expanded && morph?.isRunning != true) {
            runCatching {
                renderGrid(t)
                // 行数/模式变化(如簇结构可用)时同步更新锁定尺寸
                val (w, h) = expandedSize()
                val lpE = expandedBox.layoutParams as FrameLayout.LayoutParams
                if (lpE.width != w || lpE.height != h) {
                    lpE.width = w; lpE.height = h
                    expandedBox.layoutParams = lpE
                    val lpS = shell.layoutParams as FrameLayout.LayoutParams
                    lpS.width = w; lpS.height = h
                    shell.layoutParams = lpS
                }
            }
        }
    }

    /**
     * 展开参数网格(1:1 Metric):
     *   #RAM 88%  148% / DDR 2706 / #CPU 66℃ / #0-5 1670MHz /
     *   CPU0 1055M IPC0.92(有IPC) 或 CPU0 55%(无IPC) / #FPS 50.1 / #PWR +69.40W
     */
    private fun renderGrid(t: TickData?) {
        val s = t?.snap
        val ipcOn = hasIpc()
        val rows = ArrayList<Pair<String, String>>()

        val ram = s?.ramUsedPct ?: -1
        val comb = s?.ramCombinedPct ?: -1
        rows.add("#RAM" to buildString {
            append(if (ram >= 0) "$ram%" else "--")
            if (comb >= 0) append("  ").append("$comb%")
        })
        val ddr = s?.ddrMhz ?: -1
        rows.add("DDR" to if (ddr > 0) "$ddr" else "--")
        val tc = s?.cpuTempC ?: -999.0
        rows.add("#CPU" to if (tc > 0) String.format(Locale.US, "%.0f℃", tc) else "--℃")

        val loads = s?.coreLoadPct.orEmpty()
        val freqs = s?.coreMhz.orEmpty()
        val clusters = s?.clusters.orEmpty().filter { it.cpus.isNotEmpty() }
        if (clusters.isNotEmpty()) {
            for (c in clusters) {
                val cpus = c.cpus.sorted()
                val range = if (cpus.size == 1) "${cpus.first()}"
                    else "${cpus.first()}-${cpus.last()}"
                rows.add("#$range" to if (c.mhz > 0) "${c.mhz}MHz" else "-- MHz")
                for (cpu in cpus) rows.add(coreRow(cpu, loads.getOrNull(cpu), freqs.getOrNull(cpu), ipcOn))
            }
        } else if (loads.isNotEmpty() || freqs.isNotEmpty()) {
            val n = maxOf(loads.size, freqs.size)
            for (cpu in 0 until n) rows.add(coreRow(cpu, loads.getOrNull(cpu), freqs.getOrNull(cpu), ipcOn))
        }

        val fps = s?.fps ?: -1
        rows.add("#FPS" to if (fps >= 0) String.format(Locale.US, "%.1f", fps.toFloat()) else "0.0")
        val w = s?.watt ?: 0.0
        rows.add("#PWR" to if (w != 0.0 && w.isFinite())
            String.format(Locale.US, "%+.2fW", w) else "--W")

        ensureGridRows(rows.size, ipcOn)
        rows.forEachIndexed { i, (l, v) ->
            gridRows[i].label.text = l
            gridRows[i].value.text = v
        }
    }

    // Met 截图每核行 = 负载% + 每核有效频率M + IPC(如 "59% 1078M IPC0.81");
    // 有效频率取 perf 计数器算的 cycles/秒(同簇也各核不同), 取不到退 scaling 频率。
    private fun coreRow(cpu: Int, load: Int?, mhz: Int?, ipcOn: Boolean): Pair<String, String> {
        val label = if (load != null && load >= 0) "$load%" else "--"
        if (!ipcOn) {
            // 无 perf: 值列显示每核 scaling 频率
            val f = if (mhz != null && mhz > 0) "${mhz}M" else "--"
            return label to f
        }
        val eff = com.ktwo.cts.data.PerfIpcSampler.effMhzOf(cpu)
        val fStr = when {
            eff > 0 -> "${eff}M"
            mhz != null && mhz > 0 -> "${mhz}M"
            else -> "--M"
        }
        val ipc = com.ktwo.cts.data.PerfIpcSampler.ipcOf(cpu)
        val ipcStr = if (ipc.isNaN()) "--" else String.format(Locale.US, "%.2f", ipc)
        return label to String.format(Locale.US, "%-5s IPC%s", fStr, ipcStr)
    }
}

/**
 * 迷你监视器(1:1 对齐 Metric kg2/wk2/oy2): 顶部居中 160×12dp 深色细条, 点击穿透。
 * 底色 #D91B1D22, 圆角 8dp, 内容 padding 横3/纵1, 段间距 1dp; 四段定宽 54/32/30/38dp:
 *  CPU(绿标+每核负载柱状图+最高簇频率) / GPU(蓝标+GPU频率) / FPS(金标, %.1f) / PWR(粉标, 功耗温度 9s 轮换)。
 * 文本 7sp 纯白等宽; 标签文本替代原版矢量图标(无图标资源), 颜色同图标 tint。
 */
class MiniOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {

    override val windowCornerDp = 8

    private val segGreen = Color.parseColor("#8DEB9A")
    private val segBlue = Color.parseColor("#8FB9FF")
    private val segGold = Color.parseColor("#FFD166")
    private val segPink = Color.parseColor("#FF8EA1")

    private fun cell(color: Int = WHITE) = TextView(ctx).apply {
        setTextColor(color)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 7f)   // Metric kg2.c: 7sp/8lh
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        maxLines = 1
    }

    private val bars = MiniCoreBars(ctx, segGreen)
    private val vCpu = cell()
    private val vGpu = cell()
    private val vFps = cell()
    private val vPwr = cell()

    private val barBg = GradientDrawable().apply {
        cornerRadius = px(8).toFloat()
        setColor(Color.argb(217, 27, 29, 34))   // Metric #D91B1D22
    }

    private val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(3), px(1), px(3), px(1))   // Metric: padding 横3/纵1
        background = barBg
    }
    override val root: View = bar

    /** 定宽段(Metric 54/32/30/38dp), 段内元素间距 1dp。 */
    private fun seg(widthDp: Int, vararg views: View) {
        val segBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(
                    if (v is MiniCoreBars) px(18) else LinearLayout.LayoutParams.WRAP_CONTENT,
                    if (v is MiniCoreBars) px(8) else LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (i > 0) marginStart = px(1) })
            }
        }
        bar.addView(segBox, LinearLayout.LayoutParams(
            px(widthDp), LinearLayout.LayoutParams.MATCH_PARENT).apply {
            if (bar.childCount > 0) marginStart = px(1)   // Metric 段间 spacedBy(1dp)
        })
    }

    init {
        seg(54, cell(segGreen).apply { text = "CPU" }, bars, vCpu)
        seg(32, cell(segBlue).apply { text = "GPU" }, vGpu)
        seg(35, cell(segGold).apply { text = "FPS" }, vFps)   // 文本标签比原版图标宽, 30->35 装下 '120.0'
        seg(40, cell(segPink).apply { text = "PWR" }, vPwr)   // 38->40 装下 '12.34W'
    }

    private var tick = 0L

    override fun onTick(t: TickData) {
        tick++
        val s = t.snap
        // Metric: CPU 段值 = 最高簇频率(裸数字), GPU 段值 = GPU 频率(裸数字)
        val topMhz = s?.clusters?.maxOfOrNull { it.mhz } ?: -1
        vCpu.text = if (topMhz > 0) "$topMhz" else "--"
        val gpuMhz = s?.gpuMhz ?: -1
        vGpu.text = if (gpuMhz > 0) "$gpuMhz" else "--"
        // 柱状图 = 每核瞬时负载(Metric k9)
        s?.coreLoadPct?.takeIf { it.isNotEmpty() }?.let { bars.set(it) }
        val fps = s?.fps ?: -1
        vFps.text = if (fps >= 0) String.format(Locale.US, "%.1f", fps.toFloat()) else "0.0"
        // 第四段: 功耗/温度 9s 轮换(前 2/3 功耗后 1/3 温度), 标签恒定不变(Metric 图标不变)
        if (tick % 9 < 6) {
            val w = s?.watt ?: 0.0
            vPwr.text = if (w != 0.0 && w.isFinite())
                String.format(Locale.US, "%+.2fW", w) else "--W"   // 带正负号(充电正/放电负)
        } else {
            val c = s?.cpuTempC ?: -999.0
            vPwr.text = if (c > 0) String.format(Locale.US, "%.1f℃", c) else "--℃"
        }
    }
}

/** 迷你条 CPU 段的每核负载柱状图(Metric k9): 柱宽 0.72×step, 圆角 1dp, 高度∝负载。 */
class MiniCoreBars(ctx: Context, barColor: Int) : View(ctx) {
    private var loads: IntArray = IntArray(0)
    private val dp = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val rect = RectF()

    fun set(coreLoads: List<Int>) {
        loads = IntArray(coreLoads.size) { coreLoads[it].coerceIn(0, 100) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = loads.size
        if (n == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val step = w / n
        val barW = maxOf(0.72f * step, 1f * dp)
        val usable = h - 1.5f * dp
        for (i in 0 until n) {
            val bh = maxOf(loads[i] / 100f * usable, 1f * dp)
            val x = i * step + (step - barW) / 2f
            val top = h - 1f * dp - bh
            rect.set(x, top, x + barW, h - 1f * dp)
            canvas.drawRoundRect(rect, 1f * dp, 1f * dp, paint)
        }
    }
}

/**
 * 帧率记录器(1:1 对齐 Metric qb1/xb1/eb1): 双态窗。
 *  - 收起 = 固定 40×26dp 胶囊(12sp 帧率, 空闲 0xB7D29A04/录制 0xB7D32F2F);
 *  - 展开 = 固定 268×202dp 面板(圆角 18/padding 9): 顶排 30dp 双圆钮+状态丸,
 *    整行录制钮(高22, 绿/红), {5,10,15,30}分 等分时长片(录制中禁点),
 *    '当前'+22sp 大数字, 温度(琥珀)/平均/最小/最大(白74, 间距7), 74dp 发光图表;
 *  - 形变 420/360ms CubicBezier(0.15,1,0.3,1);
 *  - 玻璃模糊状态机(Metric xb1.b): 建窗 0 → 展开 290 → 收起 180ms 后 100,
 *    收起态拖动立即 100, 每次激活 10.18s 后 7 步×50ms 淡出到 0。
 */
class FpsOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {

    private val idleTint = Color.argb(0xB7, 0xD2, 0x9A, 0x04)
    private val recTint = Color.argb(0xB7, 0xD3, 0x2F, 0x2F)
    private val panelTint = withAlpha(glassBaseColor(), alpha)   // 液态玻璃面板底
    private val amber = Color.parseColor("#FFA726")
    private val recGreen = Color.argb(0x80, 0x4C, 0xAF, 0x50)
    private val recRed = Color.argb(0x94, 0xD3, 0x2F, 0x2F)
    private val chipIdleBg = Color.argb(0xB8, 27, 29, 34)     // colorScheme.p@0.72 近似
    private val pillBg = Color.argb(0x94, 27, 29, 34)         // 状态丸底 @0.58

    // ── 收起态胶囊: 固定 40×26 ──
    private val capsule = TextView(ctx).apply {
        text = "0.0"
        setTextColor(Color.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(px(2), px(5), px(2), px(5))
        maxLines = 1
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph
        setTextColor(WHITE74)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(26, 255, 255, 255))
        }
        setOnClickListener { runCatching { onClick() } }
    }

    private fun pillText(sizeSp: Float, color: Int = Color.WHITE) = TextView(ctx).apply {
        setTextColor(color)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizeSp)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
    }

    private val btnCollapse = iconBtn("⌄") { setExpanded(false) }
    private val btnClose = iconBtn("✕") { onCloseRequest?.invoke() }

    private val statusDot = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
    }
    private val statusText = pillText(10f, WHITE86)
    private val statusPill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumWidth = px(50)
        setPadding(px(7), px(3), px(7), px(3))
        background = GradientDrawable().apply {
            cornerRadius = px(11).toFloat()
            setColor(pillBg)
        }
        addView(statusDot, LinearLayout.LayoutParams(px(6), px(6)))
        addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(5) })
        visibility = View.GONE
    }
    private var dotAnim: ValueAnimator? = null

    private val recBtn = pillText(10f).apply {
        text = "记录"
        setPadding(px(9), px(4), px(9), px(4))
        background = GradientDrawable().apply {
            cornerRadius = px(11).toFloat()
            setColor(recGreen)
        }
        setOnClickListener { runCatching { toggleRecord() } }
    }

    // 时长片: Metric 仅 {5,10,15,30}分, 等分整行, 无选中=不限时, 录制中禁点
    private val limits = intArrayOf(5, 10, 15, 30)
    private var limitMin = 0
    private val chipViews = ArrayList<TextView>()
    private val chipsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        limits.forEachIndexed { i, m ->
            val chip = pillText(9f, WHITE74).apply {
                text = "${m}分"
                background = GradientDrawable().apply {
                    cornerRadius = px(8).toFloat()
                    setColor(chipIdleBg)
                }
                setOnClickListener {
                    if (recState().recording) return@setOnClickListener
                    limitMin = if (limitMin == m) 0 else m   // 点已选中片取消 = 不限时
                    refreshChips()
                }
            }
            chipViews.add(chip)
            addView(chip, LinearLayout.LayoutParams(0, px(22), 1f).apply {
                if (i > 0) marginStart = px(4)
            })
        }
    }

    private val tCurLabel = pillText(9f, WHITE74).apply { text = "当前" }
    private val tBig = pillText(22f, accent)
    private fun statCell(label: String, labelColor: Int): Pair<LinearLayout, TextView> {
        val l = pillText(9f, labelColor).apply { text = label }
        val v = pillText(13f, WHITE74)
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
    private val cAvg = statCell("平均", WHITE74)
    private val cMin = statCell("最小", WHITE74)
    private val cMax = statCell("最大", WHITE74)

    private val chart = FpsChartView(ctx, accent)

    // 面板纵向节奏(Metric): 顶排→录制钮 4, →时长片 13, →当前 13, →大数字 4, →统计 4, →图表 13
    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(9), px(9), px(9), px(9))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnCollapse, LinearLayout.LayoutParams(px(30), px(30)))
            addView(btnClose, LinearLayout.LayoutParams(px(30), px(30)).apply { marginStart = px(4) })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(statusPill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, px(22)))
        })
        addView(recBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, px(22)).apply { topMargin = px(4) })
        addView(chipsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, px(22)).apply { topMargin = px(13) })
        addView(tCurLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(13) })
        addView(tBig, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cTemp.first)
            listOf(cAvg, cMin, cMax).forEach {
                addView(it.first, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(7) })
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        addView(chart, LinearLayout.LayoutParams(px(250), px(74)).apply { topMargin = px(13) })
        visibility = View.GONE
        this.alpha = 0f
    }

    private val shellBg = GradientDrawable().apply { setColor(idleTint) }
    private val shell = FrameLayout(ctx).apply {
        clipChildren = true
        background = shellBg
        addView(capsule, FrameLayout.LayoutParams(px(40), px(26), Gravity.CENTER))
        addView(panel, FrameLayout.LayoutParams(px(268), FrameLayout.LayoutParams.WRAP_CONTENT))
    }
    override val root: View = FrameLayout(ctx).apply {
        clipChildren = true
        addView(shell, FrameLayout.LayoutParams(px(40), px(26)))
    }

    private var expandedState = false
    private var morphAnim: ValueAnimator? = null
    override val isExpandedWindow: Boolean get() = expandedState

    // ── 玻璃模糊状态机(Metric xb1.b/e) ──
    private val blurH = android.os.Handler(android.os.Looper.getMainLooper())
    private fun setBlur(r: Int) { blurSetter?.invoke(r) }
    private fun cancelBlurJobs() = blurH.removeCallbacksAndMessages(null)
    private fun armFadeOut() {
        blurH.postDelayed({
            for (i in 0..6) blurH.postDelayed({ setBlur(100 - 100 * i / 6) }, i * 50L)
        }, 10180)
    }
    private fun glassPulse() {   // 收起态激活: 180ms 后 100, 10.18s 后淡出
        cancelBlurJobs()
        blurH.postDelayed({ setBlur(100); armFadeOut() }, 180)
    }
    private fun glassTouch() {   // 收起态触碰: 立即 100
        cancelBlurJobs()
        setBlur(100)
        armFadeOut()
    }
    override fun onDragged() { if (!expandedState) glassTouch() }

    init {
        shell.post { shellBg.cornerRadius = shell.height / 2f }
        refreshChips()
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!expandedState) glassPulse()
            }
            override fun onViewDetachedFromWindow(v: View) {
                morphAnim?.cancel(); morphAnim = null
                dotAnim?.cancel(); dotAnim = null
                cancelBlurJobs()
            }
        })
    }

    override fun onTap() {
        if (!expandedState) setExpanded(true)
    }

    private fun setExpanded(want: Boolean) {
        if (expandedState == want) return
        val fromC = currentTint()   // 必须在翻转状态前取
        expandedState = want
        morphAnim?.cancel()

        // 模糊状态机: 展开 290; 收起 180ms 后 100 再淡出
        if (want) { cancelBlurJobs(); setBlur(290) } else glassPulse()

        val show: View = if (want) panel else capsule
        val hide: View = if (want) capsule else panel
        show.visibility = View.VISIBLE

        val toW = if (want) px(268) else px(40)
        // 展开高按内容实测(View 排版比 Metric Compose 松, 硬锁原版 202 会把图表整块裁掉);
        // 量完锁定为像素高, 形变期间只裁剪不重排
        val toH: Int
        if (want) {
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(px(268), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            toH = maxOf(panel.measuredHeight, px(202))
            val lpP = panel.layoutParams as FrameLayout.LayoutParams
            if (lpP.height != toH) { lpP.height = toH; panel.layoutParams = lpP }
        } else {
            toH = px(26)
        }
        val fromW = if (shell.width > 0) shell.width else px(40)
        val fromH = if (shell.height > 0) shell.height else px(26)
        val fromR = shellBg.cornerRadius
        val toR = if (want) px(18).toFloat() else toH / 2f
        val toC = if (want) panelTint else recordingTint()
        val lpS = shell.layoutParams as FrameLayout.LayoutParams
        val ev = android.animation.ArgbEvaluator()

        morphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (want) 420 else 360
            interpolator = metricEase()
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
                private var canceled = false
                override fun onAnimationCancel(a: android.animation.Animator) { canceled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (canceled) return
                    hide.visibility = View.GONE
                    hide.alpha = 1f
                    lpS.width = toW; lpS.height = toH
                    shell.layoutParams = lpS
                }
            })
            start()
        }
    }

    private fun recState() = com.ktwo.cts.service.FpsRecordService.state.value

    private fun currentTint(): Int =
        if (expandedState) panelTint else recordingTint()

    private fun recordingTint(): Int = if (recState().recording) recTint else idleTint

    private fun toggleRecord() {
        val st = recState()
        if (st.active) com.ktwo.cts.service.FpsRecordService.stop(ctx)
        else com.ktwo.cts.service.FpsRecordService.start(ctx, null, limitMin * 60)
    }

    private fun refreshChips() {
        chipViews.forEachIndexed { i, chip ->
            val sel = limits[i] == limitMin
            (chip.background as GradientDrawable).setColor(
                if (sel) (accent and 0x00FFFFFF) or (0xBD shl 24) else chipIdleBg)
            chip.setTextColor(if (sel) Color.WHITE else WHITE74)
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
        val fpsStr = if (fps >= 0) String.format(Locale.US, "%.1f", fps.toFloat()) else "0.0"
        capsule.text = fpsStr
        if (fps >= 0) chart.push(fps)

        val st = recState()
        if (!expandedState && morphAnim?.isRunning != true) shellBg.setColor(recordingTint())
        if (!expandedState) {
            setDotBreathing(false)
            return
        }

        tBig.text = fpsStr
        // Metric rb1.b: 温度统计格 = 电池温度
        val bt = t.battTempC
        cTemp.second.text = if (bt > 0) String.format(Locale.US, "%.1f℃", bt) else "--℃"
        val pts = chart.values()
        cAvg.second.text = if (pts.isEmpty()) "--" else String.format(Locale.US, "%.1f", pts.average())
        cMin.second.text = if (pts.isEmpty()) "--" else "${pts.min()}"
        cMax.second.text = if (pts.isEmpty()) "--" else "${pts.max()}"

        if (st.active) {
            statusPill.visibility = View.VISIBLE
            if (st.recording) {
                (statusDot.background as GradientDrawable).setColor(RED)
                statusText.text = "录制中 " + fmtElapsed(st.elapsedSec)
            } else {
                (statusDot.background as GradientDrawable).setColor(ORANGE)
                statusText.text = "等待进入目标应用…"
            }
            setDotBreathing(true)
            recBtn.text = "停止"
            (recBtn.background as GradientDrawable).setColor(recRed)
        } else {
            statusPill.visibility = View.GONE
            setDotBreathing(false)
            recBtn.text = "记录"
            (recBtn.background as GradientDrawable).setColor(recGreen)
        }
        // 时长片: 录制中禁点(变灰由透明度体现)
        chipsRow.alpha = if (st.recording) 0.5f else 1f
    }
}

/**
 * 帧率曲线图(1:1 对齐 Metric eb1): 10dp 圆角深色底(p@0.64) + 横向网格线 +
 * 原版 7 档自动量程(最小 120) + 9sp 粗体刻度 + 中点二次贝塞尔平滑 +
 * 渐变填充(primary@0.25, 曲线顶→基线) + 7 层霓虹辉光(基准线宽 1.92dp) + 端点双圈(15/9px)。
 */
class FpsChartView(ctx: Context, private val accent: Int) : View(ctx) {
    private val data = ArrayDeque<Int>()
    private val cap = 120
    private val dp = ctx.resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(163, 27, 29, 34) }
    private val gridPaint = Paint().apply {
        color = Color.argb(50, 255, 255, 255)
        strokeWidth = 1f * dp
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(158, 255, 255, 255)
        textSize = 9 * dp
        typeface = Typeface.DEFAULT_BOLD
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

    // Metric qb1.<clinit>: 7 层辉光(宽度倍率, 透明度)
    private val glow = arrayOf(
        4.4f to 0.035f, 3.8f to 0.055f, 3.2f to 0.08f, 2.6f to 0.11f,
        2.1f to 0.145f, 1.7f to 0.18f, 1.35f to 0.22f,
    )

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

        // Metric eb1 七档量程(最小恒 120), 过渡档 top=实际峰值
        val maxV = data.maxOrNull() ?: 60
        val pair = when {
            maxV <= 120 -> 120f to intArrayOf(0, 30, 60, 90, 120)
            maxV <= 125 -> maxV.toFloat() to intArrayOf(0, 30, 60, 90, 120)
            maxV <= 144 -> 144f to intArrayOf(0, 60, 90, 120, 144)
            maxV <= 148 -> maxV.toFloat() to intArrayOf(0, 60, 90, 120, 144)
            maxV <= 165 -> 165f to intArrayOf(0, 90, 120, 144, 165)
            maxV <= 168 -> maxV.toFloat() to intArrayOf(0, 90, 120, 144, 165)
            maxV <= 185 -> 185f to intArrayOf(0, 120, 144, 165, 185)
            else -> maxV.toFloat() to intArrayOf(0, 120, 144, 165, 185)
        }
        val top = pair.first
        val ticks = pair.second

        // 刻度 + 横向网格线(Metric: 标签槽左 26dp, 右留 8dp, 上留 2dp)
        val leftPad = 26 * dp; val rightPad = 8 * dp; val vPad = 2 * dp
        val chartH = h - vPad * 2
        for (tv in ticks) {
            val y = h - vPad - tv / top * chartH
            canvas.drawLine(leftPad, y, w - rightPad, y, gridPaint)
            canvas.drawText("$tv", leftPad - 4 * dp, y + 3 * dp, labelPaint)
        }

        val stepX = (w - leftPad - rightPad) / (cap - 1)
        val start = cap - data.size
        val xs = FloatArray(data.size); val ys = FloatArray(data.size)
        data.forEachIndexed { i, v ->
            xs[i] = leftPad + (start + i) * stepX
            ys[i] = h - vPad - (v.coerceAtMost(top.toInt()) / top) * chartH
        }

        // 中点二次贝塞尔平滑(Metric qb1.e)
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until data.size) {
            val mx = (xs[i - 1] + xs[i]) / 2f
            val my = (ys[i - 1] + ys[i]) / 2f
            path.quadTo(xs[i - 1], ys[i - 1], mx, my)
        }
        path.lineTo(xs.last(), ys.last())

        // 渐变填充: primary@0.25, 从曲线最高点到基线(Metric zz3.E)
        fillPath.set(path)
        fillPath.lineTo(xs.last(), h - vPad)
        fillPath.lineTo(xs[0], h - vPad)
        fillPath.close()
        val topY = ys.min()
        fillPaint.shader = android.graphics.LinearGradient(
            0f, topY, 0f, h - vPad,
            (accent and 0x00FFFFFF) or (0x40 shl 24), Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)

        // 7 层霓虹辉光 + 主线(Metric 基准线宽 3dp×0.64 = 1.92dp)
        val base = 1.92f * dp
        glow.forEach { (mul, a) ->
            linePaint.strokeWidth = base * mul
            linePaint.color = (accent and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            canvas.drawPath(path, linePaint)
        }
        linePaint.strokeWidth = base
        linePaint.color = accent
        canvas.drawPath(path, linePaint)

        // 端点双圈(Metric 裸像素 15/9)
        dotPaint.color = (accent and 0x00FFFFFF) or (0x99 shl 24)
        canvas.drawCircle(xs.last(), ys.last(), 15f, dotPaint)
        dotPaint.color = (accent and 0x00FFFFFF) or (0xE6 shl 24)
        canvas.drawCircle(xs.last(), ys.last(), 9f, dotPaint)
    }
}

/**
 * 温度监视器(1:1 对齐 Metric wc4/vc4): 固定 83×54dp / 18dp 圆角 / padding 12×7,
 * BAT/CPU/GPU/DDR 每行 "%s %.1f℃"(行高 10dp, 8.5sp 统一白), temp≤0 整行隐藏,
 * 全空保持空玻璃卡(无占位文案); 长按关闭。
 */
class TempOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(12), px(7), px(12), px(7))
        gravity = Gravity.CENTER
        background = cardBg(18)
    }
    private fun tempRow() = mono(8.5f).apply { gravity = Gravity.CENTER_VERTICAL }
    private val tBat = tempRow()
    private val tCpu = tempRow()
    private val tGpu = tempRow()
    private val tDdr = tempRow()
    override val root: View = FrameLayout(ctx).apply {
        addView(card, FrameLayout.LayoutParams(px(83), px(54)))   // Metric 精确 83×54
    }
    init {
        listOf(tBat, tCpu, tGpu, tDdr).forEach {
            card.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, px(10)))   // Metric 行高 10dp
        }
    }

    override fun onLongPress() {
        onCloseRequest?.invoke()
    }

    /** Metric wc4.d: temp≤0 → 整行隐藏。 */
    private fun line(tv: TextView, label: String, c: Double) {
        if (c <= 0.0) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        tv.text = String.format(Locale.US, "%s %.1f℃", label, c)
    }

    override fun onTick(t: TickData) {
        line(tBat, "BAT", t.battTempC)
        val soc = t.snap?.cpuTempC ?: -999.0
        line(tCpu, "CPU", soc)
        line(tGpu, "GPU", t.snap?.gpuTempC ?: -999.0)
        line(tDdr, "DDR", t.snap?.ddrTempC ?: -999.0)
    }
}

/**
 * 进程监视器(1:1 对齐 Metric eb3/pc1): 纵向 padding 10, 标题'进程' 11sp 纯白粗体(weight1) +
 * 72×24dp 分段滑块筛选(全部/应用, 高亮块 34×20 白18, 180ms 平移) + 24dp 圆形关闭钮;
 * 列表恒定 165dp 高滚动 top-30, 行高 21dp/距 3dp: 圆角图标 16dp + 名称 9sp 粗(weight1) + CPU% 10sp 白74;
 * 行双击结束进程, 长按打开线程监视; 空数据显示 96dp 居中空态。
 */
class ProcessOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {

    private class Line(val box: LinearLayout, val icon: ImageView, val name: TextView, val mem: TextView, val pct: TextView)

    private val card = verticalCard().apply {
        setPadding(px(10), px(10), px(10), px(10))
    }
    private val tHead = mono(11f).apply { text = "进程" }
    private var appsOnly = false
    private var lastProcs: List<ProcSampler.Proc>? = null

    // ── 分段滑块筛选(Metric pc1.l): 72×24 轨道 + 34×20 白18 高亮块 180ms 平移 ──
    private fun tab(label: String, onClick: () -> Unit) = mono(8f, GREY, bold = false).apply {
        text = label
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
    private val tabAll = tab("全部") { setMode(false) }
    private val tabApp = tab("应用") { setMode(true) }
    private val indicator = View(ctx).apply {
        background = GradientDrawable().apply {
            cornerRadius = px(10).toFloat()
            setColor(Color.argb(46, 255, 255, 255))   // Metric 高亮块 白@0.18
        }
    }
    private val segTrack = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            setColor(Color.argb(26, 255, 255, 255))   // 轨道底 白@0.10
        }
        setPadding(px(2), px(2), px(2), px(2))
        addView(indicator, FrameLayout.LayoutParams(px(34), px(20)))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabAll, LinearLayout.LayoutParams(px(34), px(20)))
            addView(tabApp, LinearLayout.LayoutParams(px(34), px(20)))
        })
    }

    private val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(tHead, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(segTrack, LinearLayout.LayoutParams(px(72), px(24)))
        addView(circleBtn("✕", 10f) { onCloseRequest?.invoke() },
            LinearLayout.LayoutParams(px(24), px(24)).apply { marginStart = px(4) })
    }

    private val lines = ArrayList<Line>()
    private val linePkgs = arrayOfNulls<String>(30)
    private val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var listScroll: View? = null
    private val emptyView = mono(9f).apply {
        text = "无进程数据"
        gravity = Gravity.CENTER
        minimumHeight = px(96)
        setPadding(px(12), 0, px(12), 0)
        visibility = View.GONE
    }
    override val root: View = card

    init {
        styleTabs()
        card.addView(header, LinearLayout.LayoutParams(
            px(168), LinearLayout.LayoutParams.WRAP_CONTENT))
        // Metric pc1.n: 行高 21 / 图标 16(圆角6裁剪) / 名称 9sp 粗 weight1 / CPU% 10sp 白74 / 行距 3
        repeat(30) { i ->
            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, (6 * dp))
                    }
                }
                clipToOutline = true
            }
            val name = mono(9f).apply {
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
            }
            // 内存列(取 Scene 之长: 每进程 RES 内存, MB/GB 自适应)。9sp 白74, 右对齐。
            val mem = mono(9f, WHITE74).apply { gravity = Gravity.END }
            val pct = mono(10f, WHITE74)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams(px(16), px(16)))
                addView(name, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(4) })
                addView(mem, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
                addView(pct, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
            }
            // Metric 行手势: 双击结束进程, 长按打开线程监视
            val gd = android.view.GestureDetector(ctx,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: android.view.MotionEvent) = true
                    override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                        killRow(i); return true
                    }
                    override fun onLongPress(e: android.view.MotionEvent) { openThreads() }
                })
            box.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
            lines.add(Line(box, icon, name, mem, pct))
            listBox.addView(box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(21)).apply {
                if (i > 0) topMargin = px(3)
            })
        }
        val scroll = MaxHeightScrollView(ctx, px(165), exact = true).apply { addView(listBox) }
        listScroll = scroll
        card.addView(scroll, LinearLayout.LayoutParams(
            px(168), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(5) })
        card.addView(emptyView, LinearLayout.LayoutParams(
            px(168), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun styleTabs() {
        tabAll.setTextColor(if (!appsOnly) WHITE92 else GREY)
        tabAll.typeface = Typeface.create(Typeface.MONOSPACE,
            if (!appsOnly) Typeface.BOLD else Typeface.NORMAL)
        tabApp.setTextColor(if (appsOnly) WHITE92 else GREY)
        tabApp.typeface = Typeface.create(Typeface.MONOSPACE,
            if (appsOnly) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun setMode(only: Boolean) {
        if (appsOnly == only) return
        appsOnly = only
        // Metric ProcessFilterTransition: 高亮块 180ms 平移
        indicator.animate().translationX(if (only) px(34).toFloat() else 0f)
            .setDuration(180).setInterpolator(metricEase()).start()
        styleTabs()
        runCatching { render() }
    }

    // 双击结束进程(root am force-stop) + 提示(Metric process_overlay_kill_requested)
    private fun killRow(i: Int) {
        val pkg = linePkgs.getOrNull(i) ?: return
        Thread {
            runCatching { com.ktwo.cts.root.RootShell.execSilent("am force-stop $pkg") }
        }.start()
        runCatching {
            android.widget.Toast.makeText(ctx, "已请求结束: $pkg", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 长按打开线程监视窗(Metric: Long press for threads)
    private fun openThreads() {
        runCatching { FloatWindowService.show(ctx, com.ktwo.cts.data.OverlayType.THREAD) }
    }

    // ── 图标/应用判定缓存(进程名 → Drawable / 是否用户应用)──
    private val iconCache = HashMap<String, Drawable?>()
    private val userAppCache = HashMap<String, Boolean>()
    private val defaultIcon: Drawable? by lazy {
        runCatching { ctx.getDrawable(android.R.drawable.sym_def_app_icon) }.getOrNull()
    }

    private fun pkgOf(name: String) = name.substringBefore(':').trim()

    // RES 内存格式化(对齐 Scene: >8192KB 显示 MB, 更大显示 GB): >=1024MB → x.xG, 否则 xM; <0(未知)留空。
    private fun memStr(rssMb: Int): String =
        if (rssMb < 0) ""
        else if (rssMb >= 1024) String.format(Locale.US, "%.1fG", rssMb / 1024f)
        else "${rssMb}M"

    private fun iconFor(name: String): Drawable? {
        if (!iconCache.containsKey(name)) {
            if (iconCache.size > 256) iconCache.clear()
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
        val ps = if (appsOnly) all.orEmpty().filter { isUserApp(it.name) } else all.orEmpty()
        val empty = ps.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listScroll?.visibility = if (empty) View.GONE else View.VISIBLE
        lines.forEachIndexed { i, l ->
            val p = ps.getOrNull(i)
            linePkgs[i] = p?.let { pkgOf(it.name) }
            if (p == null) {
                l.box.visibility = View.GONE
            } else {
                l.box.visibility = View.VISIBLE
                l.icon.setImageDrawable(runCatching { iconFor(p.name) }.getOrNull())
                l.name.text = p.name
                l.mem.text = memStr(p.rssMb)
                l.pct.text = String.format(Locale.US, "%.1f%%", p.cpuPct)
            }
        }
    }
}

/**
 * 线程监视器(1:1 对齐 Metric ya0/ph2): padding 8×8;
 * 表头左列竖排 = 前台应用名(11sp 纯白粗体, 最多2行) + 包名行 + 'PID %d' 行(7sp 白74),
 * 右上 30dp 圆形关闭钮; 列宽 CPU% 26 / CPUS 34 / TID 30 / COMM weight(表头 7sp 白80, 行 7sp 白74, 行距 2);
 * 列表 96~126dp 滚动; 空态 96dp 居中双行文案。
 */
class ThreadOverlayWindow(ctx: Context, accent: Int, alpha: Int, textSp: Float) :
    OverlayWindow(ctx, accent, alpha, textSp) {
    private val card = verticalCard().apply {
        setPadding(px(8), px(8), px(8), px(8))
    }
    private val tHead = mono(11f).apply {
        text = "线程"
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val tPkg = mono(7f, WHITE74).apply { visibility = View.GONE }
    private val tPid = mono(7f, WHITE74).apply { visibility = View.GONE }
    private val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(tHead)
            addView(tPkg, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2) })
            addView(tPid, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(circleBtn("✕") { onCloseRequest?.invoke() },
            LinearLayout.LayoutParams(px(30), px(30)).apply { marginStart = px(6) })
    }

    /** 四列行(Metric ph2.j/l): 26/34/30/weight。 */
    private class TRow(val box: LinearLayout, val c: Array<TextView>)

    private fun makeRow(color: Int): TRow {
        val widths = intArrayOf(26, 34, 30, -1)
        val cells = Array(4) {
            mono(7f, color).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(2), 0, px(2), 0)
            cells.forEachIndexed { i, c ->
                addView(c, if (widths[i] > 0)
                    LinearLayout.LayoutParams(px(widths[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        return TRow(box, cells)
    }

    private val headRow = makeRow(WHITE80).apply {
        c[0].text = "CPU%"; c[1].text = "CPUS"; c[2].text = "TID"; c[3].text = "COMM"
    }
    private val rows = ArrayList<TRow>()
    private val listBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = px(96)   // Metric: 线程表 heightIn 96~126dp
    }
    private var listScroll: View? = null
    // 空态(Metric ph2.h): 96dp 居中双行
    private val emptyBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = px(96)
        setPadding(px(12), 0, px(12), 0)
        addView(mono(9f).apply { text = "无线程数据"; gravity = Gravity.CENTER })
        addView(mono(7f, GREY).apply {
            text = "目标进程可能已退出, 或尚未产生采样"
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(5) })
        visibility = View.GONE
    }
    override val root: View = card
    init {
        card.addView(header, LinearLayout.LayoutParams(
            px(176), LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(headRow.box, LinearLayout.LayoutParams(
            px(176), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(5) })
        repeat(24) {
            val r = makeRow(WHITE74)
            rows.add(r)
            listBox.addView(r.box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2) })
        }
        val scroll = MaxHeightScrollView(ctx, px(126)).apply { addView(listBox) }
        listScroll = scroll
        card.addView(scroll, LinearLayout.LayoutParams(
            px(176), LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(emptyBox, LinearLayout.LayoutParams(
            px(176), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    override fun onTick(t: TickData) {
        // 表头(前台应用)每拍都可刷新, 与线程表是否采到无关
        runCatching {
            tHead.text = t.fgLabel.ifEmpty { t.fgPkg.ifEmpty { "线程" } }
            if (t.fgPkg.isNotEmpty()) { tPkg.text = t.fgPkg; tPkg.visibility = View.VISIBLE }
            else tPkg.visibility = View.GONE
            if (t.fgPid > 0) { tPid.text = "PID ${t.fgPid}"; tPid.visibility = View.VISIBLE }
            else tPid.visibility = View.GONE
        }
        val ts = t.threads ?: return   // null=本拍未采, 保持上次
        val empty = ts.isEmpty()
        emptyBox.visibility = if (empty) View.VISIBLE else View.GONE
        listScroll?.visibility = if (empty) View.GONE else View.VISIBLE
        headRow.box.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) return
        rows.forEachIndexed { i, r ->
            val th = ts.getOrNull(i)
            if (th == null) { r.box.visibility = View.GONE } else {
                r.box.visibility = View.VISIBLE
                r.c[0].text = String.format(Locale.US, "%.1f", th.cpuPct)
                r.c[1].text = if (th.affinity.isEmpty()) "-"
                    else th.affinity + (if (th.core >= 0) "[${th.core}]" else "")
                r.c[2].text = "${th.tid}"
                r.c[3].text = th.name.ifEmpty { "-" }
            }
        }
    }
}
