package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션 열기 요청")
data class OpenCollabSessionRequest(

    @field:Schema(
        description = "부부가 링크를 구분하려고 붙이는 이름. 하객 첫 화면에도 보인다. " +
            "폴더에서 열 때는 그 폴더 이름을 그대로 보내면 된다.",
        example = "본식 후보",
    )
    val name: String,

    @field:Schema(
        description = "이 폴더에 담긴 사진으로 세션을 채운다. 생략하면 빈 세션이 열리고 사진은 따로 담는다. " +
            "**복사다** — 세션을 연 뒤 폴더를 고치거나 지워도 하객이 보는 사진은 그대로다. " +
            "폴더가 비어 있거나 아직 올라오지 않은 사진이 섞여 있으면 요청 전체가 거절된다.",
    )
    val folderId: Long? = null,
)
