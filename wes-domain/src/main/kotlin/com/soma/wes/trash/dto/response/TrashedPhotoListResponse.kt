package com.soma.wes.trash.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "갤러리 하나의 휴지통 사진 목록")
data class TrashedPhotoListResponse(
    val photos: List<TrashedPhotoResponse>,

    @field:Schema(description = "viewUrl의 남은 수명(초). 지나면 목록을 다시 불러 새 URL을 받는다")
    val viewUrlTtlSeconds: Long,
)
