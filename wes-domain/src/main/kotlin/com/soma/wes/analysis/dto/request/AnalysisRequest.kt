package com.soma.wes.analysis.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/** AI 분석 요청 본문. 본문이 없어도 된다(옛 클라이언트). */
data class AnalysisRequest(
    @field:Min(1)
    @field:Max(30)
    @field:Schema(description = "사용자가 기억하는 컨셉(1층 폴더) 수. 1~30, categorize가 촬영 시각 구간을 이 수로 묶는다. 생략하면 AI가 개수를 정한다.", example = "5")
    val conceptCount: Int? = null,
)
