package com.soma.wes.collab.dto.request

import com.soma.wes.collab.domain.CollabReaction
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "사진 반응 요청")
data class VoteCollabPhotoRequest(

    @field:Schema(description = "이 사진에 대한 반응. 다시 보내면 덮어쓴다 — 하객 한 사람의 표는 하나다.")
    val reaction: CollabReaction,
)
