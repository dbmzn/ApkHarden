package com.apkharden.packager.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import com.apkharden.packager.ui.developer.DeveloperToolsScreen
import com.apkharden.packager.ui.developer.DeveloperToolsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apkharden.packager.signing.SigningProfileStore
import com.apkharden.packager.ui.adb.AdbTab
import com.apkharden.packager.ui.adb.AdbToolboxScreen
import com.apkharden.packager.ui.archive.ArchiveScreen
import com.apkharden.packager.ui.barcode.BarcodeScreen
import com.apkharden.packager.ui.common.ToolIcons
import com.apkharden.packager.ui.compare.CompareScreen
import com.apkharden.packager.ui.device.DeviceScreen
import com.apkharden.packager.ui.harden.HardenScreen
import com.apkharden.packager.ui.inspect.InspectScreen
import com.apkharden.packager.ui.manifest.ManifestScreen
import com.apkharden.packager.ui.signing.SigningScreen
import com.apkharden.packager.ui.size.SizeAnalysisScreen
import com.apkharden.packager.ui.theme.AppearanceDialog
import com.apkharden.packager.ui.theme.AppearanceSettings
import com.apkharden.packager.ui.theme.AppearanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import com.apkharden.packager.ui.theme.AppTheme
import com.apkharden.packager.ui.theme.LocalSemantic

private enum class Destination(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val adbTab: AdbTab? = null,
) {
    CAPTURE("截图与录屏", "画面采集与录制", ToolIcons.Capture, adbTab = AdbTab.CAPTURE),
    MIRROR("实时镜像", "大屏查看与操控", ToolIcons.Mirror, adbTab = AdbTab.MIRROR),
    CLIPBOARD("手机剪贴板", "查看与复制手机文字", ToolIcons.Clipboard, adbTab = AdbTab.CLIPBOARD),
    FILES("文件互传", "电脑与手机双向传输", ToolIcons.Transfer, adbTab = AdbTab.FILES),
    BARCODE("二维码与条形码", "生成码图与识别内容", ToolIcons.Code),
    DEVELOPER("通用工具", "JSON、文本、编码、时间与图片", ToolIcons.Toolkit),
    ADB("更多调试", "日志、性能与应用操作", ToolIcons.Debug),
    DEVICE("设备验证", "安装与冷启动门禁", ToolIcons.Device),
    HARDEN("APK 加固", "DEX 保护与加固签名", ToolIcons.Shield),
    INSPECT("APK 体检", "发布风险检查", ToolIcons.Inspect),
    COMPARE("APK 对比", "新旧版本变化", ToolIcons.Compare),
    MANIFEST("Manifest", "组件与 Deep Link", ToolIcons.Manifest),
    ARCHIVE("文件浏览", "目录与大小占比", ToolIcons.Folder),
    SIZE("包体分析", "增量来源排行", ToolIcons.Chart),
    SIGNING("签名百宝箱", "证书与签名管理", ToolIcons.Key),
}

@Composable
fun App(openDeveloperToolsSignal: Int = 0) {
    val appearanceStore = remember { AppearanceStore() }
    var appearance by remember { mutableStateOf(appearanceStore.load()) }
    var showAppearance by remember { mutableStateOf(false) }
    var savingAppearance by remember { mutableStateOf(false) }
    var appearanceError by remember { mutableStateOf<String?>(null) }
    val appearanceWrites = remember { Channel<AppearanceSettings>(Channel.CONFLATED) }
    val dark = appearance.mode.isDark(isSystemInDarkTheme())

    // Serialize writes, keeping the most recent selection when users browse skins quickly.
    LaunchedEffect(appearanceStore) {
        for (selection in appearanceWrites) {
            val result = withContext(Dispatchers.IO) { runCatching { appearanceStore.save(selection) } }
            if (selection == appearance) {
                savingAppearance = false
                appearanceError = if (result.isFailure) "本次外观已应用，但保存失败；请重试。" else null
            }
        }
    }
    DisposableEffect(appearanceWrites) { onDispose { appearanceWrites.close() } }
    var selected by remember { mutableStateOf(Destination.CAPTURE) }
    val developerTools = remember { DeveloperToolsState() }
    DisposableEffect(developerTools) { onDispose { developerTools.image.close() } }
    LaunchedEffect(openDeveloperToolsSignal) {
        if (openDeveloperToolsSignal > 0) {
            selected = Destination.DEVELOPER
            developerTools.focusSearchSignal++
        }
    }
    val signingStore = remember { SigningProfileStore() }
    var signingProfile by remember { mutableStateOf(runCatching { signingStore.load() }.getOrNull()) }

    AppTheme(dark = dark, skin = appearance.skin) {
        if (showAppearance) AppearanceDialog(
            settings = appearance, dark = dark, saving = savingAppearance, saveError = appearanceError,
            onChange = { selection ->
                appearance = selection
                appearanceError = null
                savingAppearance = true
                appearanceWrites.trySend(selection)
            },
            onDismiss = { showAppearance = false },
        )
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(selected = selected, onSelect = { selected = it }, onAppearance = { showAppearance = true })
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        // Keep device selection and the mirror session alive across device tools.
                        if (selected.adbTab != null || selected == Destination.ADB) {
                            AdbToolboxScreen(page = selected.adbTab)
                        } else when (selected) {
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
                            Destination.BARCODE -> BarcodeScreen()
                            Destination.DEVELOPER -> DeveloperToolsScreen(developerTools)
                            Destination.ADB, Destination.CAPTURE, Destination.MIRROR,
                            Destination.CLIPBOARD, Destination.FILES -> Unit
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Sidebar(selected: Destination, onSelect: (Destination) -> Unit, onAppearance: () -> Unit) {
    val sem = LocalSemantic.current
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxHeight().width(104.dp).padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        TooltipArea(tooltip = { NavTooltip("ApkHarden · 设备与开发工具箱") }) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(13.dp)).background(sem.accentBg),
                contentAlignment = Alignment.Center) {
                Icon(ToolIcons.Shield, "ApkHarden", Modifier.size(23.dp), tint = sem.onAccent)
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                Destination.entries.forEach { destination ->
                    if (destination == Destination.ADB || destination == Destination.HARDEN) {
                        Divider(Modifier.width(28.dp).padding(vertical = 6.dp), color = sem.cardBorder)
                    }
                    NavItem(destination, selected == destination, onSelect)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp))
        }
        Spacer(Modifier.height(8.dp))
        TooltipArea(tooltip = { NavTooltip("选择皮肤与深浅外观") }) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onAppearance)
                .semantics { contentDescription = "皮肤与外观" }
                .padding(horizontal = 16.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(ToolIcons.Theme, null, Modifier.size(21.dp), tint = sem.subtle)
                Text("换肤", style = MaterialTheme.typography.caption, color = sem.subtle)
            }
        }
    }
}

@Composable
private fun NavTooltip(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colors.onSurface,
        contentColor = MaterialTheme.colors.surface, elevation = 4.dp) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.caption)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavItem(destination: Destination, active: Boolean, onSelect: (Destination) -> Unit) {
    val sem = LocalSemantic.current
    TooltipArea(tooltip = { NavTooltip("${destination.title} · ${destination.description}") }) {
        Column(Modifier.width(96.dp).clip(RoundedCornerShape(16.dp))
            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(destination) })
            .semantics { contentDescription = destination.title }
            .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(60.dp).height(32.dp).clip(RoundedCornerShape(50))
                .background(if (active) sem.selectionBg else Color.Transparent), contentAlignment = Alignment.Center) {
                Icon(destination.icon, null, Modifier.size(23.dp),
                    tint = if (active) MaterialTheme.colors.onSurface else sem.subtle)
            }
            if (active) Text(destination.title, style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurface, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
