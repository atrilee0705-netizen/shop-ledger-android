package cn.shopledger

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.shopledger.data.*
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
class SettlementBreakdownUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun previewSavedSlipAndCorrectionShowEveryEmployeesProjectCountsAndShopRevenue() {
        val app=ui.activity.application as LedgerApp
        val now=Rules.now()
        val day=Rules.today(now).minusDays(1)
        runBlocking {
            app.ledger.dao.saveSettings(Settings(autoSettlement=false,sendSettlementReport=false))
            app.ledger.dao.saveEmployee(Employee("e1","甲员工",createdAt=1))
            app.ledger.dao.saveEmployee(Employee("e2","乙员工",createdAt=2))
            app.ledger.dao.saveTask(TaskType("a","项目A",2000,6000))
            app.ledger.dao.saveTask(TaskType("b","项目B",3500,10000))
            app.ledger.addQuantity("e1","a",day,"",15,now)
            app.ledger.addQuantity("e1","b",day,"",20,now)
            app.ledger.addQuantity("e2","a",day,"",5,now)
            app.ledger.addQuantity("e1","a",day.plusDays(1),"本期不含今天",10,now)
        }
        ui.onNodeWithText("工资结算").press()
        ui.waitUntil(10000){ui.onAllNodesWithText("¥320.00").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("¥320.00").assertExists()
        ui.onNodeWithText("1.5次").assertExists()
        ui.onNodeWithText("2次").assertExists()
        ui.onNodeWithText("0.5次").assertExists()
        val slip=runBlocking {
            val saved=app.ledger.settle(true,now)
            app.ledger.saveTask("a","新项目名称",9999,12345,true)
            app.ledger.saveEmployee("e1","甲员工",true)
            saved
        }
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("展开工资明细"))
        ui.onNodeWithText("展开工资明细").press()
        ui.waitUntil(10000){ui.onAllNodesWithText("1.5次").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("甲员工").assertExists()
        ui.onNodeWithText("乙员工").assertExists()
        ui.onAllNodesWithText("项目A").assertCountEquals(2)
        ui.onNodeWithText("项目B").assertExists()
        ui.onNodeWithText("新项目名称").assertDoesNotExist()
        ui.onNodeWithText("¥320.00").assertExists()
        ui.onNodeWithText("¥100.00").assertExists()
        runBlocking { app.ledger.addQuantity("e2","b",day,"补记",5,now+1000) }
        ui.waitUntil(10000){ui.onAllNodesWithText("¥370.00").fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithText("项目B").assertCountEquals(2)
        ui.onAllNodesWithText("0.5次").assertCountEquals(2)
        assertEquals(2,runBlocking{app.ledger.dao.settlement(slip.id)!!.revision})
    }
    private fun SemanticsNodeInteraction.press()=performSemanticsAction(SemanticsActions.OnClick){it()}
}
