package cn.shopledger

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application=LedgerApp::class,sdk=[34],qualifiers="w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class GroupedDetailUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun oneDetailPerSubmissionWithWholeGroupDeletionAndPayrollProtection() {
        val app=ui.activity.application as LedgerApp
        val now=Rules.now()
        val day=Rules.today(now).minusDays(1)
        runBlocking {
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
        runBlocking { app.ledger.settle(true,now+2000) }
        ui.waitUntil(10000){ui.onAllNodesWithText("已列入工资单 · 补记会修正原单").fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithText("修改").assertCountEquals(0)
        ui.onAllNodesWithText("删除").assertCountEquals(0)
        ui.onNodeWithText("服务 · 1.5次").assertExists()
    }
    private fun SemanticsNodeInteraction.press()=performSemanticsAction(SemanticsActions.OnClick){it()}
}
