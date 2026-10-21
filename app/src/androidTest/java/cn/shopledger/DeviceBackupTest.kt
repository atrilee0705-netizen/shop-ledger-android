package cn.shopledger

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.shopledger.backup.BackupEngine
import cn.shopledger.backup.DeviceSecrets
import cn.shopledger.backup.BackupFormat
import cn.shopledger.backup.BackupWorker
import cn.shopledger.data.Ledger
import cn.shopledger.data.LedgerDb
import cn.shopledger.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import androidx.work.*
import kotlinx.coroutines.delay
import androidx.test.platform.app.InstrumentationRegistry
import javax.net.ssl.SSLContext
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeviceBackupTest {
    @Before fun requireIsolatedTestPackage() {
        assertEquals("请使用 -PdeviceCheck=true", "cn.shopledger.devicetest", ApplicationProvider.getApplicationContext<Context>().packageName)
    }
    private fun checkpoint(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir,"device-test-stage.txt").writeText(name)
    }
    companion object {
        private lateinit var originalTls: SSLContext
        private lateinit var testTls: SSLContext
        @BeforeClass @JvmStatic fun enableTestCertificateInThisTestProcessOnly() {
            originalTls = SSLContext.getDefault()
            testTls = DeviceSmtpServer.tlsContext()
            SSLContext.setDefault(testTls)
        }
        @AfterClass @JvmStatic fun restoreTls() { SSLContext.setDefault(originalTls) }
    }
    @Test fun authorizationCodesAreEncryptedByRealAndroidKeystore() {
        val secrets = DeviceSecrets()
        val encrypted = secrets.encrypt("test-authorization-code")
        assertFalse(encrypted.contains("test-authorization-code"))
        assertEquals("test-authorization-code",secrets.decrypt(encrypted))
        assertNotEquals(encrypted,secrets.encrypt("test-authorization-code"))
    }
    @Test fun wageReportAndItsBackfillRevisionAreReallySentWithoutDoubleCounting() = runBlocking<Unit>(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,LedgerDb::class.java).build()
        val ledger = Ledger(db)
        val engine = BackupEngine(context,ledger)
        try {
            ledger.saveEmployee(null,"修订测试员工"); ledger.saveTask(null,"A",2000,6000)
            val employee = ledger.dao.employees().single().id
            val task = ledger.dao.tasks().single().id
            val yesterday = Rules.today().minusDays(1)
            ledger.add(employee,task,yesterday,"")
            val slip = ledger.settle(true)
            suspend fun sendAndReceive(): String = DeviceSmtpServer(testTls).use { server ->
                engine.configure(Settings(smtpHost="localhost",smtpPort=server.port,sender="test@example.com",recipient="report@example.com",autoSettlement=false),"report-test-code")
                engine.sendSettlementReports()
                val received = server.received.get(10,TimeUnit.SECONDS)
                val message = jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),received.byteInputStream(Charsets.US_ASCII))
                val body = (message.content as jakarta.mail.Multipart).getBodyPart(0).content.toString()
                assertTrue(received.contains("${slip.id}-r${ledger.dao.settlement(slip.id)!!.revision}@shopledger.local"))
                assertNotNull(ledger.dao.delivery(slip.id)!!.sentAt)
                assertTrue(ledger.dao.unsentSettlements().isEmpty())
                body
            }
            val original = sendAndReceive()
            assertTrue(original.contains("全员工资：¥20.00"))
            ledger.addMany(employee,task,yesterday,"漏记",2)
            assertNull(ledger.dao.delivery(slip.id)!!.sentAt)
            val revision = sendAndReceive()
            assertTrue(revision.contains("第2版")); assertTrue(revision.contains("修正版"))
            assertTrue(revision.contains("全员工资：¥60.00"))
            assertTrue(revision.contains("由¥20.00改为¥60.00"))
            assertTrue(ledger.dao.payable(Rules.end(Rules.today())).isEmpty())
        } finally { db.close() }
    }
    @Test fun realKeystoreExportAndFailedSmtpRetainExactlyOneAttachmentForRetry() = runBlocking<Unit>(Dispatchers.IO) {
        checkpoint("restore: start")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,LedgerDb::class.java).build()
        val ledger = Ledger(db)
        val engine = BackupEngine(context,ledger)
        // Reserve then close a loopback port, so no real mailbox is contacted.
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            ledger.saveEmployee(null,"真机备份测试")
            ledger.saveTask(null,"服务",2021,6023)
            ledger.add(ledger.dao.employees().single().id,ledger.dao.tasks().single().id,Rules.today(),"测试备注")
            checkpoint("restore: set password")
            engine.setPassword("123456")
            checkpoint("restore: configure")
            engine.configure(Settings(smtpHost="localhost",smtpPort=closedPort,sender="test@example.com",recipient="backup@example.com"),"test-authorization-code")
            val output = File(context.cacheDir,"device-export-test.ledgerbak")
            checkpoint("restore: export")
            engine.export(Uri.fromFile(output))
            assertTrue(output.length()>56)
            assertFalse(output.readBytes().toString(Charsets.UTF_8).contains("test-authorization-code"))
            val original = engine.snapshot()
            ledger.saveEmployee(null,"恢复前临时员工")
            checkpoint("restore: restore file")
            engine.restore(Uri.fromFile(output),"123456")
            val restored = engine.snapshot()
            assertEquals(original.employees,restored.employees)
            assertEquals(original.tasks,restored.tasks)
            assertEquals(original.entries,restored.entries)
            assertNull(ledger.dao.localState()!!.smtpSecret)
            engine.setPassword("123456")
            engine.configure(Settings(smtpHost="localhost",smtpPort=closedPort,sender="test@example.com",recipient="backup@example.com"),"test-authorization-code")
            repeat(2) {
                checkpoint("restore: failed send $it")
                try { engine.sendBackup(false); fail("未监听的SMTP端口应发送失败") } catch (_: jakarta.mail.MessagingException) { }
                val status = ledger.dao.localState()!!
                assertNotNull(status.pendingFile); assertNotNull(status.pendingId)
                assertNotNull(status.lastBackupAt); assertNotNull(status.lastError); assertNull(status.lastSentAt)
            }
            val pending = ledger.dao.localState()!!
            val beforeRetry = File(context.filesDir,"backups/${pending.pendingFile}").readBytes()
            checkpoint("restore: final failed send")
            try { engine.sendBackup(false) } catch (_: jakarta.mail.MessagingException) { }
            assertEquals(pending.pendingId,ledger.dao.localState()!!.pendingId)
            assertArrayEquals(beforeRetry,File(context.filesDir,"backups/${pending.pendingFile}").readBytes())
            output.delete()
            checkpoint("restore: passed")
        } finally { db.close() }
    }
    @Test fun automaticWorkerReallySendsInBackgroundAndDeduplicatesTheBeijingDay() = runBlocking<Unit>(Dispatchers.IO) {
        checkpoint("worker: start")
        val app = ApplicationProvider.getApplicationContext<LedgerApp>()
        assertEquals("请使用 -PdeviceCheck=true", "cn.shopledger.devicetest", app.packageName)
        val server = DeviceSmtpServer(testTls)
        val settings = Settings(smtpHost="localhost",smtpPort=server.port,sender="test@example.com",recipient="backup@example.com",autoBackup=true)
        try {
            checkpoint("worker: reset state")
            app.ledger.dao.saveLocalState(cn.shopledger.data.LocalState())
            checkpoint("worker: set password")
            app.backup.setPassword("worker-test-backup-password")
            app.backup.setRecoveryAutoEmail(false)
            checkpoint("worker: configure")
            app.backup.configure(settings,"device-test-authorization-code")
            checkpoint("worker: send home")
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            val manager = WorkManager.getInstance(app)
            suspend fun runAutomaticWorker(): WorkInfo {
                checkpoint("worker: enqueue")
                val request = OneTimeWorkRequestBuilder<BackupWorker>()
                    .setInputData(workDataOf("automatic" to true))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
                manager.enqueue(request).result.get(10,TimeUnit.SECONDS)
                repeat(180) {
                    val status = manager.getWorkInfoById(request.id).get(10,TimeUnit.SECONDS)
                    if (status != null && status.state.isFinished) return status
                    if (it % 10 == 0) checkpoint("worker: wait ${status?.state}, attempt ${status?.runAttemptCount}")
                    delay(500)
                }
                throw AssertionError("真实 WorkManager 在90秒内未完成")
            }
            assertEquals(WorkInfo.State.SUCCEEDED,runAutomaticWorker().state)
            checkpoint("worker: sent")
            val state = app.ledger.dao.localState()!!
            assertNotNull(state.lastBackupAt); assertNotNull(state.lastSentAt)
            assertNull(state.lastError); assertNull(state.pendingFile)
            assertEquals(Rules.today().toString(),state.lastAutoDay)
            val received = server.received.get(10,TimeUnit.SECONDS)
            val mime = jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()), received.byteInputStream(Charsets.US_ASCII))
            val multipart = mime.content as jakarta.mail.Multipart
            val bytes = multipart.getBodyPart(1).inputStream.use { BackupFormat.readBounded(it) }
            val restored = BackupFormat.decrypt(bytes,"worker-test-backup-password".toCharArray())
            assertEquals(app.ledger.dao.entries().size,restored.entries.size)
            assertFalse(bytes.toString(Charsets.UTF_8).contains("device-test-authorization-code"))
            assertEquals(WorkInfo.State.SUCCEEDED,runAutomaticWorker().state)
            assertEquals(state.lastSentAt,app.ledger.dao.localState()!!.lastSentAt)
            checkpoint("worker: passed")
        } finally {
            app.backup.configure(settings.copy(autoBackup=false),"")
            server.close()
        }
    }
}
