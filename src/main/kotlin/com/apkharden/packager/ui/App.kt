package com.apkharden.packager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.apkharden.packager.signing.SigningProfileStore
import com.apkharden.packager.ui.harden.HardenScreen
import com.apkharden.packager.ui.signing.SigningScreen
import com.apkharden.packager.ui.theme.AppTheme
import com.apkharden.packager.ui.theme.LocalSemantic

private enum class Destination(val title: String, val icon: ImageVector) {
    HARDEN("APK 加固", Icons.Default.Lock),
    SIGNING("签名工具", Icons.Default.Settings),
}

@Composable
fun App() {
    var dark by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(Destination.HARDEN) }
    val signingStore = remember { SigningProfileStore() }
    var signingProfile by remember {
        mutableStateOf(runCatching { signingStore.load() }.getOrNull())
    }

    AppTheme(dark = dark) {
        val sem = LocalSemantic.current
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background,
            contentColor = MaterialTheme.colors.onBackground,
        ) {
            Column(Modifier.fillMaxSize()) {
                TopBar(dark = dark, onToggleTheme = { dark = !dark })
                Divider(color = sem.cardBorder)
                Row(Modifier.fillMaxSize()) {
                    NavRail(selected = selected, onSelect = { selected = it })
                    Divider(color = sem.cardBorder, modifier = Modifier.fillMaxHeight().width(1.dp))
                    Box(Modifier.fillMaxSize()) {
                        when (selected) {
                            Destination.HARDEN -> HardenScreen(
                                signingProfile = signingProfile,
                                onConfigureSigning = { selected = Destination.SIGNING },
                            )
                            Destination.SIGNING -> SigningScreen(
                                initialProfile = signingProfile,
                                onSave = { profile ->
                                    runCatching {
                                        signingStore.save(profile)
                                        signingProfile = profile
                                    }
                                },
                                onClear = {
                                    runCatching {
                                        signingStore.clear()
                                        signingProfile = null
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRail(selected: Destination, onSelect: (Destination) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(104.dp).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Destination.entries.forEach { destination ->
            NavItem(
                label = destination.title,
                icon = destination.icon,
                active = selected == destination,
                onClick = { onSelect(destination) },
            )
        }
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
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) accent else sem.subtle,
            modifier = Modifier.size(22.dp),
        )
        Text(label, style = MaterialTheme.typography.caption, color = if (active) accent else sem.subtle)
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
        Text("Android APK 加固工具", style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle)
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
