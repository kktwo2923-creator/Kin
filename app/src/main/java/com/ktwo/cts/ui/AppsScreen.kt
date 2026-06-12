package com.ktwo.cts.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ktwo.cts.data.Mode
import com.ktwo.cts.data.PrefsExt
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.theme.LocalEnableBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.blur.layerBackdrop

private data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val isSystem: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var showSystem by remember { mutableStateOf(false) }
    val profiles = remember { mutableStateMapOf<String, String>() }
    var globalMode by remember { mutableStateOf("balance") }
    val blacklist by PrefsExt.blacklistFlow(ctx).collectAsState(initial = emptySet())

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            globalMode = RootShell.readGlobalMode()
            val m = RootShell.readPerApp()
            withContext(Dispatchers.Main) { profiles.clear(); profiles.putAll(m) }
        }
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val pkgs = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            pkgs.map {
                AppEntry(
                    pkg = it.packageName,
                    label = pm.getApplicationLabel(it).toString(),
                    icon = runCatching { pm.getApplicationIcon(it) }.getOrNull(),
                    isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }.sortedBy { it.label.lowercase() }
        }
    }

    val filtered = remember(apps, query.text, showSystem, profiles.toMap()) {
        apps.filter {
            (showSystem || !it.isSystem) &&
            (query.text.isEmpty() ||
             it.label.contains(query.text, ignoreCase = true) ||
             it.pkg.contains(query.text, ignoreCase = true))
        }.sortedWith(
            compareByDescending<AppEntry> { profiles.containsKey(it.pkg) }
                .thenBy { it.label.lowercase() }
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val blurBackdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barBackdrop = rememberBarBackdrop(blurBackdrop)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(barBackdrop) {
                TopAppBar(
                    title = { Text("应用画像") },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .then(
                    if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop)
                    else Modifier
                )
                .padding(inner)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                    label = { Text("系统") },
                    shape = RoundedCornerShape(12.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.pkg }) { app ->
                    AppCard(
                        app = app,
                        selectedKey = profiles[app.pkg] ?: "follow",
                        blacklisted = app.pkg in blacklist,
                        onChangeMode = { newKey ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    RootShell.setPerApp(app.pkg, newKey, globalMode)
                                }
                                if (newKey == "follow") profiles.remove(app.pkg)
                                else profiles[app.pkg] = newKey
                            }
                        },
                        onToggleBlacklist = {
                            scope.launch { PrefsExt.toggleBlacklist(ctx, app.pkg) }
                        }
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}

@Composable
private fun AppCard(
    app: AppEntry,
    selectedKey: String,
    blacklisted: Boolean,
    onChangeMode: (String) -> Unit,
    onToggleBlacklist: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        color = if (blacklisted)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        else
            MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val bmp = remember(app.pkg) {
                    runCatching { app.icon?.toBitmap(96, 96) }.getOrNull()
                }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    if (bmp != null) {
                        Image(
                            painter = BitmapPainter(bmp.asImageBitmap()),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else Text(
                        app.label.firstOrNull()?.toString() ?: "?",
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (blacklisted) "已加入黑名单(不接管)" else app.pkg,
                        fontSize = 11.sp,
                        color = if (blacklisted) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val mode = Mode.fromKey(selectedKey)
                AssistChip(
                    onClick = { expanded = !expanded },
                    label = { Text(mode.label, fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(CtsIcons.forMode(mode.key), null, modifier = Modifier.size(16.dp))
                    },
                    shape = RoundedCornerShape(10.dp),
                )
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("调度模式", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Mode.PROFILES.chunked(2).forEach { rowModes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowModes.forEach {
                                Box(Modifier.weight(1f)) {
                                    ModeChip(it, selectedKey == it.key) {
                                        onChangeMode(it.key); expanded = false
                                    }
                                }
                            }
                            if (rowModes.size == 1) Box(Modifier.weight(1f)) {}
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggleBlacklist() }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (blacklisted) Icons.Outlined.RestartAlt
                            else Icons.Outlined.Block,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (blacklisted) "移出黑名单" else "加入黑名单(不接管)",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(mode: Mode, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(mode.label, modifier = Modifier.padding(vertical = 2.dp))
        },
        leadingIcon = {
            Icon(CtsIcons.forMode(mode.key), null, modifier = Modifier.size(16.dp))
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    )
}
