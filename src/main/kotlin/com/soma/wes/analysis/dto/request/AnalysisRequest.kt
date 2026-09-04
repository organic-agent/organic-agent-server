package com.soma.wes.analysis.dto.request

import com.soma.wes.analysis.domain.AnalysisMode
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "AI 분석 잡 요청. 본문을 생략하면 FULL이다.")
data class AnalysisRequest(
    @field:Schema(
        description = "FULL은 미리보기·임베딩 → 사진별 점수 → 그룹·이름 전체, EMBED는 미리보기·임베딩만, NAMING은 이름·배정만 다시 돈다. " +
            "NAMING은 FULL이 DONE인 적이 있어야 받는다 — 이름 붙이기는 FULL이 남긴 임베딩 그룹 위에서 돈다.",
        example = "FULL",
    )
    val mode: AnalysisMode = AnalysisMode.FULL,
    @field:Schema(
        description = "이미 벡터·점수가 있는 사진도 다시 계산한다. 모델이나 전처리를 바꿔 전량 재계산할 때만 쓴다.",
        example = "false",
    )
    val force: Boolean = false,
)
