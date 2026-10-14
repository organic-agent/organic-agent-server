package com.soma.wes.analysis.dto.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/** AI 분석 요청 본문. 본문이 없어도 된다(옛 클라이언트). */
data class AnalysisRequest(
    /** 사용자가 기억하는 컨셉 수(선택). 비우면 AI 가 개수를 정한다. */
    @field:Min(1)
    @field:Max(30)
    val conceptCount: Int? = null,
)
