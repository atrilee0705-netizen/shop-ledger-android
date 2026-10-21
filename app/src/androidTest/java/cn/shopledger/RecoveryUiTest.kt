package cn.shopledger

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shopledger.backup.*
import cn.shopledger.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecoveryUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Before fun setup()=runBlocking<Unit>(Dispatchers.IO) {
        val app=ui.activity.application as LedgerApp
        assertEquals("cn.shopledger.devicetest",app.packageName)
        app.ledger.db.withTransaction {
            val dao=app.ledger.dao
            dao.clearItems();dao.clearSettlements();dao.clearEntries();dao.clearTasks();dao.clearEmployees();dao.clearRecovery()
            dao.saveSettings(Settings(autoSettlement=false));dao.saveLocalState(LocalState());dao.saveRecoveryAttempts(RecoveryAttempts())
        }
        app.backup.setPassword("123456");app.backup.setRecoveryAutoEmail(false)
    }
    @Test fun ownerCanChooseThreeQuestionsResetPasswordAndCancelSystemVerification() {
        val app=ui.activity.application as LedgerApp
        fun frame(){ui.waitForIdle()}
        ui.onNodeWithText("设置").press();frame()
        ui.onNodeWithText("邮箱与备份").performScrollTo().press();frame()
        ui.onNodeWithText("忘记密码 / 密保与找回").performScrollTo().press();frame()
        ui.onNodeWithText("从题库挑选三个问题").performScrollTo().press();frame()
        ui.onNodeWithText("1. 你小时候最喜欢的昵称是什么？").press();frame()
        ui.onNodeWithText("你为找回密码准备的一个专属词是什么？").press();frame()
        ui.onNodeWithText("第1题答案").performScrollTo().performTextInput("private one")
        ui.onNodeWithText("第2题答案").performScrollTo().performTextInput("private two")
        ui.onNodeWithText("第3题答案").performScrollTo().performTextInput("private three")
        capture("06-question-picker")
        ui.onNodeWithText("保存三个问题").press()
        ui.waitUntil(15_000){runBlocking{app.ledger.dao.recovery()?.questionsJson!=null}}
        val ids=runBlocking{RecoveryFormat.decodeQuestions(app.ledger.dao.recovery()!!.questionsJson!!).ids}
        assertEquals("private_word",ids.first());assertEquals(3,ids.toSet().size)
        ui.onNodeWithText("回答密保问题重设密码").performScrollTo().press();frame()
        listOf("private one","private two","private three").forEachIndexed{i,text->ui.onNodeWithText("第${i+1}题答案").performScrollTo().performTextInput(text)}
        ui.onNodeWithText("新密码（至少6位）").performScrollTo().performTextInput("654321")
        ui.onNodeWithText("再输入一次新密码").performScrollTo().performTextInput("654321")
        ui.onNodeWithText("保存新密码").press();frame()
        ui.waitUntil(15_000){ui.onAllNodesWithText("设置新的备份密码").fetchSemanticsNodes().isEmpty()}
        runBlocking(Dispatchers.IO){
            val output=File(app.cacheDir,"ui-reset.ledgerbak")
            app.backup.export(android.net.Uri.fromFile(output))
            BackupFormat.decrypt(output.readBytes(),"654321".toCharArray())
        }
        ui.onNodeWithText("指纹 / 锁屏验证后重设").performScrollTo().press();frame()
        android.os.SystemClock.sleep(500)
        capture("07-system-verification")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        android.os.SystemClock.sleep(300);frame()
        ui.onAllNodesWithText("设置新的备份密码").assertCountEquals(0)
        capture("08-recovery-page")
        val icon=app.packageManager.getApplicationIcon(app.packageName)
        val bitmap=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0,0,256,256);icon.draw(android.graphics.Canvas(bitmap))
        File(directory(),"09-app-icon.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun directory():File {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        return (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(instrumentation.targetContext.getExternalFilesDir(null),"device-check")).apply{mkdirs()}
    }
    private fun capture(name:String){
        ui.waitForIdle();android.os.SystemClock.sleep(500)
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory(),"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun SemanticsNodeInteraction.press()=performSemanticsAction(SemanticsActions.OnClick){it()}
}
