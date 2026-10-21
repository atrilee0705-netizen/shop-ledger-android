package cn.shopledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.time.format.DateTimeFormatter

object Rules {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    // Bounds keep SQLite SUM and restored totals well inside signed 64-bit values.
    const val MAX_CENTS = 100_000_000_000L
    const val MAX_ROWS = 1_000_000
    val beginning: Long = LocalDate.of(1900, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日")
    val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
    fun now(): Long = System.currentTimeMillis()
    fun today(now: Long = now()): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun start(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
    fun end(date: LocalDate): Long = start(date.plusDays(1)) - 1
    fun periodText(after: Long, end: Long): String {
        val from = if (after == beginning) "首次记账起" else if (after == this.end(today(after)))
            today(after).plusDays(1).format(dateFormat) + " 00:00起" else timestampFormat.format(Instant.ofEpochMilli(after)) + "之后"
        return "$from — ${today(end).format(dateFormat)} 23:59止"
    }
    fun dayRange(date: LocalDate): LongRange = start(date) until start(date.plusDays(1))
    fun selectedTime(date: LocalDate, now: Long = now()): Long =
        date.atTime(Instant.ofEpochMilli(now).atZone(zone).toLocalTime()).atZone(zone).toInstant().toEpochMilli()
    fun editedTime(text: String, original: Long): Long {
        if (text == timestampFormat.format(Instant.ofEpochMilli(original))) return original
        val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        return LocalDateTime.parse(text, format).atZone(zone).toInstant().toEpochMilli()
    }
    fun money(cents: Long): String = "¥" + BigDecimal.valueOf(cents, 2).setScale(2).toPlainString()
    fun parseMoney(text: String): Long {
        require(Regex("\\d{1,10}(\\.\\d{1,2})?").matches(text.trim())) { "金额请输入非负数字，最多两位小数" }
        return BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact().also { require(it in 0..MAX_CENTS) { "单笔金额过大" } }
    }
    fun name(text: String): String = text.trim().also { require(it.isNotEmpty() && it.length <= 80) { "名称需为1–80个字符" } }
    fun clock(text: String): LocalTime {
        val parts = Regex("(\\d{1,2})(?::(\\d{2}))?").matchEntire(text.trim()) ?: throw IllegalArgumentException("时间请填12:00这样的格式")
        val hour = parts.groupValues[1].toInt()
        val minute = parts.groupValues[2].ifBlank { "0" }.toInt()
        require(hour in 0..23 && minute in 0..59) { "小时填0至23，分钟填0至59" }
        return LocalTime.of(hour, minute)
    }
    fun checkTime(time: Long) { require(time >= start(LocalDate.of(1900, 1, 1)) && time < start(LocalDate.of(2200, 1, 1))) { "日期需在1900至2199年之间" } }
    fun fixedDate(month: YearMonth, day: Int): LocalDate {
        require(day in 1..31)
        return month.atDay(minOf(day, month.lengthOfMonth()))
    }
    fun nextFixed(day: Int, now: Long = now()): LocalDate {
        val today = today(now)
        val current = fixedDate(YearMonth.from(today), day)
        return if (today <= current) current else fixedDate(YearMonth.from(today).plusMonths(1), day)
    }
    fun settlementDue(day: Int, previousEnd: Long?, firstEntry: Long?, now: Long = now()): LocalDate {
        if (previousEnd == null) return nextFixed(day, firstEntry ?: now)
        // A cutoff at 14th 23:59:59.999 has already closed the 15th's cycle.
        val date = today(previousEnd + 1)
        val month = YearMonth.from(date)
        val fixed = fixedDate(month, day)
        return if (date < fixed) fixed else fixedDate(month.plusMonths(1), day)
    }
    fun nextBackup(hour: Int, minute: Int, now: Long = now()): Long {
        require(hour in 0..23 && minute in 0..59)
        val current = Instant.ofEpochMilli(now).atZone(zone)
        var next = current.toLocalDate().atTime(hour, minute).atZone(zone)
        if (!next.isAfter(current)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
    fun total(values: Iterable<Long>): Long = values.fold(0L, Math::addExact)
}
