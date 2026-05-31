package com.ktwo.cts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
/**
 * 共享 UI 组件 - SukiSU-Ultra 风格。
 * 统一的圆角(22dp)、Tonal 卡片、状态卡、信息卡。
 */

/** SukiSU 风格大圆角 Tonal 卡片(无描边,纯填充) */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = contentColorFor(color),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = color,
        contentColor = contentColor,
    ) {
        Column(content = content)
    }
}

/**
 * 顶部状态主卡(对应 SukiSU StatusCard / WarningCard):
 *  - working=true  -> secondaryContainer + 勾
 *  - working=false -> errorContainer + 警告
 * 左图标 + 右标题/副标题,可选内联徽章行与尾部内容(开关等)。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun StatusHeroCard(
    working: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    badges: List<Pair<String, Boolean>> = emptyList(), // text to isPrimary
    trailing: (@Composable () -> Unit)? = null,
) {
    val container = if (working)
        MaterialTheme.colorScheme.secondaryContainer
    else
        MaterialTheme.colorScheme.errorContainer
    val onContainer = if (working)
        MaterialTheme.colorScheme.onSecondaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        contentColor = onContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                // 标题 + 徽章用 FlowRow: 一行放不下时整块徽章换到下一行,
                // 而不是被挤成 0 宽导致文字竖排。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    badges.forEach { (text, primary) ->
                        Badge(text, primary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer.copy(alpha = 0.8f),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}

/** 内联小徽章(LKM/GKI 之类) */
@Composable
fun Badge(text: String, primary: Boolean = true) {
    val bg = if (primary) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.error
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onError
    Surface(shape = RoundedCornerShape(8.dp), color = bg, contentColor = fg) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 统计小卡(并排,如 Superuser 数 / 模块数) */
@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    TonalCard(modifier = modifier) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, modifier = Modifier.size(26.dp), tint = tint)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 信息卡 - 堆叠的 label/value 行(对应 SukiSU InfoCard) */
@Composable
fun InfoCard(title: String, rows: List<Pair<String, String>>) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) Spacer(Modifier.height(14.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 分区标题 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}
