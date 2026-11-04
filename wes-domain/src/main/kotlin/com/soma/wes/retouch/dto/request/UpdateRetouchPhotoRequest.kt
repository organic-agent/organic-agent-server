package com.soma.wes.retouch.dto.request

import com.soma.wes.retouch.domain.RetouchPhoto
import com.soma.wes.retouch.domain.RetouchPoint
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "사진 한 장의 보정 요청 내용을 적는 요청. 제출 전(DRAFTING)에는 몇 번이고 덮어쓴다")
data class UpdateRetouchPhotoRequest(

    @field:Size(max = RetouchPhoto.MAX_REQUEST_TEXT_LENGTH)
    @field:Schema(description = "보정 요청 텍스트. null이면 지운다.", example = "왼쪽 눈가 잡티 제거해주세요")
    val requestText: String? = null,

    @field:Schema(description = "사진 위 지점별 요청. 보낸 목록으로 통째로 바꾸므로 생략하거나 비우면 지점이 모두 지워진다. 100개 이하, x·y는 0~1로 정규화한 좌표이고 text는 비울 수 없다(2000자 이하).")
    val points: List<RetouchPoint> = emptyList(),
)
