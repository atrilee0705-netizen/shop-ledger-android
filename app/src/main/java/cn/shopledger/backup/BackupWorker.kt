package cn.shopledger.backup

import android.content.Context
import androidx.work.*
import cn.shopledger.LedgerApp
import cn.shopledger.Rules
import cn.shopledger.data.Settings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as LedgerApp
        val automatic = inputData.getBoolean("automatic", true)
        try {
            app.backup.sendBackup(automatic)
            if (automatic) BackupScheduler.continueDaily(applicationContext, app.ledger.dao.settings() ?: Settings())
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

object BackupScheduler {
    private const val DAILY = "ledger-daily"
    private const val MANUAL = "ledger-manual"
    private const val TAG = "ledger-backup"
    private fun request(automatic: Boolean, delay: Long = 0) = OneTimeWorkRequestBuilder<BackupWorker>()
        .setInputData(workDataOf("automatic" to automatic))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(TAG).build()
    fun ensure(context: Context, settings: Settings) {
        if (settings.autoBackup) WorkManager.getInstance(context).enqueueUniqueWork(DAILY, ExistingWorkPolicy.KEEP,
            request(true, Rules.nextBackup(settings.backupHour, settings.backupMinute) - Rules.now()))
    }
    fun reschedule(context: Context, settings: Settings) {
        val work = WorkManager.getInstance(context)
        if (!settings.autoBackup) work.cancelUniqueWork(DAILY)
        else work.enqueueUniqueWork(DAILY, ExistingWorkPolicy.REPLACE,
            request(true, Rules.nextBackup(settings.backupHour, settings.backupMinute) - Rules.now()))
    }
    fun continueDaily(context: Context, settings: Settings) {
        if (settings.autoBackup) WorkManager.getInstance(context).enqueueUniqueWork(DAILY, ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(true, Rules.nextBackup(settings.backupHour, settings.backupMinute) - Rules.now()))
    }
    fun sendNow(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, request(false))
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }
}
