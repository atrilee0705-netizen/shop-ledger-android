package cn.shopledger

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import cn.shopledger.backup.*
import cn.shopledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackupTest {
    private lateinit var db: LedgerDb
    private lateinit var ledger: Ledger
    private lateinit var engine: BackupEngine
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val password = "123456".toCharArray()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, LedgerDb::class.java).build(); ledger = Ledger(db); engine = BackupEngine(context,ledger)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().build())
    }
    @After fun close() { db.close(); WorkManagerTestInitHelper.closeWorkDatabase() }
    private suspend fun snapshot(): LedgerSnapshot {
        ledger.saveEmployee(null,"张三"); ledger.saveTask(null,"任务A",2021,6088)
        ledger.add(ledger.dao.employees().single().id,ledger.dao.tasks().single().id, LocalDate.of(2026,10,18),"测试中文备注",LedgerTest.at("2026-10-18T10:00:00"))
        ledger.settle(true,LedgerTest.at("2026-10-19T12:00:00"))
        ledger.updateSettings { it.copy(settlementDay = 31,primaryColor = 0xFF315EA8, backgroundOpacity = 24) }
        return engine.snapshot()
    }
    @Test fun encryptedRoundTripIncludesWholeLedgerAndIsRandomized() = runBlocking {
        val original = snapshot()
        val first = BackupFormat.encrypt(original,password)
        val second = BackupFormat.encrypt(original,password)
        assertFalse(first.contentEquals(second)); assertEquals(original,BackupFormat.decrypt(first,password))
        assertFalse(first.toString(Charsets.UTF_8).contains("测试中文备注"))
        assertFalse(first.toString(Charsets.UTF_8).contains("smtpSecret"))
    }
    @Test fun correctedSlipsAndLegacyBackupsBothRetainTheirEntryMembership() = runBlocking {
        val original = snapshot()
        val legacy = original.copy(version=1, entries=original.entries.map { it.copy(settlementId=null) })
        val converted = BackupFormat.normalize(BackupFormat.decrypt(BackupFormat.encrypt(legacy,password),password))
        assertEquals(original,converted)
        val employee = ledger.dao.employees().single().id
        val task = ledger.dao.tasks().single().id
        ledger.addMany(employee,task,LocalDate.of(2026,10,18),"漏记",2)
        val revised = engine.snapshot()
        val restored = BackupFormat.decrypt(BackupFormat.encrypt(revised,password),password)
        assertEquals(revised,restored)
        assertEquals(2,restored.settlements.single().revision)
        assertEquals(1,restored.adjustments.size)
        assertTrue(restored.entries.all { it.settlementId==restored.settlements.single().id })
    }
    @Test fun badPasswordTamperingAndVersionAreRejected() = runBlocking<Unit> {
        val encrypted = BackupFormat.encrypt(snapshot(),password)
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.decrypt(encrypted,"wrong-password".toCharArray()) }
        val altered = encrypted.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.decrypt(altered,password) }
        val version = encrypted.clone().apply { this[7] = 2 }
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.decrypt(version,password) }
    }
    @Test fun rejectsOrphansChangedSettlementTotalsAndBrokenIntervals() = runBlocking<Unit> {
        val s = snapshot()
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.validate(s.copy(employees=emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.validate(s.copy(items=s.items.map { it.copy(amountCents=1) })) }
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.validate(s.copy(settlements=s.settlements.map { it.copy(periodStart=0) })) }
    }
    @Test fun restoreReplacesAllTablesAndBadFileLeavesCurrentLedgerUntouched() = runBlocking {
        val original = snapshot()
        val file = File(context.cacheDir,"restore-test.ledgerbak").apply { writeBytes(BackupFormat.encrypt(original,password)) }
        ledger.saveEmployee(null,"恢复前临时员工")
        try { engine.restore(Uri.fromFile(file),"wrong-password"); fail("密码错误应拒绝") } catch (_: IllegalArgumentException) { }
        assertEquals(2,ledger.dao.employees().size)
        engine.restore(Uri.fromFile(file),String(password))
        val restored = engine.snapshot()
        assertEquals(original.copy(exportedAt=restored.exportedAt, settings=original.settings.copy(autoSettlement=false)),restored)
        assertNull(ledger.dao.localState()?.smtpSecret); assertFalse(ledger.dao.settings()!!.autoBackup)
    }
    @Test fun ledgerAndSettingsSurviveDatabaseCloseAndReopen() = runBlocking {
        val fileName = "persistence-test.db"
        context.deleteDatabase(fileName)
        var disk = Room.databaseBuilder(context,LedgerDb::class.java,fileName).build()
        val real = Ledger(disk)
        real.saveEmployee(null,"持久化员工"); real.saveTask(null,"持久化任务",1,3)
        real.add(real.dao.employees().single().id,real.dao.tasks().single().id,LocalDate.of(2026,10,18),"备注",LedgerTest.at("2026-10-18T12:00:00"))
        real.updateSettings { it.copy(settlementDay=31,backupHour=9) }
        disk.close(); disk=Room.databaseBuilder(context,LedgerDb::class.java,fileName).build()
        assertEquals("持久化员工",disk.dao().employees().single().name)
        assertEquals("备注",disk.dao().entries().single().note)
        assertEquals(31,disk.dao().settings()?.settlementDay); assertEquals(9,disk.dao().settings()?.backupHour)
        disk.close(); context.deleteDatabase(fileName); Unit
    }
    @Test fun backgroundIsCopiedIntoTheAppAndRoundTripsThroughEncryptedRestore() = runBlocking {
        val source = File(context.cacheDir,"selected-background.png")
        val bitmap = android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        engine.chooseBackground(Uri.fromFile(source)); source.delete()
        val original = engine.snapshot()
        val originalBytes = engine.background(original.settings.backgroundFile!!).readBytes()
        val file = File(context.cacheDir,"background-test.ledgerbak").apply { writeBytes(BackupFormat.encrypt(original,password)) }
        engine.clearBackground()
        engine.restore(Uri.fromFile(file),String(password))
        val restored = engine.snapshot()
        assertEquals(original.backgroundBase64,restored.backgroundBase64)
        assertArrayEquals(originalBytes,engine.background(restored.settings.backgroundFile!!).readBytes())
        assertFalse(source.exists())
    }
}
