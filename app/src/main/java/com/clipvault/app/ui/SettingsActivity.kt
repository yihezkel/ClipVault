package com.clipvault.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.clipvault.app.ClipVaultApp
import com.clipvault.app.sync.AppSigningInfo
import com.clipvault.app.sync.GoogleAuth
import com.clipvault.app.sync.SyncManager
import com.clipvault.app.sync.SyncResult
import com.clipvault.app.service.ClipboardAccessibilityService
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ClipVaultApp
        // Make sure the global accessibility overlay isn't covering this screen
        // (or any system dialogs we open from it, e.g. the Google sign-in consent).
        ClipboardAccessibilityService.instance?.dismissOverlayIfShowing()
        setContent {
            ClipVaultTheme {
                SettingsScreen(
                    syncManager = app.syncManager,
                    syncPrefs = app.syncPrefs,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ClipboardAccessibilityService.instance?.dismissOverlayIfShowing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    syncManager: SyncManager,
    syncPrefs: com.clipvault.app.sync.SyncPrefs,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { GoogleAuth(context) }

    var signedInEmail by remember { mutableStateOf(syncPrefs.signedInEmail) }
    var hasConsent by remember { mutableStateOf(syncPrefs.hasConsent) }
    var lastSyncedAt by remember { mutableStateOf(syncPrefs.lastSyncedAt) }
    var lastStatus by remember { mutableStateOf(syncPrefs.lastSyncStatus) }
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun handleGranted(result: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        val email = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()
        syncPrefs.hasConsent = true
        syncPrefs.signedInEmail = email
        signedInEmail = email
        hasConsent = true
        statusMessage = "Signed in. Running first sync…"
        scope.launch {
            statusMessage = runSync(syncManager) { isWorking = it }
            lastSyncedAt = syncPrefs.lastSyncedAt
            lastStatus = syncPrefs.lastSyncStatus
        }
    }

    val consentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        android.util.Log.i(
            "ClipVaultAuth",
            "consent result resultCode=${result.resultCode} data=${result.data} extras=${result.data?.extras?.keySet()}"
        )
        when (val outcome = auth.parseConsentOutcome(result.data)) {
            is GoogleAuth.ConsentOutcome.Granted -> handleGranted(outcome.result)
            is GoogleAuth.ConsentOutcome.Failed -> {
                statusMessage = buildConsentFailureMessage(context, outcome, signedInEmail)
            }
            is GoogleAuth.ConsentOutcome.UserCancelled -> {
                // Two cases produce RESULT_CANCELED + null data:
                //  (a) user actually dismissed a consent screen
                //  (b) consent was already implicitly granted, and the helper activity
                //      auto-finished without putting a result Intent in the response.
                // We can't tell them apart here — but a fresh silent authorize() will
                // succeed in case (b) and still fail (returning null) in case (a).
                scope.launch {
                    val retry = runCatching { auth.authorizeSilent() }.getOrNull()
                    if (retry != null) {
                        handleGranted(retry)
                    } else {
                        statusMessage = buildCancelMessage(context, result.resultCode, signedInEmail)
                    }
                }
            }
        }
    }

    fun signIn() {
        scope.launch {
            try {
                isWorking = true
                statusMessage = "Connecting to Google…"
                // Belt-and-suspenders: drop the accessibility overlay so it can't sit
                // on top of the consent activity Google is about to show.
                ClipboardAccessibilityService.instance?.dismissOverlayIfShowing()
                when (val outcome = auth.authorizeWithUi()) {
                    is GoogleAuth.AuthorizationOutcome.Granted -> {
                        val email = runCatching { outcome.result.toGoogleSignInAccount()?.email }.getOrNull()
                        syncPrefs.hasConsent = true
                        syncPrefs.signedInEmail = email
                        signedInEmail = email
                        hasConsent = true
                        statusMessage = "Signed in. Running first sync…"
                        statusMessage = runSync(syncManager) { isWorking = it }
                        lastSyncedAt = syncPrefs.lastSyncedAt
                        lastStatus = syncPrefs.lastSyncStatus
                    }
                    is GoogleAuth.AuthorizationOutcome.NeedsConsent -> {
                        ClipboardAccessibilityService.instance?.dismissOverlayIfShowing()
                        consentLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                    }
                }
            } catch (e: Exception) {
                statusMessage = "Sign-in failed: ${e.message}"
            } finally {
                isWorking = false
            }
        }
    }

    fun signOut() {
        syncPrefs.clearAuth()
        syncPrefs.cloudFileId = null
        syncPrefs.lastSyncedRevisionId = null
        signedInEmail = null
        hasConsent = false
        statusMessage = "Signed out"
    }

    fun syncNow() {
        scope.launch {
            statusMessage = runSync(syncManager) { working -> isWorking = working }
            lastSyncedAt = syncPrefs.lastSyncedAt
            lastStatus = syncPrefs.lastSyncStatus
        }
    }

    fun openCloudFile() {
        val fileId = syncPrefs.cloudFileId
        val url = if (fileId != null) "https://drive.google.com/file/d/$fileId/view"
        else "https://drive.google.com/drive/my-drive"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader("Cloud sync")
            SectionCard {
                Text(
                    "Pinned clips are synced to Google Drive at " +
                            "/ClipVault/pinned-clips.json. " +
                            "You can edit that file in Drive and changes propagate to all your devices on the next sync.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Account:", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
                    Text(signedInEmail ?: if (hasConsent) "(signed in)" else "(not signed in)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Last sync:", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
                    Text(formatLastSync(lastSyncedAt))
                }
                if (!lastStatus.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status:", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
                        Text(lastStatus!!)
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (!hasConsent) {
                    Button(
                        onClick = { signIn() },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in with Google")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { syncNow() },
                            enabled = !isWorking,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Sync now")
                        }
                        OutlinedButton(
                            onClick = { signOut() },
                            enabled = !isWorking,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Sign out")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { openCloudFile() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open file in Google Drive")
                    }
                }

                if (isWorking) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (!statusMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            statusMessage!!,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            SectionHeader("How it works")
            SectionCard {
                Text(
                    """
                    • Sync runs once per day overnight, plus whenever you pin or unpin an item.
                    • The cloud file is your source of truth: edits there propagate to every device.
                    • Identity is the exact text — to rename a pin, delete it and re-add it.
                    • The "_comment" and "schemaVersion" fields are reserved; don't remove them.
                    """.trimIndent(),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionHeader("OAuth diagnostics")
            DiagnosticsCard()
        }
    }
}

@Composable
private fun DiagnosticsCard() {
    val context = LocalContext.current
    val pkg = remember { AppSigningInfo.packageName(context) }
    val sha1 = remember { AppSigningInfo.signingSha1(context) ?: "(unavailable)" }

    SectionCard {
        Text(
            "If sign-in fails with status 10 (DEVELOPER_ERROR), it means the values below " +
                    "don't match the OAuth Android client in your Google Cloud project. " +
                    "Tap to copy and paste them into the Cloud Console exactly as shown.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        DiagnosticRow(label = "Package", value = pkg)
        Spacer(Modifier.height(8.dp))
        DiagnosticRow(label = "SHA-1", value = sha1)
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    val context = LocalContext.current
    Column {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = { copyToClipboard(context, label, value) }) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

private fun buildConsentFailureMessage(
    context: Context,
    failed: GoogleAuth.ConsentOutcome.Failed,
    signedInEmail: String?
): String {
    val codeHint = when (failed.statusCode) {
        10 -> "DEVELOPER_ERROR — the package name or SHA-1 in your Google Cloud OAuth Android client doesn't match this APK."
        16 -> "CANCELED — the consent screen was dismissed."
        7 -> "NETWORK_ERROR — check your internet connection."
        17 -> "API_NOT_CONNECTED — Google Play Services not available."
        4 -> "SIGN_IN_REQUIRED — your account isn't a Test user on the OAuth consent screen, or no Google account is on this device."
        else -> failed.message ?: "(no detail)"
    }
    return buildString {
        append("Sign-in failed: status ${failed.statusCode} ${failed.statusName}\n")
        append(codeHint)
        append("\n\n")
        append(diagnosticsBlock(context, signedInEmail))
    }
}

/**
 * Called when the consent activity returned RESULT_CANCELED with no data. This
 * happens both when the user actually taps Cancel AND when Google's helper
 * activity bails out before showing UI (e.g. config mismatch). We can't tell
 * them apart, so surface the most useful diagnostics in either case.
 */
private fun buildCancelMessage(
    context: Context,
    resultCode: Int,
    signedInEmail: String?
): String = buildString {
    append("Sign-in flow closed without granting access (resultCode=$resultCode).\n")
    append("If a real consent screen appeared and you tapped Cancel — try again.\n")
    append("If you only saw a brief flash, it usually means Google rejected the request before showing UI. Most common causes:\n")
    append(" 1. Package name or SHA-1 in the OAuth Android client doesn't match this APK (compare below).\n")
    append(" 2. Your Google account isn't on the Test users list of your OAuth consent screen.\n")
    append(" 3. Drive API is not enabled on your Cloud project.\n")
    append("\n")
    append(diagnosticsBlock(context, signedInEmail))
}

private fun diagnosticsBlock(context: Context, signedInEmail: String?): String {
    val pkg = AppSigningInfo.packageName(context)
    val sha1 = AppSigningInfo.signingSha1(context) ?: "(unavailable)"
    return buildString {
        append("This APK reports:\n")
        append("  • Package: ").append(pkg).append('\n')
        append("  • SHA-1:   ").append(sha1)
        if (!signedInEmail.isNullOrBlank()) {
            append('\n').append("  • Account: ").append(signedInEmail)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

private fun formatLastSync(epoch: Long): String =
    if (epoch == 0L) "never"
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch))

/** Run a sync and return a user-facing status message. */
private suspend fun runSync(
    manager: SyncManager,
    setWorking: (Boolean) -> Unit
): String {
    setWorking(true)
    return try {
        when (val result = manager.sync()) {
            is SyncResult.Success -> if (result.noChanges) "Up to date"
            else "Pulled ${result.pulledFromCloud}, pushed ${result.pushedToCloud}, removed ${result.removed}"
            is SyncResult.NeedsConsent -> "Sign in again to grant Drive access"
            is SyncResult.NotSignedIn -> "Sign in to sync"
            is SyncResult.NetworkError -> "Network error: ${result.message.take(80)}"
            is SyncResult.AuthError -> "Auth error: ${result.message.take(80)}"
            is SyncResult.UnexpectedError -> "Error: ${result.throwable.message?.take(80) ?: "unknown"}"
        }
    } finally {
        setWorking(false)
    }
}

