package com.clipvault.app

import android.app.Application
import com.clipvault.app.data.ClipDatabase
import com.clipvault.app.data.ClipRepository

class ClipVaultApp : Application() {

    val database: ClipDatabase by lazy { ClipDatabase.getInstance(this) }
    val repository: ClipRepository by lazy { ClipRepository(database.clipDao()) }
}
