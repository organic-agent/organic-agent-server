package com.soma.wes.admin.resource.domain

/**
 * 갤러리 재처리의 범위.
 *
 * - [ALL]: 갤러리의 분석 행을 전부 지운다 — 모델이 바뀌어 전부 다시 계산할 때.
 * - [FAILED_ONLY]: 결정적으로 실패한 사진(`photo_analysis.error`)의 행만 지운다 — 일시 장애로 빠진 몇 장을 되살릴 때.
 *   정상 사진의 벡터·점수는 그대로라 다시 도는 것은 실패한 장뿐이다.
 */
enum class AdminReprocessScope {
    ALL,
    FAILED_ONLY,
}
