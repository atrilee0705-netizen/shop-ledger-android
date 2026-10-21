package cn.shopledger

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.TimeZone

class RulesTest {
    @Test fun completedFixedCycleAdvancesToNextMonthAndClockAcceptsPlainHour() {
        val end = Rules.end(LocalDate.of(2026,10,14))
        assertEquals(LocalDate.of(2026,11,15),Rules.settlementDue(15,end,null))
        assertEquals(LocalTime.NOON,Rules.clock("12"))
        assertEquals(LocalTime.of(0,5),Rules.clock("00:05"))
        listOf("24:00","12:60","12:5","noon").forEach { text -> assertThrows(IllegalArgumentException::class.java) { Rules.clock(text) } }
    }
    @Test fun moneyIsExactAndRejectsInvalidInputs() {
        assertEquals(1L, Rules.parseMoney("0.01"))
        assertEquals(12345L, Rules.parseMoney("123.45"))
        assertEquals("¥123.45", Rules.money(12345))
        listOf("-1", "NaN", "1.001", "1e3", "", "1000000001.00").forEach {
            assertThrows(IllegalArgumentException::class.java) { Rules.parseMoney(it) }
        }
    }
    @Test fun monthEndAndLeapYear() {
        assertEquals(LocalDate.of(2026, 2, 28), Rules.fixedDate(YearMonth.of(2026, 2), 31))
        assertEquals(LocalDate.of(2028, 2, 29), Rules.fixedDate(YearMonth.of(2028, 2), 31))
        assertEquals(LocalDate.of(2026, 4, 30), Rules.fixedDate(YearMonth.of(2026, 4), 31))
    }
    @Test fun editingOnlyTheNotePreservesOriginalMillisecondsAtSettlementBoundary() {
        val original = Instant.parse("2026-10-21T14:00:00.125Z").toEpochMilli()
        assertEquals(original,Rules.editedTime("2026-10-21 22:00:00",original))
        assertEquals(original+875,Rules.editedTime("2026-10-21 22:00:01",original))
        assertThrows(java.time.format.DateTimeParseException::class.java) { Rules.editedTime("2026-02-30 22:00:00",original) }
    }
    @Test fun earlySettlementDoesNotMoveFixedDay() {
        val early = LocalDateTime.of(2026,10,18,14,20).atZone(Rules.zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026,10,25), Rules.settlementDue(25, early, null, early))
        assertEquals(LocalDate.of(2026,10,25), Rules.nextFixed(25, early))
    }
    @Test fun overdueSettlementKeepsTheUnpaidFixedDate() {
        val last = Rules.start(LocalDate.of(2026,8,25))
        assertEquals(LocalDate.of(2026,9,25), Rules.settlementDue(25, last, null, Rules.start(LocalDate.of(2026,10,1))))
    }
    @Test fun backupUsesBeijingClockAndRecalculatesNextCalendarDay() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val now = Instant.parse("2026-10-21T14:00:00Z").toEpochMilli()
            assertEquals(Instant.parse("2026-10-22T14:00:00Z").toEpochMilli(), Rules.nextBackup(22,0,now))
            assertEquals(LocalDate.of(2026,10,21), Rules.today(now))
            assertEquals(86_400_000, Rules.start(LocalDate.of(2026,10,22)) - Rules.start(LocalDate.of(2026,10,21)))
        } finally { TimeZone.setDefault(old) }
    }
}
