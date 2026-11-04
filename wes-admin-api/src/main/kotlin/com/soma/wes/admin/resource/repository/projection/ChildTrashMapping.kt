package com.soma.wes.admin.resource.repository.projection


internal data class ChildTrashMapping(
    val childTable: String,
    val parentTable: String,
    val childParentPredicate: String,
)
