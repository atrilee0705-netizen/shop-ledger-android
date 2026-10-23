package cn.shopledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.shopledger.backup.*
import cn.shopledger.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FractionalQuantityTest {
    private lateinit var db: LedgerDb
    private lateinit var ledger: Ledger
    private val day=LocalDate.of(2026,10,21)
    private val now=Rules.start(day.plusDays(1))+3600000
    @Before fun setup()=runBlocking<Unit> {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LedgerDb::class.java).build()
        ledger=Ledger(db)
        ledger.dao.saveEmployee(Employee("e","员工",createdAt=Rules.start(day)))
        ledger.dao.saveTask(TaskType("t","服务",3800,6000))
    }
    @After fun close(){db.close()}
    private suspend fun total()=ledger.dao.dailyTotal(Rules.start(day),Rules.start(day.plusDays(1))).first()
    @Test fun tenthsParsingRejectsExtraPrecisionAndHandlesBoundsWithoutFloatingPoint() {
        for((text,value) in listOf("0.1" to 1,".5" to 5,"1." to 10,"1.3" to 13,"1.5" to 15,"999.0" to 9990)) assertEquals(value,Rules.parseQuantity(text))
        for(text in listOf("",".","0","0.0","-0.5","1.25","1e1","NaN","999.1","1000","1,5")) assertThrows(IllegalArgumentException::class.java){Rules.parseQuantity(text)}
        assertEquals("0.5",Rules.quantityText(5));assertEquals("2",Rules.quantityText(20))
        assertEquals(1900L,Rules.quantityAmount(3800,5));assertEquals(5700L,Rules.quantityAmount(3800,15))
    }
    @Test fun fractionsDriveSqlTotalsEmployeeTotalsAndCountsAndCanBeDeletedIndependently()=runBlocking<Unit> {
        val half=ledger.addQuantity("e","t",day,"半次",5,now).single()
        val onePointThree=ledger.addQuantity("e","t",day,"1.3次",13,now)
        assertEquals(listOf(10,3),onePointThree.map{it.quantityTenths})
        assertEquals(1900L,half.wageAmountCents)
        assertEquals(DailyTotal(6840,10800),total())
        assertEquals(18,ledger.dao.taskCounts(Rules.start(day),Rules.start(day.plusDays(1))).first().single().countTenths)
        assertEquals(6840L,ledger.dao.employeeTotals(Rules.start(day),Rules.start(day.plusDays(1))).first().single().amount)
        ledger.delete(half.id)
        assertEquals(DailyTotal(4940,7800),total())
        ledger.saveTask("t","新价格",9900,10000)
        assertEquals(DailyTotal(4940,7800),total()) // per-task prices remain historical snapshots
        val before=ledger.dao.entries()
        for(bad in listOf(0,-1,9991)) assertThrows(IllegalArgumentException::class.java){runBlocking{ledger.addQuantity("e","t",day,"",bad,now)}}
        assertEquals(before,ledger.dao.entries())
    }
    @Test fun sqliteAndKotlinRoundEachSavedLineToFenIdentically()=runBlocking<Unit> {
        ledger.saveTask("t","分位测试",1,3)
        val rows=ledger.addQuantity("e","t",day,"",15,now)
        assertEquals(DailyTotal(2,5),total()) // 0.015 yuan -> 0.02; 0.045 -> 0.05
        assertEquals(total().wage,rows.sumOf{it.wageAmountCents})
        assertEquals(total().revenue,rows.sumOf{it.revenueAmountCents})
        ledger.settle(true,now)
        assertEquals(2L,ledger.dao.settlementItems().single().amountCents)
    }
    @Test fun oldBusinessPayloadsWithoutQuantityRemainOneTaskPerRow() {
        for (version in 1..2) {
            val original=LedgerSnapshot(version=version,exportedAt=now,employees=listOf(Employee("e","员工",createdAt=Rules.start(day))),tasks=listOf(TaskType("t","服务",3800,6000)),
                entries=listOf(WorkEntry("w","e","t","服务",3800,6000,Rules.start(day),"旧账",Rules.start(day))),settlements=emptyList(),items=emptyList(),settings=Settings())
            val legacy=BackupFormat.encodeSnapshot(original).toString(Charsets.UTF_8).replace(",\"quantityTenths\":10", "").toByteArray()
            val normalized=BackupFormat.normalize(BackupFormat.decodeSnapshot(legacy))
            assertEquals(3,normalized.version)
            assertEquals(10,normalized.entries.single().quantityTenths)
            assertEquals(3800L,normalized.entries.single().wageAmountCents)
            assertEquals(6000L,normalized.entries.single().revenueAmountCents)
        }
    }
    @Test fun fractionalBackfillRevisesOriginalSlipAndNewBackupRetainsAllRecoveryRoutes()=runBlocking<Unit> {
        ledger.addQuantity("e","t",day,"",15,now)
        ledger.addQuantity("e","t",day.plusDays(1),"今天",5,now)
        ledger.addQuantity("e","t",day.plusDays(2),"未来",3,now)
        val slip=ledger.settle(true,now)
        assertEquals(5700L,ledger.dao.settlementItems().single().amountCents)
        ledger.addQuantity("e","t",day,"漏记",2,now+1000)
        assertEquals(6460L,ledger.dao.settlementItems().single().amountCents)
        assertEquals(2,ledger.dao.settlement(slip.id)!!.revision)
        assertTrue(ledger.dao.adjustments().single().reason.contains("0.2次"))
        assertEquals(2,ledger.dao.entries().count{it.settlementId==null})
        val snapshot=LedgerSnapshot(exportedAt=now+2000,employees=ledger.dao.employees(),tasks=ledger.dao.tasks(),entries=ledger.dao.entries(),settlements=ledger.dao.settlements(),items=ledger.dao.settlementItems(),settings=Settings(),adjustments=ledger.dao.adjustments())
        val root=RecoveryFormat.newCredential(now)
        val questions=RecoveryFormat.protectQuestions(root,RecoveryFormat.questionBank.keys.take(3),listOf("甲","乙","丙"))
        val bytes=RecoveryFormat.encrypt(snapshot,"123456".toCharArray(),root,questions)
        assertEquals(snapshot,BackupFormat.decrypt(bytes,"123456".toCharArray()))
        assertEquals(snapshot,RecoveryFormat.decryptCredential(bytes,root).snapshot)
        assertEquals(snapshot,RecoveryFormat.decryptQuestions(bytes,listOf("甲","乙","丙")).snapshot)
        assertThrows(IllegalArgumentException::class.java){BackupFormat.validate(snapshot.copy(entries=snapshot.entries.map{it.copy(quantityTenths=11)}))}
        assertThrows(IllegalArgumentException::class.java){BackupFormat.validate(snapshot.copy(version=2))}
    }
    @Test fun retiringTaskKeepsSettledAndUnsettledHistorySnapshotsAndBackup()=runBlocking<Unit> {
        ledger.addQuantity("e","t",day,"已结账",15,now)
        ledger.addQuantity("e","t",day.plusDays(1),"待结账",5,now)
        val slip=ledger.settle(true,now)
        val entries=ledger.dao.entries()
        val items=ledger.dao.settlementItems()
        val slips=ledger.dao.settlements()
        ledger.saveTask("t","停止提供的服务",9900,12000,true)
        assertTrue(ledger.dao.tasks().single().isArchived)
        assertEquals(entries,ledger.dao.entries())
        assertEquals(items,ledger.dao.settlementItems())
        assertEquals(slips,ledger.dao.settlements())
        assertEquals(DailyTotal(5700,9000),total())
        assertEquals(DailyTotal(1900,3000),ledger.dao.dailyTotal(Rules.start(day.plusDays(1)),Rules.start(day.plusDays(2))).first())
        assertEquals(listOf(10,5),ledger.dao.settledEntries(slip.id).map { it.quantityTenths }.sortedDescending())
        assertTrue(ledger.dao.settledEntries(slip.id).all { it.taskNameSnapshot=="服务" && it.wageCentsSnapshot==3800L && it.revenueCentsSnapshot==6000L })
        assertThrows(IllegalArgumentException::class.java){runBlocking{ledger.addQuantity("e","t",day,"不能再记",5,now)}}
        val snapshot=LedgerSnapshot(exportedAt=now,employees=ledger.dao.employees(),tasks=ledger.dao.tasks(),entries=entries,settlements=slips,items=items,settings=Settings())
        val decoded=BackupFormat.decodeSnapshot(BackupFormat.encodeSnapshot(snapshot))
        BackupFormat.validate(decoded)
        assertEquals(snapshot,decoded)
        ledger.saveTask("t","重新提供的服务",9900,12000,false)
        assertEquals(entries,ledger.dao.entries())
        assertEquals(items,ledger.dao.settlementItems())
    }
    @Test fun groupedEditAndDeleteApplyToTheWholeSubmissionAndLeaveOtherSubmissionsAlone()=runBlocking<Unit> {
        val batch=ledger.addQuantity("e","t",day,"一起记的",15,now)
        val separate=ledger.addQuantity("e","t",day,"另外记的",20,now+1)
        ledger.editMany(batch.map{it.id},Rules.start(day)+1000,"一起改备注")
        val edited=ledger.dao.entries().filter{it.id in batch.map{row->row.id}}
        assertTrue(edited.all{it.completedAt==Rules.start(day)+1000 && it.note=="一起改备注"})
        assertEquals(15,edited.sumOf{it.quantityTenths})
        assertEquals(separate,ledger.dao.entries().filter{it.id in separate.map{row->row.id}})
        val before=ledger.dao.entries()
        assertThrows(IllegalArgumentException::class.java){runBlocking{ledger.editMany(listOf(batch.first().id,"missing"),Rules.start(day)+2000,"不能只改一半")}}
        assertThrows(IllegalArgumentException::class.java){runBlocking{ledger.deleteMany(listOf(batch.first().id,"missing"))}}
        assertEquals(before,ledger.dao.entries())
        ledger.deleteMany(batch.map{it.id})
        assertEquals(separate,ledger.dao.entries())
        assertEquals(DailyTotal(7600,12000),total())
    }
    @Test fun groupedOperationsCannotPartiallyChangeRowsWhenAnyAreSettled()=runBlocking<Unit> {
        val settled=ledger.addQuantity("e","t",day,"已结",15,now)
        val slip=ledger.settle(true,now)
        val pending=ledger.addQuantity("e","t",day.plusDays(1),"未结",15,now+1)
        val before=ledger.dao.entries()
        val ids=pending.map{it.id}+settled.map{it.id}
        assertThrows(IllegalArgumentException::class.java){runBlocking{ledger.deleteMany(ids)}}
        assertThrows(IllegalArgumentException::class.java){runBlocking{ledger.editMany(ids,Rules.start(day.plusDays(1)),"不应更改")}}
        assertThrows(IllegalArgumentException::class.java){runBlocking{ledger.editMany(pending.map{it.id},Rules.start(day),"不能移入工资单")}}
        assertEquals(before,ledger.dao.entries())
        assertEquals(5700L,ledger.dao.settlementItems().single{it.settlementId==slip.id}.amountCents)
    }
}
