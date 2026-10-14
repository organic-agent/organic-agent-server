package com.soma.wes.photo.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "업로드 URL 일괄 발급 결과")
data class IssueUploadUrlsResponse(
    val uploads: List<IssuedUploadResponse>,

    @field:Schema(description = "uploadUrl이 살아 있는 시간(초). 이 안에 업로드를 마쳐야 한다.")
    val uploadUrlTtlSeconds: Long,
)
