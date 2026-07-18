// 使用文档页(M7): 分页的使用手册。目录弹窗选章、一页一章、底部上一页/下一页。
// 内容为 Kin 功能的原创说明文字, 与实际代码功能对齐。
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

// ── 手册章节(原创撰写, 与当前版本功能对齐)──
private val CHAPTERS = listOf(
    DocChapter(
        "快速开始",
        listOf(
            Para(
                "OnePlus V Pro 由两部分组成: 这颗 App 负责界面、监控与设置; 底层的 Kin 调度模块" +
                    "以 Magisk / KernelSU 模块方式刷入, 常驻后台执行真正的调频调度。二者通过配置文件" +
                    "热重载联动: App 里改的每一项, 守护进程几秒内自动生效, 无需重启。"
            ),
            SubHead("初始化三步(首次打开自动引导)"),
            Bullets(
                listOf(
                    "ROOT 授权: 弹出授权窗口时选择「允许」。调频/调度所有能力都依赖 ROOT。",
                    "调度模块: 未检测到时点「下载模块」获取模块包, 在 Magisk/KernelSU 里刷入并重启, 回到 App 点「重新检测」; 之后也可从「设置 → 重新运行初始化引导」再次进入。",
                    "无障碍权限(可选): 用于秒级识别前台应用; 不开也能用, 守护会自动回退轮询检测。",
                )
            ),
            SubHead("接管方式"),
            Para(
                "默认由本 App 接管调度(应用画像/智能分类可用)。如果你习惯用 Scene 等第三方调度管理器, " +
                    "可在初始化页或「设置 → 第三方接管」切换: 此时停用应用画像, 仅保留" +
                    "省电/均衡/性能/风驰四个基础模式, 档位切换交给第三方。"
            ),
            SubHead("智能版"),
            Para(
                "智能调度、智能策略、系统优化、线程分配等进阶能力属于「智能版」, " +
                    "在「设置 → 智能版」用卡密激活; 监控类功能免费开放。"
            ),
        ),
    ),
    DocChapter(
        "首页 · 实时监控",
        listOf(
            Para(
                "首页是一块实时仪表盘, 数据直接读取内核 sysfs / proc 节点: " +
                    "优先走免 root 路径, 读不到时自动用 root 兜底。"
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
            Para("个别机型的内核节点路径不同, 某项显示「--」属正常现象, 详见「常见问题」。"),
        ),
    ),
    DocChapter(
        "功能页导览",
        listOf(
            Para("「功能」页是所有能力的聚合入口, 双列卡片按三组排列:"),
            Bullets(
                listOf(
                    "智能调度: 智能调度设置、智能策略(问答调优/状态频率)、智能监控。",
                    "设备监测: 性能监控大屏、悬浮窗管理器、电量记录。",
                    "设备调优: 应用策略、性能配置(含调速器参数)、ColorOS 专属、线程分配。",
                )
            ),
            Para("带 PRO 徽章的是智能版功能; 点击仍可进入预览, 使用需激活。"),
        ),
    ),
    DocChapter(
        "智能调度(智能版)",
        listOf(
            Para(
                "智能调度是一套会学习的自动模式: 按负载在六个状态(深度空闲/轻度使用/中度负载/" +
                    "高负载/持续高载/游戏)之间切换, 并按你的使用习惯逐步学出省电倾向。"
            ),
            Bullets(
                listOf(
                    "省电学习: 按时段(12 个桶)学习省电/性能倾向, 越用越贴合习惯。",
                    "夜间安静: 自动学习你的作息(约 3 晚), 睡觉熄屏时段强制省电; 学得不准可在问卷里纠正。",
                    "低电自动省电: 电量低于阈值自动进入省电。",
                    "温控: 过热时压频降温, 起控温度可调; 游戏不被热限降档。",
                    "系统优化: WiFi/网络/内存调优、开机精简、后台内存回收(智能版)。",
                    "触控加速: 触摸时短暂提频, 跟手不粘滞。",
                )
            ),
            Para("开关与细项都在「功能 → 智能调度设置」; 运行状态到「智能监控」实时查看。"),
        ),
    ),
    DocChapter(
        "智能策略 · 问答调优(智能版)",
        listOf(
            Para(
                "智能策略页负责把智能调度\"调成你喜欢的样子\", 分三块: 问答调优、状态频率策略、" +
                    "应用分类策略。"
            ),
            SubHead("问答调优(5 题)"),
            Para(
                "每道题先亮出系统当前学到/生效的值(作息、省电倾向、各状态频率上限、降温时机), " +
                    "你只需确认「准不准/合不合适」: 选「保持现状」什么都不动; 选纠正项则在当前值" +
                    "基础上小步调整。每轮回答都留有历史记录, 随时可重新回答。"
            ),
            SubHead("状态频率策略"),
            Para(
                "六个负载状态各自的频率「上限」(封顶值, 不是实时频率)。带 ~ 的由智能曲线" +
                    "按学习自动决定并随省电倾向浮动; 点开任一状态可改为固定的自定义上限, " +
                    "「恢复智能」即交回曲线。当前命中的状态会实时高亮。"
            ),
            SubHead("应用分类策略"),
            Para(
                "分类与「应用策略」一致(游戏/视频/社交/阅读/音乐/未分类): 在应用策略里给 App 选画像, " +
                    "智能模式就按对应分类在状态上限之上再封顶(只压不抬)。可整体开关\"分类封顶\", " +
                    "「分类关核」默认关闭需显式开启(CPU0 恒在线, 大核簇可整簇停泊)。游戏/未分类不限制、永远全核。"
            ),
        ),
    ),
    DocChapter(
        "应用策略",
        listOf(
            Para(
                "为每个应用单独指定调度档位(画像)。守护进程检测到前台应用后自动切到对应画像, " +
                    "退到桌面回到全局默认。"
            ),
            Bullets(
                listOf(
                    "基础模式: 省电 / 均衡 / 性能 / 风驰, 也是全局默认档的候选。",
                    "场景画像: 风驰游戏、普通游戏、视频、社交、阅读、音乐 —— 同时决定智能模式下的应用分类。",
                    "跟随全局: 不单独设置, 用全局默认档。",
                    "黑名单: 加入后该应用完全不接管, 保持系统原生调度。",
                )
            ),
            Para("开启「第三方接管」后本页画像不再生效, 档位切换交给 Scene 等第三方管理器。"),
        ),
    ),
    DocChapter(
        "性能配置 · 调速器参数",
        listOf(
            Para(
                "性能配置直接编辑各画像的底层参数: 每个 CPU 簇的最小/最大频率、调速器(governor)、" +
                    "GPU 频率范围、核心开关。保存后守护进程热重载立即生效; 「恢复默认」可一键回到出厂配置。"
            ),
            SubHead("调速器参数"),
            Para(
                "页面底部的「调速器参数」入口按 CPU 簇列出当前调速器的全部可调参数" +
                    "(如响应速度类参数), 点击某项即可修改。改的是内核运行时节点, 重启后还原。"
            ),
            Para("部分机型有厂商温控/参数回写机制, 自定义值可能很快被系统改回, 属机型限制。"),
        ),
    ),
    DocChapter(
        "悬浮窗管理器",
        listOf(
            Para(
                "提供五个互相独立的监视悬浮窗(负载/进程/线程/迷你/温度), 每个都可单独开关、" +
                    "拖动到任意位置, 位置会被记住。"
            ),
            SubHead("需要的权限"),
            Para(
                "悬浮窗依赖系统「显示在其他应用上层」权限, 首次开启时会引导授权; " +
                    "悬浮窗没出现时优先检查该权限。"
            ),
        ),
    ),
    DocChapter(
        "线程分配(智能版)",
        listOf(
            Para(
                "很多游戏的渲染线程默认可能跑在小核上, 帧率上不去。「线程分配」把游戏的关键渲染线程" +
                    "归拢到大核, 换取更稳的帧率。页面按四组排布: 通用配置 → 普通 App → 游戏 → 线程规则。"
            ),
            SubHead("通用配置"),
            Bullets(
                listOf(
                    "「启用线程分配」是总开关, 由 Kin 守护常驻执行, 关闭即全部还原。",
                    "模式可选亲和(强制绑核)或迁移(即将支持); 权重配置把绑到大核的关键线程移入独立算力组抬高 CPU 权重。",
                )
            ),
            SubHead("普通 App / 游戏"),
            Bullets(
                listOf(
                    "游戏组: 打开开关后编辑游戏库勾选游戏, 前台命中即自动绑核, 退出自动还原; 绿色名字表示有预设配置。",
                    "应用黑名单里的 App 一律不做线程分配, 即使配了规则。",
                    "进页会自动检测 Scene、AppOpt 等冲突工具并提示, 可一键修复。",
                )
            ),
            SubHead("线程规则管理(三合一)"),
            Para(
                "每个应用一份配置, 点开有三个标签页: 「绑核规则」(线程名→核心, 点芯片选核, 支持预设与自动识别)、" +
                    "「实时优先级」(线程名→SCHED_FIFO/RR)、「实时线程」(查看正在跑的线程, " +
                    "可一次性绑核或直接存成规则)。右上角菜单支持导入内置预设、与 AppOpt 配置互导。"
            ),
            Para(
                "预设里新增「多引擎自适应(Metric)」: 覆盖 Unity/UE/Roblox/Minecraft/迷你世界/DAVA/SDL 等" +
                    "引擎的渲染主线程签名, 自动识别会据实时线程把它们绑到大核。"
            ),
        ),
    ),
    DocChapter(
        "电量记录",
        listOf(
            Para(
                "电量记录持续记录充放电曲线与功耗: 充电/放电各时段的平均功率、电量变化历史, " +
                    "并据此估算续航。数据由守护进程每 30 秒采样一次, 不依赖 App 在前台。"
            ),
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
                    "激活成功后本地缓存解锁状态, 之后正常离线使用。",
                    "曾激活过的设备可在同一页面直接恢复购买, 无需重新输入卡密。",
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
                "多半是未授予 Root(部分节点只有 root 能读), 或该机型内核没有对应节点。" +
                    "属机型差异, 不影响其他功能。"
            ),
            SubHead("智能调度没生效"),
            Bullets(
                listOf(
                    "确认已刷入调度模块并重启(「设置 → 重新运行初始化引导」可重新检测/下载模块)。",
                    "确认「智能调度设置」里总开关已打开(需智能版)。",
                    "开了「第三方接管」时应用画像不生效属预期, 关闭即可恢复。",
                )
            ),
            SubHead("学习数据会不会丢"),
            Para(
                "不会。智能调度的学习数据与问卷记录都保存在 /sdcard/Android/Kin 下" +
                    "(不在 App 私有目录), 清除 App 数据/缓存、甚至卸载重装 App 都不受影响; " +
                    "问卷记录还会在重装后自动恢复显示。"
            ),
            SubHead("悬浮窗不显示"),
            Para(
                "检查是否已授予「显示在其他应用上层」权限; 部分 ROM 还需在电池优化中允许后台运行。"
            ),
            SubHead("还有其他问题?"),
            Para("可以到「关于」页的开源地址提交反馈, 说明机型、系统版本与复现步骤。"),
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
                // 最后一章按钮变成可点的「完成」直接返回, 而不是灰掉的「下一页」。
                Button(
                    onClick = { if (page < CHAPTERS.lastIndex) page++ else onBack() },
                ) { Text(if (page < CHAPTERS.lastIndex) "下一页" else "完成") }
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
