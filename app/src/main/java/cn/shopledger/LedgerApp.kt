package cn.shopledger

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import cn.shopledger.backup.BackupEngine
import cn.shopledger.backup.BackupScheduler
import cn.shopledger.backup.SettlementScheduler
import cn.shopledger.backup.RecoveryScheduler
import cn.shopledger.data.*
import kotlinx.coroutines.*

class LedgerApp : Application() {
    val wheelPreferences by lazy { WheelPreferences(this) }
    val ledger: Ledger by lazy { Ledger(Room.databaseBuilder(this, LedgerDb::class.java, "shop-ledger.db").addMigrations(LedgerDb.MIGRATION_1_2, LedgerDb.MIGRATION_2_3, LedgerDb.MIGRATION_3_4).build()) }
    val backup: BackupEngine by lazy { BackupEngine(this, ledger) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            val settings = ledger.dao.settings() ?: Settings()
            BackupScheduler.ensure(this@LedgerApp, settings)
            SettlementScheduler.ensure(this@LedgerApp, settings)
            try {
                backup.ensureRecovery()
                RecoveryScheduler.enqueue(this@LedgerApp)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ledger.db.withTransaction { ledger.dao.saveLocalState((ledger.dao.localState() ?: LocalState()).copy(lastError=BackupEngine.safeError(e))) }
            }
        }
    }
}
