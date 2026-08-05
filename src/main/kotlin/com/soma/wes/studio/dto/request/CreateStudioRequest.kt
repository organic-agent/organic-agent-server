package com.soma.wes.studio.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "스튜디오 생성 요청. 이 요청이 성공하면 사용자 종류가 PHOTOGRAPHER로 확정된다.")
data class CreateStudioRequest(

    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(description = "스튜디오 이름", example = "오가닉 스튜디오")
    val name: String,

    @field:NotBlank
    @field:Size(min = 3, max = 50)
    @field:Schema(
        description = "공개 주소 식별자. 소문자·숫자·하이픈 3~50자이며 서비스 예약어는 쓸 수 없다.",
        example = "organic-studio",
    )
    val galleryUrl: String,

    @field:Size(max = 255)
    @field:Schema(description = "유입 경로. 마케팅 집계용이라 없어도 가입은 된다.", example = "인스타그램")
    val inflowChannel: String? = null,
)
