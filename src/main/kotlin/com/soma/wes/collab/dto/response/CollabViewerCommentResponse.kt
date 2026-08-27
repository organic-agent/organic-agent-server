package com.soma.wes.collab.dto.response

import java.time.ZonedDateTime

/** 로그인 사용자가 갤러리 결과 화면에서 보는 댓글의 안전한 관계 정보. */
data class CollabViewerCommentResponse(
    val commentId: Long,
    val sessionId: Long,
    val photoId: Long,
    val content: String,
    val createdAt: ZonedDateTime?,
)
