// Mirrored from SukiSU-Ultra ui/component/bottombar/BottomBarMiuix.kt — Miuix 组件换成 Material3。

package com.ktwo.cts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBar
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBarBlur
import top.yukonga.miuix.kmp.blur.Backdrop

@Composable
fun BottomBar(
    backdrop: Backdrop,
    items: List<Pair<String, ImageVector>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val selected by rememberUpdatedState(selectedIndex)

    if (!enableFloatingBottomBar) {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            items.forEachIndexed { index, (label, icon) ->
                NavigationBarItem(
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) },
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                )
            }
        }
    } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            FloatingBottomBar(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(
                        bottom = 12.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                selectedIndex = { selected },
                onSelected = onSelect,
                backdrop = backdrop,
                tabsCount = items.size,
                isBlurEnabled = enableFloatingBottomBarBlur,
            ) {
                items.forEachIndexed { index, (label, icon) ->
                    FloatingBottomBarItem(
                        onClick = { onSelect(index) },
                        // 源码为 76.dp(4 个页签); CTS 有 5 个页签, 收窄防小屏溢出
                        modifier = Modifier.defaultMinSize(minWidth = 64.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }
        }
    }
}
