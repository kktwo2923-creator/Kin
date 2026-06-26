package com.ktwo.cts.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.*
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBar
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBarBlur
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

private data class TopDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

// 关闭智能调度时的常规 tab(含 CPU / 应用)
private val TABS_NORMAL = listOf(
    TopDest("home",     "首页", CtsIcons.Speed),
    TopDest("cpu",      "CPU", Icons.Outlined.Memory),
    TopDest("apps",     "应用", CtsIcons.Apps),
    TopDest("logs",     "日志", CtsIcons.Logs),
    TopDest("settings", "设置", CtsIcons.Settings),
)

// 开启智能调度时:隐藏 CPU / 应用,改为「监控」面板(手动档无意义)
private val TABS_SMART = listOf(
    TopDest("home",     "首页", CtsIcons.Speed),
    TopDest("monitor",  "监控", Icons.Outlined.Insights),
    TopDest("records",  "记录", Icons.Outlined.Timeline),
    TopDest("logs",     "日志", CtsIcons.Logs),
    TopDest("settings", "设置", CtsIcons.Settings),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val curRoute = entry?.destination?.hierarchy
    val hazeState = remember { HazeState() }
    val ctx = LocalContext.current
    val view = LocalView.current
    val wallpaperUri by Prefs.wallpaperUriFlow(ctx).collectAsState(initial = "")

    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    // 同 SukiSU MainScreen: backdrop 供悬浮底栏液态玻璃折射(整页内容+壁纸)
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    // 智能调度是否开启 → 决定底栏 tab 集(开:监控/记录;关:CPU/应用)。
    // 唯一可信来源是持久化的 Prefs(开关时由 AutoModeVm 写入), 稳定不抖 —— tab 集不会
    // 反复重算, 底栏也就不会把刚点的页签弹回。启动时探测 root 一次同步真实状态:
    // 读到 null(root 未就绪)就稍后再试, 一旦读到确定值即落盘并【停止轮询】(不再无限轮询,
    // 那正是上一版让 tab 集持续churn、点击被弹回的祸根)。
    val persistedSmart by Prefs.smartEnabledFlow(ctx).collectAsState(initial = null)
    LaunchedEffect(Unit) {
        while (true) {
            val probed = withContext(Dispatchers.IO) { RootShell.probeSmartOn() }
            if (probed != null) {
                Prefs.setSmartEnabled(ctx, probed)   // DataStore 同值不触发变更, 不会churn
                break
            }
            delay(800)   // root 未就绪, 稍后重试
        }
    }
    val smartOn: Boolean? = persistedSmart
    val smart = smartOn == true
    val tabs = if (smart) TABS_SMART else TABS_NORMAL

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        val selectedIndex = tabs.indexOfFirst { d ->
            curRoute?.any { it.route == d.route } == true
        }.coerceAtLeast(0)
        val curDestRoute = entry?.destination?.route
        // 切换智能调度后, 若正停在【另一套 tab 才有】的页面(智能开却停在 CPU/应用,
        // 或智能关却停在 监控/记录), 回首页一次。【只 key 在 smartOn 上】——
        // 不再 key curDestRoute, 否则每次点 tab(路由变化)都会重跑本效应; 仅在真正切换
        // 智能开关时才触发, 此时才需要这次跳转。点 tab 永远不会被它误踢。
        LaunchedEffect(smartOn) {
            val s = smartOn ?: return@LaunchedEffect          // 状态未定前不跳转
            val r = entry?.destination?.route ?: return@LaunchedEffect
            val stranded = if (s) (r == "cpu" || r == "apps")
                           else   (r == "monitor" || r == "records")
            if (stranded) {
                nav.navigate("home") {
                    popUpTo(nav.graph.findStartDestination().id)
                    launchSingleTop = true
                }
            }
        }
        val hideBottomBar = curDestRoute == "about" || curDestRoute == "automode"
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (!hideBottomBar) BottomBar(
                    backdrop = backdrop,
                    items = tabs.map { it.label to it.icon },
                    selectedIndex = selectedIndex,
                ) { idx ->
                    val d = tabs[idx]
                    if (d.route != entry?.destination?.route) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    nav.navigate(d.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        ) { _ ->
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (enableFloatingBottomBar && enableFloatingBottomBarBlur)
                            Modifier.layerBackdrop(backdrop)
                        else Modifier
                    )
            ) {
                WallpaperBackground(hazeState, wallpaperUri = wallpaperUri)

                // 淡入淡出过渡;整页缩放会反复重算模糊导致卡顿
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(tween(140)) },
                    exitTransition = { fadeOut(tween(140)) },
                    popEnterTransition = { fadeIn(tween(140)) },
                    popExitTransition = { fadeOut(tween(140)) },
                ) {
                    composable("home")     { HomeScreen() }
                    composable("cpu")      { CpuControlScreen() }
                    composable("apps")     { AppsScreen() }
                    composable("monitor")  { AutoMonitorScreen() }
                    composable("records")  { RecordsScreen() }
                    composable("logs")     { LogScreen() }
                    composable("settings") {
                        SettingsScreen(
                            onOpenAbout = { nav.navigate("about") },
                            onOpenAutoMode = { nav.navigate("automode") },
                        )
                    }
                    composable("about") {
                        AboutScreen(onBack = { nav.popBackStack() })
                    }
                    composable("automode") {
                        AutoModeScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
