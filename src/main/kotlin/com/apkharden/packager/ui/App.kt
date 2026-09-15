package com.apkharden.packager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apkharden.packager.signing.SigningProfileStore
import com.apkharden.packager.ui.adb.AdbToolboxScreen
import com.apkharden.packager.ui.archive.ArchiveScreen
import com.apkharden.packager.ui.compare.CompareScreen
import com.apkharden.packager.ui.device.DeviceScreen
import com.apkharden.packager.ui.harden.HardenScreen
import com.apkharden.packager.ui.inspect.InspectScreen
import com.apkharden.packager.ui.manifest.ManifestScreen
import com.apkharden.packager.ui.signing.SigningScreen
import com.apkharden.packager.ui.size.SizeAnalysisScreen
import com.apkharden.packager.ui.theme.AppTheme
import com.apkharden.packager.ui.theme.LocalSemantic

private enum class Destination(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val primary: Boolean = false,
) {
    ADB("ADB 工具箱", "无线连接与日常调试", Icons.Default.Settings, true),
    DEVICE("设备验证", "安装与冷启动门禁", Icons.Default.PlayArrow),
    HARDEN("APK 加固", "DEX 保护与加固签名", Icons.Default.Lock),
    INSPECT("APK 体检", "发布风险检查", Icons.Default.Search),
    COMPARE("APK 对比", "新旧版本变化", Icons.AutoMirrored.Filled.List),
    MANIFEST("Manifest", "组件与 Deep Link", Icons.Default.Info),
    ARCHIVE("文件浏览", "目录与大小占比", Icons.Default.Search),
    SIZE("包体分析", "增量来源排行", Icons.AutoMirrored.Filled.List),
    SIGNING("签名百宝箱", "证书与签名管理", Icons.Default.Settings),
}

@Composable
fun App() {
    var dark by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(Destination.ADB) }
    val signingStore = remember { SigningProfileStore() }
    var signingProfile by remember { mutableStateOf(runCatching { signingStore.load() }.getOrNull()) }

    AppTheme(dark = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(selected = selected, onSelect = { selected = it })
                Column(Modifier.fillMaxSize()) {
                    TopBar(selected = selected, dark = dark, onToggleTheme = { dark = !dark })
                    Divider(color = LocalSemantic.current.cardBorder)
                    Box(Modifier.fillMaxSize()) {
                        when (selected) {
                            Destination.HARDEN -> HardenScreen(
                                signingProfile = signingProfile,
                                onConfigureSigning = { selected = Destination.SIGNING },
                            )
                            Destination.INSPECT -> InspectScreen()
                            Destination.COMPARE -> CompareScreen()
                            Destination.MANIFEST -> ManifestScreen()
                            Destination.ARCHIVE -> ArchiveScreen()
                            Destination.SIZE -> SizeAnalysisScreen()
                            Destination.DEVICE -> DeviceScreen()
                            Destination.ADB -> AdbToolboxScreen()
                            Destination.SIGNING -> SigningScreen(
                                initialProfile = signingProfile,
                                onSave = { profile ->
                                    runCatching { signingStore.save(profile); signingProfile = profile }
                                },
                                onClear = { runCatching { signingStore.clear(); signingProfile = null } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Destination, onSelect: (Destination) -> Unit) {
    val sem = LocalSemantic.current
    Column(
        Modifier.fillMaxHeight().width(220.dp).background(MaterialTheme.colors.surface)
            .border(width = 0.dp, color = Color.Transparent).padding(16.dp),
    ) {
        Row(Modifier.height(54.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colors.primary),
                contentAlignment = Alignment.Center,
            ) { Text("A", color = MaterialTheme.colors.onPrimary, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("ApkHarden", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold)
                Text("Android 设备与 APK 工具箱", style = MaterialTheme.typography.caption, color = sem.subtle)
            }
        }

        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            NavSection("设备调试", listOf(Destination.ADB, Destination.DEVICE), selected, onSelect)
            Spacer(Modifier.height(8.dp))
            NavSection("APK 工具", listOf(Destination.INSPECT, Destination.MANIFEST, Destination.ARCHIVE,
                Destination.COMPARE, Destination.SIZE, Destination.HARDEN), selected, onSelect)
            Spacer(Modifier.height(8.dp))
            NavSection("配置", listOf(Destination.SIGNING), selected, onSelect)
        }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colors.primary.copy(alpha = .08f)).padding(12.dp),
        ) {
            Text("设备调试，随手可用", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
            Spacer(Modifier.height(3.dp))
            Text("USB / Wi-Fi 连接 · 截图 · 剪贴板", style = MaterialTheme.typography.caption, color = sem.subtle)
        }
    }
}

@Composable
private fun NavSection(title: String, items: List<Destination>, selected: Destination, onSelect: (Destination) -> Unit) {
    Text(title, style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle,
        modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
    items.forEach { NavItem(it, selected == it, onSelect) }
}

@Composable
private fun NavItem(destination: Destination, active: Boolean, onSelect: (Destination) -> Unit) {
    val sem = LocalSemantic.current
    val tint = if (active) MaterialTheme.colors.primary else sem.subtle
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (active) MaterialTheme.colors.primary.copy(alpha = .13f) else Color.Transparent)
            .clickable { onSelect(destination) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(destination.icon, destination.title, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(destination.title, style = MaterialTheme.typography.body2,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface)
            Text(destination.description, style = MaterialTheme.typography.caption, color = sem.subtle)
        }
        if (destination.primary) {
            Text("常用", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colors.primary.copy(alpha = .1f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun TopBar(selected: Destination, dark: Boolean, onToggleTheme: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(selected.title, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
        Text("  /  ${selected.description}", style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colors.surface)
                .clickable(onClick = onToggleTheme).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colors.primary))
            Spacer(Modifier.width(7.dp))
            Text(if (dark) "深色模式" else "浅色模式", style = MaterialTheme.typography.caption)
        }
    }
}
