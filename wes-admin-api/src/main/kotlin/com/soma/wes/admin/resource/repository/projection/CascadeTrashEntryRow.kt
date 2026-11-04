package com.soma.wes.admin.resource.repository.projection


data class CascadeTrashEntryRow(
    val resourceType: String,
    val resourceId: Long,
    val root: Boolean,
    val relationPath: String,
)
