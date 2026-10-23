package cn.shopledger.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Employee::class, TaskType::class, WorkEntry::class, Settlement::class,
    SettlementItem::class, SettlementAdjustment::class, SettlementDelivery::class, Settings::class, LocalState::class,
    RecoveryProfile::class, RecoveryAttempts::class], version = 4, exportSchema = true)
abstract class LedgerDb : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN quantityTenths INTEGER NOT NULL DEFAULT 10")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE recovery_profile (id INTEGER NOT NULL PRIMARY KEY, keyId TEXT NOT NULL, keySecret TEXT NOT NULL, createdAt INTEGER NOT NULL, questionsJson TEXT, autoEmail INTEGER NOT NULL, sentAt INTEGER, sentTo TEXT, lastError TEXT)")
                db.execSQL("CREATE TABLE recovery_attempts (id INTEGER NOT NULL PRIMARY KEY, failures INTEGER NOT NULL, blockedUntil INTEGER NOT NULL)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN settlementId TEXT")
                db.execSQL("ALTER TABLE settlements ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX index_entries_settlementId ON entries(settlementId)")
                db.execSQL("UPDATE entries SET settlementId=(SELECT id FROM settlements WHERE entries.completedAt>periodStart AND entries.completedAt<=periodEnd LIMIT 1)")
                db.execSQL("ALTER TABLE settings ADD COLUMN autoSettlement INTEGER NOT NULL DEFAULT 1")
                // Existing owners used manual closing. Let them review the new schedule
                // before enabling it, rather than unexpectedly closing old unpaid days.
                db.execSQL("UPDATE settings SET autoSettlement=0")
                db.execSQL("ALTER TABLE settings ADD COLUMN settlementHour INTEGER NOT NULL DEFAULT 12")
                db.execSQL("ALTER TABLE settings ADD COLUMN settlementMinute INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN sendSettlementReport INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE TABLE settlement_delivery (settlementId TEXT NOT NULL PRIMARY KEY, sentAt INTEGER, error TEXT, FOREIGN KEY(settlementId) REFERENCES settlements(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO settlement_delivery(settlementId,sentAt) SELECT id,0 FROM settlements")
                db.execSQL("CREATE TABLE settlement_adjustments (id TEXT NOT NULL PRIMARY KEY, settlementId TEXT NOT NULL, previousCents INTEGER NOT NULL, updatedCents INTEGER NOT NULL, reason TEXT NOT NULL, correctedAt INTEGER NOT NULL, FOREIGN KEY(settlementId) REFERENCES settlements(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_settlement_adjustments_settlementId ON settlement_adjustments(settlementId)")
            }
        }
    }
}
