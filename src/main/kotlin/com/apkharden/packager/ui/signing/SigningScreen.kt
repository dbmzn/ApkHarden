package com.apkharden.packager.ui.signing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.KeystoreUtil
import com.apkharden.packager.signing.SigningProfile
import com.apkharden.packager.ui.common.pickFile
import com.apkharden.packager.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SigningScreen(
    initialProfile: SigningProfile?,
    onSave: (SigningProfile) -> Result<Unit>,
    onClear: () -> Result<Unit>,
) {
    var keystorePath by remember(initialProfile) { mutableStateOf(initialProfile?.keystorePath.orEmpty()) }
    var alias by remember(initialProfile) { mutableStateOf(initialProfile?.alias.orEmpty()) }
    var storePassword by remember(initialProfile) { mutableStateOf(initialProfile?.storePassword.orEmpty()) }
    var keyPassword by remember(initialProfile) { mutableStateOf(initialProfile?.keyPassword.orEmpty()) }
    var showPasswords by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sem = LocalSemantic.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("签名百宝箱", style = MaterialTheme.typography.h5, color = MaterialTheme.colors.onBackground)
        Text(
            "集中管理正式签名，查看证书指纹，并比较 APK 是否使用同一签名",
            style = MaterialTheme.typography.caption,
            color = sem.subtle,
        )
        Text(
            "签名密码使用 Windows DPAPI 加密，只能由当前 Windows 用户解密；不会以明文写入配置。",
            style = MaterialTheme.typography.body2,
            color = sem.advice,
            modifier = Modifier.fillMaxWidth()
                .background(sem.advice.copy(alpha = 0.08f))
                .border(1.dp, sem.advice.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .padding(10.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = keystorePath,
                onValueChange = { keystorePath = it; status = null },
                label = { Text("Keystore") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = signingFieldColors(),
            )
            OutlinedButton(
                onClick = {
                    pickFile("Keystore", extensions = listOf("jks", "keystore", "p12", "pfx"))
                        ?.let { keystorePath = it; status = null }
                },
                shape = RoundedCornerShape(10.dp),
            ) { Text("浏览") }
        }
        signingField("别名 alias", alias) { alias = it; status = null }
        passwordField("keystore 密码", storePassword, showPasswords) {
            storePassword = it; status = null
        }
        passwordField("key 密码", keyPassword, showPasswords) {
            keyPassword = it; status = null
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showPasswords, onCheckedChange = { showPasswords = it })
            Text("显示密码", style = MaterialTheme.typography.body2)
        }

        if (status != null) {
            Text(
                status!!,
                style = MaterialTheme.typography.body2,
                color = if (statusIsError) MaterialTheme.colors.error else sem.advice,
            )
        } else if (initialProfile != null) {
            val fingerprint = initialProfile.certificateSha256
            Text(
                if (fingerprint.isBlank()) "已保存签名配置" else "已保存 · 证书 SHA-256: ${fingerprint.chunked(2).joinToString(":")}",
                style = MaterialTheme.typography.caption,
                color = sem.subtle,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !saving && keystorePath.isNotBlank() && alias.isNotBlank() &&
                    storePassword.isNotEmpty() && keyPassword.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp),
                onClick = {
                    saving = true
                    status = null
                    scope.launch {
                        val result = runCatching {
                            val file = File(keystorePath)
                            require(file.isFile) { "签名文件不存在" }
                            val credentials = withContext(Dispatchers.IO) {
                                KeystoreUtil.load(file, storePassword, alias, keyPassword)
                            }
                            val profile = SigningProfile(
                                keystorePath = file.absolutePath,
                                alias = alias.trim(),
                                storePassword = storePassword,
                                keyPassword = keyPassword,
                                certificateSha256 = KeystoreUtil.expectedSigHash(credentials),
                            )
                            onSave(profile).getOrThrow()
                        }
                        statusIsError = result.isFailure
                        status = result.fold(
                            onSuccess = { "签名验证通过并已安全保存，后续加固会自动使用此签名" },
                            onFailure = { "保存失败：${friendlyMessage(it)}" },
                        )
                        saving = false
                    }
                },
            ) { Text(if (saving) "验证并保存中…" else "验证并保存") }

            if (initialProfile != null) {
                OutlinedButton(
                    enabled = !saving,
                    shape = RoundedCornerShape(10.dp),
                    onClick = {
                        val result = onClear()
                        statusIsError = result.isFailure
                        status = result.fold(
                            onSuccess = {
                                keystorePath = ""
                                alias = ""
                                storePassword = ""
                                keyPassword = ""
                                "已清除签名配置"
                            },
                            onFailure = { "清除失败：${friendlyMessage(it)}" },
                        )
                    },
                ) { Text("清除配置") }
            }
        }
        SignatureToolbox(initialProfile)
    }
}

private fun friendlyMessage(error: Throwable): String {
    val message = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .firstOrNull()
    return message ?: error.javaClass.simpleName
}

@Composable
private fun signingField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = signingFieldColors(),
    )
}

@Composable
private fun passwordField(
    label: String,
    value: String,
    visible: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = signingFieldColors(),
    )
}

@Composable
private fun signingFieldColors() = TextFieldDefaults.outlinedTextFieldColors(
    textColor = MaterialTheme.colors.onSurface,
    cursorColor = MaterialTheme.colors.primary,
    focusedBorderColor = MaterialTheme.colors.primary,
    unfocusedBorderColor = LocalSemantic.current.subtle,
    focusedLabelColor = MaterialTheme.colors.primary,
    unfocusedLabelColor = LocalSemantic.current.subtle,
)
