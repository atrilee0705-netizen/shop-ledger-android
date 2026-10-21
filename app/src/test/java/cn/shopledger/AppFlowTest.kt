package cn.shopledger

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LedgerApp::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AppFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun collapsibleQuantityEntryRangeQueryAndBackfillRevisionAreVisible() {
        val app = ui.activity.application as LedgerApp
        runBlocking {
            app.ledger.saveEmployee(null,"测试店员")
            app.ledger.saveTask(null,"基础服务",2000,6000)
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("展开测试店员").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("上一天").press()
        ui.onNodeWithContentDescription("展开测试店员").press()
        ui.onNodeWithText("次数").performTextReplacement("2")
        ui.onNodeWithContentDescription("为测试店员记录基础服务").press()
        ui.waitUntil(10_000) { runBlocking { app.ledger.dao.entries().size == 2 } }
        ui.onNodeWithText("¥120.00").assertExists()
        runBlocking { app.ledger.delete(app.ledger.dao.entries().first().id) }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("¥60.00").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("工资结算").press()
        ui.onAllNodesWithText("¥20.00").onFirst().assertExists()
        ui.onNodeWithText("只查金额").press()
        ui.onNodeWithText("店铺营业额").assertExists()
        org.junit.Assert.assertTrue(runBlocking { app.ledger.dao.settlements().isEmpty() })
        ui.onNodeWithText("接着上次结账").press()
        ui.onNodeWithText("保存工资单").performScrollTo().press()
        ui.onNodeWithText("确认保存").press()
        ui.waitUntil(10_000) { runBlocking { app.ledger.dao.settlements().size == 1 } }
        ui.onNodeWithText("记账").press()
        ui.onNodeWithContentDescription("展开测试店员").press()
        ui.onNodeWithContentDescription("为测试店员记录基础服务").press()
        ui.onNodeWithText("补记并修正").press()
        ui.waitUntil(10_000) { runBlocking { app.ledger.dao.settlements().single().revision==2 } }
        ui.onNodeWithText("当天明细").press()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("已列入工资单 · 补记会修正原单").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("删除").assertCountEquals(0)
    }
    private fun SemanticsNodeInteraction.press() = performSemanticsAction(SemanticsActions.OnClick) { it() }
}
