package com.soma.wes.retouch.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "발급된 결과 업로드 URL 하나")
data class IssuedResultUploadResponse(

    val photoId: Long,

    @field:Schema(description = "업로드를 마친 뒤 결과 확정 API의 resultKey로 그대로 보내는 값")
    val resultKey: String,

    @field:Schema(description = "이 URL로 S3에 직접 PUT 한다. 발급 요청의 contentType을 그대로 보내야 한다.")
    val uploadUrl: String,
)
