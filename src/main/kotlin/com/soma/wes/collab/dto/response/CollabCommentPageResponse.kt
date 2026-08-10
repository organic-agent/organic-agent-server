package com.soma.wes.collab.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "댓글 한 페이지")
data class CollabCommentPageResponse(
    val comments: List<CollabCommentResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val hasNext: Boolean,
)
