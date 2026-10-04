package com.soma.wes.photo.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "발급된 업로드 URL 하나")
data class IssuedUploadResponse(
    val photoId: Long,
    val storageKey: String,

    @field:Schema(description = "이 URL로 S3에 직접 PUT 한다. 발급 요청의 contentType을 그대로 보내야 한다.")
    val uploadUrl: String,
)
