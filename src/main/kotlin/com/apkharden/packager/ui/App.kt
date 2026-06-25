package com.apkharden.packager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apkharden.packager.ui.harden.HardenScreen
import com.apkharden.packager.ui.scan.ScanScreen
import com.apkharden.packager.ui.tool.Tool

private val tools: List<Tool> = listOf(
    object : Tool {
        override val id = "harden"; override val title = "基础加固"
        @Composable override fun Content() = HardenScreen()
    },
    object : Tool {
        override val id = "scan"; override val title = "隐私扫描"
        @Composable override fun Content() = ScanScreen()
    },
)

@Composable
fun App() {
    var selected by remember { mutableStateOf(tools.first().id) }
    Row(Modifier.fillMaxSize()) {
        NavigationRail {
            Spacer(Modifier.height(8.dp))
            for (t in tools) {
                NavigationRailItem(
                    selected = selected == t.id,
                    onClick = { selected = t.id },
                    icon = {},
                    label = { Text(t.title) },
                    alwaysShowLabel = true,
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            tools.first { it.id == selected }.Content()
        }
    }
}
