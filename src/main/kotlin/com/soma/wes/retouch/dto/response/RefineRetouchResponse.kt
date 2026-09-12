package com.soma.wes.retouch.dto.response

import com.soma.wes.retouch.domain.RetouchRefineStatus
import io.swagger.v3.oas.annotations.media.Schema

data class RefineRetouchResponse(
    @field:Schema(description = "부부가 적은 원문. 정제와 무관하게 그대로 돌려준다.", example = "볼 잡티 지워줘")
    val originalText: String,

    @field:Schema(
        description = "채택하면 원문을 대신할 정리안. 정제가 돌지 않았거나 정리할 요청이 아니면 null이다.",
        example = "볼의 잡티를 자연스럽게 지워 주세요.",
    )
    val refinedText: String?,

    @field:Schema(description = "정제 기능을 쓸 수 있는 환경인지. false면 LLM이 꺼져 있거나 쓸 수 없는 상태다.")
    val available: Boolean,

    @field:Schema(
        description = "정제 판정. available=false면 정제가 돌지 않았으므로 null이다.",
        example = "READY",
    )
    val status: RetouchRefineStatus?,
)
