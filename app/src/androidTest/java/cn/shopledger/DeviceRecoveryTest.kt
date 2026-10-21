package cn.shopledger

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.shopledger.backup.*
import cn.shopledger.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

@RunWith(AndroidJUnit4::class)
class DeviceRecoveryTest {
    @get:Rule val timeout=org.junit.rules.Timeout.seconds(120)
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db:LedgerDb
    private lateinit var ledger:Ledger
    private lateinit var engine:BackupEngine
    private var activity:ActivityScenario<MainActivity>?=null
    private val ids=listOf("favorite_character","special_phrase","private_word")
    private val answers=listOf("A Character","只记住这句话","Private phrase")
    private fun stage(text:String){File(context.filesDir,"recovery-test-stage.txt").writeText(text)}
    @Before fun setup()=runBlocking<Unit>(Dispatchers.IO) {
        assertEquals("cn.shopledger.devicetest",context.packageName)
        activity=ActivityScenario.launch(MainActivity::class.java)
        stage("setup database")
        db=Room.inMemoryDatabaseBuilder(context,LedgerDb::class.java).build();ledger=Ledger(db);engine=BackupEngine(context,ledger)
        ledger.saveEmployee(null,"恢复测试员工");ledger.saveTask(null,"A",2000,6000)
        ledger.addMany(ledger.dao.employees().single().id,ledger.dao.tasks().single().id,Rules.today().minusDays(1),"恢复测试",2)
        ledger.settle(true)
        stage("setup password");engine.setPassword("123456")
        stage("setup auto false");engine.setRecoveryAutoEmail(false)
        stage("setup questions");engine.setQuestions(ids,answers);stage("setup finished")
    }
    @After fun close(){db.close();activity?.close()}
    private fun file(name:String)=File(context.cacheDir,name)
    @Test fun allRecoveryRoutesPreserveLedgerAndCredentialSurvivesPasswordChanges()=runBlocking<Unit>(Dispatchers.IO) {
        val backup=file("recovery-source.ledgerbak");val credential=file("recovery-source.ledgerkey")
        engine.export(Uri.fromFile(backup));engine.exportRecovery(Uri.fromFile(credential))
        val original=engine.snapshot()
        val profile=ledger.dao.recovery()!!
        assertFalse(profile.questionsJson!!.contains("Private phrase"))
        val keyFile=RecoveryFormat.decodeCredential(credential.readBytes())
        assertFalse(profile.keySecret.contains(keyFile.key))
        engine.resetWithQuestions(answers,"654321")
        val fresh=file("recovery-fresh.ledgerbak");engine.export(Uri.fromFile(fresh))
        assertEquals(original.entries,BackupFormat.decrypt(fresh.readBytes(),"654321".toCharArray()).entries)
        assertEquals(original.entries,RecoveryFormat.decryptCredential(backup.readBytes(),keyFile).snapshot.entries)
        assertEquals(original.entries,RecoveryFormat.decryptCredential(fresh.readBytes(),keyFile).snapshot.entries)
        engine.resetWithFile(Uri.fromFile(credential),"778899")
        engine.export(Uri.fromFile(fresh));assertEquals(original.entries,BackupFormat.decrypt(fresh.readBytes(),"778899".toCharArray()).entries)
        val otherDb=Room.inMemoryDatabaseBuilder(context,LedgerDb::class.java).build()
        try {
            val other=Ledger(otherDb);val otherEngine=BackupEngine(context,other)
            otherEngine.restoreWithFile(Uri.fromFile(backup),Uri.fromFile(credential),"abcdef")
            assertEquals(original.entries,other.dao.entries())
            assertEquals(original.items,other.dao.settlementItems())
            assertEquals(ids,RecoveryFormat.decodeQuestions(other.dao.recovery()!!.questionsJson!!).ids)
            assertNotNull(other.dao.localState()!!.backupSecret)
            assertNull(other.dao.localState()!!.smtpSecret)
            otherEngine.restoreWithQuestions(Uri.fromFile(backup),answers,"ghijkl")
            assertEquals(original.entries,other.dao.entries())
        } finally {otherDb.close()}
    }
    @Test fun wrongRecoveryAndFiveBadAnswersLeaveCurrentLedgerUntouched()=runBlocking<Unit>(Dispatchers.IO) {
        val backup=file("guard-source.ledgerbak");val wrong=file("wrong.ledgerkey")
        stage("guard export");engine.export(Uri.fromFile(backup));stage("guard exported");wrong.writeBytes(RecoveryFormat.encodeCredential(RecoveryFormat.newCredential()))
        val original=ledger.dao.entries()
        repeat(5){
            stage("guard wrong attempt $it")
            try{engine.resetWithQuestions(listOf("wrong","wrong","wrong"),"654321");fail("错误答案应被拒绝")}catch(_:IllegalArgumentException){}
        }
        assertTrue(ledger.dao.recoveryAttempts()!!.blockedUntil>Rules.now())
        stage("guard blocked")
        try{engine.resetWithQuestions(answers,"654321");fail("应暂时限制尝试")}catch(_:IllegalArgumentException){}
        try{engine.restoreWithFile(Uri.fromFile(backup),Uri.fromFile(wrong),"654321");fail("错误文件应被拒绝")}catch(_:IllegalArgumentException){}
        assertEquals(original,ledger.dao.entries())
        assertEquals(1,ledger.dao.settlements().size)
        ledger.dao.saveRecoveryAttempts(RecoveryAttempts(blockedUntil=Rules.now()-1))
        stage("guard correct reset")
        engine.resetWithQuestions(answers,"654321")
        stage("guard passed")
        assertEquals(0,ledger.dao.recoveryAttempts()!!.failures)
    }
    @Test fun recoveryAttachmentIsSentToOwnerRetriedAndDeduplicated()=runBlocking<Unit>(Dispatchers.IO) {
        val originalTls=SSLContext.getDefault();val tls=DeviceSmtpServer.tlsContext();SSLContext.setDefault(tls)
        try {
            val closed=ServerSocket(0).use{it.localPort}
            val settings=Settings(smtpHost="localhost",smtpPort=closed,sender="owner@example.com",recipient="bookkeeper@example.com",autoSettlement=false)
            engine.configure(settings,"test-code");engine.setRecoveryAutoEmail(true)
            val before=ledger.dao.recovery()!!
            try{engine.sendRecoveryEmail();fail("关闭端口应失败")}catch(_:jakarta.mail.MessagingException){}
            assertNotNull(ledger.dao.recovery()!!.lastError);assertNull(ledger.dao.recovery()!!.sentAt)
            DeviceSmtpServer(tls).use{server->
                engine.configure(settings.copy(smtpPort=server.port),"")
                engine.sendRecoveryEmail()
                val transcript=server.received.get(15,TimeUnit.SECONDS)
                val message=jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),transcript.byteInputStream(Charsets.US_ASCII))
                assertEquals("owner@example.com",(message.allRecipients.single() as jakarta.mail.internet.InternetAddress).address)
                val multipart=message.content as jakarta.mail.Multipart
                assertTrue(multipart.getBodyPart(1).fileName.endsWith(".ledgerkey"))
                val mailed=RecoveryFormat.decodeCredential(multipart.getBodyPart(1).inputStream.readBytes())
                assertEquals(before.keyId,mailed.keyId)
                val backup=file("mailed-root.ledgerbak");engine.export(Uri.fromFile(backup))
                assertEquals(ledger.dao.entries(),RecoveryFormat.decryptCredential(backup.readBytes(),mailed).snapshot.entries)
                val sent=ledger.dao.recovery()!!
                assertNotNull(sent.sentAt);assertNull(sent.lastError)
                engine.sendRecoveryEmail()
                assertEquals(sent.sentAt,ledger.dao.recovery()!!.sentAt)
            }
        }finally{SSLContext.setDefault(originalTls)}
    }
    @Test fun configurationAutomaticallyQueuesAndSendsTheRecoveryFile()=runBlocking<Unit>(Dispatchers.IO) {
        val app=context as LedgerApp
        val originalTls=SSLContext.getDefault();val tls=DeviceSmtpServer.tlsContext();SSLContext.setDefault(tls)
        try {
            app.ledger.dao.saveLocalState(LocalState())
            app.backup.setPassword("123456");app.backup.setRecoveryAutoEmail(true)
            DeviceSmtpServer(tls).use {server->
                app.backup.configure(Settings(smtpHost="localhost",smtpPort=server.port,sender="auto-owner@example.com",recipient="other@example.com",autoSettlement=false),"automatic-recovery-test-code")
                val transcript=server.received.get(30,TimeUnit.SECONDS)
                withTimeout(30_000){while(app.ledger.dao.recovery()?.sentAt==null)delay(100)}
                val message=jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),transcript.byteInputStream(Charsets.US_ASCII))
                assertEquals("auto-owner@example.com",(message.allRecipients.single() as jakarta.mail.internet.InternetAddress).address)
                val keyFile=RecoveryFormat.decodeCredential((message.content as jakarta.mail.Multipart).getBodyPart(1).inputStream.readBytes())
                assertEquals(app.ledger.dao.recovery()!!.keyId,keyFile.keyId)
                val sent=app.ledger.dao.recovery()!!.sentAt
                app.backup.sendRecoveryEmail()
                assertEquals(sent,app.ledger.dao.recovery()!!.sentAt)
            }
        }finally{
            app.backup.setRecoveryAutoEmail(false)
            app.ledger.dao.saveLocalState(LocalState())
            SSLContext.setDefault(originalTls)
        }
    }
}
