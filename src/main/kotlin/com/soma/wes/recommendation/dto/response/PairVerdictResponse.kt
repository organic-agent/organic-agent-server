package com.soma.wes.recommendation.dto.response

import com.soma.wes.recommendation.dto.PairVerdictDto
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "비교샷 AI 판정. 항상 한 장을 고른다 — 거의 같을 때는 confidence=slight로 표현한다.")
data class PairVerdictResponse(

    @field:Schema(description = "AI가 고른 사진. 요청한 두 사진 중 하나다.")
    val chosenPhotoId: Long,

    @field:Schema(description = "clear(뚜렷한 차이) 또는 slight(거의 같아요, 굳이 고르면).", example = "clear")
    val confidence: String,

    @field:Schema(description = "판정 근거. 사진에서 본 것과 측정된 수치를 구분해 말한다.")
    val reason: String,

    @field:Schema(
        description = "llm(사진을 본 판정) 또는 template(응답 예산 초과·실패 시 수치만으로). " +
            "template이면 \"AI가 못 골랐어요\"가 아니라 \"기준으로만 골랐어요\" 톤으로 그린다.",
        example = "llm",
    )
    val source: String,

    @field:Schema(description = "같은 쌍의 저장된 판정을 그대로 돌려준 경우 true. 재요청은 과금되지 않는다.")
    val cached: Boolean,
) {

    companion object {

        fun from(verdict: PairVerdictDto): PairVerdictResponse = PairVerdictResponse(
            chosenPhotoId = verdict.chosenPhotoId,
            confidence = verdict.confidence,
            reason = verdict.reason,
            source = verdict.source,
            cached = verdict.cached,
        )
    }
}
