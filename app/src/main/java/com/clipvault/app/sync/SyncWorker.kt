package com.clipvault.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clipvault.app.ClipVaultApp

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ClipVaultApp
        val manager = app.syncManager
        return when (manager.sync()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.NotSignedIn,
            is SyncResult.NeedsConsent -> Result.success() // wait for user; nothing to retry automatically
            is SyncResult.AuthError -> Result.success()
            is SyncResult.NetworkError -> Result.retry()
            is SyncResult.UnexpectedError -> Result.retry()
        }
    }
}
