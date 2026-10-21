package cn.shopledger.backup

import android.content.Context
import androidx.work.*
import cn.shopledger.LedgerApp
import cn.shopledger.Rules
import cn.shopledger.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SettlementWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as LedgerApp
        try {
            app.ledger.settleAutomatically()
            SettlementScheduler.sendReports(app)
            if (!inputData.getBoolean("checkOnly", false)) SettlementScheduler.continueDaily(app, app.ledger.dao.settings() ?: Settings())
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

class SettlementReportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            (applicationContext as LedgerApp).backup.sendSettlementReports()
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

object SettlementScheduler {
    private const val DAILY = "ledger-settlement-daily"
    private const val CHECK = "ledger-settlement-check"
    private const val REPORT = "ledger-settlement-reports"
    private fun request(settings: Settings) = OneTimeWorkRequestBuilder<SettlementWorker>()
        .setInitialDelay((Rules.nextBackup(settings.settlementHour, settings.settlementMinute) - Rules.now()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    fun ensure(context: Context, settings: Settings) {
        if (settings.autoSettlement) {
            WorkManager.getInstance(context).enqueueUniqueWork(DAILY, ExistingWorkPolicy.KEEP, request(settings))
            WorkManager.getInstance(context).enqueueUniqueWork(CHECK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SettlementWorker>().setInputData(workDataOf("checkOnly" to true)).build())
        }
        sendReports(context)
    }
    fun reschedule(context: Context, settings: Settings) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(CHECK)
        if (settings.autoSettlement) wm.enqueueUniqueWork(DAILY, ExistingWorkPolicy.REPLACE, request(settings))
        else wm.cancelUniqueWork(DAILY)
        sendReports(context)
    }
    fun continueDaily(context: Context, settings: Settings) {
        if (settings.autoSettlement) WorkManager.getInstance(context).enqueueUniqueWork(DAILY, ExistingWorkPolicy.APPEND_OR_REPLACE, request(settings))
    }
    fun sendReports(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(REPORT, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SettlementReportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
