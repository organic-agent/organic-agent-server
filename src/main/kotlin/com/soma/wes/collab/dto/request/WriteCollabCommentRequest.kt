package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "댓글 작성 요청")
data class WriteCollabCommentRequest(

    @field:Schema(description = "댓글 내용(1~500자). 수정은 없다 — 지우고 다시 쓴다.", example = "이 표정이 제일 신부님답네요")
    val content: String,
)
