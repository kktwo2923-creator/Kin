package com.ktwo.cts.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.license.Pro
import com.ktwo.cts.ui.coloros.ColorOsScreen
import com.ktwo.cts.ui.docs.DocsScreen
import com.ktwo.cts.ui.fpsrecord.FpsRecordDetailScreen
import com.ktwo.cts.ui.fpsrecord.FpsRecordListScreen
import com.ktwo.cts.ui.fpsrecord.ThreadLoadScreen
import com.ktwo.cts.ui.governor.GovernorParamsScreen
import com.ktwo.cts.ui.memory.MemoryDetailScreen
import com.ktwo.cts.ui.process.ProcessScreen
import com.ktwo.cts.ui.threadalloc.ThreadRulesScreen
import com.ktwo.cts.ui.threadalloc.ThreadAllocScreen
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBar
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBarBlur
import com.ktwo.cts.updater.UpdateAvailableDialog
import com.ktwo.cts.updater.UpdateChecker
import com.ktwo.cts.updater.UpdateScreen
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

private data class TopDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

// 固定四个底栏 tab: 首页 / 功能 / 日志 / 关于(对齐 OShin: 第4个 tab 是关于)。
// 「功能」是一个聚合入口(hub), 把 监控 / 记录 / 应用 / 配置 / 智能调度高级设置 / 常规开关 收进去。
// 「关于」承载版本信息/链接/检查更新/设置入口(App 级偏好在 about_setting 子页)。
private val TABS = listOf(
    TopDest("home",     "首页", CtsIcons.Speed),
    TopDest("features", "功能", Icons.Outlined.GridView),
    TopDest("logs",     "日志", CtsIcons.Logs),
    TopDest("about",    "关于", Icons.Outlined.Info),
)

// 「功能」hub 下属的子页面: 选中它们时底栏仍高亮「功能」tab。
private val FEATURE_SUBROUTES = setOf("monitor", "records", "apps", "cpu", "automode", "perfmon")

// 自带返回/沉浸的二级页, 隐藏底栏。
// about 现在是底栏第4个 tab, 不再在此列表(始终显示底栏)。
// about_setting 是关于 tab 下的设置子页, 隐藏底栏。
private val FULLSCREEN_ROUTES =
    setOf(
        "about_setting", "automode", "pro", "perfmon", "fpsrecord",
        "fpsrecord_detail/{id}", "fpsrecord_thread/{id}", "process", "threadalloc",
        "threadalloc_rules", "memory", "governor", "docs", "coloros", "update",
        "smartpolicy?survey={survey}",
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

    // 付费解锁态: App 启动时从 License 同步一次(root/网络在 IO 线程), 之后 UI 秒读 Prefs 缓存。
    val proUnlocked by Prefs.proUnlockedFlow(ctx).collectAsState(initial = false)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { Pro.sync(ctx) } }
        // 覆盖层一次性迁移: 老版本把 App 设置写在 config.json, 这里搬进 settings.json 并清理
        // config(出厂键保留)。settings.json 已存在则直接跳过。必须在下方接管补写之前跑。
        withContext(Dispatchers.IO) { runCatching { com.ktwo.cts.data.KinSettings.migrateIfNeeded(ctx) } }
        // 问卷记录恢复: 清过 App 数据时从 /sdcard 落盘副本自动找回(有记录则不打扰重新问卷)。
        runCatching { com.ktwo.cts.data.SmartSurveyStore.restoreIfNeeded(ctx) }
        // 接管方式待办补写: 初始化时 config 尚不存在(模块没刷)的选择, 常见路径是"稍后设置→
        // 刷模块→重开 App", 引导页不会再出现 —— 在这里兜底补写。只有写成功才清待办。写覆盖层 settings.json。
        withContext(Dispatchers.IO) {
            runCatching {
                val pending = Prefs.pendingTakeover(ctx)
                if (pending >= 0) {
                    val ok = com.ktwo.cts.data.KinSettings.updateOverlay(ctx) { r ->
                        com.ktwo.cts.data.TakeoverCfg.mergeInto(r, pending == 1)
                    }
                    if (ok) Prefs.setPendingTakeover(ctx, -1)
                }
            }
        }
    }

    // 首启引导: docs_seen=false 时弹一次「先看文档」。initial=true 防止 DataStore 加载前误闪弹窗;
    // 无论选「查看文档」还是「稍后」都置 true, 只打扰这一次。
    val scope = rememberCoroutineScope()
    val docsSeen by Prefs.docsSeenFlow(ctx).collectAsState(initial = true)

    // 首启初始化引导: setup_done=false 时先走 SetupScreen(ROOT 授权/模块检测/无障碍),
    // 完成前不进主界面。initial=null: DataStore 加载完之前空一帧 —— 用 true 占位会闪现主界面
    // 并提前触发首页的 root-shell 副作用, 用 false 占位会给老用户闪引导页。
    val setupDone by Prefs.setupDoneFlow(ctx).collectAsState(initial = null as Boolean?)
    when (setupDone) {
        null -> return
        false -> {
            SetupScreen(onDone = { scope.launch { Prefs.setSetupDone(ctx, true) } })
            return
        }
        else -> {}
    }

    // 自动检查更新:启动 3 秒后静默查一次(非强制更新距上次检查 4 小时内不重复打扰)
    // 放在 setupDone 声明之后: LaunchedEffect 依赖 setupDone 状态, 且 when 块在 null/false 时提前 return
    var autoUpdateResult by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }
    LaunchedEffect(setupDone) {
        if (setupDone != true) return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        val sp = ctx.getSharedPreferences("kin_updater", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = sp.getLong("last_check_ts", 0L)
        val r = UpdateChecker.check()
        sp.edit().putLong("last_check_ts", now).apply()
        // 有强制更新:必弹;普通更新:4 小时内不重复弹
        val hasForce = (r.appUpdate?.forceUpdate == true) ||
            (r.binaryUpdate?.forceUpdate == true) ||
            (r.daemonUpdate?.forceUpdate == true)
        if (r.hasUpdate && (hasForce || now - last > 4L * 60 * 60 * 1000)) {
            autoUpdateResult = r
        }
    }

    // 智能策略问卷提示: 智能调度开着 + 从没答过问卷 + 「稍后再说」已到期 +
    // (已积累学习数据 或 开启满 24 小时) 时弹一次引导。initial 用占位值防加载前误判;
    // 学习数据检查走 IO 线程; 本进程只判定/弹一次。
    val smartOnForPrompt by Prefs.smartEnabledFlow(ctx).collectAsState(initial = null)
    val surveyJsonForPrompt by Prefs.smartSurveyFlow(ctx).collectAsState(initial = "\u0000")
    val surveySnooze by Prefs.smartSurveySnoozeFlow(ctx).collectAsState(initial = Long.MAX_VALUE)
    val smartSince by Prefs.smartEnabledSinceFlow(ctx).collectAsState(initial = -1L)
    // rememberSaveable: 旋转/进程重建不重弹(去回答后再转屏, 弹窗不会盖到答题页上)。
    var surveyPromptChecked by rememberSaveable { mutableStateOf(false) }
    var showSurveyPrompt by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(smartOnForPrompt, surveyJsonForPrompt, surveySnooze, smartSince, docsSeen) {
        if (surveyPromptChecked) return@LaunchedEffect
        if (!docsSeen) return@LaunchedEffect                  // 首启文档引导优先, 不叠两个弹窗
        if (smartOnForPrompt != true) return@LaunchedEffect
        if (surveyJsonForPrompt != "") return@LaunchedEffect  // 已答过 / 占位值(未加载完)
        if (smartSince < 0) return@LaunchedEffect             // 未加载完
        val now = System.currentTimeMillis()
        if (now <= surveySnooze) return@LaunchedEffect        // 稍后再说未到期 / 占位值
        // 学习数据判定(老用户): auto_state.txt 非空, 或 auto_status 已有置信度/夜间样本。
        val hasData = withContext(Dispatchers.IO) {
            runCatching {
                if (com.ktwo.cts.root.RootShell.hasLearningData()) true
                else {
                    var conf = 0
                    var night = 0
                    com.ktwo.cts.root.RootShell.readAutoStatus().lineSequence().forEach { line ->
                        val i = line.indexOf('=')
                        if (i > 0) {
                            val v = line.substring(i + 1).trim().toIntOrNull() ?: 0
                            when (line.substring(0, i).trim()) {
                                "conf" -> conf = v
                                "night_samples" -> night = v
                            }
                        }
                    }
                    conf > 0 || night > 0
                }
            }.getOrDefault(false)
        }
        surveyPromptChecked = true
        val ranOneDay = smartSince > 0 && now - smartSince >= 24L * 60 * 60 * 1000
        if (hasData || ranOneDay) showSurveyPrompt = true
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        val curDestRoute = entry?.destination?.route
        // 选中项: 子页面(监控/记录/应用/配置/智能高级)一律高亮「功能」tab。
        val selectedIndex = run {
            val onFeatureSub = curDestRoute != null &&
                (curDestRoute in FEATURE_SUBROUTES)
            if (onFeatureSub) TABS.indexOfFirst { it.route == "features" }
            else TABS.indexOfFirst { d -> curRoute?.any { it.route == d.route } == true }
                .coerceAtLeast(0)
        }
        // 二级页(自带返回/沉浸)隐藏底栏; 功能 hub 的其余子页面(监控/记录/应用/配置)保留底栏。
        val hideBottomBar = curDestRoute in FULLSCREEN_ROUTES
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (!hideBottomBar) BottomBar(
                    backdrop = backdrop,
                    items = TABS.map { it.label to it.icon },
                    selectedIndex = selectedIndex,
                ) { idx ->
                    val d = TABS[idx]
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
                    composable("features") {
                        FeaturesScreen(
                            onOpenPerfMon  = { nav.navigate("perfmon") },
                            onOpenMonitor  = { nav.navigate("monitor") },
                            onOpenRecords  = { nav.navigate("records") },
                            onOpenApps     = { nav.navigate("apps") },
                            onOpenCpu      = { nav.navigate("cpu") },
                            onOpenAutoMode = { nav.navigate("automode") },
                            onOpenSmartPolicy = { nav.navigate("smartpolicy") },
                            onOpenFpsRecord = { nav.navigate("fpsrecord") },
                            onOpenThreadAlloc = { nav.navigate("threadalloc") },
                            onOpenColorOs = { nav.navigate("coloros") },
                        )
                    }
                    // 调速器参数入口已整合进 CPU 控制页(性能配置)。
                    composable("cpu")      { CpuControlScreen(onOpenGovernor = { nav.navigate("governor") }) }
                    composable("apps")     { AppsScreen() }
                    composable("monitor")  { AutoMonitorScreen() }
                    composable("records")  { RecordsScreen() }
                    composable("logs")     { LogScreen() }
                    // 关于 tab(底栏第4个): OShin 风格主页, 承载版本/链接/检查更新/设置入口
                    composable("about") {
                        AboutScreen(
                            onOpenSetting = { nav.navigate("about_setting") },
                            onOpenPro = { nav.navigate("pro") },
                            onOpenDocs = { nav.navigate("docs") },
                            onOpenUpdate = { nav.navigate("update") },
                        )
                    }
                    // 关于 tab 下的设置子页: App 级偏好(外观/检测更新/文档等)
                    composable("about_setting") {
                        AboutSettingScreen(
                            onBack = { nav.popBackStack() },
                            onOpenUpdate = { nav.navigate("update") },
                            onOpenDocs = { nav.navigate("docs") },
                        )
                    }
                    composable("docs") {
                        DocsScreen(onBack = { nav.popBackStack() })
                    }
                    composable("update") {
                        UpdateScreen(onBack = { nav.popBackStack() })
                    }
                    composable("automode") {
                        AutoModeScreen(
                            onBack = { nav.popBackStack() },
                            proUnlocked = proUnlocked,
                            onNeedPro = { nav.navigate("pro") },
                        )
                    }
                    composable(
                        "smartpolicy?survey={survey}",
                        arguments = listOf(navArgument("survey") {
                            type = NavType.BoolType
                            defaultValue = false
                        }),
                    ) { backEntry ->
                        SmartPolicyScreen(
                            onBack = { nav.popBackStack() },
                            openSurvey = backEntry.arguments?.getBoolean("survey") ?: false,
                            proUnlocked = proUnlocked,
                            onNeedPro = { nav.navigate("pro") },
                        )
                    }
                    composable("perfmon") {
                        MonitorOverlayScreen(onBack = { nav.popBackStack() })
                    }
                    composable("process") {
                        ProcessScreen(onBack = { nav.popBackStack() })
                    }
                    composable("memory") {
                        MemoryDetailScreen(onBack = { nav.popBackStack() })
                    }
                    composable("governor") {
                        GovernorParamsScreen(onBack = { nav.popBackStack() })
                    }
                    composable("coloros") {
                        ColorOsScreen(onBack = { nav.popBackStack() })
                    }
                    composable("threadalloc") {
                        ThreadAllocScreen(
                            onBack = { nav.popBackStack() },
                            proUnlocked = proUnlocked,
                            onNeedPro = { nav.navigate("pro") },
                            onOpenRules = { nav.navigate("threadalloc_rules") },
                        )
                    }
                    composable("threadalloc_rules") {
                        ThreadRulesScreen(onBack = { nav.popBackStack() })
                    }
                    composable("pro") {
                        ProUnlockScreen(onBack = { nav.popBackStack() })
                    }
                    composable("fpsrecord") {
                        FpsRecordListScreen(
                            onBack = { nav.popBackStack() },
                            onOpenDetail = { id -> nav.navigate("fpsrecord_detail/$id") },
                        )
                    }
                    composable(
                        "fpsrecord_detail/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { backEntry ->
                        FpsRecordDetailScreen(
                            id = backEntry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onOpenThreads = { id -> nav.navigate("fpsrecord_thread/$id") },
                        )
                    }
                    composable(
                        "fpsrecord_thread/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { backEntry ->
                        ThreadLoadScreen(
                            id = backEntry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }

                // 首次使用引导弹窗(只弹一次, 不阻塞主流程)
                if (!docsSeen) {
                    AlertDialog(
                        onDismissRequest = {
                            scope.launch { Prefs.setDocsSeen(ctx, true) }
                        },
                        title = { Text("欢迎使用 OnePlus V Pro") },
                        text = {
                            Text(
                                "首次使用建议先阅读使用文档, 了解各功能的作用与所需权限。" +
                                    "之后也可以随时在「设置 → 使用文档」中查看。"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch { Prefs.setDocsSeen(ctx, true) }
                                nav.navigate("docs")
                            }) { Text("查看文档") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                scope.launch { Prefs.setDocsSeen(ctx, true) }
                            }) { Text("稍后") }
                        },
                    )
                }

                // 智能策略问卷引导(条件见上面的 LaunchedEffect; 「稍后再说」压 3 天)
                if (showSurveyPrompt) {
                    val snoozeAndClose: () -> Unit = {
                        showSurveyPrompt = false
                        scope.launch {
                            Prefs.setSmartSurveySnooze(
                                ctx, System.currentTimeMillis() + 3L * 24 * 60 * 60 * 1000
                            )
                        }
                    }
                    AlertDialog(
                        onDismissRequest = snoozeAndClose,
                        title = { Text("让智能调度更懂你") },
                        text = {
                            Text(
                                "智能调度已经运行了一段时间。回答几个小问题(睡眠时间、省电偏好、是否卡顿)," +
                                    "帮它把不同负载下的频率调得更合适。"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showSurveyPrompt = false
                                // 也压 1 天: 万一没答完就退出 App, 下次启动不立刻再弹。
                                scope.launch {
                                    Prefs.setSmartSurveySnooze(
                                        ctx, System.currentTimeMillis() + 24L * 60 * 60 * 1000
                                    )
                                }
                                nav.navigate("smartpolicy?survey=true")
                            }) { Text("去回答") }
                        },
                        dismissButton = {
                            TextButton(onClick = snoozeAndClose) { Text("稍后再说") }
                        },
                    )
                }

                // 自动检查更新弹窗(启动时静默检测到有新版本时弹出, 点"去更新"跳转更新页)
                autoUpdateResult?.let { r ->
                    UpdateAvailableDialog(
                        result = r,
                        onGoUpdate = {
                            autoUpdateResult = null
                            nav.navigate("update")
                        },
                        onDismiss = { autoUpdateResult = null },
                    )
                }
            }
        }
    }
}
