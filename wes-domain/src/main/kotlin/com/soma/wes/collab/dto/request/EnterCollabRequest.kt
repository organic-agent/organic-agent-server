package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "하객 입장 요청")
data class EnterCollabRequest(

    @field:Schema(
        description = "화면에 이름표로 붙는 값(1~50자). 중복을 막지 않는다 — '친구'가 셋이어도 서로 다른 하객이다.",
        example = "신랑 대학동기 철수",
    )
    val nickname: String,
)
