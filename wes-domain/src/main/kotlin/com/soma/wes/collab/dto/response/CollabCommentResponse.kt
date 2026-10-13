package com.soma.wes.collab.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "공유 세션 참여자가 남긴 댓글")
data class CollabCommentResponse(
    val commentId: Long,

    @field:Schema(description = "쓴 사람의 닉네임. 세션 참여자 정보에서 읽는다.")
    val nickname: String,

    val content: String,
    val createdAt: ZonedDateTime?,

    @field:Schema(description = "현재 로그인 사용자 또는 하객 토큰으로 식별된 참여자가 직접 쓴 댓글인지.")
    val mine: Boolean,
)
