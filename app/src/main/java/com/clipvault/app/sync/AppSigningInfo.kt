package com.clipvault.app.sync

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Returns the SHA-1 fingerprint (uppercase, colon-separated) of the certificate that
 * signed the currently-installed APK, or null if it can't be determined.
 *
 * Useful to display in the Settings screen so the user can verify the value matches
 * the SHA-1 they configured in their Google Cloud OAuth Android client.
 */
object AppSigningInfo {
    private const val TAG = "ClipVaultSigning"

    fun packageName(context: Context): String = context.packageName

    fun signingSha1(context: Context): String? {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val signatures: Array<Signature>? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                    val signingInfo = info.signingInfo
                    when {
                        signingInfo == null -> null
                        signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                        else -> signingInfo.signingCertificateHistory
                    }
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
                }
            val first = signatures?.firstOrNull() ?: return null
            val digest = MessageDigest.getInstance("SHA-1").digest(first.toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute signing SHA-1", e)
            null
        }
    }
}
