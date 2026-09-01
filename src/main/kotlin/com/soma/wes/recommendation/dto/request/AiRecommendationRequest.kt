package com.soma.wes.recommendation.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "AI 추천 잡 요청. 본문을 생략하면 갤러리의 최신 AI 폴더 세트를 기준으로 한다.")
data class AiRecommendationRequest(

    @field:Schema(
        description = "기준으로 삼을 AI 폴더 세트의 키(폴더 목록 응답의 analysisJobId). " +
            "생략하면 최신 세트다. 이 갤러리에 없는 세트면 404_2.",
        example = "7",
    )
    val analysisJobId: Long? = null,
)
