package com.clipvault.app.sync

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps Google's Authorization API (the modern replacement for GoogleSignIn) to obtain
 * a short-lived OAuth 2.0 access token for the Drive API.
 *
 * Setup required (one-time, by the user, outside this code):
 *  1. Create a Google Cloud project.
 *  2. Enable the Drive API.
 *  3. Create an OAuth 2.0 Android client ID with this app's package name + signing-cert SHA-1.
 *  4. Configure OAuth consent screen in "Testing" mode and add your account as a test user.
 *
 * No client ID needs to be embedded in the app: Google identifies the Android app by its
 * package + signing certificate fingerprint at runtime.
 */
class GoogleAuth(context: Context) {

    private val authClient = Identity.getAuthorizationClient(context.applicationContext)

    /** The Drive API scope: per-file access to files this app creates or opens. */
    private val driveScope = Scope("https://www.googleapis.com/auth/drive.file")

    /**
     * Try to obtain an access token without showing UI. Returns null if user consent is required.
     */
    suspend fun authorizeSilent(): AuthorizationResult? {
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .build()

        return suspendCancellableCoroutine { cont ->
            authClient.authorize(req)
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        // User must grant consent — caller needs an Activity for that.
                        cont.resume(null)
                    } else {
                        cont.resume(result)
                    }
                }
                .addOnFailureListener {
                    Log.w(TAG, "authorizeSilent failed: ${describe(it)}", it)
                    cont.resumeWithException(it)
                }
        }
    }

    /**
     * Obtain an access token, prompting the user for consent if needed.
     * If consent is required, returns the [PendingIntent] sender for the caller to launch.
     */
    suspend fun authorizeWithUi(): AuthorizationOutcome {
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .build()

        return suspendCancellableCoroutine { cont ->
            authClient.authorize(req)
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        cont.resume(AuthorizationOutcome.NeedsConsent(result.pendingIntent!!.intentSender))
                    } else {
                        cont.resume(AuthorizationOutcome.Granted(result))
                    }
                }
                .addOnFailureListener {
                    Log.w(TAG, "authorizeWithUi failed: ${describe(it)}", it)
                    cont.resumeWithException(it)
                }
        }
    }

    /**
     * Parse the result returned by the consent activity. Returns:
     *  - [ConsentOutcome.Granted] on success.
     *  - [ConsentOutcome.Failed] if Google returned an error intent (e.g. DEVELOPER_ERROR
     *    when the package + SHA-1 don't match the Cloud Console OAuth client).
     *  - [ConsentOutcome.UserCancelled] if the user dismissed the consent screen.
     */
    fun parseConsentOutcome(data: Intent?): ConsentOutcome {
        if (data == null) return ConsentOutcome.UserCancelled
        return try {
            val r = authClient.getAuthorizationResultFromIntent(data)
            ConsentOutcome.Granted(r)
        } catch (e: ApiException) {
            val name = CommonStatusCodes.getStatusCodeString(e.statusCode)
            Log.w(TAG, "parseConsentOutcome ApiException: $name (${e.statusCode}) ${e.message}", e)
            ConsentOutcome.Failed(e.statusCode, name, e.message)
        } catch (e: Exception) {
            Log.w(TAG, "parseConsentOutcome unexpected exception", e)
            ConsentOutcome.Failed(-1, "Unknown", e.message)
        }
    }

    /** Legacy helper kept for tests / future use; prefer [parseConsentOutcome]. */
    fun parseConsentResult(data: Intent?): AuthorizationResult? {
        return data?.let {
            runCatching { authClient.getAuthorizationResultFromIntent(it) }.getOrNull()
        }
    }

    sealed class AuthorizationOutcome {
        data class Granted(val result: AuthorizationResult) : AuthorizationOutcome()
        data class NeedsConsent(val intentSender: IntentSender) : AuthorizationOutcome()
    }

    sealed class ConsentOutcome {
        data class Granted(val result: AuthorizationResult) : ConsentOutcome()
        data object UserCancelled : ConsentOutcome()
        data class Failed(val statusCode: Int, val statusName: String, val message: String?) : ConsentOutcome()
    }

    private fun describe(t: Throwable): String = when (t) {
        is ApiException -> "ApiException ${CommonStatusCodes.getStatusCodeString(t.statusCode)} (${t.statusCode}): ${t.message}"
        else -> "${t::class.java.simpleName}: ${t.message}"
    }

    companion object {
        private const val TAG = "ClipVaultAuth"
    }
}

