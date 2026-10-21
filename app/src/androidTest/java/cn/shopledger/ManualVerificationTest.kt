package cn.shopledger

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shopledger.backup.BackupFormat
import cn.shopledger.backup.DeviceSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Explicitly selected only when the owner is present to perform system authentication. */
@RunWith(AndroidJUnit4::class)
class ManualVerificationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun ownerVerifiesWithTheSystemAndTheAppResetsItsBackupPassword() {
        val app=ui.activity.application as LedgerApp
        assertEquals("cn.shopledger.devicetest",app.packageName)
        runBlocking(Dispatchers.IO){app.backup.setPassword("123456");app.backup.setRecoveryAutoEmail(false)}
        ui.onNodeWithText("设置").press();ui.waitForIdle()
        ui.onNodeWithText("邮箱与备份").performScrollTo().press();ui.waitForIdle()
        ui.onNodeWithText("忘记密码 / 密保与找回").performScrollTo().press();ui.waitForIdle()
        ui.onNodeWithText("指纹 / 锁屏验证后重设").performScrollTo().press()
        File(app.filesDir,"manual-auth-stage.txt").writeText("waiting for owner system authentication")
        ui.waitUntil(120_000){ui.onAllNodesWithText("设置新的备份密码").fetchSemanticsNodes().isNotEmpty()}
        File(app.filesDir,"manual-auth-stage.txt").writeText("system authentication accepted")
        ui.onNodeWithText("新密码（至少6位）").performScrollTo().performTextInput("654321")
        ui.onNodeWithText("再输入一次新密码").performScrollTo().performTextInput("654321")
        ui.onNodeWithText("保存新密码").press()
        ui.waitUntil(20_000){runBlocking(Dispatchers.IO){app.ledger.dao.localState()?.backupSecret?.let{DeviceSecrets().decrypt(it)}=="654321"}}
        runBlocking(Dispatchers.IO){
            val file=File(app.cacheDir,"system-auth-result.ledgerbak")
            app.backup.export(android.net.Uri.fromFile(file))
            BackupFormat.decrypt(file.readBytes(),"654321".toCharArray())
        }
        File(app.filesDir,"manual-auth-stage.txt").writeText("passed")
    }
    private fun SemanticsNodeInteraction.press()=performSemanticsAction(SemanticsActions.OnClick){it()}
}
