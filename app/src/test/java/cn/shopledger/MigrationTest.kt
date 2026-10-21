package cn.shopledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.shopledger.backup.BackupEngine
import cn.shopledger.backup.BackupFormat
import cn.shopledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationTest {
    @Test fun versionOneDatabaseMigratesWithoutChangingOldMoneyAndCanBeRevised() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-real-v1.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/cn.shopledger.data.LedgerDb/1.json").readText()).getJSONObject("database")
        val old = context.openOrCreateDatabase(name,0,null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            fun sql(text: String) = text.replace("\${TABLE_NAME}",entity.getString("tableName"))
            old.execSQL(sql(entity.getString("createSql")))
            val indices = entity.optJSONArray("indices")
            if (indices != null) for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
        val date = LocalDate.of(2026,10,14)
        val at = Rules.start(date) + 1000
        val end = Rules.end(date)
        val settledAt = Rules.start(date.plusDays(1)) + 1000
        old.execSQL("INSERT INTO employees VALUES('e','员工',0,$at)")
        old.execSQL("INSERT INTO tasks VALUES('t','A',2000,6000,0)")
        old.execSQL("INSERT INTO entries VALUES('w','e','t','A',2000,6000,$at,'旧账',$at)")
        old.execSQL("INSERT INTO settlements VALUES('s',${Rules.beginning},$end,$settledAt,0)")
        old.execSQL("INSERT INTO settlement_items VALUES('s','e',2000)")
        old.execSQL("INSERT INTO settings VALUES(1,15,4280642656,NULL,12,'',465,'','',22,0,0)")
        old.version = 1; old.close()
        val db = Room.databaseBuilder(context,LedgerDb::class.java,name).addMigrations(LedgerDb.MIGRATION_1_2,LedgerDb.MIGRATION_2_3).build()
        try {
            val ledger = Ledger(db)
            assertEquals("s",ledger.dao.entries().single().settlementId)
            assertEquals(2000L,ledger.dao.settlementItems().single().amountCents)
            assertEquals(12,ledger.dao.settings()!!.settlementHour)
            assertFalse(ledger.dao.settings()!!.autoSettlement)
            assertEquals(0L,ledger.dao.delivery("s")!!.sentAt)
            ledger.addMany("e","t",date,"补记",2)
            assertEquals(6000L,ledger.dao.settlementItems().single().amountCents)
            assertEquals(2,ledger.dao.settlement("s")!!.revision)
            BackupFormat.validate(BackupEngine(context,ledger).snapshot())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
