package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션에서 사진 빼기 요청")
data class RemoveCollabPhotosRequest(

    @field:Schema(
        description = "뺄 사진 id. 이미 빠진 id가 섞여 있어도 막지 않는다 — 화면이 조금 낡은 것일 뿐이다. " +
            "뺀 사진에 달린 댓글과 반응은 함께 사라진다.",
    )
    val photoIds: List<Long>,
)
