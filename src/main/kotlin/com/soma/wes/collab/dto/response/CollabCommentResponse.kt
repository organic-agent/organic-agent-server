package com.soma.wes.collab.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "하객이 남긴 댓글")
data class CollabCommentResponse(
    val commentId: Long,

    @field:Schema(description = "쓴 사람의 닉네임. 하객 행에서 읽어오므로 이름을 고치면 과거 댓글도 함께 바뀐다.")
    val nickname: String,

    val content: String,
    val createdAt: ZonedDateTime?,

    @field:Schema(description = "이 요청을 보낸 하객이 쓴 댓글인지. 화면이 삭제 버튼을 여기에만 붙인다.")
    val mine: Boolean,
)
