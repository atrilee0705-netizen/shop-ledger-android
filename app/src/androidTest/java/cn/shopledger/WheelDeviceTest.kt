package cn.shopledger

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shopledger.data.LocalState
import cn.shopledger.data.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real injected touch events travel through the page, text field and wheel. */
@RunWith(AndroidJUnit4::class)
class WheelDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun wheelGesturesFeedbackAndArchivedHistoryOnIsolatedCopy() {
        val app=ui.activity.application as LedgerApp
        assertEquals("cn.shopledger.devicetest",app.packageName)
        runBlocking {
            app.ledger.db.withTransaction {
                val dao=app.ledger.dao
                dao.clearItems(); dao.clearSettlements(); dao.clearEntries(); dao.clearTasks(); dao.clearEmployees()
                dao.saveSettings(Settings(autoSettlement=false,sendSettlementReport=false))
                dao.saveLocalState(LocalState())
            }
            app.ledger.saveEmployee(null,"滚轮测试员工")
            repeat(8){app.ledger.saveEmployee(null,"其他员工$it")}
            app.ledger.saveTask(null,"测试服务",3800,6000)
        }
        app.wheelPreferences.setStrength(1)
        ui.waitUntil(10000){ui.onAllNodesWithContentDescription("展开滚轮测试员工").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("上一天").press()
        ui.onNodeWithContentDescription("展开滚轮测试员工").press()
        val wheel=ui.onNodeWithContentDescription("滚轮测试员工的测试服务次数滚轮，每格0.5次",useUnmergedTree=true)
        val input=ui.onNodeWithText("次数")
        val header=ui.onNodeWithText("滚轮测试员工")
        ui.waitForIdle()
        val original=header.fetchSemanticsNode().boundsInRoot.top
        repeat(15) {
            wheel.performTouchInput { swipe(Offset(width*.25f,height*.8f),Offset(width*.75f,height*.15f),220) }
            assertEquals("滚轮拖动不能带动页面",original,header.fetchSemanticsNode().boundsInRoot.top,1f)
        }
        assertTrue(Rules.parseQuantity(input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)>10)
        wheel.performTouchInput { swipe(center,Offset(width*2f,-height*.5f),300) }
        assertEquals(original,header.fetchSemanticsNode().boundsInRoot.top,1f)
        val stopped=input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        ui.mainClock.advanceTimeBy(1500)
        input.assertTextContains(stopped)
        input.performTextReplacement("0.5")
        repeat(5){wheel.performTouchInput{swipeDown()}}
        input.assertTextContains("0.5")
        val lower=header.fetchSemanticsNode().boundsInRoot.top
        wheel.performTouchInput{swipeDown()}
        assertEquals(lower,header.fetchSemanticsNode().boundsInRoot.top,1f)
        input.performTextReplacement("999.0")
        repeat(5){wheel.performTouchInput{swipeUp()}}
        input.assertTextContains("999.0")
        assertEquals(0,runBlocking{app.ledger.dao.entryCount()})
        input.performTextReplacement("1.3")
        wheel.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"1.3次"))
        wheel.performTouchInput{click(center)}
        input.assertIsNotFocused()
        input.assertTextContains("1.3")
        capture("01-inline-wheel")
        ui.onNodeWithContentDescription("为滚轮测试员工记录测试服务").press()
        ui.waitUntil(10000){runBlocking{app.ledger.dao.entries().sumOf{it.quantityTenths}==13}}
        ui.onAllNodesWithText("当天明细").onFirst().press()
        ui.onNodeWithText("测试服务 · 1.3次").assertExists()
        ui.onNodeWithContentDescription("返回").press()
        ui.onNodeWithText("设置").press()
        ui.onNodeWithText("次数滚轮震动").assertExists()
        ui.onNodeWithText("关闭").press()
        assertEquals(0,app.wheelPreferences.strength.value)
        ui.onNodeWithText("试一下震动").assertIsNotEnabled()
        ui.onNodeWithText("强").press()
        assertEquals(3,WheelPreferences(app).strength.value)
        ui.onNodeWithText("试一下震动").press()
        capture("02-wheel-haptics")
        ui.onNodeWithText("轻").press()
        runBlocking {
            val slip=app.ledger.settle(true)
            val entries=app.ledger.dao.entries()
            val items=app.ledger.dao.settlementItems()
            val task=app.ledger.dao.tasks().single()
            app.ledger.saveTask(task.id,task.name,task.wageCents,task.revenueCents,true)
            assertEquals(entries,app.ledger.dao.entries())
            assertEquals(items,app.ledger.dao.settlementItems())
            assertEquals(4940L,items.single().amountCents)
            assertEquals(13,app.ledger.dao.settledEntries(slip.id).sumOf{it.quantityTenths})
        }
        ui.onNodeWithText("记账").press()
        ui.onNodeWithContentDescription("为滚轮测试员工记录测试服务").assertDoesNotExist()
        ui.onAllNodesWithText("当天明细").onFirst().press()
        ui.onNodeWithText("测试服务 · 1.3次").assertExists()
        capture("03-archived-history")
        ui.onNodeWithContentDescription("返回").press()
        val page=ui.onNode(hasScrollToIndexAction())
        val beforeScroll=header.fetchSemanticsNode().boundsInRoot.top
        page.performTouchInput{swipeUp()}
        assertTrue(ui.onAllNodesWithText("滚轮测试员工").fetchSemanticsNodes().isEmpty() || header.fetchSemanticsNode().boundsInRoot.top<beforeScroll)
        Log.i("WheelDeviceTest","PASS: real gestures, endpoints, haptics settings, fractional recording and archived historical slip")
    }
    private fun SemanticsNodeInteraction.press()=performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun capture(name: String) {
        ui.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"wheel-check").apply{mkdirs()}
        val image=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
}
