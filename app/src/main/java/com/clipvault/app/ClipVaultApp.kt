package com.clipvault.app

import android.app.Application
import com.clipvault.app.data.ClipDatabase
import com.clipvault.app.data.ClipRepository
import com.clipvault.app.sync.SyncManager
import com.clipvault.app.sync.SyncPrefs
import com.clipvault.app.sync.SyncScheduler

class ClipVaultApp : Application() {

    val database: ClipDatabase by lazy { ClipDatabase.getInstance(this) }
    val repository: ClipRepository by lazy { ClipRepository(database.clipDao()) }
    val syncPrefs: SyncPrefs by lazy { SyncPrefs(this) }
    val syncManager: SyncManager by lazy { SyncManager(this, repository) }

    override fun onCreate() {
        super.onCreate()
        // Trigger an async sync whenever the user changes the pinned set.
        repository.onPinsChanged = { SyncScheduler.scheduleNow(this) }
        // Schedule the daily nightly sync (idempotent: WorkManager dedupes by unique name).
        SyncScheduler.scheduleDaily(this)
    }
}

