package com.apkharden.packager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.apkharden.packager.ui.harden.HardenScreen
import com.apkharden.packager.ui.device.DeviceScreen
import com.apkharden.packager.ui.release.ReleaseScreen
import com.apkharden.packager.ui.scan.ScanScreen
import com.apkharden.packager.ui.theme.AppTheme
import com.apkharden.packager.ui.theme.LocalSemantic
import com.apkharden.packager.ui.tool.Tool

private val tools: List<Tool> = listOf(
    object : Tool {
        override val id = "harden"; override val title = "APK加固"
        override val icon = Icons.Default.Lock
        @Composable override fun Content() = HardenScreen()
    },
    object : Tool {
        override val id = "release"; override val title = "生产校验"
        override val icon = Icons.Default.CheckCircle
        @Composable override fun Content() = ReleaseScreen()
    },
    object : Tool {
        override val id = "device"; override val title = "真机验证"
        override val icon = Icons.Default.Info
        @Composable override fun Content() = DeviceScreen()
    },
    object : Tool {
        override val id = "scan"; override val title = "隐私扫描"
        override val icon = Icons.Default.Search
        @Composable override fun Content() = ScanScreen()
    },
)

@Composable
fun App() {
    var dark by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(tools.first().id) }

    AppTheme(dark = dark) {
        val sem = LocalSemantic.current
        Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            TopBar(dark = dark, onToggleTheme = { dark = !dark })
            Divider(color = sem.cardBorder)
            Row(Modifier.fillMaxSize()) {
                NavRail(selected) { selected = it }
                Divider(color = sem.cardBorder, modifier = Modifier.fillMaxHeight().width(1.dp))
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                    tools.first { it.id == selected }.Content()
                }
            }
        }
    }
}

@Composable
private fun TopBar(dark: Boolean, onToggleTheme: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colors.primary),
            contentAlignment = Alignment.Center,
        ) { Text("A", color = MaterialTheme.colors.onPrimary, style = MaterialTheme.typography.subtitle1) }
        Spacer(Modifier.width(10.dp))
        Text("ApkHarden", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.onBackground)
        Spacer(Modifier.width(8.dp))
        Text("Android 加固工具箱", style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle)
        Spacer(Modifier.weight(1f))
        ThemeToggle(dark = dark, onClick = onToggleTheme)
    }
}

@Composable
private fun ThemeToggle(dark: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colors.primary))
        Spacer(Modifier.width(7.dp))
        Text(if (dark) "深色" else "浅色", style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface)
    }
}

@Composable
private fun NavRail(selected: String, onSelect: (String) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(92.dp).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (t in tools) NavItem(t.title, t.icon, selected == t.id) { onSelect(t.id) }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colors.primary
    val sem = LocalSemantic.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = label, tint = if (active) accent else sem.subtle,
            modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.caption, color = if (active) accent else sem.subtle)
    }
}
