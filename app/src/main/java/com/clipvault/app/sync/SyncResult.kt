package com.clipvault.app.sync

sealed class SyncResult {
    data class Success(
        val pulledFromCloud: Int,
        val pushedToCloud: Int,
        val removed: Int,
        val noChanges: Boolean
    ) : SyncResult()

    /** No Google account selected yet. User must sign in via Settings. */
    object NotSignedIn : SyncResult()

    /** Account selected but Drive scope hasn't been authorized yet. UI must prompt for consent. */
    object NeedsConsent : SyncResult()

    data class NetworkError(val message: String) : SyncResult()

    data class AuthError(val message: String) : SyncResult()

    data class UnexpectedError(val throwable: Throwable) : SyncResult()
}
