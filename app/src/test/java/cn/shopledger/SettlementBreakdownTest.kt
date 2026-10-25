package cn.shopledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.shopledger.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class SettlementBreakdownTest {
    private lateinit var db: LedgerDb
    private lateinit var ledger: Ledger
    private val day=java.time.LocalDate.of(2026,10,24)
    private val now=Rules.start(day.plusDays(1))+3600000
    @Before fun setup()=runBlocking<Unit> {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LedgerDb::class.java).build()
        ledger=Ledger(db)
        ledger.dao.saveEmployee(Employee("e1","甲员工",createdAt=1))
        ledger.dao.saveEmployee(Employee("e2","乙员工",createdAt=2))
        ledger.dao.saveTask(TaskType("a","A",2000,6000))
        ledger.dao.saveTask(TaskType("b","B",3500,10000))
    }
    @After fun close(){db.close()}
    @Test fun savedPayrollFlowsPreserveArchivesAndUpdateCountsAndRevenueAfterBackfill()=runBlocking<Unit> {
        ledger.addQuantity("e1","a",day,"",15,now)
        ledger.addQuantity("e1","b",day,"",20,now)
        ledger.addQuantity("e2","a",day,"",5,now)
        ledger.addQuantity("e1","b",day.plusDays(1),"今天不进工资单",10,now)
        ledger.addQuantity("e1","b",day.plusDays(2),"未来不进工资单",10,now)
        val slip=ledger.settle(true,now)
        ledger.saveTask("a","新名称新价格",9999,12345,true)
        ledger.saveEmployee("e1","甲员工",true)
        val work=ledger.dao.settledEntriesFlow(slip.id).first()
        assertEquals(40,work.sumOf{it.quantityTenths})
        assertEquals(listOf(TaskQuantity("a","A",15),TaskQuantity("b","B",20)).toSet(),taskQuantities(work.filter{it.employeeId=="e1"}).toSet())
        assertEquals(32000L,ledger.dao.settlementRevenueFlow(slip.id).first())
        assertEquals(11000L,ledger.dao.settlementItems().sumOf{it.amountCents})
        ledger.addQuantity("e2","b",day,"补记",5,now+1000)
        val updated=ledger.dao.settledEntriesFlow(slip.id).first()
        assertEquals(45,updated.sumOf{it.quantityTenths})
        assertEquals(5,taskQuantities(updated.filter{it.employeeId=="e2"}).single{it.taskId=="b"}.quantityTenths)
        assertEquals(37000L,ledger.dao.settlementRevenueFlow(slip.id).first())
        assertEquals(12750L,ledger.dao.settlementItems().sumOf{it.amountCents})
        assertEquals(2,ledger.dao.settlement(slip.id)!!.revision)
        assertEquals(2,ledger.dao.entries().count{it.settlementId==null})
        assertEquals(0L,ledger.dao.settlementRevenueFlow("nonexistent").first())
    }
    @Test fun aggregateRevenueMatchesSavedRoundedRowsAndAnEmptyPayroll()=runBlocking<Unit> {
        ledger.saveTask("a","分位服务",1,3)
        ledger.addQuantity("e1","a",day,"",5,now)
        ledger.addQuantity("e1","a",day,"",5,now+1)
        val slip=ledger.settle(true,now)
        assertEquals(4L,ledger.dao.settlementRevenueFlow(slip.id).first())
        assertEquals(4L,ledger.dao.settledEntriesFlow(slip.id).first().sumOf{it.revenueAmountCents})
        val empty=ledger.settle(true,now+86400000,allowEmpty=true)
        assertTrue(ledger.dao.settledEntriesFlow(empty.id).first().isEmpty())
        assertEquals(0L,ledger.dao.settlementRevenueFlow(empty.id).first())
    }
}
