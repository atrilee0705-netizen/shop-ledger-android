package cn.shopledger

import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import cn.shopledger.backup.*
import cn.shopledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LedgerApp::class, sdk = [34])
class BackupWorkerTest {
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()
    @Before fun setup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().build())
        app.ledger.dao.saveSettings(Settings()); app.ledger.dao.saveLocalState(LocalState())
    }
    @After fun close() { WorkManagerTestInitHelper.closeWorkDatabase(); app.ledger.db.close() }
    @Test fun disabledAutomaticWorkSucceedsWithoutPretendingToHaveSent() = runBlocking {
        val worker = TestListenableWorkerBuilder<BackupWorker>(app).setInputData(workDataOf("automatic" to true)).build()
        assertEquals(ListenableWorker.Result.success(),worker.doWork())
        assertNull(app.ledger.dao.localState()?.lastSentAt)
        assertNull(app.ledger.dao.localState()?.lastBackupAt)
    }
    @Test fun missingConfigurationPersistsErrorAndRequestsRetry() = runBlocking {
        val worker = TestListenableWorkerBuilder<BackupWorker>(app).setInputData(workDataOf("automatic" to false)).build()
        assertEquals(ListenableWorker.Result.retry(),worker.doWork())
        val state = app.ledger.dao.localState()!!
        assertNotNull(state.lastError); assertNull(state.lastSentAt)
        assertNull(state.lastBackupAt); assertNull(state.pendingFile)
    }
    @Test fun dailyScheduleIsUniqueAndConfigCanCancelIt() {
        val config = Settings(autoBackup=true)
        BackupScheduler.ensure(app,config); BackupScheduler.ensure(app,config)
        val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork("ledger-daily").get()
        assertEquals(1,infos.count { !it.state.isFinished })
        BackupScheduler.reschedule(app,config.copy(autoBackup=false))
        val cancelled = WorkManager.getInstance(app).getWorkInfosForUniqueWork("ledger-daily").get()
        assertTrue(cancelled.all { it.state.isFinished })
    }
}
