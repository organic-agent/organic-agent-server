package com.soma.wes.recommendation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "한 셀렉의 AI 추천. 항상 최신 라운드다 — 이전 라운드는 이력으로만 남는다.")
data class AiRecommendationListResponse(

    @field:Schema(description = "이 응답이 담은 추천 라운드. 추천이 한 번도 없었으면 null이고 photos는 빈 목록이다.")
    val round: Int?,

    @field:Schema(description = "가장 최근 추천 잡. 요청한 적 없으면 null — 프론트 폴링이 잡보다 먼저 시작해도 오류가 아니다.")
    val job: AiSelectionJobResponse?,

    val photos: List<AiRecommendationResponse>,

    @field:Schema(description = "photos의 미리보기 URL 수명(초). 넘기면 다시 조회한다.")
    val viewUrlTtlSeconds: Long,

    @field:Schema(
        description = "viewUrl 중 만료되는 서명 URL이 있으면 true — 프론트는 true일 때만 수명 전에 다시 부른다. 미리보기가 모두 CDN 고정 주소(만료 없음)면 false.",
    )
    val viewUrlsExpire: Boolean = true,
) {

    companion object {

        fun empty(viewUrlTtlSeconds: Long): AiRecommendationListResponse = AiRecommendationListResponse(
            round = null,
            job = null,
            photos = emptyList(),
            viewUrlTtlSeconds = viewUrlTtlSeconds,
            viewUrlsExpire = false,
        )
    }
}
