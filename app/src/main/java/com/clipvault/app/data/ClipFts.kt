package com.clipvault.app.data

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = ClipEntity::class)
@Entity(tableName = "clips_fts")
data class ClipFts(
    val text: String
)
