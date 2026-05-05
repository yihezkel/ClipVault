package com.clipvault.app.sync

import android.content.Context
import androidx.core.content.edit

/**
 * SharedPreferences-backed storage for sync state.
 */
class SyncPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var cloudFileId: String?
        get() = prefs.getString(KEY_FILE_ID, null)
        set(value) = prefs.edit { putString(KEY_FILE_ID, value) }

    /** Drive's `headRevisionId` from the last successful sync, used to detect cloud-side changes. */
    var lastSyncedRevisionId: String?
        get() = prefs.getString(KEY_REVISION_ID, null)
        set(value) = prefs.edit { putString(KEY_REVISION_ID, value) }

    var lastSyncedAt: Long
        get() = prefs.getLong(KEY_LAST_SYNCED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNCED_AT, value) }

    var lastSyncStatus: String?
        get() = prefs.getString(KEY_LAST_STATUS, null)
        set(value) = prefs.edit { putString(KEY_LAST_STATUS, value) }

    var signedInEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit { putString(KEY_EMAIL, value) }

    /**
     * True once the user has completed the Drive consent flow. The new Google Identity
     * Authorization API does not always include account info in the result, so we track
     * "signed in" with this boolean rather than relying on knowing the email.
     */
    var hasConsent: Boolean
        get() = prefs.getBoolean(KEY_HAS_CONSENT, false)
        set(value) = prefs.edit { putBoolean(KEY_HAS_CONSENT, value) }

    /** Cached OAuth access token. Refreshed on demand if expired. */
    var cachedAccessToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_TOKEN, value) }

    var cachedAccessTokenExpiry: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit { putLong(KEY_TOKEN_EXPIRY, value) }

    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SYNC, value) }

    fun clearAuth() {
        prefs.edit {
            remove(KEY_EMAIL)
            remove(KEY_TOKEN)
            remove(KEY_TOKEN_EXPIRY)
            remove(KEY_HAS_CONSENT)
        }
    }

    companion object {
        private const val NAME = "clipvault_sync"
        private const val KEY_FILE_ID = "cloud_file_id"
        private const val KEY_REVISION_ID = "last_synced_revision_id"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
        private const val KEY_LAST_STATUS = "last_sync_status"
        private const val KEY_EMAIL = "signed_in_email"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_TOKEN_EXPIRY = "access_token_expiry"
        private const val KEY_AUTO_SYNC = "auto_sync_enabled"
        private const val KEY_HAS_CONSENT = "has_consent"
    }
}
