package com.soma.wes.retouch.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "정제된 요청을 한 부위 한 동작으로 나눈 항목.")
data class RefineRetouchItemResponse(
    @field:Schema(description = "작업 대상. 인물이면 누구의 어느 부위인지, 사물이면 무엇인지와 사진 속 위치.", example = "신부 위팔(사진 오른쪽)")
    val target: String,

    @field:Schema(description = "대상 인물. groom / bride / other_person / none", example = "bride")
    val person: String,

    @field:Schema(description = "부위 분류.", example = "arm")
    val region: String,

    @field:Schema(description = "요청한 동작.", example = "smooth_skin")
    val action: String,

    @field:Schema(description = "강도. 원문이 말하지 않았으면 unspecified — 서버도 모델도 추측하지 않는다.", example = "subtle")
    val intensity: String,

    @field:Schema(description = "스튜디오 보정 메뉴 id. 규칙표(Phase 2) 전까지는 항상 null이다.", nullable = true)
    val menuId: String?,

    @field:Schema(description = "이 항목의 근거가 된 원문 구절 그대로. 원문에 없는 구절이면 서버가 항목을 버린다.", example = "팔에 점도 많은편이라")
    val sourceSpan: String,
)
