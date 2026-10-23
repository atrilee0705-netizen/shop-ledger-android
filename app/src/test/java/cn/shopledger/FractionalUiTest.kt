package cn.shopledger

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
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
@Config(application = LedgerApp::class, sdk = [34], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FractionalUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun inlineWheelAndDecimalInputProduceTheShownMoneyWithoutOpeningAMenu() {
        val app=ui.activity.application as LedgerApp
        runBlocking {
            app.ledger.saveEmployee(null,"测试员工")
            app.ledger.saveTask(null,"服务",3800,6000)
        }
        ui.waitUntil(10000){ui.onAllNodesWithContentDescription("展开测试员工").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("上一天").press()
        ui.onNodeWithContentDescription("展开测试员工").press()
        ui.onNodeWithText("滚轮选次数").assertDoesNotExist()
        ui.onNodeWithText("使用这个次数").assertDoesNotExist()
        ui.onNodeWithText("次数").performTextReplacement("1.3")
        wheel().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"1.3次"))
        ui.onNodeWithText("次数").assertTextContains("1.3")
        assertEquals(0,runBlocking { app.ledger.dao.entryCount() })
        ui.onNodeWithText("次数").performTextReplacement("1.0")
        wheel().performTouchInput { swipe(Offset(centerX,height*.2f),Offset(centerX,height*.9f),600) }
        ui.onNodeWithText("次数").assertTextContains("0.5")
        assertEquals(0,runBlocking { app.ledger.dao.entryCount() })
        ui.onNodeWithContentDescription("为测试员工记录服务").press()
        ui.waitUntil(10000){runBlocking{app.ledger.dao.entries().sumOf{it.quantityTenths}==5}}
        ui.onNodeWithText("次数").performTextReplacement("1.25")
        ui.onNodeWithContentDescription("为测试员工记录服务").assertIsNotEnabled()
        ui.onNodeWithText("次数").performTextReplacement("1.3")
        ui.onNodeWithContentDescription("为测试员工记录服务").press()
        ui.waitUntil(10000){runBlocking{app.ledger.dao.entries().sumOf{it.quantityTenths}==18}}
        ui.onNodeWithText("1.8次 · ¥68.40").assertExists()
        ui.onNodeWithText("当天明细").press()
        ui.onNodeWithText("服务 · 0.5次").assertExists()
        ui.onNodeWithText("服务 · 1.3次").assertExists()
        ui.onNodeWithText("服务 · 0.3次").assertDoesNotExist()
        ui.onNodeWithText("服务 · 1次").assertDoesNotExist()
        ui.onNodeWithText("¥108.00").assertExists()
    }
    private fun wheel()=ui.onNodeWithContentDescription("测试员工的服务次数滚轮，每格0.5次",useUnmergedTree=true)
    private fun SemanticsNodeInteraction.press()=performSemanticsAction(SemanticsActions.OnClick){it()}
}
