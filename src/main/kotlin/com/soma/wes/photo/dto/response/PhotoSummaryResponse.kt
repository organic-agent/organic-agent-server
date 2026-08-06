package com.soma.wes.photo.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "갤러리의 사진 상태 집계. 임베딩은 비동기라 진행 상황을 이 값으로 확인한다.")
data class PhotoSummaryResponse(
    val total: Long,
    val pending: Long,
    val uploaded: Long,
    val embedded: Long,
)
