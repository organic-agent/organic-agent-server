package com.soma.wes.folder.dto.request

data class MoveFolderPhotosRequest(
    val photoIds: List<Long>,
    /** null이면 논리적 미분류 상태로 옮긴다. */
    val targetDetailFolderId: Long? = null,
)
