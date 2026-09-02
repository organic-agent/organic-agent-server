package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션 열기 요청")
data class OpenCollabSessionRequest(

    @field:Schema(description = "현재 사진 구성을 동적으로 공유할 컨셉폴더 id")
    val conceptFolderId: Long,

    @field:Schema(
        description = "부부가 링크를 구분하려고 붙이는 이름. 하객 첫 화면에도 보인다. " +
            "같은 컨셉으로 다시 열면 기존 세션의 이름이 이 값으로 갱신된다.",
        example = "본식 후보",
    )
    val name: String,

)
