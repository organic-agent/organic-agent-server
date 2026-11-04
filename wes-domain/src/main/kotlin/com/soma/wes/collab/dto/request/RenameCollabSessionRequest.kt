package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "공유폴더 이름·표지 부분 변경 요청")
data class RenameCollabSessionRequest(

    @field:Schema(description = "새 이름. 생략하면 현재 이름을 유지한다. 링크(collabToken)는 그대로다 — 하객이 들고 있는 주소가 죽지 않는다.")
    val name: String? = null,
    @field:Schema(
        description = "새 표지 제목. 생략하면 그대로 두고, 빈 문자열이면 지운다 — 그때 하객 화면은 공유폴더 이름을 제목으로 쓴다. 앞뒤 공백을 빼고 100자 이하다.",
        example = "민지 ♥ 준호의 본식",
    )
    val coverTitle: String? = null,
    @field:Schema(description = "새 표지 작성자 표기. 생략하면 그대로 두고, 빈 문자열이면 지운다. 앞뒤 공백을 빼고 100자 이하다.", example = "민지·준호")
    val coverAuthor: String? = null,
    @field:Schema(description = "하객 첫 화면에 같은 갤러리의 다른 공유폴더(거둬들였거나 만료된 것은 빼고)도 함께 보일지. 생략하면 지금 값을 유지한다.", example = "true")
    val includeAllAlbums: Boolean? = null,
)
