package com.apkharden.packager.core.image

import androidx.compose.material.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.apkharden.packager.ui.developer.*
import com.apkharden.packager.ui.theme.AppTheme
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class ImageEditUiTest {
    private fun fixture(): File {
        val folder = File("build/image-edit-ui-test").apply { mkdirs() }
        val file = File(folder, "透明图形.png")
        val image = BufferedImage(640, 420, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics(); g.color = Color(30, 130, 95, 180); g.fillRoundRect(30, 40, 580, 340, 60, 60)
        g.color = Color(30, 50, 40); g.font = g.font.deriveFont(32f); g.drawString("百工WorkKit · 图片工具", 130, 220); g.dispose()
        ImageIO.write(image, "png", file)
        return file
    }

    @Test fun `drag crop precise fields convert resize and export actual WebP`() = runDesktopComposeUiTest(width = 1076, height = 820) {
        assumeTrue(ImageEngines().missing().isEmpty())
        val state = DeveloperToolsState().apply { selected = DeveloperTool.IMAGE }
        val source = fixture()
        try {
            setContent { AppTheme(dark = false) { Surface { DeveloperToolsScreen(state) } } }
            runOnIdle { state.image.add(listOf(source)) }
            waitUntil(timeoutMillis = 10000) { !state.image.busy && state.image.rows.isNotEmpty() }
            onNodeWithText("转换 / 尺寸 / 裁剪").performClick()
            onNodeWithText("输出格式：保持原格式 ▾").performClick()
            onNodeWithText("WebP", substring = false).performClick()
            onNodeWithText("尺寸方式：保持原尺寸 ▾").performClick()
            onNodeWithText("等比适配", substring = false).performClick()
            onNodeWithTag("目标宽度").performTextReplacement("300")
            onNodeWithTag("目标高度").performTextReplacement("")
            onNodeWithText("启用当前图片裁剪").performClick()
            waitForIdle()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithContentDescription("裁剪图片已加载").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("crop-canvas").performTouchInput {
                swipe(Offset(width * .25f, height * .3f), Offset(width * .75f, height * .7f), 600)
            }
            assertFalse(onNodeWithTag("裁剪宽度").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "640")
            onNodeWithTag("裁剪 X").performTextReplacement("100")
            onNodeWithTag("裁剪 Y").performTextReplacement("100")
            onNodeWithTag("裁剪宽度").performTextReplacement("300")
            onNodeWithTag("裁剪高度").performTextReplacement("150")
            onNodeWithTag("crop-canvas").performTouchInput { swipe(center, center + Offset(20f, 20f), 500) }
            fun field(name: String) = onNodeWithTag(name).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.toInt()
            assertTrue(field("裁剪 X") > 100 && field("裁剪 Y") > 100)
            val movedX = field("裁剪 X"); val movedY = field("裁剪 Y")
            onNodeWithTag("crop-canvas").performTouchInput {
                val scale = minOf(width / 640f, height / 420f)
                val corner = Offset((width - 640 * scale) / 2 + (movedX + 300) * scale, (height - 420 * scale) / 2 + (movedY + 150) * scale)
                swipe(corner, corner + Offset(20f, 20f), 500)
            }
            assertTrue(field("裁剪宽度") > 300 && field("裁剪高度") > 150)
            onNodeWithTag("裁剪 X").performTextReplacement("20")
            onNodeWithTag("裁剪 Y").performTextReplacement("30")
            onNodeWithTag("裁剪宽度").performTextReplacement("600")
            onNodeWithTag("裁剪高度").performTextReplacement("360")
            onNodeWithText("JSON 工具").performClick()
            onNodeWithText("图片工具").performClick()
            onNodeWithTag("目标宽度").assertTextContains("300")
            onNodeWithTag("裁剪宽度").assertTextContains("600")
            waitUntil(timeoutMillis = 10000) { onAllNodesWithContentDescription("裁剪图片已加载").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(source.parentFile, "edit-settings-light.png"))
            onNodeWithText("应用设置").performClick()
            onNodeWithText("开始处理").performClick()
            waitUntil(timeoutMillis = 15000) { !state.image.busy && state.image.rows.single().result != null }
            val result = state.image.rows.single().result!!
            assertEquals(ImageKind.WEBP, result.info.kind)
            assertEquals(300 to 180, result.info.width to result.info.height)
            waitUntil(timeoutMillis = 10000) { onAllNodesWithContentDescription("压缩结果预览").fetchSemanticsNodes().isNotEmpty() }
            ImageIO.write(captureToImage().toAwtImage(), "png", File(source.parentFile, "edit-result-light.png"))
            runOnIdle { state.image.export(source.parentFile) }
            waitUntil(timeoutMillis = 10000) { !state.image.busy && state.image.rows.single().exported != null }
            assertEquals("webp", state.image.rows.single().exported!!.extension)
            assertTrue(source.isFile)
            onNodeWithText("转换 / 尺寸 / 裁剪").performClick()
            onNodeWithTag("目标宽度").performTextReplacement("150")
            onNodeWithText("应用设置").performClick()
            onNodeWithText("批量另存到文件夹").assertIsNotEnabled()
            onNodeWithText("设置已变更，请重新处理").assertExists()
        } finally { state.image.close() }
    }

    @Test fun `minimum dark editor validates bounds and cancel preserves settings`() = runDesktopComposeUiTest(width = 896, height = 680) {
        val state = DeveloperToolsState().apply { selected = DeveloperTool.IMAGE }
        val source = fixture()
        try {
            setContent { AppTheme(dark = true) { Surface { DeveloperToolsScreen(state) } } }
            runOnIdle { state.image.add(listOf(source)) }
            waitUntil(timeoutMillis = 10000) { !state.image.busy && state.image.rows.isNotEmpty() }
            onNodeWithText("转换 / 尺寸 / 裁剪").performClick()
            onNodeWithText("启用当前图片裁剪").performClick()
            onNodeWithTag("裁剪宽度").performTextReplacement("10000")
            onNodeWithText("应用设置").assertIsNotEnabled()
            onNodeWithText("裁剪范围超出图片边界").assertExists()
            onNodeWithTag("裁剪宽度").performTextReplacement("400")
            onNodeWithText("输出格式：保持原格式 ▾").performClick()
            onNodeWithText("JPEG", substring = false).performClick()
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(source.parentFile, "edit-settings-dark-minimum.png"))
            onNodeWithText("取消编辑").performClick()
            assertEquals(ImageEditSettings(), state.image.settings)
            assertNull(state.image.selected!!.crop)
        } finally { state.image.close() }
    }

    @Test fun `batch shares resize while crop is per image and stale output cannot export`() = runDesktopComposeUiTest(width = 1076, height = 820) {
        assumeTrue(ImageEngines().missing().isEmpty())
        val state = DeveloperToolsState().apply { selected = DeveloperTool.IMAGE }
        val a = fixture(); val b = File(a.parentFile, "another.png"); a.copyTo(b, overwrite = true)
        try {
            setContent { AppTheme(dark = false) { Surface { DeveloperToolsScreen(state) } } }
            runOnIdle { state.image.add(listOf(a, b)) }
            waitUntil(timeoutMillis = 10000) { !state.image.busy && state.image.rows.size == 2 }
            runOnIdle {
                state.image.settings = ImageEditSettings(output = ImageOutput.WEBP, resize = ResizeMode.PERCENT, percent = 50)
                state.image.rows[0].crop = CropRegion(20, 20, 100, 80)
            }
            onNodeWithText("开始处理").performClick()
            waitUntil(timeoutMillis = 15000) { !state.image.busy && state.image.rows.all { it.result != null } }
            val first = state.image.rows[0]; val second = state.image.rows[1]
            assertEquals(50 to 40, first.result!!.info.width to first.result!!.info.height)
            assertEquals(320 to 210, second.result!!.info.width to second.result!!.info.height)
            runOnIdle { first.crop = CropRegion(20, 20, 120, 80); state.image.export(a.parentFile) }
            waitUntil(timeoutMillis = 10000) { !state.image.busy && second.exported != null }
            assertNull(first.exported)
            assertTrue(second.exported!!.isFile)
        } finally { state.image.close() }
    }
}
