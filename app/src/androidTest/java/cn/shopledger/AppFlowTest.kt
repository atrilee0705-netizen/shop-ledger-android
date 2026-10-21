package cn.shopledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import cn.shopledger.data.Settings
import cn.shopledger.data.LocalState
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun startWithAnEmptyIsolatedLedger() = runBlocking<Unit> {
        val app = ui.activity.application as LedgerApp
        assertEquals("请使用 -PdeviceCheck=true，防止修改正式账本", "cn.shopledger.devicetest", app.packageName)
        app.ledger.db.withTransaction {
            val dao = app.ledger.dao
            dao.clearItems(); dao.clearSettlements(); dao.clearEntries(); dao.clearTasks(); dao.clearEmployees()
            dao.saveSettings(Settings()); dao.saveLocalState(LocalState())
        }
    }
    @Test fun ownerCanCreateSetupRecordTwiceDeleteAndSettle() {
        val app = ui.activity.application as LedgerApp
        fun frame() { ui.waitForIdle() }
        fun waitFor(check: () -> Boolean) { ui.waitUntil(10_000,check); frame() }
        frame()
        ui.onNodeWithText("设置").press()
        frame()
        ui.onNodeWithText("员工管理").performScrollTo().press()
        frame()
        ui.onNodeWithText("添加员工").press()
        frame()
        ui.onNodeWithText("员工姓名").performTextInput("测试店员")
        frame()
        ui.onNodeWithText("保存").press()
        waitFor { runBlocking { app.ledger.dao.employees().any { it.name == "测试店员" } } }
        ui.onNodeWithContentDescription("返回").press()
        frame()
        ui.onNodeWithText("任务管理").performScrollTo().press()
        frame()
        ui.onNodeWithText("添加任务").press()
        frame()
        ui.onNodeWithText("任务名称").performTextInput("基础服务")
        frame()
        ui.onNodeWithText("员工工资（元 / 次）").performTextInput("20")
        frame()
        ui.onNodeWithText("店铺收入（元 / 次）").performTextInput("60")
        frame()
        ui.onNodeWithText("保存").press()
        waitFor { runBlocking { app.ledger.dao.tasks().isNotEmpty() } }
        ui.onNodeWithText("记账").press()
        frame()
        ui.onNodeWithContentDescription("上一天").press()
        waitFor { ui.onAllNodesWithContentDescription("展开测试店员").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("展开测试店员").press()
        frame()
        ui.onNodeWithText("次数").performTextReplacement("2")
        ui.onNodeWithContentDescription("为测试店员记录基础服务").press()
        waitFor { runBlocking { app.ledger.dao.entries().size == 2 } }
        ui.onNodeWithText("店铺营业额").assertExists()
        ui.onNodeWithText("¥120.00").assertExists()
        capture("01-daily-ledger")
        ui.onNodeWithText("当天明细").press()
        frame()
        capture("02-work-details")
        ui.onAllNodesWithText("删除").onFirst().press()
        frame()
        ui.onAllNodesWithText("删除").onLast().press()
        waitFor { runBlocking { app.ledger.dao.entries().size == 1 } }
        ui.onNodeWithContentDescription("返回").press()
        frame()
        ui.onNodeWithText("工资结算").press()
        frame()
        ui.onNodeWithText("只查金额").press()
        frame()
        ui.onNodeWithText("店铺营业额").assertExists()
        assertTrue(runBlocking { app.ledger.dao.settlements().isEmpty() })
        ui.onNodeWithText("接着上次结账").press()
        frame()
        ui.onNodeWithText("保存工资单").performScrollTo().press()
        frame()
        ui.onNodeWithText("确认保存").press()
        waitFor { runBlocking { app.ledger.dao.settlements().size == 1 } }
        runBlocking { assertEquals(2000L, app.ledger.dao.settlementItems().single().amountCents) }
        ui.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        waitFor { ui.onAllNodesWithText("撤销最近一期结账").fetchSemanticsNodes().isNotEmpty() }
        capture("03-settlement")
        ui.onNodeWithText("记账").press()
        frame()
        ui.onNodeWithContentDescription("展开测试店员").press()
        frame()
        ui.onNodeWithContentDescription("为测试店员记录基础服务").press()
        frame()
        ui.onNodeWithText("补记并修正").press()
        waitFor { runBlocking { app.ledger.dao.settlements().single().revision==2 } }
        runBlocking {
            assertEquals(4000L,app.ledger.dao.settlementItems().single().amountCents)
            assertEquals(1,app.ledger.dao.adjustments().size)
            assertTrue(app.ledger.dao.entries().all { it.settlementId!=null })
        }
        ui.onNodeWithText("设置").press()
        frame()
        capture("04-settings")
        ui.onNodeWithText("邮箱与备份").performScrollTo().press()
        frame()
        ui.onNodeWithText("授权码怎么获取？点这里看步骤").performScrollTo().press()
        frame()
        ui.onNodeWithText("授权码怎么获取").assertExists()
        ui.onNodeWithText("Gmail").press()
        frame()
        capture("05-mail-help")
    }
    private fun SemanticsNodeInteraction.press() = performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun capture(name: String) {
        ui.waitForIdle()
        android.os.SystemClock.sleep(500)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(instrumentation.targetContext.getExternalFilesDir(null), "device-check")
        directory.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
