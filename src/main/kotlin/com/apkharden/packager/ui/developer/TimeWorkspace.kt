package com.apkharden.packager.ui.developer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.developer.*
import com.apkharden.packager.ui.common.*
import com.apkharden.packager.ui.theme.LocalSemantic
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
internal fun TimeWorkspace(state: TimeWorkspaceState) {
    val task = state.task
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimePage.entries.forEach { page ->
                ToolOutlinedButton(enabled = !task.busy, onClick = { state.page = page; state.changed() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        backgroundColor = if (state.page == page) MaterialTheme.colors.primary else sem.cardBg,
                        contentColor = if (state.page == page) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                    )) { Text(page.title) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTextField(state.zone, { state.zone = it; state.changed() }, Modifier.weight(1f), enabled = !task.busy,
                label = { Text("时区 · 如 Asia/Shanghai、UTC") }, singleLine = true)
            ChoiceMenu("常用时区", state.zone, listOf("Asia/Shanghai", "UTC", "Asia/Tokyo", "America/New_York", "Europe/London"), { it }, !task.busy) {
                state.zone = it; state.changed()
            }
        }
        TaskStatus(task)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ToolPanel(Modifier.weight(1f), sem.formBg) {
                when (state.page) {
                    TimePage.CONVERT -> {
                        Text("时间戳与日期互转", style = MaterialTheme.typography.subtitle1)
                        Spacer(Modifier.height(10.dp))
                        ToolTextField(state.timestamp, { state.timestamp = it; state.changed() }, Modifier.fillMaxWidth(), enabled = !task.busy,
                            label = { Text("整数时间戳") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChoiceMenu("单位", state.unit, TimestampUnit.entries, { it.title }, !task.busy) { state.unit = it; state.changed() }
                            ToolButton(enabled = !task.busy, onClick = {
                                val input = state.timestamp; val unit = state.unit; val zone = state.zone
                                task.run(scope, { TimeTool.timestamp(input, unit, zone) }) {
                                    state.output = it.text()
                                    state.date = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(it.instant.atZone(it.zone))
                                }
                            }) { Text("转日期") }
                        }
                        Spacer(Modifier.height(12.dp))
                        ToolTextField(state.date, { state.date = it; state.changed() }, Modifier.fillMaxWidth(), enabled = !task.busy,
                            label = { Text("日期 · yyyy-MM-dd HH:mm:ss 或 ISO 8601") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ToolButton(enabled = !task.busy, onClick = {
                                val input = state.date; val zone = state.zone; val unit = state.unit
                                task.run(scope, { TimeTool.date(input, zone) }) {
                                    state.output = it.text()
                                    state.timestamp = if (unit == TimestampUnit.SECONDS) it.instant.epochSecond.toString() else it.instant.toEpochMilli().toString()
                                    if (unit == TimestampUnit.AUTO) state.unit = TimestampUnit.MILLIS
                                }
                            }) { Text("转时间戳") }
                            TextButton(enabled = !task.busy, onClick = {
                                val zone = state.zone
                                task.run(scope, { TimeConversion(Instant.now(), TimeTool.zone(zone)) }) {
                                    state.timestamp = it.instant.toEpochMilli().toString(); state.unit = TimestampUnit.MILLIS
                                    state.date = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(it.instant.atZone(it.zone)); state.output = it.text()
                                }
                            }) { Text("填入当前时间") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("自动识别 10 位秒 / 13 位毫秒；其他长度请明确单位。带 Z 或偏移的日期按自身时区解析。", style = MaterialTheme.typography.caption, color = sem.subtle)
                    }
                    TimePage.DIFFERENCE -> {
                        Text("计算结束时间与开始时间之差", style = MaterialTheme.typography.subtitle1)
                        Spacer(Modifier.height(10.dp))
                        ToolTextField(state.start, { state.start = it; state.changed() }, Modifier.fillMaxWidth(), enabled = !task.busy,
                            label = { Text("开始 · 如 2026-10-28 09:00:00") }, singleLine = true)
                        Spacer(Modifier.height(10.dp))
                        ToolTextField(state.end, { state.end = it; state.changed() }, Modifier.fillMaxWidth(), enabled = !task.busy,
                            label = { Text("结束 · 如 2026-10-28 18:30:00") }, singleLine = true)
                        Spacer(Modifier.height(12.dp))
                        ToolButton(enabled = !task.busy, onClick = {
                            val start = state.start; val end = state.end; val zone = state.zone
                            task.run(scope, { TimeTool.difference(start, end, zone) }) { state.output = it }
                        }) { Text("计算时间差") }
                    }
                    TimePage.CRON -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            ChoiceMenu("语法", state.cronFormat, CronFormat.entries, { it.title }, !task.busy) {
                                state.cronFormat = it
                                state.cron = if (it == CronFormat.UNIX) "*/5 * * * *" else "0 */5 * * * *"
                                state.changed()
                            }
                            Text(state.cronFormat.fields, style = MaterialTheme.typography.caption, color = sem.subtle)
                        }
                        Spacer(Modifier.height(12.dp))
                        ToolTextField(state.cron, { state.cron = it; state.changed() }, Modifier.fillMaxWidth(), enabled = !task.busy,
                            label = { Text("Cron 表达式") }, singleLine = true)
                        Spacer(Modifier.height(12.dp))
                        ToolTextField(state.after, { state.after = it; state.changed() }, Modifier.fillMaxWidth(), enabled = !task.busy,
                            label = { Text("基准日期 · 留空使用当前时间") }, singleLine = true)
                        Spacer(Modifier.height(12.dp))
                        ToolButton(enabled = !task.busy, onClick = {
                            val cron = state.cron; val format = state.cronFormat; val zone = state.zone; val after = state.after
                            task.run(scope, {
                                val base = if (after.isBlank()) Instant.now() else TimeTool.date(after, zone).instant
                                val times = TimeTool.cron(cron, format, zone, base)
                                "时区：$zone\n基准（UTC）：$base\n\n" + times.mapIndexed { i, value -> "${i + 1}. ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value)}" }.joinToString("\n")
                            }) { state.output = it }
                        }) { Text("预览未来 5 次") }
                        Spacer(Modifier.height(8.dp))
                        Text("只计算基准时间之后的执行时刻，不会创建或运行定时任务。", style = MaterialTheme.typography.caption, color = sem.subtle)
                    }
                }
            }
            EditorPane("计算结果", state.output, task, Modifier.weight(1f), defaultName = "time-result.txt", editorHeight = 285.dp)
        }
    }
}
