package com.soma.wes.retouch.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "주석 이미지 업로드 URL 발급 결과")
data class IssueAnnotationUploadUrlResponse(

    @field:Schema(
        description = "업로드를 마친 뒤 요청 저장 API의 annotationKey로 그대로 보내는 값. " +
            "저장하기 전까지는 어느 사진에도 붙지 않는다.",
    )
    val annotationKey: String,

    @field:Schema(description = "이 URL로 S3에 직접 PUT 한다. Content-Type은 image/png로 보내야 서명이 맞는다.")
    val uploadUrl: String,

    @field:Schema(description = "uploadUrl이 살아 있는 시간(초). 이 안에 업로드를 마쳐야 한다.")
    val uploadUrlTtlSeconds: Long,
)
