package com.clipvault.app.sync

import android.content.Context
import android.util.Log
import com.clipvault.app.data.ClipEntity
import com.clipvault.app.data.ClipRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * Orchestrates a full sync cycle:
 *   1. Acquire access token (silent).
 *   2. Locate or create the cloud file.
 *   3. Compare the cloud file's headRevisionId to our last-known.
 *   4. 3-way merge against the local snapshot.
 *   5. Apply changes locally + upload merged content if needed.
 *   6. Persist new snapshot + revision.
 */
class SyncManager(
    private val context: Context,
    private val repository: ClipRepository,
    private val auth: GoogleAuth = GoogleAuth(context),
    private val prefs: SyncPrefs = SyncPrefs(context),
    private val snapshot: SyncSnapshot = SyncSnapshot(context)
) {

    /** Run sync. Safe to call from any coroutine context; switches to IO internally. */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        try {
            // Treat absence of consent as "user has not opted in / has signed out".
            // (We can't gate on signedInEmail because the new Authorization API does not
            // always include the user's email in the result.)
            if (!prefs.hasConsent) return@withContext SyncResult.NotSignedIn

            val authResult = auth.authorizeSilent() ?: return@withContext SyncResult.NeedsConsent

            val accessToken = authResult.accessToken
                ?: return@withContext SyncResult.AuthError("No access token returned")

            val email = runCatching { authResult.toGoogleSignInAccount()?.email }.getOrNull()
            if (email != null) prefs.signedInEmail = email

            val drive = DriveClient(accessToken)

            // Locate (or create) folder + file.
            val folderId = ensureFolder(drive)
            val fileMeta = ensureFile(drive, folderId)
            prefs.cloudFileId = fileMeta.id

            // Read current cloud file content.
            val cloudContent = drive.downloadFile(fileMeta.id)
            val cloudFile = parseOrEmpty(cloudContent)

            // Read current local pinned set.
            val localPinned: List<ClipEntity> = repository.getPinnedClips().first()

            // 3-way merge against snapshot.
            val snap = snapshot.read()
            val mergeInputs = SyncMerge.MergeInputs(
                local = localPinned.map { it.toSyncPin() },
                cloud = cloudFile.pins,
                snapshot = snap
            )
            val mergeResult = SyncMerge.merge(mergeInputs)

            val mergedTexts = mergeResult.merged.map { it.text }.toSet()
            val cloudTexts = cloudFile.pins.map { it.text }.toSet()
            val localTexts = localPinned.map { it.text }.toSet()
            val cloudUnchanged = cloudTexts == mergedTexts
            val localUnchanged = localTexts == mergedTexts

            // Apply local changes if needed.
            if (!localUnchanged) {
                applyLocalPins(localPinned, mergeResult.merged)
            }

            // Upload merged content if needed.
            val newRevision: String? = if (!cloudUnchanged) {
                val payload = SyncFileCodec.encode(
                    SyncFile(
                        lastUpdated = SyncFileCodec.isoNow(),
                        pins = mergeResult.merged
                    )
                )
                val updated = drive.updateFile(fileMeta.id, payload)
                updated.headRevisionId
            } else {
                fileMeta.headRevisionId
            }

            // Persist snapshot + revision.
            snapshot.write(mergedTexts)
            if (newRevision != null) prefs.lastSyncedRevisionId = newRevision
            prefs.lastSyncedAt = System.currentTimeMillis()

            val noChanges = cloudUnchanged && localUnchanged
            val statusMessage = if (noChanges) "Up to date" else
                "Pulled ${mergeResult.addedToLocal}, pushed ${mergeResult.addedToCloud}, removed ${mergeResult.removed}"
            prefs.lastSyncStatus = statusMessage

            SyncResult.Success(
                pulledFromCloud = mergeResult.addedToLocal,
                pushedToCloud = mergeResult.addedToCloud,
                removed = mergeResult.removed,
                noChanges = noChanges
            )
        } catch (e: DriveAuthException) {
            Log.w(TAG, "Drive auth failed", e)
            prefs.lastSyncStatus = "Auth error"
            SyncResult.AuthError(e.message ?: "Auth error")
        } catch (e: IOException) {
            Log.w(TAG, "Sync network error", e)
            prefs.lastSyncStatus = "Network error"
            SyncResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            prefs.lastSyncStatus = "Error: ${e.message}"
            SyncResult.UnexpectedError(e)
        }
    }

    /** Find existing /ClipVault folder or create it. */
    private fun ensureFolder(drive: DriveClient): String {
        val existing = drive.findFolder(FOLDER_NAME, parentId = "root")
        if (existing != null) return existing.id
        val created = drive.createFolder(FOLDER_NAME)
        // Sanity-check: createFolder must actually produce a folder. If Drive returns
        // something else, surface it instead of letting createFile fail with a confusing
        // "parentNotAFolder" error a moment later.
        if (created.mimeType != null && created.mimeType != DriveClient.MIME_FOLDER) {
            throw IllegalStateException(
                "Drive createFolder returned non-folder mime=${created.mimeType} id=${created.id}"
            )
        }
        return created.id
    }

    /** Find existing pinned-clips.json or create it with an empty pins list. */
    private fun ensureFile(drive: DriveClient, folderId: String): DriveClient.FileMeta {
        // Trust cached fileId if present and still accessible.
        prefs.cloudFileId?.let { cachedId ->
            return runCatching { drive.getFileMeta(cachedId) }.getOrNull() ?: run {
                prefs.cloudFileId = null
                ensureFile(drive, folderId)
            }
        }

        val existing = drive.findFileInFolder(folderId, FILE_NAME)
        if (existing != null) return existing

        val initialContent = SyncFileCodec.encode(
            SyncFile(
                lastUpdated = SyncFileCodec.isoNow(),
                pins = emptyList()
            )
        )
        return drive.createFile(FILE_NAME, folderId, initialContent)
    }

    private fun parseOrEmpty(content: String): SyncFile {
        if (content.isBlank()) {
            return SyncFile(lastUpdated = SyncFileCodec.isoNow(), pins = emptyList())
        }
        return try {
            SyncFileCodec.decode(content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cloud file; treating as empty", e)
            SyncFile(lastUpdated = SyncFileCodec.isoNow(), pins = emptyList())
        }
    }

    /**
     * Update the local DB so its pinned set matches [merged]:
     *  - For each local clip: if its text is in mergedTexts, ensure isPinned=true; else isPinned=false.
     *  - For each merged pin not present locally: insert a new pinned clip.
     */
    private suspend fun applyLocalPins(currentLocal: List<ClipEntity>, merged: List<SyncPin>) {
        val mergedTexts = merged.map { it.text }.toSet()

        // Pin or unpin existing clips.
        val toPin = currentLocal.filter { !it.isPinned && mergedTexts.contains(it.text) }.map { it.id }
        val toUnpin = currentLocal.filter { it.isPinned && !mergedTexts.contains(it.text) }.map { it.id }
        if (toPin.isNotEmpty()) repository.pinManySilently(toPin)
        if (toUnpin.isNotEmpty()) repository.unpinManySilently(toUnpin)

        // Insert any merged pins missing locally.
        val localTexts = currentLocal.map { it.text }.toSet()
        for (pin in merged) {
            if (pin.text !in localTexts) {
                repository.addPinnedClip(pin.text, SyncFileCodec.parseIsoOrNull(pin.createdAt))
            }
        }
    }

    private fun ClipEntity.toSyncPin(): SyncPin = SyncPin(
        text = text,
        createdAt = SyncFileCodec.isoOf(createdAt)
    )

    companion object {
        private const val TAG = "ClipVaultSync"
        const val FOLDER_NAME = "ClipVault"
        const val FILE_NAME = "pinned-clips.json"
    }
}
