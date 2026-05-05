package com.clipvault.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {

    @Query("""
        SELECT * FROM clips 
        ORDER BY isPinned DESC, lastUsedAt DESC
    """)
    fun getAllClipsPinnedFirst(): Flow<List<ClipEntity>>

    @Query("""
        SELECT * FROM clips 
        ORDER BY lastUsedAt DESC
    """)
    fun getAllClipsByRecency(): Flow<List<ClipEntity>>

    @Query("""
        SELECT * FROM clips WHERE isPinned = 1
        ORDER BY lastUsedAt DESC
    """)
    fun getPinnedClips(): Flow<List<ClipEntity>>

    @Query("""
        SELECT clips.* FROM clips
        JOIN clips_fts ON clips.rowid = clips_fts.rowid
        WHERE clips_fts MATCH :query
        ORDER BY clips.isPinned DESC, clips.lastUsedAt DESC
    """)
    fun searchClipsPinnedFirst(query: String): Flow<List<ClipEntity>>

    @Query("""
        SELECT clips.* FROM clips
        JOIN clips_fts ON clips.rowid = clips_fts.rowid
        WHERE clips_fts MATCH :query
        ORDER BY clips.lastUsedAt DESC
    """)
    fun searchClipsByRecency(query: String): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips ORDER BY createdAt DESC LIMIT 1")
    suspend fun getMostRecent(): ClipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clip: ClipEntity): Long

    @Query("UPDATE clips SET isPinned = NOT isPinned WHERE id = :id")
    suspend fun togglePin(id: Long)

    @Query("UPDATE clips SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM clips WHERE text = :text LIMIT 1")
    suspend fun findByText(text: String): ClipEntity?

    @Query("UPDATE clips SET isPinned = 1 WHERE id = :id")
    suspend fun pin(id: Long)

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM clips WHERE id IN (:ids)")
    suspend fun deleteMany(ids: List<Long>)

    @Query("UPDATE clips SET isPinned = 1 WHERE id IN (:ids)")
    suspend fun pinMany(ids: List<Long>)

    @Query("UPDATE clips SET isPinned = 0 WHERE id IN (:ids)")
    suspend fun unpinMany(ids: List<Long>)

    @Query("DELETE FROM clips WHERE isPinned = 0")
    suspend fun deleteAllUnpinned()

    @Query("SELECT COUNT(*) FROM clips WHERE isPinned = 0")
    suspend fun getUnpinnedCount(): Int

    @Query("""
        DELETE FROM clips WHERE isPinned = 0 AND id NOT IN (
            SELECT id FROM clips WHERE isPinned = 0 ORDER BY lastUsedAt DESC LIMIT :keep
        )
    """)
    suspend fun pruneUnpinned(keep: Int)

    @Query("SELECT COUNT(*) FROM clips")
    suspend fun getCount(): Int
}
