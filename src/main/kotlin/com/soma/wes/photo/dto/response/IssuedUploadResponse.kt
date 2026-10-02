package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.UploadState
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "발급된 업로드 URL 하나")
data class IssuedUploadResponse(
    val photoId: Long,
    val storageKey: String,

    @field:Schema(
        description = "이 URL로 S3에 직접 PUT 한다. 발급 요청의 contentType을 그대로 보내야 한다. "
            + "state 가 UPLOADED 면 null 이다 — 이미 올라온 사진이라 올릴 것이 없다.",
        nullable = true,
    )
    val uploadUrl: String?,

    @field:Schema(
        description = "NEW 는 새로 만든 사진, PENDING 은 같은 지문의 올리다 만 사진(같은 photoId 로 URL 만 새로 받았다), "
            + "UPLOADED 는 이미 올라온 사진이다. 지문 없이 요청하면 항상 NEW.",
    )
    val state: UploadState,
)
