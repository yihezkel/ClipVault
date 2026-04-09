package com.clipvault.app.data

import kotlinx.coroutines.flow.Flow

class ClipRepository(private val dao: ClipDao) {

    companion object {
        const val MAX_UNPINNED = 1000
    }

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

    suspend fun togglePin(id: Long) = dao.togglePin(id)

    suspend fun pinMany(ids: List<Long>) = dao.pinMany(ids)

    suspend fun unpinMany(ids: List<Long>) = dao.unpinMany(ids)

    suspend fun updateLastUsed(id: Long) = dao.updateLastUsed(id)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun deleteMany(ids: List<Long>) = dao.deleteMany(ids)

    suspend fun deleteAllUnpinned() = dao.deleteAllUnpinned()
}
