package cn.shopledger

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shopledger.backup.BackupEngine
import cn.shopledger.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

@RunWith(AndroidJUnit4::class)
class SettlementBreakdownDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Before fun isolatedCopyOnly()=runBlocking<Unit> {
        val app=ui.activity.application as LedgerApp
        assertEquals("请使用 -PdetailCheck=true", "cn.shopledger.detailtest",app.packageName)
        app.ledger.db.withTransaction {
            val dao=app.ledger.dao
            dao.clearItems(); dao.clearSettlements(); dao.clearEntries(); dao.clearTasks(); dao.clearEmployees()
            dao.saveSettings(Settings(autoSettlement=false,sendSettlementReport=false))
            dao.saveLocalState(LocalState())
        }
    }
    @Test fun savedSlipCountsAndRevenueRefreshAfterBackfillAndKeepArchivedHistory() {
        val app=ui.activity.application as LedgerApp
        val now=Rules.now()
        val day=Rules.today(now).minusDays(1)
        runBlocking {
            app.ledger.dao.saveEmployee(Employee("e1","甲员工",createdAt=1))
            app.ledger.dao.saveEmployee(Employee("e2","乙员工",createdAt=2))
            app.ledger.dao.saveTask(TaskType("a","项目A",2000,6000))
            app.ledger.dao.saveTask(TaskType("b","项目B",3500,10000))
            app.ledger.addQuantity("e1","a",day,"",15,now)
            app.ledger.addQuantity("e1","b",day,"",20,now)
            app.ledger.addQuantity("e2","a",day,"",5,now)
            app.ledger.addQuantity("e1","b",day.plusDays(1),"今天不进本期",10,now)
        }
        ui.onNodeWithText("工资结算").press()
        ui.waitUntil(10000){ui.onAllNodesWithText("¥320.00").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("1.5次").assertExists()
        ui.onNodeWithText("2次").assertExists()
        ui.onNodeWithText("0.5次").assertExists()
        runBlocking {
            app.ledger.settle(true,now)
            app.ledger.saveTask("a","新名称新价格",9999,12345,true)
            app.ledger.saveEmployee("e1","甲员工",true)
        }
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("展开工资明细"))
        ui.onNodeWithText("展开工资明细").press()
        ui.waitUntil(10000){ui.onAllNodesWithText("1.5次").fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithText("项目A").assertCountEquals(2)
        ui.onNodeWithText("项目B").assertExists()
        ui.onNodeWithText("新名称新价格").assertDoesNotExist()
        ui.onNodeWithText("¥320.00").assertExists()
        ui.onNodeWithText("¥100.00").assertExists()
        ui.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        capture("01-payroll-project-counts")
        runBlocking { app.ledger.addQuantity("e2","b",day,"补记",5,now+1000) }
        ui.waitUntil(10000){ui.onAllNodesWithText("¥370.00").fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithText("项目B").assertCountEquals(2)
        ui.onAllNodesWithText("0.5次").assertCountEquals(2)
        capture("02-backfill-refresh")
    }
    @Test fun originalAndCorrectedEmailsReallyContainCountsAndShopRevenue()=runBlocking<Unit>(Dispatchers.IO) {
        val context=ui.activity.applicationContext
        val db=Room.inMemoryDatabaseBuilder(context,LedgerDb::class.java).build()
        val ledger=Ledger(db)
        val engine=BackupEngine(context,ledger)
        val originalTls=SSLContext.getDefault()
        val tls=DeviceSmtpServer.tlsContext()
        SSLContext.setDefault(tls)
        try {
            ledger.dao.saveSettings(Settings(autoSettlement=false))
            ledger.dao.saveEmployee(Employee("e","邮件测试员工",createdAt=1))
            ledger.dao.saveTask(TaskType("a","项目A",2000,6000))
            ledger.dao.saveTask(TaskType("b","项目B",3500,10000))
            val day=Rules.today().minusDays(1)
            ledger.addQuantity("e","a",day,"",15)
            ledger.addQuantity("e","b",day,"",5)
            val slip=ledger.settle(true)
            suspend fun receive(): String=DeviceSmtpServer(tls).use { server ->
                engine.configure(Settings(smtpHost="localhost",smtpPort=server.port,sender="test@example.com",recipient="report@example.com"),"test-only-code")
                engine.sendSettlementReports()
                val message=jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),
                    server.received.get(10,TimeUnit.SECONDS).byteInputStream(Charsets.US_ASCII))
                assertNotNull(ledger.dao.delivery(slip.id)!!.sentAt)
                (message.content as jakarta.mail.Multipart).getBodyPart(0).content.toString()
            }
            val first=receive()
            assertTrue(first.contains("项目A：1.5次")); assertTrue(first.contains("项目B：0.5次"))
            assertTrue(first.contains("全员工资：¥47.50")); assertTrue(first.contains("店铺总营业额：¥140.00"))
            ledger.saveTask("a","新名称",9999,12345,true)
            ledger.addQuantity("e","b",day,"补记",5)
            val corrected=receive()
            assertTrue(corrected.contains("第2版"))
            assertTrue(corrected.contains("项目A：1.5次")); assertTrue(corrected.contains("项目B：1次"))
            assertTrue(corrected.contains("全员工资：¥65.00")); assertTrue(corrected.contains("店铺总营业额：¥190.00"))
            assertFalse(corrected.contains("新名称"))
            assertTrue(ledger.dao.payable(Rules.end(day)).isEmpty())
        } finally { SSLContext.setDefault(originalTls); db.close() }
    }
    private fun SemanticsNodeInteraction.press()=performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun capture(name: String) {
        ui.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"payroll-check").apply{mkdirs()}
        // Capture this app window only; other apps' floating windows are private.
        val image=ui.onRoot().captureToImage().asAndroidBitmap()
        File(directory,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
}
