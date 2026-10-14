package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "공유폴더 이름·표지 부분 변경 요청")
data class RenameCollabSessionRequest(

    @field:Schema(description = "새 이름. 생략하면 현재 이름을 유지한다. 링크(collabToken)는 그대로다 — 하객이 들고 있는 주소가 죽지 않는다.")
    val name: String? = null,
    val coverTitle: String? = null,
    val coverAuthor: String? = null,
    val includeAllAlbums: Boolean? = null,
)
