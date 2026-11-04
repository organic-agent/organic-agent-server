package com.soma.wes.collab.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

/** 로그인 사용자가 갤러리 결과 화면에서 보는 댓글의 안전한 관계 정보. */
data class CollabViewerCommentResponse(
    val commentId: Long,
    val sessionId: Long,
    val photoId: Long,
    val content: String,
    @field:Schema(description = "댓글을 남긴 시각. 저장된 댓글이면 늘 채워진다.", example = "2026-11-04T10:15:30+09:00")
    val createdAt: ZonedDateTime?,
)
