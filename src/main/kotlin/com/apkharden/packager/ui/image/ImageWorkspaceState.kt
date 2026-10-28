package com.apkharden.packager.ui.image

import androidx.compose.runtime.*
import com.apkharden.packager.core.image.*
import kotlinx.coroutines.*
import java.io.File

internal class ImageRow(val input: ImageInput) {
    var result by mutableStateOf<ImageResult?>(null)
    var error by mutableStateOf<String?>(null)
    var processing by mutableStateOf(false)
    var exported by mutableStateOf<File?>(null)
    var crop by mutableStateOf<CropRegion?>(null)
    var appliedSettings: ImageEditSettings? = null
    var appliedCrop: CropRegion? = null
}

internal class ImageWorkspaceState : AutoCloseable {
    // Owned by App, not a navigation page: tasks survive switching tools.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionDelegate = lazy { ImageCompressionSession() }
    private val session by sessionDelegate
    val rows = mutableStateListOf<ImageRow>()
    var selected by mutableStateOf<ImageRow?>(null)
    var mode by mutableStateOf(ImageMode.SMART)
    var quality by mutableStateOf(ImageQuality.BALANCED)
    var settings by mutableStateOf(ImageEditSettings())
    var showEditor by mutableStateOf(false)
    var editDraft: ImageEditDraft? = null
    val hasEdits: Boolean get() = settings.active || rows.any { it.crop != null }
    var busy by mutableStateOf(false)
    var cancelling by mutableStateOf(false)
    var status by mutableStateOf("选择 PNG / JPEG / WebP，在本机批量处理")
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    fun add(files: List<File>) = launch {
        val failures = mutableListOf<String>()
        var added = 0
        for (file in files) {
            ensureActive()
            if (cancelling) break
            if (rows.size >= ImageCompressionSession.MAX_FILES) { failures += "每批最多 100 张"; break }
            if (rows.any { it.input.original == file.canonicalFile }) continue
            try {
                val input = withContext(Dispatchers.IO) { session.import(file) }
                val row = ImageRow(input); rows += row; added++
                if (selected == null) selected = row
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failures += "${file.name}：${failure.message}" }
        }
        status = "已添加 $added 张，共 ${rows.size} 张"
        error = failures.take(3).joinToString("\n").takeIf(String::isNotEmpty)?.let {
            it + if (failures.size > 3) "\n另有 ${failures.size - 3} 项未导入" else ""
        }
    }

    fun compress() = launch {
        val chosenMode = mode; val chosenQuality = quality; val chosenSettings = settings
        var done = 0
        val batch = rows.toList()
        for ((index, row) in batch.withIndex()) {
            ensureActive()
            if (cancelling) break
            status = "正在处理 ${index + 1}/${batch.size} · ${row.input.original.name}"
            row.processing = true; row.error = null
            try {
                val currentJob = currentCoroutineContext().job
                val result = withContext(Dispatchers.IO) {
                    session.process(row.input, chosenMode, chosenQuality, chosenSettings, row.crop) { cancelling || !currentJob.isActive }
                }
                row.result?.file?.delete()
                row.result = result; row.exported = null; done++
                row.appliedSettings = chosenSettings; row.appliedCrop = row.crop
            } catch (cancelled: CancellationException) {
                if (!isActive) throw cancelled
                row.error = "已取消；上次结果（如有）仍保留"
                break
            } catch (failure: Exception) { row.error = failure.message ?: "处理失败" }
            finally { row.processing = false }
        }
        status = if (cancelling) "已取消；本次完成 $done 张，可导出已完成结果" else "处理完成：$done/${batch.size} 张成功"
    }

    fun export(folder: File) = launch {
        var count = 0
        val failures = mutableListOf<String>()
        for (row in rows.toList()) {
            ensureActive()
            if (cancelling) break
            if (!isCurrent(row)) continue
            val result = row.result ?: continue
            try {
                row.exported = withContext(Dispatchers.IO) { session.export(result, folder) }; count++
            } catch (failure: Exception) { failures += "${row.input.original.name}：${failure.message}" }
        }
        status = "已另存 $count 张到 ${folder.absolutePath}"
        error = failures.take(3).joinToString("\n").takeIf(String::isNotEmpty)
    }

    fun isCurrent(row: ImageRow): Boolean = row.result?.let {
        it.mode == mode && it.quality == quality && row.appliedSettings == settings && row.appliedCrop == row.crop
    } == true

    fun remove(row: ImageRow) {
        if (busy) return
        rows.remove(row)
        if (editDraft?.row === row) { editDraft = null; showEditor = false }
        if (selected == row) selected = rows.firstOrNull()
        session.remove(row.input, row.result)
    }
    fun clear() {
        if (busy) return
        rows.toList().forEach(::remove)
        error = null; status = "已清空；原图和已导出文件保留"
    }
    fun cancel() { cancelling = true; status = "正在取消当前操作…" }
    private fun launch(work: suspend CoroutineScope.() -> Unit) {
        if (busy) return
        busy = true; cancelling = false; error = null
        job = scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "操作失败" }
            finally { busy = false; cancelling = false }
        }
    }
    override fun close() {
        cancelling = true
        scope.cancel()
        // Wait for native processes to stop before removing their temporary working files.
        val pending = job
        CoroutineScope(Dispatchers.IO).launch { pending?.join(); if (sessionDelegate.isInitialized()) session.close() }
    }
}
