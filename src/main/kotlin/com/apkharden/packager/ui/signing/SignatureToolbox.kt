package com.apkharden.packager.ui.signing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.ApkInspector
import com.apkharden.packager.core.ApkSignatureInfo
import com.apkharden.packager.core.KeystoreUtil
import com.apkharden.packager.signing.SigningProfile
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class CertificateSummary(
    val subject: String,
    val issuer: String,
    val validFrom: String,
    val validUntil: String,
    val algorithm: String,
    val md5: String,
    val sha1: String,
    val sha256: String,
)

@Composable
internal fun SignatureToolbox(profile: SigningProfile?) {
    val sem = LocalSemantic.current
    var certificate by remember { mutableStateOf<CertificateSummary?>(null) }
    var certError by remember { mutableStateOf<String?>(null) }
    var apkOne by remember { mutableStateOf("") }
    var apkTwo by remember { mutableStateOf("") }
    var resultOne by remember { mutableStateOf<ApkSignatureInfo?>(null) }
    var resultTwo by remember { mutableStateOf<ApkSignatureInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(profile) {
        certificate = null; certError = null
        if (profile != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val cert = KeystoreUtil.load(File(profile.keystorePath), profile.storePassword,
                        profile.alias, profile.keyPassword).certificates.first()
                    cert.summary()
                }
            }.onSuccess { certificate = it }.onFailure { certError = friendlySignatureError(it) }
        }
    }

    Spacer(Modifier.height(14.dp))
    Divider(color = sem.cardBorder)
    Spacer(Modifier.height(14.dp))
    Text("证书详情", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
    Text("查看当前正式签名的主体、有效期和常用指纹", style = MaterialTheme.typography.caption, color = sem.subtle)
    Spacer(Modifier.height(8.dp))
    if (profile == null) {
        ToolboxCard { Text("保存并验证签名配置后，这里会显示完整证书信息。", color = sem.subtle) }
    } else certificate?.let { cert ->
        ToolboxCard {
            DetailLine("主体", cert.subject)
            DetailLine("签发者", cert.issuer)
            DetailLine("有效期", "${cert.validFrom}  →  ${cert.validUntil}")
            DetailLine("算法", cert.algorithm)
            FingerprintLine("MD5", cert.md5)
            FingerprintLine("SHA-1", cert.sha1)
            FingerprintLine("SHA-256", cert.sha256)
        }
    } ?: Text(certError ?: "正在读取证书…", style = MaterialTheme.typography.body2,
        color = if (certError == null) sem.subtle else MaterialTheme.colors.error)

    Spacer(Modifier.height(14.dp))
    Text("APK 签名查看与比较", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
    Text("第二个 APK 可选；选择两个时会同时判断证书是否一致", style = MaterialTheme.typography.caption, color = sem.subtle)
    Spacer(Modifier.height(8.dp))
    ToolboxCard {
        SignatureApkPicker("APK 1", apkOne) { apkOne = it; resultOne = null; checkError = null }
        Spacer(Modifier.height(9.dp))
        SignatureApkPicker("APK 2（可选）", apkTwo) { apkTwo = it; resultTwo = null; checkError = null }
        Spacer(Modifier.height(11.dp))
        Button(
            enabled = !checking && apkOne.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                checking = true; checkError = null; resultOne = null; resultTwo = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            ApkInspector.signature(File(apkOne)) to
                                apkTwo.takeIf(String::isNotBlank)?.let { ApkInspector.signature(File(it)) }
                        }
                    }.onSuccess { (one, two) -> resultOne = one; resultTwo = two }
                        .onFailure { checkError = friendlySignatureError(it) }
                    checking = false
                }
            },
        ) { Text(if (checking) "读取中…" else "读取并比较签名") }
    }
    checkError?.let { Text("读取失败：$it", color = MaterialTheme.colors.error) }
    resultOne?.let { one ->
        Spacer(Modifier.height(8.dp))
        ToolboxCard {
            SignatureResult("APK 1", one)
            resultTwo?.let { two ->
                Divider(color = sem.cardBorder, modifier = Modifier.padding(vertical = 12.dp))
                SignatureResult("APK 2", two)
                val same = one.signerSha256.isNotEmpty() && one.signerSha256 == two.signerSha256
                Spacer(Modifier.height(10.dp))
                Text(if (same) "两个 APK 使用同一签名证书" else "两个 APK 的签名证书不一致",
                    color = if (same) sem.advice else MaterialTheme.colors.error,
                    style = MaterialTheme.typography.subtitle2)
            }
        }
    }
}

@Composable
private fun SignatureResult(label: String, info: ApkSignatureInfo) {
    val sem = LocalSemantic.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.subtitle2, modifier = Modifier.width(80.dp))
        Text(if (info.verified) "签名有效" else "签名未通过", color = if (info.verified) sem.advice else MaterialTheme.colors.error)
        Spacer(Modifier.weight(1f))
        Text(info.schemes.joinToString(" + ").ifBlank { "无可用方案" }, color = sem.subtle,
            style = MaterialTheme.typography.caption)
    }
    info.signerSha256.forEach { FingerprintLine("SHA-256", it) }
}

@Composable
private fun SignatureApkPicker(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { pickFile("APK 文件", extensions = listOf("apk"))?.let(onChange) }) { Text("浏览") }
    }
}

@Composable
private fun ToolboxCard(content: @Composable ColumnScope.() -> Unit) {
    val sem = LocalSemantic.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(sem.cardBg)
        .border(1.dp, sem.cardBorder, RoundedCornerShape(12.dp)).padding(14.dp), content = content)
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.caption, color = LocalSemantic.current.subtle,
            modifier = Modifier.width(78.dp))
        Text(value, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FingerprintLine(label: String, value: String) {
    DetailLine(label, value.chunked(2).joinToString(":"))
}

private fun X509Certificate.summary(): CertificateSummary {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    fun digest(name: String): String = MessageDigest.getInstance(name).digest(encoded)
        .joinToString("") { "%02x".format(it) }
    return CertificateSummary(
        subject = subjectX500Principal.name,
        issuer = issuerX500Principal.name,
        validFrom = notBefore.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(formatter),
        validUntil = notAfter.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(formatter),
        algorithm = "${publicKey.algorithm} / $sigAlgName",
        md5 = digest("MD5"),
        sha1 = digest("SHA-1"),
        sha256 = digest("SHA-256"),
    )
}

private fun friendlySignatureError(error: Throwable): String = generateSequence(error) { it.cause }
    .mapNotNull { it.message?.takeIf(String::isNotBlank) }.firstOrNull() ?: error.javaClass.simpleName
