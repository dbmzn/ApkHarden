package com.apkharden.runtime

import com.apkharden.crypto.StringCrypto
import kotlin.system.measureNanoTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardenStringsPerformanceTest {
    @AfterEach
    fun reset() {
        HardenStrings.clear()
    }

    @Test
    fun `runtime string protection stays within performance and cache budgets`() {
        val plaintexts = List(ENTRY_COUNT) { index -> "performance-entry-$index" }
        val table = table(plaintexts)

        repeat(WARMUP_RUNS) {
            HardenStrings.install(CONFIG, table)
            HardenStrings.clear()
        }
        val installMedian = List(MEASURED_RUNS) {
            measureNanoTime {
                HardenStrings.install(CONFIG, table)
            }.also { HardenStrings.clear() }
        }.median()

        HardenStrings.install(CONFIG, table)
        HardenStrings.decode(0)
        val firstDecodeMedian = (1..MEASURED_RUNS).map { entryId ->
            measureNanoTime { HardenStrings.decode(entryId) }
        }.median()
        val cachedDecodeTotal = measureNanoTime {
            repeat(CACHED_DECODE_COUNT) {
                HardenStrings.decode(1)
            }
        }
        val estimatedCacheBytes = CACHE_ARRAY_HEADER_BYTES +
            ENTRY_COUNT * REFERENCE_BYTES +
            plaintexts.sumOf { value -> STRING_OBJECT_BYTES + value.length * UTF16_BYTES }

        assertTrue(installMedian < INSTALL_BUDGET_NANOS) {
            "Median HardenStrings.install took ${installMedian / NANOS_PER_MILLISECOND} ms"
        }
        assertTrue(firstDecodeMedian < FIRST_DECODE_BUDGET_NANOS) {
            "Median first decode took ${firstDecodeMedian / NANOS_PER_MILLISECOND} ms"
        }
        assertTrue(cachedDecodeTotal < CACHED_DECODE_BUDGET_NANOS) {
            "$CACHED_DECODE_COUNT cached decodes took " +
                "${cachedDecodeTotal / NANOS_PER_MILLISECOND} ms"
        }
        assertTrue(estimatedCacheBytes <= CACHE_BUDGET_BYTES) {
            "Estimated cache upper bound is $estimatedCacheBytes bytes"
        }
    }

    private fun table(plaintexts: List<String>): HardenStringTable {
        val key = StringCrypto.deriveKey(
            FRAGMENT_A,
            FRAGMENT_B,
            CONFIG.certificateSha256,
            CONFIG.applicationId,
            CONFIG.buildId,
        )
        return try {
            val ivs = Array(plaintexts.size) { entryId ->
                ByteArray(12) { index -> (entryId * 31 + index).toByte() }
            }
            HardenStringTable(
                FRAGMENT_A,
                FRAGMENT_B,
                ivs,
                Array(plaintexts.size) { entryId ->
                    StringCrypto.encrypt(
                        plaintexts[entryId],
                        key,
                        ivs[entryId],
                        StringCrypto.entryAad(entryId),
                    )
                },
            )
        } finally {
            key.fill(0)
        }
    }

    private fun List<Long>.median(): Long = sorted()[size / 2]

    private companion object {
        const val ENTRY_COUNT = 256
        const val WARMUP_RUNS = 5
        const val MEASURED_RUNS = 20
        const val CACHED_DECODE_COUNT = 100_000
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val INSTALL_BUDGET_NANOS = 250L * NANOS_PER_MILLISECOND
        const val FIRST_DECODE_BUDGET_NANOS = 100L * NANOS_PER_MILLISECOND
        const val CACHED_DECODE_BUDGET_NANOS = 2_000L * NANOS_PER_MILLISECOND
        const val CACHE_ARRAY_HEADER_BYTES = 16
        const val REFERENCE_BYTES = 8
        const val STRING_OBJECT_BYTES = 24
        const val UTF16_BYTES = 2
        const val CACHE_BUDGET_BYTES = 64 * 1024
        val FRAGMENT_A = ByteArray(16) { 1 }
        val FRAGMENT_B = ByteArray(16) { 2 }
        val CONFIG = HardenConfig(
            applicationId = "com.example.performance",
            variantName = "release",
            buildId = "performance-build",
            certificateSha256 = "ab".repeat(32),
        )
    }
}
