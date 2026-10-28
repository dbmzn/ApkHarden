package com.apkharden.packager.core.developer

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter

internal enum class TimestampUnit(val title: String) { AUTO("自动识别"), SECONDS("秒"), MILLIS("毫秒") }
internal enum class CronFormat(val title: String, val fields: String, val type: CronType) {
    UNIX("Unix · 5 位", "分 时 日 月 周", CronType.UNIX),
    SPRING("Spring · 6 位", "秒 分 时 日 月 周", CronType.SPRING),
}
internal data class TimeConversion(val instant: Instant, val zone: ZoneId) {
    fun text(): String = "时区：${zone.id}\n当地时间：${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zone))}\nUTC：$instant\n秒时间戳：${instant.epochSecond}\n毫秒时间戳：${instant.toEpochMilli()}"
}

internal object TimeTool {
    fun zone(id: String): ZoneId = try { ZoneId.of(id.trim()) } catch (_: Exception) {
        throw IllegalArgumentException("无效时区，请填写 Asia/Shanghai、UTC 等 IANA 时区名称。")
    }

    fun timestamp(input: String, unit: TimestampUnit, zoneId: String): TimeConversion {
        val text = input.trim()
        require(text.matches(Regex("[+-]?[0-9]+"))) { "时间戳必须是整数。" }
        val resolved = if (unit == TimestampUnit.AUTO) when (text.removePrefix("-").removePrefix("+").length) {
            10 -> TimestampUnit.SECONDS
            13 -> TimestampUnit.MILLIS
            else -> throw IllegalArgumentException("自动识别支持 10 位秒、13 位毫秒；其他长度请明确选择单位。")
        } else unit
        val number = text.toLongOrNull() ?: throw IllegalArgumentException("时间戳超出有效整数范围。")
        val instant = if (resolved == TimestampUnit.SECONDS) Instant.ofEpochSecond(number) else Instant.ofEpochMilli(number)
        return TimeConversion(instant, zone(zoneId)).also { it.text() }
    }

    fun date(input: String, zoneId: String): TimeConversion {
        val zone = zone(zoneId)
        val text = input.trim().replace(' ', 'T')
        require(text.isNotBlank()) { "请输入日期时间。" }
        val explicit = runCatching { ZonedDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        if (explicit != null) return TimeConversion(explicit, zone)
        val local = try { LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
            catch (_: Exception) { throw IllegalArgumentException("日期格式应为 yyyy-MM-dd HH:mm:ss，或带偏移的 ISO 8601 时间。") }
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "该当地时间处于夏令时跳跃区间，不存在；请调整时间。" }
        require(offsets.size == 1) { "该当地时间在夏令时回拨时出现两次，请补充明确偏移，如 -04:00。" }
        return TimeConversion(local.toInstant(offsets.single()), zone)
    }

    fun difference(start: String, end: String, zoneId: String): String {
        val from = date(start, zoneId).instant
        val to = date(end, zoneId).instant
        val duration = Duration.between(from, to)
        val seconds = BigDecimal.valueOf(duration.seconds).add(BigDecimal.valueOf(duration.nano.toLong(), 9))
        val absolute = duration.abs()
        val secondPart = BigDecimal.valueOf(absolute.toSecondsPart().toLong()).add(BigDecimal.valueOf(absolute.nano.toLong(), 9)).stripTrailingZeros().toPlainString()
        val sign = if (duration.isNegative) "−" else ""
        return "结束 − 开始\n${sign}${absolute.toDays()} 天 ${absolute.toHoursPart()} 小时 ${absolute.toMinutesPart()} 分 ${secondPart} 秒" +
            "\n总秒数：${seconds.stripTrailingZeros().toPlainString()}\n总毫秒数：${seconds.multiply(BigDecimal(1000)).stripTrailingZeros().toPlainString()}" +
            "\n开始（UTC）：$from\n结束（UTC）：$to"
    }

    fun cron(expression: String, format: CronFormat, zoneId: String, after: Instant, count: Int = 5): List<ZonedDateTime> {
        require(expression.length <= 300) { "Cron 表达式过长。" }
        require(count in 1..10)
        val cron = CronParser(CronDefinitionBuilder.instanceDefinitionFor(format.type)).parse(expression.trim()).validate()
        val execution = ExecutionTime.forCron(cron)
        var cursor = after.atZone(zone(zoneId))
        return buildList {
            repeat(count) {
                val next = execution.nextExecution(cursor)
                require(next.isPresent) { "该表达式没有可计算的后续执行时间。" }
                val value = next.get()
                check(value.toInstant().isAfter(cursor.toInstant())) { "后续执行时间计算失败。" }
                add(value); cursor = value
            }
        }
    }
}
