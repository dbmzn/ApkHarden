package com.apkharden.packager.core.developer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class TimeToolTest {
    @Test fun `seconds milliseconds and epoch zero use explicit units`() {
        val a = TimeTool.timestamp("1700000000", TimestampUnit.AUTO, "UTC")
        val b = TimeTool.timestamp("1700000000000", TimestampUnit.AUTO, "Asia/Shanghai")
        assertEquals(a.instant, b.instant)
        assertTrue(b.text().contains("2023-11-15T06:13:20+08:00"))
        assertEquals(Instant.EPOCH, TimeTool.timestamp("0", TimestampUnit.SECONDS, "UTC").instant)
        assertEquals(Instant.ofEpochMilli(-1), TimeTool.timestamp("-1", TimestampUnit.MILLIS, "UTC").instant)
        assertThrows(IllegalArgumentException::class.java) { TimeTool.timestamp("123", TimestampUnit.AUTO, "UTC") }
    }
    @Test fun `local dates respect selected zone and explicit offsets preserve the instant`() {
        assertEquals(Instant.parse("2026-10-28T01:00:00Z"), TimeTool.date("2026-10-28 09:00:00", "Asia/Shanghai").instant)
        assertEquals(Instant.parse("2026-10-28T09:00:00Z"), TimeTool.date("2026-10-28T09:00:00Z", "Asia/Shanghai").instant)
        assertThrows(IllegalArgumentException::class.java) { TimeTool.date("2026-02-30 09:00:00", "UTC") }
        assertThrows(IllegalArgumentException::class.java) { TimeTool.zone("not-a-zone") }
    }
    @Test fun `DST gaps and ambiguous local times require a real offset`() {
        assertThrows(IllegalArgumentException::class.java) { TimeTool.date("2026-03-08 02:30:00", "America/New_York") }
        assertThrows(IllegalArgumentException::class.java) { TimeTool.date("2026-11-01 01:30:00", "America/New_York") }
        val a = TimeTool.date("2026-11-01T01:30:00-04:00", "America/New_York")
        val b = TimeTool.date("2026-11-01T01:30:00-05:00", "America/New_York")
        assertEquals(3600, b.instant.epochSecond - a.instant.epochSecond)
    }
    @Test fun `negative and fractional differences are exact`() {
        val result = TimeTool.difference("2026-10-28T00:00:00.5Z", "2026-10-28T00:00:00Z", "UTC")
        assertTrue(result.contains("0.5 秒"))
        assertTrue(result.contains("总秒数：-0.5"))
        assertTrue(result.contains("总毫秒数：-500"))
    }
    @Test fun `Unix and Spring cron schedules start strictly after the base time`() {
        val start = Instant.parse("2026-10-28T00:00:00Z")
        val unix = TimeTool.cron("*/5 * * * *", CronFormat.UNIX, "Asia/Shanghai", start)
        val spring = TimeTool.cron("0 */5 * * * *", CronFormat.SPRING, "Asia/Shanghai", start)
        assertEquals(unix, spring)
        assertEquals(5, unix.size)
        assertEquals(Instant.parse("2026-10-28T00:05:00Z"), unix.first().toInstant())
        assertEquals(Instant.parse("2026-10-28T00:25:00Z"), unix.last().toInstant())
    }
    @Test fun `cron rejects invalid fields and honors DST when enumerating daily runs`() {
        val start = Instant.parse("2026-03-07T08:00:00Z")
        val result = TimeTool.cron("30 2 * * *", CronFormat.UNIX, "America/New_York", start, 2)
        assertTrue(result.all { it.hour == 2 && it.minute == 30 })
        assertTrue(result.none { it.dayOfMonth == 8 })
        assertThrows(IllegalArgumentException::class.java) { TimeTool.cron("80 * * * *", CronFormat.UNIX, "UTC", start) }
        assertThrows(IllegalArgumentException::class.java) { TimeTool.cron("0 0 * * * *", CronFormat.UNIX, "UTC", start) }
    }
}
