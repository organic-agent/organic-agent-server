package com.soma.wes.collab.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션 사진 한 페이지")
data class CollabPhotoPageResponse(
    val photos: List<CollabPhotoResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val hasNext: Boolean,

    @field:Schema(
        description = "viewUrl이 살아 있는 시간(초). 프론트는 이 시간이 지나기 전에 목록을 다시 불러야 한다.",
    )
    val viewUrlTtlSeconds: Long,
)
