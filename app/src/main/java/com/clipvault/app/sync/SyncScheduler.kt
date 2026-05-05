package com.clipvault.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager schedule helpers.
 *  - [scheduleDaily]: enqueues a periodic worker that runs once a day, ideally at night.
 *  - [scheduleNow]: enqueues a one-shot worker, debounced 10 seconds, REPLACE policy.
 */
object SyncScheduler {

    private const val PERIODIC_NAME = "clipvault.sync.periodic"
    private const val ONESHOT_NAME = "clipvault.sync.oneshot"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun scheduleDaily(context: Context) {
        val initialDelayMinutes = minutesUntilNightlyWindow()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleNow(context: Context, debounceSeconds: Long = 10) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(debounceSeconds, TimeUnit.SECONDS)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_NAME)
        wm.cancelUniqueWork(ONESHOT_NAME)
    }

    /** Minutes from now until the next 03:00 local time (the daily window). */
    private fun minutesUntilNightlyWindow(): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return TimeUnit.MILLISECONDS.toMinutes(target.timeInMillis - now.timeInMillis).coerceAtLeast(1)
    }
}
