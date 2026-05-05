package com.clipvault.app.data

import kotlinx.coroutines.flow.Flow

class ClipRepository(private val dao: ClipDao) {

    companion object {
        const val MAX_UNPINNED = 1000
    }

    /**
     * Optional listener invoked whenever the pinned set changes via a user-driven action.
     * The application sets this to trigger an async cloud sync.
     * Sync-driven calls use the `*Silently` variants to avoid feedback loops.
     */
    var onPinsChanged: (() -> Unit)? = null

    fun getAllClips(pinnedFirst: Boolean = true): Flow<List<ClipEntity>> =
        if (pinnedFirst) dao.getAllClipsPinnedFirst() else dao.getAllClipsByRecency()

    fun getPinnedClips(): Flow<List<ClipEntity>> = dao.getPinnedClips()

    fun searchClips(query: String, pinnedFirst: Boolean = true): Flow<List<ClipEntity>> {
        val ftsQuery = query.trim().replace(Regex("[\"*]"), "").plus("*")
        return if (pinnedFirst) dao.searchClipsPinnedFirst(ftsQuery)
        else dao.searchClipsByRecency(ftsQuery)
    }

    suspend fun addClip(text: String): Long {
        val preview = text.take(150).replace('\n', ' ')
        val existing = dao.getMostRecent()
        if (existing != null && existing.text == text) {
            dao.updateLastUsed(existing.id)
            return existing.id
        }
        val id = dao.insert(
            ClipEntity(
                text = text,
                preview = preview
            )
        )
        // Prune oldest unpinned clips beyond the limit
        if (dao.getUnpinnedCount() > MAX_UNPINNED) {
            dao.pruneUnpinned(MAX_UNPINNED)
        }
        return id
    }

    /** Insert a pinned clip pulled from the cloud, deduplicating by exact text. Does NOT trigger sync. */
    suspend fun addPinnedClip(text: String, createdAtMillis: Long? = null): Long {
        val existing = dao.findByText(text)
        if (existing != null) {
            if (!existing.isPinned) dao.pin(existing.id)
            return existing.id
        }
        val now = System.currentTimeMillis()
        val createdAt = createdAtMillis ?: now
        return dao.insert(
            ClipEntity(
                text = text,
                preview = text.take(150).replace('\n', ' '),
                isPinned = true,
                createdAt = createdAt,
                lastUsedAt = now
            )
        )
    }

    suspend fun togglePin(id: Long) {
        dao.togglePin(id)
        onPinsChanged?.invoke()
    }

    suspend fun pinMany(ids: List<Long>) {
        dao.pinMany(ids)
        onPinsChanged?.invoke()
    }

    suspend fun unpinMany(ids: List<Long>) {
        dao.unpinMany(ids)
        onPinsChanged?.invoke()
    }

    /** Sync-internal pin update; does NOT trigger another sync. */
    suspend fun pinManySilently(ids: List<Long>) = dao.pinMany(ids)

    /** Sync-internal unpin update; does NOT trigger another sync. */
    suspend fun unpinManySilently(ids: List<Long>) = dao.unpinMany(ids)

    suspend fun updateLastUsed(id: Long) = dao.updateLastUsed(id)

    suspend fun delete(id: Long) {
        dao.delete(id)
        onPinsChanged?.invoke()
    }

    suspend fun deleteMany(ids: List<Long>) {
        dao.deleteMany(ids)
        onPinsChanged?.invoke()
    }

    suspend fun deleteAllUnpinned() = dao.deleteAllUnpinned()
}

