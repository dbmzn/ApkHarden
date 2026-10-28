package com.apkharden.packager.core.image

import androidx.compose.material.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.apkharden.packager.ui.developer.DeveloperTool
import com.apkharden.packager.ui.developer.DeveloperToolsScreen
import com.apkharden.packager.ui.developer.DeveloperToolsState
import com.apkharden.packager.ui.theme.AppTheme
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class ImageWorkspaceUiTest {
    @Test fun `compress navigate zoom theme and export preserve session results`() = runDesktopComposeUiTest(width = 1076, height = 820) {
        org.junit.jupiter.api.Assumptions.assumeTrue(ImageEngines().missing().isEmpty())
        val state = DeveloperToolsState().apply { selected = DeveloperTool.IMAGE }
        val dark = mutableStateOf(false)
        val folder = File("build/image-ui-test").apply { mkdirs() }
        val input = File(folder, "透明渐变.png")
        val fixture = BufferedImage(640, 420, BufferedImage.TYPE_INT_ARGB)
        val g = fixture.createGraphics()
        g.color = Color(46, 135, 100, 160); g.fillRoundRect(60, 70, 520, 280, 90, 90)
        g.color = Color(20, 50, 40); g.font = g.font.deriveFont(34f); g.drawString("ApkHarden · 图片压缩", 100, 230); g.dispose()
        ImageIO.write(fixture, "png", input)
        try {
            setContent { AppTheme(dark = dark.value) { Surface { DeveloperToolsScreen(state) } } }
            runOnIdle { state.image.add(listOf(input)) }
            waitUntil(timeoutMillis = 15000) { !state.image.busy && state.image.rows.isNotEmpty() }
            onNodeWithText("开始压缩").performClick()
            waitUntil(timeoutMillis = 15000) { !state.image.busy && state.image.rows.single().result != null }
            onNodeWithContentDescription("原图预览").assertExists()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithContentDescription("压缩结果预览").fetchSemanticsNodes().isNotEmpty() }
            val result = state.image.rows.single().result!!
            assertTrue(result.size <= result.input.size)
            onNodeWithText("JSON 工具").performClick()
            onNodeWithText("图片压缩").performClick()
            assertSame(result, state.image.rows.single().result)
            onNodeWithText("放大 1×").performClick()
            onNodeWithText("放大 2×").assertExists()
            onNodeWithText("放大 2×").performClick()
            onNodeWithText("放大 4×").performClick()
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(folder, "image-tools-light.png"))
            runOnIdle { dark.value = true }
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(folder, "image-tools-dark.png"))
            runOnIdle { state.image.export(folder) }
            waitUntil(timeoutMillis = 10000) { !state.image.busy && state.image.rows.single().exported != null }
            assertTrue(input.isFile)
            assertArrayEquals(result.file.readBytes(), state.image.rows.single().exported!!.readBytes())
            onNodeWithText("清空").performClick()
            assertTrue(state.image.rows.isEmpty())
            assertTrue(input.isFile)
        } finally { state.image.close() }
    }

    @Test fun `minimum window handles mixed batch and mode switch`() = runDesktopComposeUiTest(width = 896, height = 680) {
        org.junit.jupiter.api.Assumptions.assumeTrue(ImageEngines().missing().isEmpty())
        val state = DeveloperToolsState().apply { selected = DeveloperTool.IMAGE }
        val folder = File("build/image-ui-test").apply { mkdirs() }
        val a = File(folder, "batch-a.png"); val b = File(folder, "batch-b.jpg")
        val pixels = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(pixels, "png", a); ImageIO.write(pixels, "jpeg", b)
        val bad = File(folder, "broken.png").apply { writeText("invalid image") }
        try {
            setContent { AppTheme(dark = false) { Surface { DeveloperToolsScreen(state) } } }
            runOnIdle { state.image.add(listOf(a, bad, b)) }
            waitUntil(timeoutMillis = 10000) { !state.image.busy && state.image.rows.size == 2 }
            assertTrue(state.image.error!!.contains("broken.png"))
            onNodeWithText("模式：智能压缩 ▾").performClick()
            onNodeWithText("无损优化").performClick()
            onNodeWithText("开始压缩").performClick()
            waitUntil(timeoutMillis = 15000) { !state.image.busy && state.image.rows.all { it.result != null } }
            assertTrue(state.image.rows.all { it.result!!.mode == ImageMode.LOSSLESS })
            onNodeWithText("batch-b.jpg").performClick()
            onNodeWithText("批量另存到文件夹").assertIsDisplayed()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithContentDescription("压缩结果预览").fetchSemanticsNodes().isNotEmpty() }
            ImageIO.write(captureToImage().toAwtImage(), "png", File(folder, "image-tools-minimum.png"))
        } finally { state.image.close() }
    }
}
