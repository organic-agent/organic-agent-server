package com.soma.wes.selection.repository.projection

data class SelectionSyncRevision(
    val selectionRevision: Long,
    val ratingRevision: Long,
    val exclusionRevision: Long = 0,
)
