package com.apkharden.packager.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.apkharden.packager.ui.theme.LocalSemantic

@Composable
fun ToolButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick = onClick, modifier = modifier.heightIn(min = 42.dp), enabled = enabled,
    shape = shape, colors = colors, elevation = elevation,
    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp), content = content)

@Composable
fun ToolOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 42.dp), enabled = enabled,
    shape = shape, colors = colors, border = BorderStroke(1.dp, LocalSemantic.current.cardBorder),
    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), content = content)

@Composable
fun ToolTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null, placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: TextFieldColors = TextFieldDefaults.outlinedTextFieldColors(
        unfocusedBorderColor = LocalSemantic.current.subtle.copy(alpha = .65f),
        backgroundColor = Color.Transparent,
    ),
) = OutlinedTextField(value = value, onValueChange = onValueChange, modifier = modifier,
    enabled = enabled, readOnly = readOnly, label = label, placeholder = placeholder,
    singleLine = singleLine, minLines = minLines, maxLines = maxLines,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, shape = shape, colors = colors)

@Composable
fun ToolPanel(
    modifier: Modifier = Modifier, color: Color = LocalSemantic.current.cardBg,
    contentColor: Color = MaterialTheme.colors.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier, color = color, contentColor = contentColor,
        shape = RoundedCornerShape(28.dp), elevation = 0.dp) {
        Column(Modifier.padding(24.dp), content = content)
    }
}

@Composable
fun FeatureLine(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(26.dp), tint = LocalContentColor.current)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.subtitle1)
            Text(detail, style = MaterialTheme.typography.body2, color = LocalContentColor.current.copy(alpha = .78f))
        }
    }
}

/** Empty import workspace and compact loaded state share the exact same picker/actions. */
@Composable
fun ImportWorkspace(
    empty: Boolean, title: String, description: String,
    features: List<Pair<String, String>>,
    content: @Composable RowScope.() -> Unit,
) {
    val sem = LocalSemantic.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (empty) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ToolPanel(Modifier.weight(1f).fillMaxHeight(), sem.accentBg, sem.onAccent) {
                    Icon(ToolIcons.Folder, null, Modifier.size(34.dp))
                    Spacer(Modifier.height(24.dp))
                    Text(title, style = MaterialTheme.typography.h4)
                    Spacer(Modifier.height(12.dp))
                    Text(description, style = MaterialTheme.typography.body1)
                    Spacer(Modifier.height(24.dp))
                    Text("选择本地 APK，开始查看", style = MaterialTheme.typography.caption)
                }
                ToolPanel(Modifier.weight(1f).fillMaxHeight()) {
                    features.forEachIndexed { index, (heading, detail) ->
                        FeatureLine(listOf(ToolIcons.Chart, ToolIcons.Manifest, ToolIcons.Inspect)[index % 3], heading, detail)
                        if (index < features.lastIndex) Divider(color = sem.cardBorder)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(sem.formBg).padding(22.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content)
    }
}
