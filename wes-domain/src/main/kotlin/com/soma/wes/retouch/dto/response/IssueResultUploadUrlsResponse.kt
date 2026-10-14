package com.soma.wes.retouch.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "보정 결과 업로드 URL 일괄 발급 결과")
data class IssueResultUploadUrlsResponse(

    @field:Schema(description = "요청한 항목 순서대로의 photoId ↔ key ↔ URL 매핑")
    val uploads: List<IssuedResultUploadResponse>,

    @field:Schema(description = "uploadUrl이 살아 있는 시간(초). 이 안에 업로드를 마쳐야 한다.")
    val uploadUrlTtlSeconds: Long,
)
