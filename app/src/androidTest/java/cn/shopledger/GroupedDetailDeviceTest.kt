package cn.shopledger

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shopledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GroupedDetailDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun oneDetailPerSubmissionAndWholeGroupActionsPreserveAccounting() {
        val app=ui.activity.application as LedgerApp
        assertEquals("请使用 -PdetailCheck=true，与已有两个账本隔离", "cn.shopledger.detailtest",app.packageName)
        val now=Rules.now()
        val day=Rules.today(now).minusDays(1)
        runBlocking {
            app.ledger.db.withTransaction {
                val dao=app.ledger.dao
                dao.clearItems(); dao.clearSettlements(); dao.clearEntries(); dao.clearTasks(); dao.clearEmployees()
                dao.saveSettings(Settings(autoSettlement=false,sendSettlementReport=false))
                dao.saveLocalState(LocalState())
            }
            app.ledger.saveEmployee(null,"明细测试员工")
            app.ledger.saveTask(null,"服务",100,200)
            val employee=app.ledger.dao.employees().single().id
            val task=app.ledger.dao.tasks().single().id
            app.ledger.addQuantity(employee,task,day,"老牛",30,now)
            app.ledger.addQuantity(employee,task,day,"老吴",15,now+1000)
        }
        ui.waitUntil(10000){ui.onAllNodesWithText("当天明细").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("上一天").press()
        ui.onNodeWithText("当天明细").press()
        ui.waitUntil(10000){ui.onAllNodesWithText("服务 · 3次").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("服务 · 3次").assertExists()
        ui.onNodeWithText("服务 · 1.5次").assertExists()
        ui.onNodeWithText("服务 · 1次").assertDoesNotExist()
        ui.onNodeWithText("服务 · 0.5次").assertDoesNotExist()
        ui.onAllNodesWithText("删除").assertCountEquals(2)
        assertEquals(5,runBlocking{app.ledger.dao.entryCount()})
        capture("01-merged-submissions")
        ui.onAllNodesWithText("修改").onFirst().press()
        ui.onNodeWithText("备注").performTextReplacement("三次一起修改")
        ui.onNodeWithText("保存").press()
        ui.waitUntil(10000){runBlocking{app.ledger.dao.entries().count{it.note=="三次一起修改"}==3}}
        ui.onNodeWithText("服务 · 3次").assertExists()
        ui.onAllNodesWithText("删除").onFirst().press()
        ui.onNodeWithText("将删除本次记录的3次工作，减少工资 ¥3.00 和营业额 ¥6.00。").assertExists()
        ui.onNodeWithText("取消").press()
        assertEquals(5,runBlocking{app.ledger.dao.entryCount()})
        ui.onAllNodesWithText("删除").onFirst().press()
        ui.onAllNodesWithText("删除").onLast().press()
        ui.waitUntil(10000){runBlocking{app.ledger.dao.entryCount()==2}}
        ui.onNodeWithText("服务 · 3次").assertDoesNotExist()
        ui.onNodeWithText("服务 · 1.5次").assertExists()
        assertTrue(runBlocking{app.ledger.dao.entries().all{it.note=="老吴"}})
        capture("02-delete-one-submission")
        runBlocking {
            app.ledger.settle(true,now+2000)
            val item=app.ledger.dao.settlementItems().single()
            assertEquals(150L,item.amountCents)
            val task=app.ledger.dao.tasks().single()
            app.ledger.saveTask(task.id,task.name,task.wageCents,task.revenueCents,true)
        }
        ui.waitUntil(10000){ui.onAllNodesWithText("已列入工资单 · 补记会修正原单").fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithText("修改").assertCountEquals(0)
        ui.onAllNodesWithText("删除").assertCountEquals(0)
        ui.onNodeWithText("服务 · 1.5次").assertExists()
        capture("03-settled-and-archived")
    }
    private fun SemanticsNodeInteraction.press()=performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun capture(name: String) {
        ui.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"detail-check").apply{mkdirs()}
        val image=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
}
