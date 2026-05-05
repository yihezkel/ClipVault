package com.clipvault.app.sync

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the set of pinned-text values we knew about at the last successful sync.
 * Stored as a JSON array on local storage (not in SharedPreferences) so it can hold
 * arbitrary clipboard text including any control characters.
 */
class SyncSnapshot(context: Context) {

    private val file: File = File(context.filesDir, SNAPSHOT_FILE).apply {
        parentFile?.mkdirs()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    fun read(): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8)).toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun write(texts: Set<String>) {
        file.writeText(json.encodeToString(serializer, texts.toList()), Charsets.UTF_8)
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    companion object {
        private const val SNAPSHOT_FILE = "sync/last_synced_snapshot.json"
    }
}

