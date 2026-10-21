package cn.shopledger

import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import cn.shopledger.backup.*
import cn.shopledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LedgerApp::class, sdk = [34])
class SettlementWorkerTest {
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()
    @Before fun setup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().build())
        app.ledger.dao.saveSettings(Settings(autoSettlement=false)); app.ledger.dao.saveLocalState(LocalState())
    }
    @After fun close() { WorkManagerTestInitHelper.closeWorkDatabase(); app.ledger.db.close() }
    @Test fun automaticWorkerCreatesOnlyOneSlipAndMissingMailboxKeepsItLocally() = runBlocking {
        val ledger = app.ledger
        val today = Rules.today()
        ledger.saveEmployee(null,"后台工资测试"); ledger.saveTask(null,"A",2000,6000)
        ledger.add(ledger.dao.employees().single().id,ledger.dao.tasks().single().id,today.minusDays(1),"")
        ledger.updateSettings { it.copy(autoSettlement=true,settlementDay=today.dayOfMonth,settlementHour=0,settlementMinute=0) }
        fun worker() = TestListenableWorkerBuilder<SettlementWorker>(app).setInputData(workDataOf("checkOnly" to true)).build()
        assertEquals(ListenableWorker.Result.success(),worker().doWork())
        assertEquals(ListenableWorker.Result.success(),worker().doWork())
        val slip = ledger.dao.settlements().single()
        assertEquals(Rules.end(today.minusDays(1)),slip.periodEnd)
        assertEquals(2000L,ledger.dao.settlementItems().single().amountCents)
        val mail = TestListenableWorkerBuilder<SettlementReportWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(),mail.doWork())
        assertNull(ledger.dao.delivery(slip.id)!!.sentAt)
        assertNotNull(ledger.dao.delivery(slip.id)!!.error)
    }
    @Test fun disabledAutoSettlementDoesNotCreateASlip() = runBlocking {
        assertEquals(ListenableWorker.Result.success(),TestListenableWorkerBuilder<SettlementWorker>(app)
            .setInputData(workDataOf("checkOnly" to true)).build().doWork())
        assertTrue(app.ledger.dao.settlements().isEmpty())
    }
}
