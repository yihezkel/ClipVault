package com.clipvault.app.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal Google Drive v3 REST client. Just enough to:
 *   - find or create a folder + file
 *   - get file metadata + headRevisionId
 *   - download file content
 *   - upload (overwrite) file content with multipart upload
 *
 * Why not use the official Google Drive Java client? It pulls in a huge dependency tree that
 * isn't fit-for-purpose on Android. This handful of REST calls is far cheaper.
 */
class DriveClient(private val accessToken: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // encodeDefaults must be true so request bodies include mimeType (which has a default).
    // Without it Drive's create-folder endpoint creates a generic file instead of a folder.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class FileListResponse(val files: List<FileMeta> = emptyList())

    @Serializable
    data class FileMeta(
        val id: String,
        val name: String? = null,
        val mimeType: String? = null,
        val parents: List<String>? = null,
        val headRevisionId: String? = null,
        val modifiedTime: String? = null,
        val webViewLink: String? = null
    )

    @Serializable
    private data class CreateFolderRequest(
        val name: String,
        val mimeType: String = MIME_FOLDER,
        val parents: List<String> = emptyList()
    )

    @Serializable
    private data class CreateFileMetadata(
        val name: String,
        val mimeType: String = MIME_JSON,
        val parents: List<String> = emptyList()
    )

    /** Find a single file by name in a parent folder. Returns null if not found. */
    fun findFileInFolder(folderId: String, name: String): FileMeta? {
        val q = "name = '${name.escapeForQuery()}' and '${folderId.escapeForQuery()}' in parents and trashed = false"
        return listFiles(q).firstOrNull()
    }

    /** Find a folder named [name] at the given parent. `parentId = "root"` means the user's My Drive root. */
    fun findFolder(name: String, parentId: String = "root"): FileMeta? {
        val q = "name = '${name.escapeForQuery()}' and mimeType = '$MIME_FOLDER' and '${parentId.escapeForQuery()}' in parents and trashed = false"
        return listFiles(q).firstOrNull()
    }

    private fun listFiles(q: String): List<FileMeta> {
        val url = HttpUrlBuilder("$BASE/drive/v3/files")
            .add("q", q)
            .add("fields", "files(id,name,mimeType,parents,headRevisionId,modifiedTime,webViewLink)")
            .add("spaces", "drive")
            .add("pageSize", "10")
            .build()
        val req = Request.Builder().url(url).get().headers(authHeaders()).build()
        client.newCall(req).execute().use { resp ->
            ensureOk(resp, "list files")
            val body = resp.body?.string().orEmpty()
            return json.decodeFromString(FileListResponse.serializer(), body).files
        }
    }

    fun createFolder(name: String, parentId: String = "root"): FileMeta {
        val body = json.encodeToString(
            CreateFolderRequest.serializer(),
            CreateFolderRequest(name = name, parents = listOf(parentId))
        ).toRequestBody(MIME_JSON.toMediaType())
        val url = HttpUrlBuilder("$BASE/drive/v3/files")
            .add("fields", "id,name,mimeType,parents,headRevisionId,modifiedTime,webViewLink")
            .build()
        val req = Request.Builder().url(url).post(body).headers(authHeaders()).build()
        client.newCall(req).execute().use { resp ->
            ensureOk(resp, "create folder")
            return json.decodeFromString(FileMeta.serializer(), resp.body?.string().orEmpty())
        }
    }

    fun getFileMeta(fileId: String): FileMeta {
        val url = HttpUrlBuilder("$BASE/drive/v3/files/$fileId")
            .add("fields", "id,name,mimeType,parents,headRevisionId,modifiedTime,webViewLink")
            .build()
        val req = Request.Builder().url(url).get().headers(authHeaders()).build()
        client.newCall(req).execute().use { resp ->
            ensureOk(resp, "get file metadata")
            return json.decodeFromString(FileMeta.serializer(), resp.body?.string().orEmpty())
        }
    }

    fun downloadFile(fileId: String): String {
        val url = HttpUrlBuilder("$BASE/drive/v3/files/$fileId").add("alt", "media").build()
        val req = Request.Builder().url(url).get().headers(authHeaders()).build()
        client.newCall(req).execute().use { resp ->
            ensureOk(resp, "download file")
            return resp.body?.string().orEmpty()
        }
    }

    /** Multipart create. Returns the new file's metadata (with headRevisionId). */
    fun createFile(name: String, parentId: String, content: String): FileMeta {
        val metadataJson = json.encodeToString(
            CreateFileMetadata.serializer(),
            CreateFileMetadata(name = name, parents = listOf(parentId))
        )
        val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(metadataJson.toRequestBody(MIME_JSON.toMediaType()))
            .addPart(content.toRequestBody(MIME_JSON.toMediaType()))
            .build()
        val url = HttpUrlBuilder("$BASE/upload/drive/v3/files")
            .add("uploadType", "multipart")
            .add("fields", "id,name,mimeType,parents,headRevisionId,modifiedTime,webViewLink")
            .build()
        val req = Request.Builder().url(url).post(multipart).headers(authHeaders()).build()
        client.newCall(req).execute().use { resp ->
            ensureOk(resp, "create file")
            return json.decodeFromString(FileMeta.serializer(), resp.body?.string().orEmpty())
        }
    }

    /** Update existing file content (overwrite). Returns updated metadata with new headRevisionId. */
    fun updateFile(fileId: String, content: String): FileMeta {
        val url = HttpUrlBuilder("$BASE/upload/drive/v3/files/$fileId")
            .add("uploadType", "media")
            .add("fields", "id,name,mimeType,parents,headRevisionId,modifiedTime,webViewLink")
            .build()
        val body = content.toRequestBody(MIME_JSON.toMediaType())
        val req = Request.Builder().url(url).patch(body).headers(authHeaders()).build()
        client.newCall(req).execute().use { resp ->
            ensureOk(resp, "update file")
            return json.decodeFromString(FileMeta.serializer(), resp.body?.string().orEmpty())
        }
    }

    private fun authHeaders(): Headers = Headers.Builder()
        .add("Authorization", "Bearer $accessToken")
        .add("Accept", "application/json")
        .build()

    private fun ensureOk(resp: Response, action: String) {
        if (resp.isSuccessful) return
        val msg = resp.body?.string()?.take(500).orEmpty()
        if (resp.code == 401 || resp.code == 403) {
            throw DriveAuthException("Drive $action failed (${resp.code}): $msg")
        }
        throw IOException("Drive $action failed (${resp.code}): $msg")
    }

    private fun String.escapeForQuery(): String = replace("\\", "\\\\").replace("'", "\\'")

    private class HttpUrlBuilder(base: String) {
        private val sb = StringBuilder(base)
        private var first = true
        fun add(key: String, value: String): HttpUrlBuilder {
            sb.append(if (first) '?' else '&')
            first = false
            sb.append(key).append('=').append(java.net.URLEncoder.encode(value, "UTF-8"))
            return this
        }

        fun build(): String = sb.toString()
    }

    companion object {
        private const val BASE = "https://www.googleapis.com"
        const val MIME_FOLDER = "application/vnd.google-apps.folder"
        const val MIME_JSON = "application/json"
    }
}

class DriveAuthException(message: String) : IOException(message)
