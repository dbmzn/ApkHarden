package com.apkharden.packager.dex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class DexIndexTest {
    private fun sampleDex(): ByteArray = File("src/test/resources/sample.dex").readBytes()

    @Test
    fun `extracts defined type descriptors with dex location`() {
        val idx = DexIndex(listOf("classes.dex" to sampleDex()))
        val types = idx.typeDescriptors().toList()
        val sdk = types.firstOrNull { it.value == "Lcom/example/sdkdemo/TrackingSdk;" }
        assertNotNull(sdk, "应解析出定义的类型")
        assertEquals("classes.dex", sdk!!.dex)
    }

    @Test
    fun `extracts referenced method refs as class arrow name`() {
        val idx = DexIndex(listOf("classes2.dex" to sampleDex()))
        val refs = idx.methodRefs().map { it.value }.toSet()
        assertTrue(refs.contains("Lcom/example/sdkdemo/TrackingSdk;->helper"),
            "应包含被调用的方法引用，实际：$refs")
    }
}
