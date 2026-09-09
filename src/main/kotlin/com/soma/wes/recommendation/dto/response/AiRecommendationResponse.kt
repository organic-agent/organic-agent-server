package com.soma.wes.recommendation.dto.response

import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.recommendation.domain.AiRecommendation
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "AI 추천 사진 한 장. 폴더 화면은 이 응답으로 그리드 카드에 AI 배지(rank)를 그린다.")
data class AiRecommendationResponse(

    @field:Schema(description = "사진이 지금 든 세부폴더 id. 어느 폴더에도 없으면(미분류) null. 추천 당시 폴더가 아니라 현재 배정이다 — 표시는 사진을 따라간다.")
    val folderId: Long?,

    @field:Schema(description = "폴더 안 순위. 1이 그 폴더의 대표다.", example = "1")
    val rank: Int,

    @field:Schema(description = "추천 이유. 2단계라 준비 전에는 null이다 — 상세보기가 \"이유 준비 중\"을 그리고 폴링한다.")
    val reason: String?,

    @field:Schema(description = "이유 문장이 채워졌는지. false면 잠시 뒤 다시 조회한다.")
    val reasonReady: Boolean,

    @field:Schema(description = "이 사진이 지금 선택 앨범에 담겨 있는지.")
    val selected: Boolean,

    val photo: PhotoResponse,

    @field:Schema(description = "이 사진을 추천한 라운드. 현재 잡의 답변만 표시하려면 job.round와 비교한다.")
    val round: Int? = null,
) {

    companion object {

        fun of(
            recommendation: AiRecommendation,
            folderId: Long?,
            photo: PhotoResponse,
            selected: Boolean,
        ): AiRecommendationResponse = AiRecommendationResponse(
            folderId = folderId,
            rank = recommendation.rank,
            reason = recommendation.reason,
            reasonReady = recommendation.reason != null,
            selected = selected,
            photo = photo,
            round = recommendation.round,
        )
    }
}
