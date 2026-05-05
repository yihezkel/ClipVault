package com.clipvault.app.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Top-level shape of `pinned-clips.json` on Drive.
 *
 * Designed to be human-readable and hand-editable. The user's source of truth is the
 * `text` field — that's the only field the device cares about. Everything else is
 * informational and can be freely added/removed by the user.
 */
@Serializable
data class SyncFile(
    @SerialName("_comment")
    val comment: String = DEFAULT_COMMENT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val lastUpdated: String,
    val pins: List<SyncPin>
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val DEFAULT_COMMENT =
            "ClipVault pinned clips. Edit this file to add or remove pins on every signed-in device. " +
                "Identity is based on the exact 'text' field. Other fields (note, createdAt) are " +
                "informational only. Do not edit schemaVersion."
    }
}

/**
 * A single pinned clip entry. Only `text` is required and authoritative.
 *
 * - `text`: the clipboard content. Multi-line content is encoded with `\n`.
 * - `note`: optional human-friendly note; not synced back to the device, just a reminder for you.
 * - `createdAt`: original creation timestamp on the device that first added this pin (best effort).
 */
@Serializable
data class SyncPin(
    val text: String,
    val note: String? = null,
    val createdAt: String? = null
)

object SyncFileCodec {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val isoFormatter: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun encode(file: SyncFile): String = json.encodeToString(SyncFile.serializer(), file)

    fun decode(content: String): SyncFile = json.decodeFromString(SyncFile.serializer(), content)

    fun isoNow(): String = isoFormatter.format(Date())

    fun isoOf(epochMillis: Long): String = isoFormatter.format(Date(epochMillis))

    fun parseIsoOrNull(value: String?): Long? = try {
        value?.let { isoFormatter.parse(it)?.time }
    } catch (e: Exception) {
        null
    }
}
