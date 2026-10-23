package cn.shopledger

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
class WheelGestureTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun wheelOwnsVerticalDiagonalAndEndpointDragsWhileThePageStillScrollsElsewhere() {
        val app=ui.activity.application as LedgerApp
        runBlocking {
            app.ledger.saveEmployee(null,"测试员工")
            repeat(8) { app.ledger.saveEmployee(null,"其他员工$it") }
            app.ledger.saveTask(null,"服务",3800,6000)
        }
        ui.waitUntil(10000){ui.onAllNodesWithContentDescription("展开测试员工").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("展开测试员工").performSemanticsAction(SemanticsActions.OnClick){it()}
        val wheel=ui.onNodeWithContentDescription("测试员工的服务次数滚轮，每格0.5次",useUnmergedTree=true)
        val header=ui.onNodeWithText("测试员工")
        val original=header.fetchSemanticsNode().boundsInRoot.top
        repeat(12) {
            wheel.performTouchInput { swipe(Offset(width*.25f,height*.75f),Offset(width*.7f,height*.15f),180) }
        }
        assertTrue(Rules.parseQuantity(ui.onNodeWithText("次数").fetchSemanticsNode().config[SemanticsProperties.EditableText].text) > 10)
        wheel.performTouchInput { swipe(center,Offset(width*2f,-height*.5f),300) }
        assertEquals(original,header.fetchSemanticsNode().boundsInRoot.top,.5f)
        val stopped=ui.onNodeWithText("次数").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        ui.mainClock.advanceTimeBy(1500)
        ui.onNodeWithText("次数").assertTextContains(stopped)
        ui.onNodeWithText("次数").performTextReplacement("0.5")
        repeat(3) { wheel.performTouchInput { swipeDown() } }
        ui.onNodeWithText("次数").assertTextContains("0.5")
        ui.onNodeWithText("次数").performTextReplacement("999.0")
        repeat(3) { wheel.performTouchInput { swipeUp() } }
        ui.onNodeWithText("次数").assertTextContains("999.0")
        assertEquals(original,header.fetchSemanticsNode().boundsInRoot.top,.5f)
        assertEquals(0,runBlocking { app.ledger.dao.entryCount() })
        ui.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        assertTrue(ui.onAllNodesWithText("测试员工").fetchSemanticsNodes().isEmpty() || header.fetchSemanticsNode().boundsInRoot.top < original)
    }
}
