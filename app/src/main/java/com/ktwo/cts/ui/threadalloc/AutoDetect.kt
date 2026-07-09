// 线程规则「自动分配」的共享判定逻辑(供 ThreadAllocScreen 首页与 ThreadRulesScreen 编辑器复用)。
// 效果对齐 JZzz: 先按系统类别 + 关键字判「游戏 vs 应用」, 再按套 JZzz 三套模板生成规则(核区间自适应);
// 应用正在运行时额外读实时线程精确区分 Unity/UE(比 JZzz 更准), 读不到线程则退回 JZzz 的默认 Unity。
package com.ktwo.cts.ui.threadalloc

import android.content.Context
import android.content.pm.ApplicationInfo
import com.ktwo.cts.data.TaClusterPlan
import com.ktwo.cts.data.ThreadAllocRules
import com.ktwo.cts.data.ThreadAllocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 应用标签(尽力解析, 未安装/系统进程名显示原文)。 */
internal fun labelOf(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg.substringBefore(':'), 0)).toString()
}.getOrDefault(pkg)

/** 系统是否把该应用标为游戏(ApplicationInfo.category)。取不到按非游戏处理。 */
internal fun isGameCategory(ctx: Context, pkg: String): Boolean = runCatching {
    ctx.packageManager.getApplicationInfo(pkg.substringBefore(':'), 0)
        .category == ApplicationInfo.CATEGORY_GAME
}.getOrDefault(false)

/** 综合判定是否游戏: 系统类别 或 JZzz 关键字(包名+应用名)命中 —— 与 JZzz 自动分类同口径。 */
internal fun isGameApp(ctx: Context, pkg: String): Boolean =
    isGameCategory(ctx, pkg) || ThreadAllocRules.isGameByName(pkg, labelOf(ctx, pkg))

/**
 * 自动识别并生成规则: 应用在运行则读实时线程名判引擎类型, 否则按类别/关键字判定, 再套 JZzz 模板生成。
 * 返回 AutoResult(含判定来源, 供 UI 如实告知)。全程 IO 线程, 失败不抛出。
 */
internal suspend fun autoDetectRules(
    ctx: Context,
    pkg: String,
    plan: TaClusterPlan,
): ThreadAllocRules.AutoResult = withContext(Dispatchers.IO) {
    val pid = runCatching { ThreadAllocator.pidOf(pkg) }.getOrNull()
    val names = if (pid != null)
        runCatching { ThreadAllocator.listThreads(pid).map { it.name } }.getOrDefault(emptyList())
    else emptyList()
    ThreadAllocRules.autoGenerate(pkg, plan, names, isGameApp(ctx, pkg))
}

/** 如实描述识别来源(不把没读到线程的兜底猜测说成"已识别")。 */
internal fun detectLabel(res: ThreadAllocRules.AutoResult): String = when (res.source) {
    ThreadAllocRules.Source.LIVE     -> "据实时线程识别为「${res.preset.name}」"
    ThreadAllocRules.Source.CATEGORY -> "据类别/关键字识别为「${res.preset.name}」"
    ThreadAllocRules.Source.FALLBACK -> "未读到线程(应用未运行?), 已按「${res.preset.name}」兜底"
}
