package com.soma.wes.studio.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "스튜디오 생성 요청. 성공하면 해당 스튜디오의 OWNER 멤버십을 만든다.")
data class CreateStudioRequest(

    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(description = "스튜디오 이름", example = "오가닉 스튜디오")
    val name: String,

    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(
        description = "공개 주소 식별자. 앞뒤 공백과 대소문자는 정규화되며, 결과는 소문자·숫자·하이픈 3~50자여야 한다.",
        example = "organic-studio",
    )
    val galleryUrl: String,

    @field:Size(max = 100)
    @field:Schema(description = "고객에게 노출할 연락처", example = "010-1234-5678")
    val contact: String? = null,

    @field:Size(max = 500)
    @field:Schema(description = "스튜디오 소개", example = "자연스러운 순간을 기록합니다.")
    val description: String? = null,
)
