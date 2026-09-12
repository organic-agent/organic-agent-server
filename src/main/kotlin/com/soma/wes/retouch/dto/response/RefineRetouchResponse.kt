package com.soma.wes.retouch.dto.response

import com.soma.wes.retouch.domain.RetouchRefineStatus
import io.swagger.v3.oas.annotations.media.Schema

data class RefineRetouchResponse(
    @field:Schema(description = "부부가 적은 원문. 정제와 무관하게 그대로 돌려준다.", example = "볼 잡티 지워줘")
    val originalText: String,

    @field:Schema(
        description = "채택하면 원문을 대신할 정리안. 정제가 돌지 않았거나 READY가 아니면 null이다.",
        example = "볼의 잡티를 자연스럽게 지워 주세요.",
    )
    val refinedText: String?,

    @field:Schema(description = "정제 기능을 쓸 수 있는 환경인지. false면 LLM이 꺼져 있거나 사진을 읽지 못한 것이다.")
    val available: Boolean,

    @field:Schema(description = "정제 판정. available=false면 정제가 돌지 않았으므로 null이다.", example = "READY")
    val status: RetouchRefineStatus?,

    @field:Schema(
        description = "탭한 지점에 실제로 보이는 것. 원문과 무관하게 사진에서 읽은 값이라 되묻기 문구의 근거가 된다. 포인트 없이 부른 호출은 null.",
        example = "신부가 들고 있는 흰색 부케",
    )
    val tappedObject: String? = null,

    @field:Schema(
        description = "탭한 곳과 원문이 같은 것을 가리키는지. false면 서버가 status를 NEEDS_CLARIFICATION으로 되돌린다.",
        nullable = true,
    )
    val pointMatchesText: Boolean? = null,

    @field:Schema(description = "정제된 요청을 한 부위 한 동작으로 나눈 항목. 되묻는 중이면 확실한 것만 담긴다.")
    val items: List<RefineRetouchItemResponse> = emptyList(),

    @field:Schema(description = "되묻는 질문. NEEDS_CLARIFICATION이 아니면 빈 문자열.", example = "")
    val question: String = "",

    @field:Schema(description = "되묻기 선택지 2~4개. 원문에 근거가 없는 것은 서버가 걸러 낸다.")
    val options: List<RefineRetouchOptionResponse> = emptyList(),
)
