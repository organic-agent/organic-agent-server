package com.soma.wes.recommendation.dto.request

import com.soma.wes.recommendation.domain.AiAnalysisMode
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "AI 분석 잡 요청. 본문을 생략하면 FULL이다.")
data class AiAnalysisRequest(

    @field:Schema(
        description = "FULL은 사진별 분석 전체(끝에 이름 붙이기까지 이어 돈다), NAMING은 이름·배정만 다시 돈다. " +
            "NAMING은 FULL이 DONE인 적이 있어야 받는다 — 이름 붙이기는 FULL이 남긴 임베딩 그룹 위에서 돈다.",
        example = "FULL",
    )
    val mode: AiAnalysisMode = AiAnalysisMode.FULL,
)
