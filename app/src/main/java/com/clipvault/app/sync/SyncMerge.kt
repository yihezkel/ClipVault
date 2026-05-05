package com.clipvault.app.sync

/**
 * Pure 3-way merge for pinned clips, identified by exact text.
 *
 * Inputs:
 *   - local: pins on this device right now
 *   - cloud: pins in the cloud file right now
 *   - snapshot: pins as they were at the last successful sync (empty on first sync)
 *
 * Because identity is exact text:
 *   - "added on either side" → keep
 *   - "removed on either side" → remove (only if it was in the snapshot, i.e. existed at last sync)
 *   - There are no true conflicts: every (local-set, cloud-set, snapshot-set) tuple has an unambiguous result.
 */
object SyncMerge {

    data class MergeInputs(
        val local: List<SyncPin>,
        val cloud: List<SyncPin>,
        val snapshot: Set<String>
    )

    data class MergeResult(
        val merged: List<SyncPin>,
        val addedToCloud: Int,    // present locally, missing from cloud, not removed by cloud
        val addedToLocal: Int,    // present in cloud, missing locally, not removed by local
        val removed: Int          // dropped because one side removed and the other hadn't changed it
    )

    fun merge(inputs: MergeInputs): MergeResult {
        val localByText = inputs.local.associateBy { it.text }
        val cloudByText = inputs.cloud.associateBy { it.text }
        val snapshot = inputs.snapshot

        val allTexts = (localByText.keys + cloudByText.keys + snapshot)
        val merged = mutableListOf<SyncPin>()
        var addedToCloud = 0
        var addedToLocal = 0
        var removed = 0

        for (text in allTexts) {
            val inLocal = localByText.containsKey(text)
            val inCloud = cloudByText.containsKey(text)
            val inSnap = snapshot.contains(text)

            when {
                // Both sides agree on presence
                inLocal && inCloud -> {
                    // Prefer cloud's metadata (note may have been edited by user)
                    merged += cloudByText.getValue(text)
                }
                // Local has it, cloud doesn't
                !inCloud && inLocal -> {
                    if (inSnap) {
                        // Cloud removed it, local kept it → cloud wins (remove)
                        removed++
                    } else {
                        // Local added it → keep, push to cloud
                        merged += localByText.getValue(text)
                        addedToCloud++
                    }
                }
                // Cloud has it, local doesn't
                inCloud && !inLocal -> {
                    if (inSnap) {
                        // Local removed it, cloud kept it → local wins (remove)
                        removed++
                    } else {
                        // Cloud added it → keep, pull to local
                        merged += cloudByText.getValue(text)
                        addedToLocal++
                    }
                }
                // Neither side has it; was in snapshot → both removed; drop
                else -> Unit
            }
        }

        // Stable ordering: by createdAt asc when present, else lexicographic.
        merged.sortWith(compareBy({ SyncFileCodec.parseIsoOrNull(it.createdAt) ?: Long.MAX_VALUE }, { it.text }))

        return MergeResult(
            merged = merged,
            addedToCloud = addedToCloud,
            addedToLocal = addedToLocal,
            removed = removed
        )
    }
}
