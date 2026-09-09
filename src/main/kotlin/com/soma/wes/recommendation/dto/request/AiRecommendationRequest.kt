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

    @field:Schema(
        description = "이 세부폴더만 다시 추천한다. 요청 시점에 그 폴더에 든 사진의 기존 추천은 지워지고 " +
            "새로 계산되며, 다른 폴더의 추천과 다른 폴더로 옮겨 간 사진의 추천은 그대로 남는다. " +
            "생략하면 갤러리 전체를 한 라운드로 계산한다. 이 갤러리에 없는 폴더면 404_4.",
        example = "12",
    )
    val detailFolderId: Long? = null,

    @field:Schema(description = "폴더 범위와 추천 장수를 자연어로 지정한다. 예: '가든 사진 중에 10장만 골라줘'. " +
        "현재 AI 폴더 이름을 기준으로 해석하며, 다른 시각적 조건이나 모호한 범위는 잡을 FAILED로 끝낸다. 최대 1000자.")
    val prompt: String? = null,

    @field:Schema(description = "이번 잡에서 새로 추천할 최대 장수(1~500). 이미 선택한 사진은 제외한다. " +
        "prompt의 숫자보다 우선한다. 후보가 부족하면 가능한 수와 shortfallCount를 반환한다. " +
        "생략하면 prompt의 숫자, 둘 다 없으면 기존 계약 장수 기반 폴더 쿼터를 사용한다.", example = "10")
    val targetCount: Int? = null,
)
