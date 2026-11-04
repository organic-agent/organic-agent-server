package com.soma.wes.studio.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "스튜디오 수정 요청")
data class UpdateStudioRequest(

    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(description = "스튜디오 이름", example = "오가닉 스튜디오")
    val name: String,

    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(
        description = "공개 주소 식별자. 앞뒤 공백과 대소문자는 정규화되며, 바꾸지 않을 거라면 지금 값을 그대로 보내면 된다.",
        example = "organic-studio",
    )
    val galleryUrl: String,

    @field:Size(max = 100)
    @field:Schema(description = "스튜디오 연락처. 100자까지이며, null이면 저장된 연락처를 지운다.", example = "010-1234-5678")
    val contact: String? = null,

    @field:Size(max = 500)
    @field:Schema(description = "스튜디오 소개. 500자까지이며, null이면 저장된 소개를 지운다.", example = "자연광 웨딩 스냅 전문 스튜디오입니다.")
    val description: String? = null,
)
