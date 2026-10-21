package cn.shopledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.shopledger.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LedgerTest {
    private lateinit var db: LedgerDb
    private lateinit var ledger: Ledger
    private val date = LocalDate.of(2026,10,18)
    private val now = at("2026-10-18T12:00:00")
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDb::class.java).build(); ledger = Ledger(db) }
    @After fun close() { db.close() }
    private suspend fun seed(): Pair<List<Employee>, List<TaskType>> {
        listOf("张三", "李四", "王五").forEach { ledger.saveEmployee(null, it) }
        ledger.saveTask(null, "A", 2000, 6000); ledger.saveTask(null, "B", 3500, 10000); ledger.saveTask(null, "C", 5000, 15000)
        return ledger.dao.employees() to ledger.dao.tasks().sortedBy { it.name }
    }
    private suspend fun sums(day: LocalDate = date) = ledger.dao.dailyTotal(Rules.start(day), Rules.start(day.plusDays(1))).first()
    @Test fun threeEmployeesHaveIndependentWagesAndRevenue() = runBlocking {
        val (e,t) = seed()
        ledger.add(e[0].id,t[0].id,date,"客户1",now); ledger.add(e[0].id,t[0].id,date,"客户2",now); ledger.add(e[0].id,t[2].id,date,"加急",now)
        ledger.add(e[1].id,t[1].id,date,"",now); ledger.add(e[1].id,t[2].id,date,"",now)
        ledger.add(e[2].id,t[0].id,date,"",now); ledger.add(e[2].id,t[1].id,date,"",now)
        assertEquals(DailyTotal(23000, 68000), sums())
        val totals = ledger.dao.employeeTotals(Rules.start(date), Rules.start(date.plusDays(1))).first().associate { it.employeeId to it.amount }
        assertEquals(9000L,totals[e[0].id]); assertEquals(8500L,totals[e[1].id]); assertEquals(5500L,totals[e[2].id])
        assertEquals(7,ledger.dao.entries().size)
    }
    @Test fun archivingAndRenamingPreserveHistoryAndDuplicateNamesHaveDifferentIds() = runBlocking {
        val (e,t) = seed()
        ledger.add(e[0].id,t[0].id,date,"",now)
        ledger.saveEmployee(e[0].id,"李四",true)
        ledger.saveTask(t[0].id,"A",2000,6000,true)
        assertEquals(DailyTotal(2000,6000), sums())
        assertEquals(2,ledger.dao.employees().count { it.name == "李四" })
        assertEquals("李四", ledger.dao.employeeTotals(Rules.start(date), Rules.start(date.plusDays(1))).first().find { it.employeeId == e[0].id }?.name)
        fails { ledger.add(e[0].id,t[1].id,date,"",now) }
        fails { ledger.add(e[1].id,t[0].id,date,"",now) }
    }
    @Test fun taskSnapshotsSurvivePriceChangesAndSameNameCreation() = runBlocking {
        val (e,t) = seed()
        val old = ledger.add(e[0].id,t[0].id,date,"旧",now)
        ledger.saveTask(t[0].id,"新A",2101,7002)
        val fresh = ledger.add(e[0].id,t[0].id,date,"新",now)
        ledger.saveTask(null,"新A",9900,12300)
        assertEquals("A",ledger.dao.entry(old.id)?.taskNameSnapshot)
        assertEquals(2000L,ledger.dao.entry(old.id)?.wageCentsSnapshot)
        assertEquals(2101L,fresh.wageCentsSnapshot)
        assertEquals(DailyTotal(4101,13002),sums())
        assertEquals(2,ledger.dao.tasks().count { it.name == "新A" })
    }
    @Test fun deletingOneEntryUpdatesBothFlowAggregates() = runBlocking {
        val (e,t) = seed()
        val first = ledger.add(e[0].id,t[0].id,date,"",now)
        ledger.add(e[1].id,t[1].id,date,"",now)
        ledger.delete(first.id)
        assertEquals(DailyTotal(3500,10000),sums())
        assertEquals(0L,ledger.dao.employeeTotals(Rules.start(date),Rules.start(date.plusDays(1))).first().find { it.employeeId==e[0].id }?.amount)
    }
    @Test fun midnightSeparatesBeijingDaysEvenWhenDeviceIsInAmerica() = runBlocking {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val (e,t) = seed()
            ledger.add(e[0].id,t[0].id,date,"23:59",at("2026-10-18T23:59:59"))
            ledger.add(e[0].id,t[1].id,date.plusDays(1),"00:00",at("2026-10-19T00:00:00"))
            assertEquals(DailyTotal(2000,6000),sums())
            assertEquals(DailyTotal(3500,10000),sums(date.plusDays(1)))
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun cutoffExcludesTodayAndFutureButIncludesLastMillisecondOfYesterday() = runBlocking {
        val (e,t) = seed()
        val yesterday = date.minusDays(1)
        val old = ledger.add(e[0].id,t[0].id,yesterday,"",Rules.end(yesterday))
        ledger.add(e[1].id,t[1].id,date,"今天",Rules.start(date))
        ledger.add(e[1].id,t[1].id,date.plusDays(5),"未来",now)
        val batch = ledger.settle(true,now)
        assertEquals(Rules.end(yesterday),batch.periodEnd)
        assertEquals(2000L,ledger.dao.settlementItems().single().amountCents)
        fails { ledger.edit(old.id,now+2000,"变更") }; fails { ledger.delete(old.id) }
        fails { ledger.settle(true,now,through = date) }
        fails { ledger.settle(true,now,through = date.plusDays(5)) }
        fails { ledger.settle(true,now) }
        assertEquals(batch.id,ledger.dao.settlements().single().id)
        assertEquals(2,ledger.dao.payable(Rules.end(date.plusDays(5))).size)
    }
    @Test fun earlyAndMonthlySettlementsNeverDoubleCount() = runBlocking {
        val (e,t) = seed()
        ledger.add(e[0].id,t[0].id,date.minusDays(1),"",now)
        val early = ledger.settle(true,now)
        assertEquals(LocalDate.of(2026,10,25),Rules.settlementDue(25,early.periodEnd,null,now))
        ledger.add(e[0].id,t[1].id,LocalDate.of(2026,10,24),"",at("2026-10-24T23:59:59"))
        ledger.add(e[0].id,t[2].id,LocalDate.of(2026,10,25),"今天不结",at("2026-10-25T09:00:00"))
        ledger.settle(false,at("2026-10-25T18:00:00"))
        assertEquals(5500L,Rules.total(ledger.dao.settlementItems().map { it.amountCents }))
        assertEquals(2,ledger.dao.settlements().size)
        fails { ledger.settle(true,at("2026-10-25T19:00:00")) }
    }
    @Test fun onlyLatestCanBeUndoneThenCorrectedAndResettled() = runBlocking {
        val (e,t) = seed()
        ledger.add(e[0].id,t[0].id,date.minusDays(1),"",now)
        val first = ledger.settle(true,now)
        val entry = ledger.add(e[0].id,t[1].id,date,"误记",now)
        val nextDay = at("2026-10-19T12:00:00")
        val second = ledger.settle(true,nextDay)
        fails { ledger.undo(first.id) }
        ledger.undo(second.id); ledger.delete(entry.id)
        ledger.add(e[1].id,t[2].id,date,"修正",nextDay)
        ledger.settle(true,nextDay+1000)
        assertEquals(7000L,Rules.total(ledger.dao.settlementItems().map { it.amountCents }))
        e.forEach { ledger.saveEmployee(it.id,it.name,true) }
        assertEquals(2,ledger.dao.settlements().size)
        assertEquals(2,ledger.dao.settlementItems().size)
        assertTrue(ledger.dao.employees().all { it.isArchived })
    }
    @Test fun concurrentSettlementIsAtomic() = runBlocking {
        val (e,t) = seed(); ledger.add(e[0].id,t[0].id,date.minusDays(1),"",now)
        val attempts = List(2) { async(Dispatchers.IO) { runCatching { ledger.settle(true,now) }.isSuccess } }.awaitAll()
        assertEquals(1,attempts.count { it }); assertEquals(1,ledger.dao.settlements().size)
        assertEquals(2000L,ledger.dao.settlementItems().single().amountCents)
    }
    @Test fun backfillRevisesOriginalSlipAndQueuesCorrectedMailWithoutEnteringNextPayroll() = runBlocking {
        val (e,t) = seed()
        ledger.add(e[0].id,t[0].id,date.minusDays(1),"",now)
        val first = ledger.settle(true,now)
        ledger.dao.saveDelivery(SettlementDelivery(first.id, sentAt = now))
        ledger.add(e[1].id,t[1].id,date,"下期",now)
        val second = ledger.settle(true,at("2026-10-19T12:00:00"))
        val added = ledger.addMany(e[0].id,t[0].id,date.minusDays(1),"补漏",2,at("2026-10-20T12:00:00"))
        assertTrue(added.all { it.settlementId == first.id })
        assertEquals(6000L,ledger.dao.settlementItems().single { it.settlementId==first.id }.amountCents)
        assertEquals(3500L,ledger.dao.settlementItems().single { it.settlementId==second.id }.amountCents)
        assertEquals(2,ledger.dao.settlement(first.id)!!.revision)
        val correction = ledger.dao.adjustments().single()
        assertEquals(2000L,correction.previousCents); assertEquals(6000L,correction.updatedCents)
        assertNull(ledger.dao.delivery(first.id)!!.sentAt)
        assertTrue(ledger.dao.payable(Rules.end(date.plusDays(3))).isEmpty())
        assertEquals(DailyTotal(6000,18000),sums(date.minusDays(1)))
    }
    @Test fun automaticFifteenthAtNoonResumesAfterEarlyClosingAndCatchesUpOnce() = runBlocking {
        val (e,t) = seed()
        ledger.updateSettings { it.copy(settlementDay=15,settlementHour=12) }
        val fifth = LocalDate.of(2026,10,5)
        ledger.add(e[0].id,t[0].id,fifth.minusDays(1),"",Rules.start(fifth))
        val early = ledger.settle(true,Rules.start(fifth))
        ledger.add(e[0].id,t[1].id,fifth,"",Rules.start(fifth))
        ledger.add(e[0].id,t[0].id,LocalDate.of(2026,10,14),"边界",at("2026-10-14T23:59:59.999"))
        ledger.add(e[0].id,t[2].id,LocalDate.of(2026,10,15),"不进本期",at("2026-10-15T00:00:00"))
        assertTrue(ledger.settleAutomatically(at("2026-10-15T11:59:59")).isEmpty())
        val slip = ledger.settleAutomatically(at("2026-10-15T12:00:00")).single()
        assertEquals(early.periodEnd,slip.periodStart)
        assertEquals(Rules.end(LocalDate.of(2026,10,14)),slip.periodEnd)
        assertEquals(5500L,ledger.dao.settlementItems().single { it.settlementId == slip.id }.amountCents)
        assertTrue(ledger.settleAutomatically(at("2026-10-15T13:00:00")).isEmpty())
        val late = ledger.settleAutomatically(at("2026-12-16T13:00:00"))
        assertEquals(listOf(LocalDate.of(2026,11,14),LocalDate.of(2026,12,14)),late.map { Rules.today(it.periodEnd) })
        assertEquals(5000L,ledger.dao.settlementItems().single { it.settlementId==late[0].id }.amountCents)
        assertTrue(ledger.dao.settlementItems().none { it.settlementId==late[1].id })
    }
    @Test fun quantityIsAtomicAndRangeQueryDoesNotCloseAnything() = runBlocking {
        val (e,t) = seed()
        fails { ledger.addMany(e[0].id,t[0].id,date,"",0,now) }
        fails { ledger.addMany(e[0].id,t[0].id,date,"",1000,now) }
        ledger.addMany(e[0].id,t[0].id,date,"",2,now)
        assertEquals(DailyTotal(4000,12000),sums())
        assertEquals(2,ledger.dao.rangeEntriesFlow(Rules.start(date),Rules.start(date.plusDays(1))).first().size)
        assertTrue(ledger.dao.settlements().isEmpty())
        assertEquals(2,ledger.dao.payable(Rules.end(date)).size)
    }
    private suspend fun fails(block: suspend () -> Unit) {
        try { block(); fail("应拒绝此操作") } catch (_: IllegalArgumentException) { }
    }
    companion object { fun at(text: String): Long = LocalDateTime.parse(text).atZone(Rules.zone).toInstant().toEpochMilli() }
}
