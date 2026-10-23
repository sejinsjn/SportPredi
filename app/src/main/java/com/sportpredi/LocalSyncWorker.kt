package com.sportpredi

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class LocalSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val database = SportPrediDatabase.get(applicationContext)
            val report = LocalSyncRepository(applicationContext, database).synchronize()
            val supplementaryReport = SupplementaryDataSync(database).synchronize(
                ApiCredentialsStore(applicationContext).load(),
            )
            LocalPredictionEngine(database.sportPrediDao()).refreshUpcomingPredictions()
            if (report.errors.isEmpty() && supplementaryReport.errors.isEmpty()) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "Geplante lokale Synchronisierung fehlgeschlagen.", exception)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LocalSyncWorker"
        private const val UNIQUE_WORK_NAME = "sportpredi-periodic-sync"
        private const val SYNC_INTERVAL_HOURS = 12L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<LocalSyncWorker>(
                SYNC_INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setInitialDelay(SYNC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
