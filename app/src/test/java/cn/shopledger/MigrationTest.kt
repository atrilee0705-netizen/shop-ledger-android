package cn.shopledger

import android.app.Application
import android.database.Cursor
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
        val db = Room.databaseBuilder(context,LedgerDb::class.java,name).addMigrations(LedgerDb.MIGRATION_1_2,LedgerDb.MIGRATION_2_3, LedgerDb.MIGRATION_3_4).build()
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
    @Test fun versionThreeKeepsExistingSlipsSettingsAndRecoveryCredentials()=runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val name="migration-real-v3.db"
        context.deleteDatabase(name)
        val schema=JSONObject(File("schemas/cn.shopledger.data.LedgerDb/3.json").readText()).getJSONObject("database")
        val old=context.openOrCreateDatabase(name,0,null)
        val entities=schema.getJSONArray("entities")
        for(i in 0 until entities.length()) {
            val entity=entities.getJSONObject(i)
            fun sql(text:String)=text.replace("\${TABLE_NAME}",entity.getString("tableName"))
            old.execSQL(sql(entity.getString("createSql")))
            val indexes=entity.optJSONArray("indices")
            if(indexes!=null) for(j in 0 until indexes.length()) old.execSQL(sql(indexes.getJSONObject(j).getString("createSql")))
        }
        val setup=schema.getJSONArray("setupQueries")
        for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
        val day=LocalDate.of(2026,10,21);val at=Rules.start(day);val settled=Rules.start(day.plusDays(1))
        old.execSQL("INSERT INTO employees VALUES('e','旧员工',0,$at)")
        old.execSQL("INSERT INTO employees VALUES('archived','已停用员工',1,${at-1000})")
        old.execSQL("INSERT INTO tasks VALUES('t','旧任务',3800,6000,0)")
        old.execSQL("INSERT INTO tasks VALUES('archived','已停用任务',1250,2450,1)")
        old.execSQL("INSERT INTO entries VALUES('w','e','t','旧任务',3800,6000,$at,'旧账',$at,'s')")
        old.execSQL("INSERT INTO entries VALUES('today','e','t','更改价格前的任务',2500,5000,$settled,'未结账',${settled+100},NULL)")
        old.execSQL("INSERT INTO entries VALUES('future','archived','archived','历史任务名',1250,2450,${settled+86400000},'未来预记账',${settled+200},NULL)")
        old.execSQL("INSERT INTO settlements VALUES('s',${Rules.beginning},${Rules.end(day)},$settled,1,1)")
        old.execSQL("INSERT INTO settlement_items VALUES('s','e',3800)")
        old.execSQL("INSERT INTO settlement_adjustments VALUES('a','s',1900,3800,'以前补记修正',${settled+100})")
        old.execSQL("INSERT INTO settlement_delivery VALUES('s',$settled,NULL)")
        old.execSQL("INSERT INTO settings VALUES(1,15,4280642656,'background-test.jpg',32,'smtp.example.com',465,'owner@example.com','reports@example.com',22,30,1,1,12,15,1)")
        old.execSQL("INSERT INTO local_state VALUES(1,'test-encrypted-smtp','test-encrypted-backup',$at,$settled,'历史错误','pending-test.ledgerbak','pending-id','2026-10-22','2026-10-21')")
        old.execSQL("INSERT INTO recovery_profile VALUES(1,'old-recovery-id','old-encrypted-secret',$at,'test-encrypted-questions',1,$settled,'owner@example.com',NULL)")
        old.execSQL("INSERT INTO recovery_attempts VALUES(1,2,${settled+1000})")
        // Compare every old column of every business/device table, including nulls.
        // All credentials and file names here are synthetic test fixtures.
        val columns = (0 until entities.length()).associate { i ->
            val table = entities.getJSONObject(i).getString("tableName")
            table to old.rawQuery("SELECT * FROM `$table` LIMIT 0", null).use { it.columnNames.toList() }
        }
        fun rows(cursor: Cursor): List<List<String?>> = cursor.use {
            buildList { while (it.moveToNext()) add((0 until it.columnCount).map { column -> if (it.isNull(column)) null else it.getString(column) }) }
        }
        fun select(table: String, names: List<String>) = "SELECT ${names.joinToString { "`$it`" }} FROM `$table` ORDER BY ${names.indices.joinToString { (it + 1).toString() }}"
        val before = columns.mapValues { (table, names) -> rows(old.rawQuery(select(table, names), null)) }
        old.version=3;old.close()
        val db=Room.databaseBuilder(context,LedgerDb::class.java,name).addMigrations(LedgerDb.MIGRATION_3_4).build()
        try {
            val ledger=Ledger(db)
            val migrated = db.openHelper.writableDatabase
            for ((table, names) in columns) {
                assertEquals("All original $table columns must survive the update", before.getValue(table), rows(migrated.query(select(table, names))))
            }
            assertTrue(ledger.dao.entries().all { it.quantityTenths == 10 })
            assertEquals(3800L,ledger.dao.entry("w")!!.wageAmountCents)
            assertEquals(3800L,ledger.dao.settlementItems().single().amountCents)
            assertEquals(1250L,ledger.dao.entry("future")!!.wageAmountCents)
            assertEquals("background-test.jpg",ledger.dao.settings()!!.backgroundFile)
            assertEquals("test-encrypted-backup",ledger.dao.localState()!!.backupSecret)
            assertEquals("old-encrypted-secret",ledger.dao.recovery()!!.keySecret)
            assertEquals(settled,ledger.dao.recovery()!!.sentAt)
            ledger.addQuantity("e","t",day,"补半次",5,settled+1000)
            assertEquals(5700L,ledger.dao.settlementItems().single().amountCents)
            assertEquals(2,ledger.dao.settlements().single().revision)
            assertEquals(15,ledger.dao.settledEntries("s").sumOf{it.quantityTenths})
            assertEquals(35,ledger.dao.entries().sumOf{it.quantityTenths})
        } finally {db.close();context.deleteDatabase(name)}
    }

}
