// 使用文档页(M7): 分页的使用手册。目录弹窗选章、一页一章、底部上一页/下一页。
// 内容为 CTS 功能的原创说明文字, 与实际代码功能对齐。
package com.ktwo.cts.ui.docs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ktwo.cts.ui.TonalCard

// ── 正文内容块建模: 段落 / 小标题 / 要点列表 ──
private sealed interface DocBlock
private data class Para(val text: String) : DocBlock
private data class SubHead(val text: String) : DocBlock
private data class Bullets(val items: List<String>) : DocBlock

private data class DocChapter(val title: String, val blocks: List<DocBlock>)

// ── 手册章节(原创撰写, 描述 CTS 实际功能)──
private val CHAPTERS = listOf(
    DocChapter(
        "快速开始",
        listOf(
            Para(
                "kin 是一款面向 Root 设备的性能监控与调度工具: 上层是这颗 App, " +
                    "负责实时监控、悬浮窗、帧率记录、进程与内存管理等; 底层配套 Kin 调度模块, " +
                    "以 Magisk / KernelSU 模块方式刷入, 负责常驻后台的调度与守护工作。"
            ),
            SubHead("运行条件"),
            Bullets(
                listOf(
                    "设备已 Root(App 通过 libsu 请求 root 权限), 首次打开请在授权弹窗中允许。",
                    "调度类功能(智能调度、线程分配守护等)需要先刷入 Kin 模块并重启。",
                    "仅做监控查看时, 大部分数据免 root 也能显示, 但覆盖面会打折扣。",
                )
            ),
            SubHead("智能版"),
            Para(
                "自动模式、系统优化、线程分配等进阶能力属于「智能版」, 需要在 " +
                    "「设置 → 智能版」用卡密激活后使用; 基础监控功能免费开放。"
            ),
        ),
    ),
    DocChapter(
        "首页 · 实时监控",
        listOf(
            Para(
                "首页是一块实时仪表盘, 集中展示设备当前的运行状态, 数据直接读取内核的 " +
                    "sysfs / proc 节点: 优先走免 root 路径, 读不到时自动用 root 兜底。"
            ),
            Bullets(
                listOf(
                    "CPU: 按簇(小核 / 大核 / 超大核)显示当前频率与占用率。",
                    "GPU: 当前频率与负载。",
                    "温度: 电池、SoC 等关键温区。",
                    "电量与功耗: 电量百分比、电流电压换算出的实时功率。",
                    "内存: 物理内存与 Swap 的使用情况。",
                    "帧率: 屏幕实测帧率, 而非系统标称刷新率。",
                )
            ),
            Para(
                "个别机型的内核节点路径不同, 某项显示「--」属正常现象, 详见「常见问题」一章。"
            ),
        ),
    ),
    DocChapter(
        "悬浮窗管理器",
        listOf(
            Para(
                "悬浮窗管理器提供六个互相独立的监视悬浮窗, 每个都可以单独开关、" +
                    "单独拖动到屏幕任意位置, 位置会被记住。"
            ),
            Bullets(
                listOf(
                    "负载: 环形仪表显示 CPU / GPU / 电池的负载与状态。",
                    "进程: 实时的进程占用列表。",
                    "线程: 关注线程级别的 CPU 占用。",
                    "迷你: 极简小窗, 只占很小的屏幕空间。",
                    "帧率: 实时帧率显示。",
                    "温度: 关键温区温度。",
                )
            ),
            SubHead("需要的权限"),
            Para(
                "悬浮窗依赖系统的「显示在其他应用上层」权限, 首次开启时会引导前往系统设置授权; " +
                    "若悬浮窗没有出现, 请优先检查该权限是否已授予。"
            ),
        ),
    ),
    DocChapter(
        "帧率记录 · 线程负载",
        listOf(
            Para(
                "帧率记录可以按应用录制一段时间内的 FPS、帧时间与卡顿情况, " +
                    "结束后生成一份会话报告: 包含帧率曲线、掉帧统计等, 用于评估游戏或应用的流畅度。"
            ),
            SubHead("使用步骤"),
            Bullets(
                listOf(
                    "在帧率记录页选择要录制的应用并开始录制。",
                    "切到目标应用正常游玩 / 使用一段时间。",
                    "回到 kin 结束录制, 在列表中点开会话查看报告与曲线。",
                )
            ),
            SubHead("线程负载(需 Root)"),
            Para(
                "在会话详情页可以进一步查看「线程负载」: 录制期间目标应用各线程的 CPU 占用、" +
                    "运行所在的核心, 以及线程在核心间的迁移与亲和(绑核)变化, " +
                    "便于分析渲染线程是否被调度到了合适的核心上。"
            ),
        ),
    ),
    DocChapter(
        "进程管理器",
        listOf(
            Para(
                "进程管理器按应用分组展示系统中正在运行的进程, 以及每个进程的 CPU 与内存占用, " +
                    "方便快速定位吃资源的后台。"
            ),
            Bullets(
                listOf(
                    "结束进程: 只杀掉选中的单个进程。",
                    "强制停止应用: 相当于系统设置里的「强行停止」, 整个应用的进程都会被停掉。",
                )
            ),
            Para(
                "以上两种操作都需要 Root 权限; 请勿随意停止系统关键进程, 以免界面异常或重启。"
            ),
        ),
    ),
    DocChapter(
        "内存详情",
        listOf(
            Para("内存详情页把与内存相关的信息集中在一处, 并允许对部分参数进行调整(改写需 Root)。"),
            Bullets(
                listOf(
                    "概览: 物理内存与 Swap 的总量、已用、可用。",
                    "ZRAM: 当前压缩算法与实际压缩比, 支持在线切换压缩算法(如 lz4 / zstd, 以内核支持为准)。",
                    "VM 参数: swappiness、vfs_cache_pressure 等内核虚拟内存参数, 点击某一项即可修改。",
                    "meminfo: /proc/meminfo 的全量字段列表, 可展开查看。",
                    "换入换出: 实时的 swap in / out 速率, 反映内存压力。",
                )
            ),
            Para("查看类信息免 root 即可显示大部分; 切换 ZRAM 算法与修改 VM 参数必须 Root。"),
        ),
    ),
    DocChapter(
        "调速器参数",
        listOf(
            Para(
                "调速器(governor)决定 CPU 频率如何随负载变化。本页按 CPU 簇列出每个簇" +
                    "当前调速器暴露的全部可调参数, 例如 rate_limit_us 一类的响应速度参数。"
            ),
            Bullets(
                listOf(
                    "点击某个参数即可输入新值, 写入后立即生效。",
                    "修改的是内核运行时节点, 重启后会恢复默认, 不会造成永久改动。",
                    "读取参数免 root 通常可行, 写入需要 Root。",
                )
            ),
            Para("不确定含义的参数建议先记下原值再尝试, 随时可以改回或重启还原。"),
        ),
    ),
    DocChapter(
        "线程分配",
        listOf(
            Para(
                "很多游戏的渲染线程默认可能运行在小核上, 导致帧率上不去。" +
                    "「线程分配」的作用是把游戏的关键渲染线程绑到大核, 换取更稳的帧率。属于智能版功能。"
            ),
            SubHead("持续守护模式"),
            Bullets(
                listOf(
                    "打开总开关, 并在游戏库中勾选需要生效的游戏。",
                    "之后由 Kin 守护进程在后台持续工作: 检测到前台是所勾游戏时自动绑核。",
                    "退出游戏后自动还原线程的原有设置, 不影响日常使用。",
                )
            ),
            SubHead("立即应用一次"),
            Para(
                "如果只想临时试效果, 也可以使用「立即应用一次」: 只对当前状态执行一次绑核, " +
                    "不常驻后台。"
            ),
            Para("此功能依赖已刷入的 Kin 模块与 Root 权限。"),
        ),
    ),
    DocChapter(
        "智能调度 · 系统优化(智能版)",
        listOf(
            SubHead("自动模式"),
            Para(
                "自动模式让调度策略随场景自动切换, 无需手动干预, 包括: " +
                    "省电场景自动降档、夜间时段自动进入安静模式、温度过高时自动温控降频等。"
            ),
            SubHead("系统优化"),
            Bullets(
                listOf(
                    "VM 优化: 一键应用一组更适合日用 / 游戏的虚拟内存参数。",
                    "网络优化: 调整网络相关内核参数。",
                    "停用服务: 停掉选中的无用系统服务, 减少后台开销。",
                    "后台内存回收: 定期回收后台应用占用的内存。",
                )
            ),
            Para("以上功能均属智能版, 需激活后使用; 底层执行依赖 Kin 模块与 Root。"),
        ),
    ),
    DocChapter(
        "智能版解锁 / 卡密激活",
        listOf(
            Para(
                "智能版通过卡密激活: 进入「设置 → 智能版」, 输入购买获得的卡密或订单号, " +
                    "点击激活即可解锁全部智能版功能。"
            ),
            Bullets(
                listOf(
                    "激活成功后, App 会在本地缓存解锁状态, 之后正常离线使用。",
                    "曾经购买并激活过的设备, 在同一页面可以直接恢复购买, 无需重新输入卡密。",
                    "激活遇到问题时, 请确认网络可用后重试。",
                )
            ),
        ),
    ),
    DocChapter(
        "常见问题",
        listOf(
            SubHead("某项数据显示「--」"),
            Para(
                "多半是两种原因: 未授予 Root(部分节点只有 root 才能读), " +
                    "或该机型内核没有对应的 sysfs 节点。属机型差异, 不影响其他功能。"
            ),
            SubHead("悬浮窗不显示"),
            Para(
                "请检查系统设置中是否已授予 kin「显示在其他应用上层」权限; " +
                    "部分 ROM 还需在电池优化中允许 kin 后台运行。"
            ),
            SubHead("线程分配没有效果"),
            Bullets(
                listOf(
                    "确认已通过 Magisk / KernelSU 刷入 Kin 模块并重启过设备。",
                    "确认总开关已打开, 且目标游戏已在游戏库中勾选。",
                    "绑核只在前台是所勾游戏时生效, 请在游戏内验证。",
                )
            ),
            SubHead("还有其他问题?"),
            Para("可以到「关于」页的开源地址提交反馈, 描述清楚机型、系统版本与复现步骤即可。"),
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsScreen(onBack: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showToc by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // 翻页后正文回到顶部
    LaunchedEffect(page) { scroll.scrollTo(0) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("使用文档") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showToc = true }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("目录")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { inner ->
        val chapter = CHAPTERS[page]
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
        ) {
            // 正文: 一页一章
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll),
            ) {
                Spacer(Modifier.height(8.dp))
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "第 ${page + 1} 章",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(14.dp))
                        chapter.blocks.forEach { block -> DocBlockView(block) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // 底部翻页栏: 上一页 / 第 X / N 章 / 下一页
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { if (page > 0) page-- },
                    enabled = page > 0,
                ) { Text("上一页") }
                Text(
                    "第 ${page + 1} / ${CHAPTERS.size} 章",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { if (page < CHAPTERS.lastIndex) page++ },
                    enabled = page < CHAPTERS.lastIndex,
                ) { Text("下一页") }
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }

    // 目录弹窗: 点某章直接跳转
    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            title = { Text("目录") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CHAPTERS.forEachIndexed { i, c ->
                        val selected = i == page
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    page = i
                                    showToc = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${i + 1}.",
                                modifier = Modifier.width(28.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                c.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showToc = false }) { Text("关闭") }
            },
        )
    }
}

// 渲染单个内容块: 段落 / 小标题 / 要点列表
@Composable
private fun DocBlockView(block: DocBlock) {
    when (block) {
        is SubHead -> {
            Spacer(Modifier.height(8.dp))
            Text(
                block.text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
        }
        is Para -> {
            Text(
                block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
            )
            Spacer(Modifier.height(10.dp))
        }
        is Bullets -> {
            Column {
                block.items.forEach { item ->
                    Row(Modifier.padding(bottom = 6.dp)) {
                        Text(
                            "•",
                            modifier = Modifier.width(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            item,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
