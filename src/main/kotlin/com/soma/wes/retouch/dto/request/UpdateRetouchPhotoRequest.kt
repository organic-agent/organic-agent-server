package com.soma.wes.retouch.dto.request

import com.soma.wes.retouch.domain.RetouchPhoto
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "사진 한 장의 보정 요청 내용을 적는 요청. 제출 전(DRAFTING)에는 몇 번이고 덮어쓴다")
data class UpdateRetouchPhotoRequest(

    @field:Size(max = RetouchPhoto.MAX_REQUEST_TEXT_LENGTH)
    @field:Schema(description = "보정 요청 텍스트. null이면 지운다.", example = "왼쪽 눈가 잡티 제거해주세요")
    val requestText: String? = null,

    @field:Size(max = 500)
    @field:Schema(
        description = "주석 이미지의 storage key. 주석 업로드 URL 발급 API가 돌려준 값을 " +
            "그대로 보낸다 — 이 갤러리의 주석 경로가 아닌 key는 400으로 거절된다. null이면 지운다.",
    )
    val annotationKey: String? = null,
)
