package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션 이름 변경 요청")
data class RenameCollabSessionRequest(

    @field:Schema(description = "새 이름. 링크(shareToken)는 그대로다 — 하객이 들고 있는 주소가 죽지 않는다.")
    val name: String,
)
