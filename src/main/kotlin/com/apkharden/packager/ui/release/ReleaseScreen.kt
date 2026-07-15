package com.apkharden.packager.ui.release

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.apkharden.packager.ui.common.pickDirectory
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.theme.LocalSemantic
import com.apkharden.release.model.FindingLevel
import com.apkharden.release.model.KeystoreRequest
import com.apkharden.release.model.ReleaseAssessment
import com.apkharden.release.model.ReleaseFinding
import com.apkharden.release.model.ReleaseRequest
import com.apkharden.release.model.ReleaseStatus
import com.apkharden.release.workflow.ProductionReleaseWorkflow
import com.apkharden.release.workflow.ReleaseBlockedException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReleaseScreen() {
    var form by remember { mutableStateOf(ReleaseFormValues()) }
    var assessment by remember { mutableStateOf<ReleaseAssessment?>(null) }
    val approvals = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var bundlePath by remember { mutableStateOf<String?>(null) }
    var showPasswords by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val inputFingerprint = listOf(
        form.onlineApk,
        form.candidateApk,
        form.metadata,
        form.keystore,
        form.alias,
    )

    LaunchedEffect(inputFingerprint) {
        assessment = null
        approvals.clear()
        bundlePath = null
        errorText = null
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("生产发布", style = MaterialTheme.typography.h6)
                Text(
                    "静态发布门禁与正式签名",
                    style = MaterialTheme.typography.caption,
                    color = LocalSemantic.current.subtle,
                )
            }
            ReleaseStatusBadge(assessment?.status)
        }
        Spacer(Modifier.height(14.dp))
        StaticQualificationNotice(assessment?.status)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(0.44f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionTitle("发布输入")
                FileField("线上旧 APK", form.onlineApk, { form = form.copy(onlineApk = it) }) {
                    pickFile("APK 文件", extensions = listOf("apk"))?.let { form = form.copy(onlineApk = it) }
                }
                FileField("候选 APK", form.candidateApk, { form = form.copy(candidateApk = it) }) {
                    pickFile("APK 文件", extensions = listOf("apk"))?.let { form = form.copy(candidateApk = it) }
                }
                FileField("harden-metadata.json", form.metadata, { form = form.copy(metadata = it) }) {
                    pickFile("JSON 文件", extensions = listOf("json"))?.let { form = form.copy(metadata = it) }
                }
                FileField("正式 Keystore", form.keystore, { form = form.copy(keystore = it) }) {
                    pickFile("Keystore", extensions = listOf("jks", "keystore", "p12"))?.let {
                        form = form.copy(keystore = it)
                    }
                }
                FileField("产物目录", form.outputDirectory, { form = form.copy(outputDirectory = it) }) {
                    pickDirectory()?.let { form = form.copy(outputDirectory = it) }
                }

                Divider(color = LocalSemantic.current.cardBorder, modifier = Modifier.padding(vertical = 4.dp))
                SectionTitle("签名凭据")
                OutlinedTextField(
                    value = form.alias,
                    onValueChange = { form = form.copy(alias = it) },
                    label = { Text("Alias") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField("Keystore 密码", form.storePassword, showPasswords) {
                    form = form.copy(storePassword = it)
                }
                PasswordField("Key 密码", form.keyPassword, showPasswords) {
                    form = form.copy(keyPassword = it)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showPasswords, onCheckedChange = { showPasswords = it })
                    Text("显示密码", style = MaterialTheme.typography.body2)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = form.canAnalyze(running),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val snapshot = form
                            val storeChars = snapshot.storePassword.toCharArray()
                            val keyChars = snapshot.keyPassword.toCharArray()
                            form = form.copy(storePassword = "", keyPassword = "")
                            running = true
                            errorText = null
                            bundlePath = null
                            scope.launch {
                                try {
                                    assessment = withContext(Dispatchers.IO) {
                                        ProductionReleaseWorkflow.analyze(
                                            snapshot.request(storeChars, keyChars, approvals.toSet()),
                                        )
                                    }
                                } catch (error: Throwable) {
                                    errorText = error.message ?: error.javaClass.simpleName
                                } finally {
                                    storeChars.fill('\u0000')
                                    keyChars.fill('\u0000')
                                    running = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("静态分析")
                    }
                    Button(
                        enabled = form.canExport(assessment?.status, running),
                        modifier = Modifier.weight(1f),
                        elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp),
                        onClick = {
                            val snapshot = form
                            val storeChars = snapshot.storePassword.toCharArray()
                            val keyChars = snapshot.keyPassword.toCharArray()
                            form = form.copy(storePassword = "", keyPassword = "")
                            running = true
                            errorText = null
                            bundlePath = null
                            scope.launch {
                                try {
                                    val bundle = withContext(Dispatchers.IO) {
                                        ProductionReleaseWorkflow.export(
                                            snapshot.request(storeChars, keyChars, approvals.toSet()),
                                            File(snapshot.outputDirectory),
                                        )
                                    }
                                    assessment = bundle.assessment
                                    bundlePath = bundle.directory.absolutePath
                                } catch (blocked: ReleaseBlockedException) {
                                    assessment = blocked.assessment
                                    errorText = blocked.message
                                } catch (error: Throwable) {
                                    errorText = error.message ?: error.javaClass.simpleName
                                } finally {
                                    storeChars.fill('\u0000')
                                    keyChars.fill('\u0000')
                                    running = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("签名并导出")
                    }
                }
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("处理中", style = MaterialTheme.typography.body2)
                    }
                }
                errorText?.let { ErrorBand(it) }
                bundlePath?.let { SuccessBand(it) }
            }

            Divider(color = LocalSemantic.current.cardBorder, modifier = Modifier.fillMaxHeight().width(1.dp))

            Column(
                Modifier.weight(0.56f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionTitle("检查结果")
                val current = assessment
                if (current == null) {
                    Text("尚无分析结果", style = MaterialTheme.typography.body2, color = LocalSemantic.current.subtle)
                } else {
                    AssessmentSummary(current)
                    if (current.findings.isEmpty()) {
                        SuccessBand("静态检查未发现阻断项")
                    } else {
                        current.findings.forEach { finding ->
                            FindingRow(
                                finding = finding,
                                approved = finding.code in approvals,
                                onApprovalChange = { checked ->
                                    if (checked) {
                                        if (finding.code !in approvals) approvals.add(finding.code)
                                    } else {
                                        approvals.remove(finding.code)
                                    }
                                    assessment = current.withApprovals(approvals.toSet())
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ReleaseFormValues.request(
    storePassword: CharArray,
    keyPassword: CharArray,
    approvals: Set<String>,
): ReleaseRequest = ReleaseRequest(
    onlineApk = File(onlineApk),
    candidateApk = File(candidateApk),
    metadataFile = File(metadata),
    keystore = KeystoreRequest(File(keystore), storePassword, alias, keyPassword),
    approvedFindingCodes = approvals,
)

private fun ReleaseAssessment.withApprovals(codes: Set<String>) = ReleaseAssessment(
    findings = findings,
    approvedFindingCodes = codes,
    online = online,
    candidate = candidate,
    keystore = keystore,
)

@Composable
private fun ReleaseStatusBadge(status: ReleaseStatus?) {
    val sem = LocalSemantic.current
    val color = when (status) {
        ReleaseStatus.STATIC_VERIFIED, ReleaseStatus.DEVICE_VERIFIED, ReleaseStatus.RELEASE_QUALIFIED -> sem.advice
        ReleaseStatus.NOT_QUALIFIED -> MaterialTheme.colors.error
        null -> sem.subtle
    }
    Text(
        status?.name ?: "NOT_ANALYZED",
        style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
        color = color,
        modifier = Modifier.border(1.dp, color, RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun StaticQualificationNotice(status: ReleaseStatus?) {
    val sem = LocalSemantic.current
    Row(
        Modifier.fillMaxWidth().background(sem.tierReview.copy(alpha = 0.1f))
            .border(1.dp, sem.tierReview.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Info, null, tint = sem.tierReview, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (status == ReleaseStatus.RELEASE_QUALIFIED) "已完成发布资格认证"
            else "当前最多为静态验证，尚未完成设备认证与正式上线资格",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface,
        )
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.onBackground)
}

@Composable
private fun FileField(label: String, value: String, onChange: (String) -> Unit, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onPick) {
            Icon(Icons.Default.Search, contentDescription = "选择$label")
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, visible: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AssessmentSummary(assessment: ReleaseAssessment) {
    val sem = LocalSemantic.current
    Column(
        Modifier.fillMaxWidth().background(sem.cardBg)
            .border(1.dp, sem.cardBorder, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SummaryLine("状态", assessment.status.name)
        SummaryLine("包名", assessment.candidate?.packageName.orEmpty())
        SummaryLine("版本", "${assessment.online?.versionCode ?: "-"} -> ${assessment.candidate?.versionCode ?: "-"}")
        SummaryLine("ABI", assessment.candidate?.abis?.toSortedSet()?.joinToString().orEmpty())
        SummaryLine("证书", assessment.keystore?.certificateSha256.orEmpty())
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle, modifier = Modifier.width(70.dp))
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.caption, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FindingRow(finding: ReleaseFinding, approved: Boolean, onApprovalChange: (Boolean) -> Unit) {
    val sem = LocalSemantic.current
    val color = when (finding.level) {
        FindingLevel.BLOCKER -> sem.sevHigh
        FindingLevel.REQUIRES_APPROVAL -> sem.sevMedium
        FindingLevel.WARNING -> sem.sevLow
        FindingLevel.INFO -> sem.sevInfo
    }
    Column(
        Modifier.fillMaxWidth().background(sem.cardBg)
            .border(1.dp, sem.cardBorder, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (finding.level == FindingLevel.BLOCKER) Icons.Default.Warning else Icons.Default.Info,
                null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(finding.code, style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace), color = color)
            Spacer(Modifier.weight(1f))
            Text(finding.level.name, style = MaterialTheme.typography.caption, color = color)
        }
        Text(finding.message, style = MaterialTheme.typography.body2)
        finding.details.toSortedMap().forEach { (key, value) ->
            Text("$key: $value", style = MaterialTheme.typography.caption, color = sem.subtle)
        }
        if (finding.level == FindingLevel.REQUIRES_APPROVAL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = approved, onCheckedChange = onApprovalChange)
                Text("批准此项并记录到报告", style = MaterialTheme.typography.body2)
            }
        }
    }
}

@Composable
private fun ErrorBand(message: String) {
    val color = MaterialTheme.colors.error
    Text(
        message,
        style = MaterialTheme.typography.body2,
        color = color,
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(10.dp),
    )
}

@Composable
private fun SuccessBand(message: String) {
    val color = LocalSemantic.current.advice
    Text(
        message,
        style = MaterialTheme.typography.body2,
        color = color,
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(10.dp),
    )
}
